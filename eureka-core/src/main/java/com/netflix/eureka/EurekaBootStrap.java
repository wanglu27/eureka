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

package com.netflix.eureka;

import com.netflix.appinfo.*;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 * <p>
 * 负责eureka-server启动时的初始化 bootstrap
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;

    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }

    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     *
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     * <p>
     * eureka-server 启动入口
     *
     * @see javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            // 初始化 Eureka 环境
            // 设置数据中心、运行环境
            // 如果没设置就设置成默认
            initEurekaEnvironment();
            // 初始化 Eureka-Server 的上下文
            // 启动 server 最重要的一步
            initEurekaServerContext();

            // 将 serverContext 设置进 ServletContext 中去
            ServletContext sc = event.getServletContext();
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");

        // 调用 ConfigurationManager.getConfigInstance() 方式
        // 初始化 ConfigurationManager 实例
        // 通过 ConfigurationManager 管理 eureka 实例的配置
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        // 没配置数据中心，就设置成默认
        if (dataCenter == null) {
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        // 没配置运行环境，就设置成默认
        if (environment == null) {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     */
    protected void initEurekaServerContext() throws Exception {
        // 第一步：加载 eureka-server.properties 文件中的配置
        // 加载完成的配置文件，通过 EurekaServiceConfig 接口定义的方法，就可以直接获取了
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);

        ApplicationInfoManager applicationInfoManager = null;
        // 第二步：初始化 eureka-server 内部的一个 eurekaClient
        // 用于跟其他节点进行通信
        if (eurekaClient == null) {
            // 读取 eureka-client.properties 文件中的配置
            // 通过 EurekaInstanceConfig 接口定义的方法就可以获取了
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext())
                    ? new CloudInstanceConfig()
                    : new MyDataCenterInstanceConfig();

            // 有一个 eurekaClient 就对应了一个应用程序
            // 因为 eureka-server 也是一个 eureka-client
            // 所以也需要 client的配置文件
            // 设置一些 client 的信息，方便之后的管理
            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig,
                    // 返回一个 InstanceInfo
                    // 也就是 client 向 server 注册所需要的信息
                    new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());

            // 面向配置项接口
            // 内部 eurekaClient 的配置项
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();

            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }

        // 第三步：处理注册相关逻辑
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            // 可以感知到 eureka server 集群的服务实例注册表
            // 如果有一个 eureka server 集群的话
            // 这里包含了其他的 server 节点中的服务实例注册表的信息
            registry = new PeerAwareInstanceRegistryImpl(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }

        // 第四步：获取 peer 节点
        // 可能一个 peerEurekaNode 代表一个 eureka 节点
        // peerEurekaNodes 应该代表的是 eureka 集群
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );

        // 第五步：完成eureka-server的上下文（context）构建
        // 包含 server 所有的信息了
        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );

        EurekaServerContextHolder.initialize(serverContext);

        serverContext.initialize();
        logger.info("Initialized server context");

        // Copy registry from neighboring eureka node
        // 第六步：从相邻的一个eureka节点拷贝注册表信息
        // 如果相邻的一个节点失败，那么就换到另一个节点
        int registryCount = registry.syncUp();
        registry.openForTraffic(applicationInfoManager, registryCount);

        // Register all monitoring statistics.
        // 第七步：注册所有监控统计信息
        EurekaMonitors.registerAllStats();
    }

    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );

        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
