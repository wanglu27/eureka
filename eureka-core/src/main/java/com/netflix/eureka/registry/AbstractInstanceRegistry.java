/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.cache.CacheBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.annotations.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.util.EurekaMonitors.*;

/**
 * Handles all registry requests from eureka clients.
 *
 * <p>
 * Primary operations that are performed are the
 * <em>Registers</em>, <em>Renewals</em>, <em>Cancels</em>, <em>Expirations</em>, and <em>Status Changes</em>. The
 * registry also stores only the delta operations
 * </p>
 *
 * @author Karthik Ranganathan
 *
 */
public abstract class AbstractInstanceRegistry implements InstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AbstractInstanceRegistry.class);

    private static final String[] EMPTY_STR_ARRAY = new String[0];
    private final ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry
            = new ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>>();
    protected Map<String, RemoteRegionRegistry> regionNameVSRemoteRegistry = new HashMap<String, RemoteRegionRegistry>();
    protected final ConcurrentMap<String, InstanceStatus> overriddenInstanceStatusMap = CacheBuilder
            .newBuilder().initialCapacity(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .<String, InstanceStatus>build().asMap();

    // CircularQueues here for debugging/statistics purposes only
    private final CircularQueue<Pair<Long, String>> recentRegisteredQueue;
    private final CircularQueue<Pair<Long, String>> recentCanceledQueue;
    private ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue = new ConcurrentLinkedQueue<>();

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock read = readWriteLock.readLock();
    private final Lock write = readWriteLock.writeLock();
    protected final Object lock = new Object();

    private Timer deltaRetentionTimer = new Timer("Eureka-DeltaRetentionTimer", true);
    private Timer evictionTimer = new Timer("Eureka-EvictionTimer", true);
    private final MeasuredRate renewsLastMin;

    private final AtomicReference<EvictionTask> evictionTaskRef = new AtomicReference<>();

    protected String[] allKnownRemoteRegions = EMPTY_STR_ARRAY;
    protected volatile int numberOfRenewsPerMinThreshold;
    protected volatile int expectedNumberOfClientsSendingRenews;

    protected final EurekaServerConfig serverConfig;
    protected final EurekaClientConfig clientConfig;
    protected final ServerCodecs serverCodecs;
    protected volatile ResponseCache responseCache;

    /**
     * Create a new, empty instance registry.
     */
    protected AbstractInstanceRegistry(EurekaServerConfig serverConfig, EurekaClientConfig clientConfig, ServerCodecs serverCodecs) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.recentCanceledQueue = new CircularQueue<Pair<Long, String>>(1000);
        this.recentRegisteredQueue = new CircularQueue<Pair<Long, String>>(1000);

        this.renewsLastMin = new MeasuredRate(1000 * 60 * 1);

        this.deltaRetentionTimer.schedule(getDeltaRetentionTask(),
                serverConfig.getDeltaRetentionTimerIntervalInMs(),
                serverConfig.getDeltaRetentionTimerIntervalInMs());
    }

    @Override
    public synchronized void initializedResponseCache() {
        if (responseCache == null) {
            responseCache = new ResponseCacheImpl(serverConfig, serverCodecs, this);
        }
    }

    protected void initRemoteRegionRegistry() throws MalformedURLException {
        Map<String, String> remoteRegionUrlsWithName = serverConfig.getRemoteRegionUrlsWithName();
        if (!remoteRegionUrlsWithName.isEmpty()) {
            allKnownRemoteRegions = new String[remoteRegionUrlsWithName.size()];
            int remoteRegionArrayIndex = 0;
            for (Map.Entry<String, String> remoteRegionUrlWithName : remoteRegionUrlsWithName.entrySet()) {
                RemoteRegionRegistry remoteRegionRegistry = new RemoteRegionRegistry(
                        serverConfig,
                        clientConfig,
                        serverCodecs,
                        remoteRegionUrlWithName.getKey(),
                        new URL(remoteRegionUrlWithName.getValue()));
                regionNameVSRemoteRegistry.put(remoteRegionUrlWithName.getKey(), remoteRegionRegistry);
                allKnownRemoteRegions[remoteRegionArrayIndex++] = remoteRegionUrlWithName.getKey();
            }
        }
        logger.info("Finished initializing remote region registries. All known remote regions: {}",
                (Object) allKnownRemoteRegions);
    }

    @Override
    public ResponseCache getResponseCache() {
        return responseCache;
    }

    public long getLocalRegistrySize() {
        long total = 0;
        for (Map<String, Lease<InstanceInfo>> entry : registry.values()) {
            total += entry.size();
        }
        return total;
    }

    /**
     * Completely clear the registry.
     */
    @Override
    public void clearRegistry() {
        overriddenInstanceStatusMap.clear();
        recentCanceledQueue.clear();
        recentRegisteredQueue.clear();
        recentlyChangedQueue.clear();
        registry.clear();
    }

    // for server info use
    @Override
    public Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot() {
        return new HashMap<>(overriddenInstanceStatusMap);
    }

    /**
     * Registers a new instance with a given duration.
     *
     * @see com.netflix.eureka.lease.LeaseManager#register(java.lang.Object, int, boolean)
     */
    public void register(InstanceInfo registrant, int leaseDuration, boolean isReplication) {
        read.lock();
        try {
            // 服务会是集群部署
            // 所以gMap是一个服务对应的所有服务实例
            /*
            所以注册表的数据结构其实就是
            {
                "ServiceA" : {
                    "001" : Lease<InstanceInfo>,
                    "002" : Lease<InstanceInfo>
                }
            }
             */
            Map<String, Lease<InstanceInfo>> gMap = registry.get(registrant.getAppName());
            REGISTER.increment(isReplication);
            // 服务的服务实例第一次来注册，那么gMap就为null
            // 创建该服务对应的一个新的ConcurrentHashMap放到注册表中
            // 这里其实就是注册服务了
            // 后边将服务实例放进去就行了
            if (gMap == null) {
                final ConcurrentHashMap<String, Lease<InstanceInfo>> gNewMap = new ConcurrentHashMap<String, Lease<InstanceInfo>>();
                gMap = registry.putIfAbsent(registrant.getAppName(), gNewMap);
                if (gMap == null) {
                    gMap = gNewMap;
                }
            }
            // 根据服务实例id拿到当前服务实例的注册信息
            Lease<InstanceInfo> existingLease = gMap.get(registrant.getId());
            // Retain the last dirty timestamp without overwriting it, if there is already a lease
            // 这里就是服务实例之前注册过了
            if (existingLease != null && (existingLease.getHolder() != null)) {
                Long existingLastDirtyTimestamp = existingLease.getHolder().getLastDirtyTimestamp();
                Long registrationLastDirtyTimestamp = registrant.getLastDirtyTimestamp();
                logger.debug("Existing lease found (existing={}, provided={}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);

                // this is a > instead of a >= because if the timestamps are equal, we still take the remote transmitted
                // InstanceInfo instead of the server local copy.
                if (existingLastDirtyTimestamp > registrationLastDirtyTimestamp) {
                    logger.warn("There is an existing lease and the existing lease's dirty timestamp {} is greater" +
                            " than the one that is being registered {}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);
                    logger.warn("Using the existing instanceInfo instead of the new instanceInfo as the registrant");
                    registrant = existingLease.getHolder();
                }
            } else {
                // The lease does not exist and hence it is a new registration
                // 服务实例第一次来注册
                // 更新注册的client的数量
                // 然后更新期望发送心跳的数量
                // 用于自我保护机制
                synchronized (lock) {
                    if (this.expectedNumberOfClientsSendingRenews > 0) {
                        // Since the client wants to register it, increase the number of clients sending renews
                        this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews + 1;
                        updateRenewsPerMinThreshold();
                    }
                }
                logger.debug("No previous lease information found; it is new registration");
            }
            // 构造注册信息
            // 注册时间、最后一次更新时间、服务实例信息
            Lease<InstanceInfo> lease = new Lease<>(registrant, leaseDuration);
            if (existingLease != null) {
                lease.setServiceUpTimestamp(existingLease.getServiceUpTimestamp());
            }
            // 这里就将服务实例的注册信息放到注册表里了
            gMap.put(registrant.getId(), lease);
            // 最近注册队列
            // 保存最近注册的服务实例
            recentRegisteredQueue.add(new Pair<Long, String>(
                    System.currentTimeMillis(),
                    registrant.getAppName() + "(" + registrant.getId() + ")"));
            // This is where the initial state transfer of overridden status happens
            // 第一次来注册就新增一个服务实例状态放到overriddenInstanceStatusMap（缓存）里
            // 并且在现在重新覆盖
            if (!InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus())) {
                logger.debug("Found overridden status {} for instance {}. Checking to see if needs to be add to the "
                                + "overrides", registrant.getOverriddenStatus(), registrant.getId());
                if (!overriddenInstanceStatusMap.containsKey(registrant.getId())) {
                    logger.info("Not found overridden id {} and hence adding it", registrant.getId());
                    overriddenInstanceStatusMap.put(registrant.getId(), registrant.getOverriddenStatus());
                }
            }
            // 这里为什么再get一次呢
            // 是因为并发的情况下可能缓存中关于这个实例的数据已经被更新了
            InstanceStatus overriddenStatusFromMap = overriddenInstanceStatusMap.get(registrant.getId());
            if (overriddenStatusFromMap != null) {
                logger.info("Storing overridden status {} from map", overriddenStatusFromMap);
                registrant.setOverriddenStatus(overriddenStatusFromMap);
            }

            // Set the status based on the overridden status rules
            InstanceStatus overriddenInstanceStatus = getOverriddenInstanceStatus(registrant, existingLease, isReplication);
            registrant.setStatusWithoutDirty(overriddenInstanceStatus);

            // If the lease is registered with UP status, set lease service up timestamp
            if (InstanceStatus.UP.equals(registrant.getStatus())) {
                lease.serviceUp();
            }
            registrant.setActionType(ActionType.ADDED);
            recentlyChangedQueue.add(new RecentlyChangedItem(lease));
            registrant.setLastUpdatedTimestamp();
            // 主动过期掉多级缓存中读写缓存对应的key
            // 这里是主动过期
            // 定时过期是在构建读写缓存的时候，已经设置了过期时间默认是180s
            invalidateCache(registrant.getAppName(), registrant.getVIPAddress(), registrant.getSecureVipAddress());
            logger.info("Registered instance {}/{} with status {} (replication={})",
                    registrant.getAppName(), registrant.getId(), registrant.getStatus(), isReplication);
        } finally {
            read.unlock();
        }
    }

    /**
     * Cancels the registration of an instance.
     *
     * <p>
     * This is normally invoked by a client when it shuts down informing the
     * server to remove the instance from traffic.
     * </p>
     *
     * @param appName the application name of the application.
     * @param id the unique identifier of the instance.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the instance was removed from the {@link AbstractInstanceRegistry} successfully, false otherwise.
     */
    @Override
    public boolean cancel(String appName, String id, boolean isReplication) {
        return internalCancel(appName, id, isReplication);
    }

    /**
     * {@link #cancel(String, String, boolean)} method is overridden by {@link PeerAwareInstanceRegistry}, so each
     * cancel request is replicated to the peers. This is however not desired for expires which would be counted
     * in the remote peers as valid cancellations, so self preservation mode would not kick-in.
     */
    protected boolean internalCancel(String appName, String id, boolean isReplication) {
        read.lock();
        try {
            CANCEL.increment(isReplication);
            // 拿到服务对应的注册表
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> leaseToCancel = null;
            if (gMap != null) {
                // 移除对应的服务实例
                leaseToCancel = gMap.remove(id);
            }
            // 塞到最近取消队列
            recentCanceledQueue.add(new Pair<Long, String>(System.currentTimeMillis(), appName + "(" + id + ")"));
            InstanceStatus instanceStatus = overriddenInstanceStatusMap.remove(id);
            if (instanceStatus != null) {
                logger.debug("Removed instance id {} from the overridden map which has value {}", id, instanceStatus.name());
            }
            if (leaseToCancel == null) {
                CANCEL_NOT_FOUND.increment(isReplication);
                logger.warn("DS: Registry: cancel failed because Lease is not registered for: {}/{}", appName, id);
                return false;
            } else {

                // 核心方法
                // 修改这里修改一下服务实例的下线时间
                leaseToCancel.cancel();

                InstanceInfo instanceInfo = leaseToCancel.getHolder();
                String vip = null;
                String svip = null;
                if (instanceInfo != null) {
                    instanceInfo.setActionType(ActionType.DELETED);

                    // 添加到最近变化队列recentCanceledQueue
                    // 以便其他client来抓取增量注册表的时候能够更新
                    recentlyChangedQueue.add(new RecentlyChangedItem(leaseToCancel));

                    instanceInfo.setLastUpdatedTimestamp();
                    vip = instanceInfo.getVIPAddress();
                    svip = instanceInfo.getSecureVipAddress();
                }
                // 还需要将读写缓存中的缓存移除
                // 只读缓存等他自动更新
                invalidateCache(appName, vip, svip);
                logger.info("Cancelled instance {}/{} (replication={})", appName, id, isReplication);
            }
        } finally {
            read.unlock();
        }

        // expectedNumberOfClientsSendingRenews > 0
        // 也就是还依然有client在注册
        // 然后更新期望发送心跳的数量
        // 用于自我保护机制
        synchronized (lock) {
            if (this.expectedNumberOfClientsSendingRenews > 0) {
                // Since the client wants to cancel it, reduce the number of clients to send renews.
                this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
                updateRenewsPerMinThreshold();
            }
        }

        return true;
    }

    /**
     * Marks the given instance of the given app name as renewed, and also marks whether it originated from
     * replication.
     *
     * @see com.netflix.eureka.lease.LeaseManager#renew(java.lang.String, java.lang.String, boolean)
     */
    public boolean renew(String appName, String id, boolean isReplication) {
        RENEW.increment(isReplication);
        // 获取服务对应的服务租约列表
        Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
        Lease<InstanceInfo> leaseToRenew = null;
        if (gMap != null) {
            // 获取client对应的服务租约
            leaseToRenew = gMap.get(id);
        }

        // 这里也没必要if-else嵌套
        // 本身就是提前结束流程
        if (leaseToRenew == null) {
            RENEW_NOT_FOUND.increment(isReplication);
            logger.warn("DS: Registry: lease doesn't exist, registering resource: {} - {}", appName, id);
            return false;
        } else {
            // 这种不相关的代码其实可以提出去
            InstanceInfo instanceInfo = leaseToRenew.getHolder();
            if (instanceInfo != null) {
                // touchASGCache(instanceInfo.getASGName());
                InstanceStatus overriddenInstanceStatus = this.getOverriddenInstanceStatus(
                        instanceInfo, leaseToRenew, isReplication);
                if (overriddenInstanceStatus == InstanceStatus.UNKNOWN) {
                    logger.info("Instance status UNKNOWN possibly due to deleted override for instance {}"
                            + "; re-register required", instanceInfo.getId());
                    RENEW_NOT_FOUND.increment(isReplication);
                    return false;
                }
                if (!instanceInfo.getStatus().equals(overriddenInstanceStatus)) {
                    logger.info(
                            "The instance status {} is different from overridden instance status {} for instance {}. "
                                    + "Hence setting the status to overridden status", instanceInfo.getStatus().name(),
                                    overriddenInstanceStatus.name(),
                                    instanceInfo.getId());
                    instanceInfo.setStatusWithoutDirty(overriddenInstanceStatus);

                }
            }

            // 记录一下这分钟接受心跳的次数
            // 一次心跳加一次
            renewsLastMin.increment();
            // 续约就是更新一下最后更新时间lastUpdateTimestamp
            leaseToRenew.renew();
            return true;
        }
    }

    /**
     * @deprecated this is expensive, try not to use. See if you can use
     * {@link #storeOverriddenStatusIfRequired(String, String, InstanceStatus)} instead.
     *
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param id the unique identifier of the instance.
     * @param overriddenStatus Overridden status if any.
     */
    @Deprecated
    @Override
    public void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null)
                || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got restarted -this will help us maintain
            // the overridden state from the replica
            logger.info(
                    "Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            List<InstanceInfo> instanceInfo = this.getInstancesById(id, false);
            if ((instanceInfo != null) && (!instanceInfo.isEmpty())) {
                instanceInfo.iterator().next().setOverriddenStatus(overriddenStatus);
                logger.info(
                        "Setting the overridden status for instance id {} and the value is {} ",
                        id, overriddenStatus.name());

            }
        }
    }

    /**
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param overriddenStatus overridden status if any.
     */
    @Override
    public void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null) || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got
            // restarted -this will help us maintain the overridden state
            // from the replica
            logger.info("Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            InstanceInfo instanceInfo = this.getInstanceByAppAndId(appName, id, false);
            instanceInfo.setOverriddenStatus(overriddenStatus);
            logger.info("Set the overridden status for instance (appname:{}, id:{}} and the value is {} ",
                    appName, id, overriddenStatus.name());
        }
    }

    /**
     * Updates the status of an instance. Normally happens to put an instance
     * between {@link InstanceStatus#OUT_OF_SERVICE} and
     * {@link InstanceStatus#UP} to put the instance in and out of traffic.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean statusUpdate(String appName, String id,
                                InstanceStatus newStatus, String lastDirtyTimestamp,
                                boolean isReplication) {
        read.lock();
        try {
            STATUS_UPDATE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();
                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }
                if ((info != null) && !(info.getStatus().equals(newStatus))) {
                    // Mark service as UP if needed
                    if (InstanceStatus.UP.equals(newStatus)) {
                        lease.serviceUp();
                    }
                    // This is NAC overridden status
                    overriddenInstanceStatusMap.put(id, newStatus);
                    // Set it for transfer of overridden status to replica on
                    // replica start up
                    info.setOverriddenStatus(newStatus);
                    long replicaDirtyTimestamp = 0;
                    info.setStatusWithoutDirty(newStatus);
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Removes status override for a give instance.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        read.lock();
        try {
            STATUS_OVERRIDE_DELETE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();

                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }

                InstanceStatus currentOverride = overriddenInstanceStatusMap.remove(id);
                if (currentOverride != null && info != null) {
                    info.setOverriddenStatus(InstanceStatus.UNKNOWN);
                    info.setStatusWithoutDirty(newStatus);
                    long replicaDirtyTimestamp = 0;
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Evicts everything in the instance registry that has expired, if expiry is enabled.
     *
     * @see com.netflix.eureka.lease.LeaseManager#evict()
     */
    @Override
    public void evict() {
        evict(0l);
    }

    public void evict(long additionalLeaseMs) {
        logger.debug("Running the evict task");

        // 自我保护机制
        // 如果这一分钟超过85%的实例没有发送心跳
        // 则认为server端网络出现问题
        // 不再剔除client实例
        if (!isLeaseExpirationEnabled()) {
            logger.debug("DS: lease expiration is currently disabled.");
            return;
        }

        // We collect first all expired items, to evict them in random order. For large eviction sets,
        // if we do not that, we might wipe out whole apps before self preservation kicks in. By randomizing it,
        // the impact should be evenly distributed across all applications.
        List<Lease<InstanceInfo>> expiredLeases = new ArrayList<>();
        for (Entry<String, Map<String, Lease<InstanceInfo>>> groupEntry : registry.entrySet()) {
            Map<String, Lease<InstanceInfo>> leaseMap = groupEntry.getValue();
            if (leaseMap != null) {
                for (Entry<String, Lease<InstanceInfo>> leaseEntry : leaseMap.entrySet()) {
                    Lease<InstanceInfo> lease = leaseEntry.getValue();
                    // 在这里判断心跳是否已经过期了，如果已经过期添加到过期集合中
                    // 根据这个方法的注释 180s没有发送心跳才会认为故障
                    // 又因为30秒自动同步只读缓存
                    // 这么算下来，如果一个client故障了，3分半其他的client才能感知到
                    if (lease.isExpired(additionalLeaseMs) && lease.getHolder() != null) {
                        expiredLeases.add(lease);
                    }
                }
            }
        }

        // To compensate for GC pauses or drifting local time, we need to use current registry size as a base for
        // triggering self-preservation. Without that we would wipe out full registry.
        // 该服务在当前注册表中注册了多少实例
        int registrySize = (int) getLocalRegistrySize();
        // serverConfig.getRenewalPercentThreshold() 0.85
        // size * 0.85
        int registrySizeThreshold = (int) (registrySize * serverConfig.getRenewalPercentThreshold());
        // size * 0.15
        // 也就是说因为自我保护机制
        // 最多这次最多摘除15%的实例
        int evictionLimit = registrySize - registrySizeThreshold;

        // 因为存在摘除实例存在最大数量的限制
        // 就比较限制和过期实例的数量
        // 取最小值
        int toEvict = Math.min(expiredLeases.size(), evictionLimit);
        if (toEvict > 0) {
            logger.info("Evicting {} items (expired={}, evictionLimit={})", toEvict, expiredLeases.size(), evictionLimit);

            // 随机摘除toEvict个数量的实例
            Random random = new Random(System.currentTimeMillis());
            for (int i = 0; i < toEvict; i++) {
                // Pick a random item (Knuth shuffle algorithm)
                int next = i + random.nextInt(expiredLeases.size() - i);
                Collections.swap(expiredLeases, i, next);
                Lease<InstanceInfo> lease = expiredLeases.get(i);

                String appName = lease.getHolder().getAppName();
                String id = lease.getHolder().getId();
                EXPIRED.increment();
                logger.warn("DS: Registry: expired lease for {}/{}", appName, id);
                // 移除服务实例
                internalCancel(appName, id, false);
            }
        }
    }


    /**
     * Returns the given app that is in this instance only, falling back to other regions transparently only
     * if specified in this client configuration.
     *
     * @param appName the application name of the application
     * @return the application
     *
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        return this.getApplication(appName, !disableTransparentFallback);
    }

    /**
     * Get application information.
     *
     * @param appName The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    @Override
    public Application getApplication(String appName, boolean includeRemoteRegion) {
        Application app = null;

        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);

        if (leaseMap != null && leaseMap.size() > 0) {
            for (Entry<String, Lease<InstanceInfo>> entry : leaseMap.entrySet()) {
                if (app == null) {
                    app = new Application(appName);
                }
                app.addInstance(decorateInstanceInfo(entry.getValue()));
            }
        } else if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application;
                }
            }
        }
        return app;
    }

    /**
     * Get all applications in this instance registry, falling back to other regions if allowed in the Eureka config.
     *
     * @return the list of all known applications
     *
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    public Applications getApplications() {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        if (disableTransparentFallback) {
            return getApplicationsFromLocalRegionOnly();
        } else {
            return getApplicationsFromAllRemoteRegions();  // Behavior of falling back to remote region can be disabled.
        }
    }

    /**
     * Returns applications including instances from all remote regions. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with a <code>null</code> argument.
     */
    public Applications getApplicationsFromAllRemoteRegions() {
        return getApplicationsFromMultipleRegions(allKnownRemoteRegions);
    }

    /**
     * Returns applications including instances from local region only. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with an empty array.
     */
    @Override
    public Applications getApplicationsFromLocalRegionOnly() {
        return getApplicationsFromMultipleRegions(EMPTY_STR_ARRAY);
    }

    /**
     * This method will return applications with instances from all passed remote regions as well as the current region.
     * Thus, this gives a union view of instances from multiple regions. <br/>
     * The application instances for which this union will be done can be restricted to the names returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for every region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     * If you are not selectively requesting for a remote region, use {@link #getApplicationsFromAllRemoteRegions()}
     * or {@link #getApplicationsFromLocalRegionOnly()}
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> or empty no remote regions are
     *                      included.
     *
     * @return The applications with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be only for certain whitelisted apps as explained above.
     */
    public Applications getApplicationsFromMultipleRegions(String[] remoteRegions) {

        boolean includeRemoteRegion = null != remoteRegions && remoteRegions.length != 0;

        logger.debug("Fetching applications registry with remote regions: {}, Regions argument {}",
                includeRemoteRegion, remoteRegions);

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS.increment();
        } else {
            GET_ALL_CACHE_MISS.increment();
        }
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {
                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();
                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }
                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        if (includeRemoteRegion) {
            for (String remoteRegion : remoteRegions) {
                RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                if (null != remoteRegistry) {
                    Applications remoteApps = remoteRegistry.getApplications();
                    for (Application application : remoteApps.getRegisteredApplications()) {
                        if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                            logger.info("Application {}  fetched from the remote region {}",
                                    application.getName(), remoteRegion);

                            Application appInstanceTillNow = apps.getRegisteredApplications(application.getName());
                            if (appInstanceTillNow == null) {
                                appInstanceTillNow = new Application(application.getName());
                                apps.addApplication(appInstanceTillNow);
                            }
                            for (InstanceInfo instanceInfo : application.getInstances()) {
                                appInstanceTillNow.addInstance(instanceInfo);
                            }
                        } else {
                            logger.debug("Application {} not fetched from the remote region {} as there exists a "
                                            + "whitelist and this app is not in the whitelist.",
                                    application.getName(), remoteRegion);
                        }
                    }
                } else {
                    logger.warn("No remote registry available for the remote region {}", remoteRegion);
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    private boolean shouldFetchFromRemoteRegistry(String appName, String remoteRegion) {
        Set<String> whiteList = serverConfig.getRemoteRegionAppWhitelist(remoteRegion);
        if (null == whiteList) {
            whiteList = serverConfig.getRemoteRegionAppWhitelist(null); // see global whitelist.
        }
        return null == whiteList || whiteList.contains(appName);
    }

    /**
     * Get the registry information about all {@link Applications}.
     *
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return applications
     *
     * @deprecated Use {@link #getApplicationsFromMultipleRegions(String[])} instead. This method has a flawed behavior
     * of transparently falling back to a remote region if no instances for an app is available locally. The new
     * behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplications(boolean includeRemoteRegion) {
        GET_ALL_CACHE_MISS.increment();
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {

                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();

                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }

                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Applications applications = remoteRegistry.getApplications();
                for (Application application : applications
                        .getRegisteredApplications()) {
                    Application appInLocalRegistry = apps
                            .getRegisteredApplications(application.getName());
                    if (appInLocalRegistry == null) {
                        apps.addApplication(application);
                    }
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    /**
     * Get the registry information about the delta changes. The deltas are
     * cached for a window specified by
     * {@link EurekaServerConfig#getRetentionTimeInMSInDeltaQueue()}. Subsequent
     * requests for delta information may return the same information and client
     * must make sure this does not adversely affect them.
     *
     * @return all application deltas.
     * @deprecated use {@link #getApplicationDeltasFromMultipleRegions(String[])} instead. This method has a
     * flawed behavior of transparently falling back to a remote region if no instances for an app is available locally.
     * The new behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplicationDeltas() {
        GET_ALL_CACHE_MISS_DELTA.increment();
        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDelta().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        write.lock();
        try {
            // 拿到3分钟内的服务实例变化情况
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is : {}",
                    this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug(
                        "The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo
                        .getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();

            if (!disableTransparentFallback) {
                Applications allAppsInLocalRegion = getApplications(false);

                for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                    Applications applications = remoteRegistry.getApplicationDeltas();
                    for (Application application : applications.getRegisteredApplications()) {
                        Application appInLocalRegistry =
                                allAppsInLocalRegion.getRegisteredApplications(application.getName());
                        if (appInLocalRegistry == null) {
                            apps.addApplication(application);
                        }
                    }
                }
            }

            // 这里可以看到拿到了全量注册表的哈希值
            // 这个哈希值在client端进行更新注册表后用的到
            Applications allApps = getApplications(!disableTransparentFallback);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the application delta also including instances from the passed remote regions, with the instances from the
     * local region. <br/>
     *
     * The remote regions from where the instances will be chosen can further be restricted if this application does not
     * appear in the whitelist specified for the region as returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for a region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> all remote regions are included.
     *                      If empty list then no remote region is included.
     *
     * @return The delta with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be further be restricted as explained above. <code>null</code> if the application does
     * not exist locally or in remote regions.
     */
    public Applications getApplicationDeltasFromMultipleRegions(String[] remoteRegions) {
        if (null == remoteRegions) {
            remoteRegions = allKnownRemoteRegions; // null means all remote regions.
        }

        boolean includeRemoteRegion = remoteRegions.length != 0;

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS_DELTA.increment();
        } else {
            GET_ALL_CACHE_MISS_DELTA.increment();
        }

        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDeltaWithRegions().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        write.lock();
        try {
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is :{}", this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug("The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo.getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            if (includeRemoteRegion) {
                for (String remoteRegion : remoteRegions) {
                    RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                    if (null != remoteRegistry) {
                        Applications remoteAppsDelta = remoteRegistry.getApplicationDeltas();
                        if (null != remoteAppsDelta) {
                            for (Application application : remoteAppsDelta.getRegisteredApplications()) {
                                if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                                    Application appInstanceTillNow =
                                            apps.getRegisteredApplications(application.getName());
                                    if (appInstanceTillNow == null) {
                                        appInstanceTillNow = new Application(application.getName());
                                        apps.addApplication(appInstanceTillNow);
                                    }
                                    for (InstanceInfo instanceInfo : application.getInstances()) {
                                        appInstanceTillNow.addInstance(new InstanceInfo(instanceInfo));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Applications allApps = getApplicationsFromMultipleRegions(remoteRegions);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id) {
        return this.getInstanceByAppAndId(appName, id, true);
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions) {
        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);
        Lease<InstanceInfo> lease = null;
        if (leaseMap != null) {
            lease = leaseMap.get(id);
        }
        if (lease != null
                && (!isLeaseExpirationEnabled() || !lease.isExpired())) {
            return decorateInstanceInfo(lease);
        } else if (includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application.getByInstanceId(id);
                }
            }
        }
        return null;
    }

    /**
     * @deprecated Try {@link #getInstanceByAppAndId(String, String)} instead.
     *
     * Get all instances by ID, including automatically asking other regions if the ID is unknown.
     *
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(String)
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id) {
        return this.getInstancesById(id, true);
    }

    /**
     * @deprecated Try {@link #getInstanceByAppAndId(String, String, boolean)} instead.
     *
     * Get the list of instances by its unique id.
     *
     * @param id the unique id of the instance
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return list of InstanceInfo objects.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id, boolean includeRemoteRegions) {
        List<InstanceInfo> list = new ArrayList<>();

        for (Iterator<Entry<String, Map<String, Lease<InstanceInfo>>>> iter =
                     registry.entrySet().iterator(); iter.hasNext(); ) {

            Map<String, Lease<InstanceInfo>> leaseMap = iter.next().getValue();
            if (leaseMap != null) {
                Lease<InstanceInfo> lease = leaseMap.get(id);

                if (lease == null || (isLeaseExpirationEnabled() && lease.isExpired())) {
                    continue;
                }

                if (list == Collections.EMPTY_LIST) {
                    list = new ArrayList<>();
                }
                list.add(decorateInstanceInfo(lease));
            }
        }
        if (list.isEmpty() && includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                for (Application application : remoteRegistry.getApplications()
                        .getRegisteredApplications()) {
                    InstanceInfo instanceInfo = application.getByInstanceId(id);
                    if (instanceInfo != null) {
                        list.add(instanceInfo);
                        return list;
                    }
                }
            }
        }
        return list;
    }

    private InstanceInfo decorateInstanceInfo(Lease<InstanceInfo> lease) {
        InstanceInfo info = lease.getHolder();

        // client app settings
        int renewalInterval = LeaseInfo.DEFAULT_LEASE_RENEWAL_INTERVAL;
        int leaseDuration = LeaseInfo.DEFAULT_LEASE_DURATION;

        // TODO: clean this up
        if (info.getLeaseInfo() != null) {
            renewalInterval = info.getLeaseInfo().getRenewalIntervalInSecs();
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }

        info.setLeaseInfo(LeaseInfo.Builder.newBuilder()
                .setRegistrationTimestamp(lease.getRegistrationTimestamp())
                .setRenewalTimestamp(lease.getLastRenewalTimestamp())
                .setServiceUpTimestamp(lease.getServiceUpTimestamp())
                .setRenewalIntervalInSecs(renewalInterval)
                .setDurationInSecs(leaseDuration)
                .setEvictionTimestamp(lease.getEvictionTimestamp()).build());

        info.setIsCoordinatingDiscoveryServer();
        return info;
    }

    /**
     * Servo route; do not call.
     *
     * @return servo data
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsInLastMin",
            description = "Number of total heartbeats received in the last minute", type = DataSourceType.GAUGE)
    @Override
    public long getNumOfRenewsInLastMin() {
        // renewsLastMin 这个既然是一分钟心跳的次数
        // 那么必然会在server端处理心跳的地方出现
        return renewsLastMin.getCount();
    }


    /**
     * Gets the threshold for the renewals per minute.
     *
     * @return the integer representing the threshold for the renewals per
     *         minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsPerMinThreshold", type = DataSourceType.GAUGE)
    @Override
    public int getNumOfRenewsPerMinThreshold() {
        return numberOfRenewsPerMinThreshold;
    }

    /**
     * Get the N instances that are most recently registered.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNRegisteredInstances() {
        List<Pair<Long, String>> list = new ArrayList<>(recentRegisteredQueue);
        Collections.reverse(list);
        return list;
    }

    /**
     * Get the N instances that have most recently canceled.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNCanceledInstances() {
        List<Pair<Long, String>> list = new ArrayList<>(recentCanceledQueue);
        Collections.reverse(list);
        return list;
    }

    private void invalidateCache(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        // invalidate cache
        responseCache.invalidate(appName, vipAddress, secureVipAddress);
    }

    protected void updateRenewsPerMinThreshold() {
        // expectedNumberOfClientsSendingRenews 注册实例的数量
        // 注册实例的数量 * 2 * 0.85
        // 这里为什么 * 2 呢，因为client每30s发送一次心跳
        // 1分钟就需要 * 2了
        // 这2倍就是根据配置的多少s发送一次心跳来算得，如果改为10s发送一次，那就是6倍了
        this.numberOfRenewsPerMinThreshold = (int) (this.expectedNumberOfClientsSendingRenews
                * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
                * serverConfig.getRenewalPercentThreshold());
    }

    private static final class RecentlyChangedItem {
        private long lastUpdateTime;
        private Lease<InstanceInfo> leaseInfo;

        public RecentlyChangedItem(Lease<InstanceInfo> lease) {
            this.leaseInfo = lease;
            lastUpdateTime = System.currentTimeMillis();
        }

        public long getLastUpdateTime() {
            return this.lastUpdateTime;
        }

        public Lease<InstanceInfo> getLeaseInfo() {
            return this.leaseInfo;
        }
    }

    protected void postInit() {
        renewsLastMin.start();
        if (evictionTaskRef.get() != null) {
            evictionTaskRef.get().cancel();
        }
        // EvictionTask 调度任务
        evictionTaskRef.set(new EvictionTask());
        // 默认1分钟感知一次
        evictionTimer.schedule(evictionTaskRef.get(),
                serverConfig.getEvictionIntervalTimerInMs(),
                serverConfig.getEvictionIntervalTimerInMs());
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        deltaRetentionTimer.cancel();
        evictionTimer.cancel();
        renewsLastMin.stop();
        responseCache.stop();
    }

    @com.netflix.servo.annotations.Monitor(name = "numOfElementsinInstanceCache", description = "Number of overrides in the instance Cache", type = DataSourceType.GAUGE)
    public long getNumberofElementsininstanceCache() {
        return overriddenInstanceStatusMap.size();
    }

    /* visible for testing */ class EvictionTask extends TimerTask {

        private final AtomicLong lastExecutionNanosRef = new AtomicLong(0l);

        @Override
        public void run() {
            try {
                /*
                上一次故障感知的时间为20:00
                但是因为Linux时钟偏差或者gc，导致这次故障感知的时间变成了20:02:30
                这就超过了一分钟一次的配置
                通过getCompensationTimeMs()获取到的就是超过这一分钟的多余的时间
                也就是20:00 20:02:30 这个返回的就是 90s
                 */
                // 计算补偿时间
                // 正常情况下返回0
                // 如果是注释的那种情况下，延迟了90s才开始新一轮的故障感知，又因为判断是否过期的bug
                // 也就是说3分钟内没有心跳，并且在这延迟的90s内也没有心跳，那么才算他过期
                // 在判断是否过期的方法中可以看到这个计算逻辑
                long compensationTimeMs = getCompensationTimeMs();
                logger.info("Running the evict task with compensationTime {}ms", compensationTimeMs);
                evict(compensationTimeMs);
            } catch (Throwable e) {
                logger.error("Could not run the evict task", e);
            }
        }

        /**
         * compute a compensation time defined as the actual time this task was executed since the prev iteration,
         * vs the configured amount of time for execution. This is useful for cases where changes in time (due to
         * clock skew or gc for example) causes the actual eviction task to execute later than the desired time
         * according to the configured cycle.
         */
        long getCompensationTimeMs() {
            long currNanos = getCurrentTimeNano();
            // 获取及上一次故障感知的时间
            // 设置这次故障感知的时间
            long lastNanos = lastExecutionNanosRef.getAndSet(currNanos);
            if (lastNanos == 0l) {
                return 0l;
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currNanos - lastNanos);
            long compensationTime = elapsedMs - serverConfig.getEvictionIntervalTimerInMs();
            return compensationTime <= 0l ? 0l : compensationTime;
        }

        long getCurrentTimeNano() {  // for testing
            return System.nanoTime();
        }

    }

    /* visible for testing */ static class CircularQueue<E> extends AbstractQueue<E> {

        private final ArrayBlockingQueue<E> delegate;
        private final int capacity;

        public CircularQueue(int capacity) {
            this.capacity = capacity;
            this.delegate = new ArrayBlockingQueue<>(capacity);
        }

        @Override
        public Iterator<E> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean offer(E e) {
            while (!delegate.offer(e)) {
                delegate.poll();
            }
            return true;
        }

        @Override
        public E poll() {
            return delegate.poll();
        }

        @Override
        public E peek() {
            return delegate.peek();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }
    }

    /**
     * @return The rule that will process the instance status override.
     */
    protected abstract InstanceStatusOverrideRule getInstanceInfoOverrideRule();

    protected InstanceInfo.InstanceStatus getOverriddenInstanceStatus(InstanceInfo r,
                                                                    Lease<InstanceInfo> existingLease,
                                                                    boolean isReplication) {
        InstanceStatusOverrideRule rule = getInstanceInfoOverrideRule();
        logger.debug("Processing override status using rule: {}", rule);
        return rule.apply(r, existingLease, isReplication).status();
    }

    private TimerTask getDeltaRetentionTask() {
        return new TimerTask() {

            @Override
            public void run() {
                Iterator<RecentlyChangedItem> it = recentlyChangedQueue.iterator();
                while (it.hasNext()) {
                    if (it.next().getLastUpdateTime() <
                            System.currentTimeMillis() - serverConfig.getRetentionTimeInMSInDeltaQueue()) {
                        it.remove();
                    } else {
                        break;
                    }
                }
            }

        };
    }
}
