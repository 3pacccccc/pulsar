/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.broker.resourcegroup.ResourceUsageTransportManager.DISABLE_RESOURCE_USAGE_TRANSPORT_MANAGER;
import static org.apache.pulsar.common.naming.SystemTopicNames.isTransactionInternalName;
import com.google.common.annotations.VisibleForTesting;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.servlet.ServletException;
import javax.websocket.DeploymentException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.common.util.OrderedExecutor;
import org.apache.bookkeeper.common.util.OrderedScheduler;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.mledger.LedgerOffloader;
import org.apache.bookkeeper.mledger.LedgerOffloaderFactory;
import org.apache.bookkeeper.mledger.LedgerOffloaderStats;
import org.apache.bookkeeper.mledger.ManagedLedgerFactory;
import org.apache.bookkeeper.mledger.impl.NullLedgerOffloader;
import org.apache.bookkeeper.mledger.offload.Offloaders;
import org.apache.bookkeeper.mledger.offload.OffloadersCache;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.PulsarVersion;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.broker.authorization.AuthorizationService;
import org.apache.pulsar.broker.intercept.BrokerInterceptor;
import org.apache.pulsar.broker.intercept.BrokerInterceptors;
import org.apache.pulsar.broker.loadbalance.LeaderBroker;
import org.apache.pulsar.broker.loadbalance.LeaderElectionService;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.LoadReportUpdaterTask;
import org.apache.pulsar.broker.loadbalance.LoadResourceQuotaUpdaterTask;
import org.apache.pulsar.broker.loadbalance.LoadSheddingTask;
import org.apache.pulsar.broker.loadbalance.extensions.ExtensibleLoadManagerImpl;
import org.apache.pulsar.broker.lookup.v1.TopicLookup;
import org.apache.pulsar.broker.namespace.NamespaceService;
import org.apache.pulsar.broker.protocol.ProtocolHandlers;
import org.apache.pulsar.broker.qos.DefaultMonotonicClock;
import org.apache.pulsar.broker.qos.MonotonicClock;
import org.apache.pulsar.broker.resourcegroup.ResourceGroupService;
import org.apache.pulsar.broker.resourcegroup.ResourceUsageTopicTransportManager;
import org.apache.pulsar.broker.resourcegroup.ResourceUsageTransportManager;
import org.apache.pulsar.broker.resources.ClusterResources;
import org.apache.pulsar.broker.resources.PulsarResources;
import org.apache.pulsar.broker.rest.Topics;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.HealthChecker;
import org.apache.pulsar.broker.service.PulsarMetadataEventSynchronizer;
import org.apache.pulsar.broker.service.SystemTopicBasedTopicPoliciesService;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.TopicPoliciesService;
import org.apache.pulsar.broker.service.TransactionBufferSnapshotServiceFactory;
import org.apache.pulsar.broker.service.schema.SchemaRegistryService;
import org.apache.pulsar.broker.service.schema.SchemaStorageFactory;
import org.apache.pulsar.broker.stats.MetricsGenerator;
import org.apache.pulsar.broker.stats.OpenTelemetryConsumerStats;
import org.apache.pulsar.broker.stats.OpenTelemetryProducerStats;
import org.apache.pulsar.broker.stats.OpenTelemetryReplicatedSubscriptionStats;
import org.apache.pulsar.broker.stats.OpenTelemetryReplicatorStats;
import org.apache.pulsar.broker.stats.OpenTelemetryTopicStats;
import org.apache.pulsar.broker.stats.OpenTelemetryTransactionCoordinatorStats;
import org.apache.pulsar.broker.stats.OpenTelemetryTransactionPendingAckStoreStats;
import org.apache.pulsar.broker.stats.PulsarBrokerOpenTelemetry;
import org.apache.pulsar.broker.stats.prometheus.PrometheusMetricsServlet;
import org.apache.pulsar.broker.stats.prometheus.PrometheusRawMetricsProvider;
import org.apache.pulsar.broker.stats.prometheus.PulsarPrometheusMetricsServlet;
import org.apache.pulsar.broker.storage.BookkeeperManagedLedgerStorageClass;
import org.apache.pulsar.broker.storage.ManagedLedgerStorage;
import org.apache.pulsar.broker.storage.ManagedLedgerStorageClass;
import org.apache.pulsar.broker.transaction.buffer.TransactionBufferProvider;
import org.apache.pulsar.broker.transaction.buffer.impl.TransactionBufferClientImpl;
import org.apache.pulsar.broker.transaction.pendingack.TransactionPendingAckStoreProvider;
import org.apache.pulsar.broker.transaction.pendingack.impl.MLPendingAckStoreProvider;
import org.apache.pulsar.broker.validator.MultipleListenerValidator;
import org.apache.pulsar.broker.validator.TransactionBatchedWriteValidator;
import org.apache.pulsar.broker.web.WebService;
import org.apache.pulsar.broker.web.plugin.servlet.AdditionalServlet;
import org.apache.pulsar.broker.web.plugin.servlet.AdditionalServletWithClassLoader;
import org.apache.pulsar.broker.web.plugin.servlet.AdditionalServletWithPulsarService;
import org.apache.pulsar.broker.web.plugin.servlet.AdditionalServlets;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.transaction.TransactionBufferClient;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.impl.conf.ConfigurationDataUtils;
import org.apache.pulsar.client.internal.PropertiesUtils;
import org.apache.pulsar.client.util.ExecutorProvider;
import org.apache.pulsar.client.util.ScheduledExecutorProvider;
import org.apache.pulsar.common.conf.InternalConfigurationData;
import org.apache.pulsar.common.configuration.PulsarConfigurationLoader;
import org.apache.pulsar.common.configuration.VipStatus;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.naming.TopicVersion;
import org.apache.pulsar.common.policies.data.ClusterDataImpl;
import org.apache.pulsar.common.policies.data.OffloadPoliciesImpl;
import org.apache.pulsar.common.protocol.schema.SchemaStorage;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.GracefulExecutorServicesShutdown;
import org.apache.pulsar.common.util.Reflections;
import org.apache.pulsar.common.util.ThreadDumpUtil;
import org.apache.pulsar.common.util.netty.EventLoopUtil;
import org.apache.pulsar.compaction.CompactionServiceFactory;
import org.apache.pulsar.compaction.Compactor;
import org.apache.pulsar.compaction.PulsarCompactionServiceFactory;
import org.apache.pulsar.compaction.StrategicTwoPhaseCompactor;
import org.apache.pulsar.compaction.TopicCompactionService;
import org.apache.pulsar.functions.worker.ErrorNotifier;
import org.apache.pulsar.functions.worker.WorkerConfig;
import org.apache.pulsar.functions.worker.WorkerService;
import org.apache.pulsar.metadata.api.MetadataStore;
import org.apache.pulsar.metadata.api.MetadataStoreConfig;
import org.apache.pulsar.metadata.api.MetadataStoreException;
import org.apache.pulsar.metadata.api.MetadataStoreFactory;
import org.apache.pulsar.metadata.api.Notification;
import org.apache.pulsar.metadata.api.NotificationType;
import org.apache.pulsar.metadata.api.coordination.CoordinationService;
import org.apache.pulsar.metadata.api.coordination.LeaderElectionState;
import org.apache.pulsar.metadata.api.extended.MetadataStoreExtended;
import org.apache.pulsar.metadata.api.extended.SessionEvent;
import org.apache.pulsar.metadata.coordination.impl.CoordinationServiceImpl;
import org.apache.pulsar.packages.management.core.PackagesManagement;
import org.apache.pulsar.packages.management.core.PackagesStorage;
import org.apache.pulsar.packages.management.core.PackagesStorageProvider;
import org.apache.pulsar.packages.management.core.impl.DefaultPackagesStorageConfiguration;
import org.apache.pulsar.packages.management.core.impl.PackagesManagementImpl;
import org.apache.pulsar.policies.data.loadbalancer.AdvertisedListener;
import org.apache.pulsar.transaction.coordinator.TransactionMetadataStoreProvider;
import org.apache.pulsar.transaction.coordinator.impl.MLTransactionMetadataStoreProvider;
import org.apache.pulsar.websocket.WebSocketConsumerServlet;
import org.apache.pulsar.websocket.WebSocketMultiTopicConsumerServlet;
import org.apache.pulsar.websocket.WebSocketProducerServlet;
import org.apache.pulsar.websocket.WebSocketReaderServlet;
import org.apache.pulsar.websocket.WebSocketService;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class for Pulsar broker service.
 */
@Getter(AccessLevel.PUBLIC)
@Setter(AccessLevel.PROTECTED)
public class PulsarService implements AutoCloseable, ShutdownService {
    private static final Logger LOG = LoggerFactory.getLogger(PulsarService.class);
    private static final double GRACEFUL_SHUTDOWN_TIMEOUT_RATIO_OF_TOTAL_TIMEOUT = 0.5d;
    private static final int DEFAULT_MONOTONIC_CLOCK_GRANULARITY_MILLIS = 8;
    private final ServiceConfiguration config;
    private NamespaceService nsService = null;
    private ManagedLedgerStorage managedLedgerStorage = null;
    private LeaderElectionService leaderElectionService = null;
    private BrokerService brokerService = null;
    private WebService webService = null;
    private WebSocketService webSocketService = null;
    private TopicPoliciesService topicPoliciesService = TopicPoliciesService.DISABLED;
    private BookKeeperClientFactory bkClientFactory;
    protected CompactionServiceFactory compactionServiceFactory;
    private StrategicTwoPhaseCompactor strategicCompactor;
    private ResourceUsageTransportManager resourceUsageTransportManager;
    private ResourceGroupService resourceGroupServiceManager;
    private final Clock clock;

    private final ScheduledExecutorService executor;

    private OrderedExecutor orderedExecutor;
    private final ScheduledExecutorService loadManagerExecutor;
    private ScheduledExecutorService compactorExecutor;
    private OrderedScheduler offloaderScheduler;
    private OrderedScheduler offloaderReadExecutor;
    private OffloadersCache offloadersCache = new OffloadersCache();
    private LedgerOffloader defaultOffloader;
    private LedgerOffloaderStats offloaderStats;
    private Map<NamespaceName, LedgerOffloader> ledgerOffloaderMap = new ConcurrentHashMap<>();
    private ScheduledFuture<?> loadReportTask = null;
    private LoadSheddingTask loadSheddingTask = null;
    private ScheduledFuture<?> loadResourceQuotaTask = null;
    private final AtomicReference<LoadManager> loadManager = new AtomicReference<>();
    private PulsarAdmin adminClient = null;
    private PulsarClient client = null;
    private final String bindAddress;
    /**
     * The host component of the broker's canonical name.
     */
    private final String advertisedAddress;
    private String webServiceAddress;
    private String webServiceAddressTls;
    private String brokerServiceUrl;
    private String brokerServiceUrlTls;
    private final String brokerVersion;
    private SchemaStorage schemaStorage = null;
    private SchemaRegistryService schemaRegistryService = null;
    private final WorkerConfig workerConfig;
    private final Optional<WorkerService> functionWorkerService;
    private ProtocolHandlers protocolHandlers = null;

    private final Consumer<Integer> processTerminator;
    protected final EventLoopGroup ioEventLoopGroup;
    private final ExecutorProvider brokerClientSharedInternalExecutorProvider;
    private final ExecutorProvider brokerClientSharedExternalExecutorProvider;
    private final ScheduledExecutorProvider brokerClientSharedScheduledExecutorProvider;
    private final Timer brokerClientSharedTimer;
    private final ExecutorProvider brokerClientSharedLookupExecutorProvider;

    private MetricsGenerator metricsGenerator;
    private final PulsarBrokerOpenTelemetry openTelemetry;
    private OpenTelemetryTopicStats openTelemetryTopicStats;
    private OpenTelemetryConsumerStats openTelemetryConsumerStats;
    private OpenTelemetryProducerStats openTelemetryProducerStats;
    private OpenTelemetryReplicatorStats openTelemetryReplicatorStats;
    private OpenTelemetryReplicatedSubscriptionStats openTelemetryReplicatedSubscriptionStats;
    private OpenTelemetryTransactionCoordinatorStats openTelemetryTransactionCoordinatorStats;
    private OpenTelemetryTransactionPendingAckStoreStats openTelemetryTransactionPendingAckStoreStats;

    private TransactionMetadataStoreService transactionMetadataStoreService;
    private TransactionBufferProvider transactionBufferProvider;
    private TransactionBufferClient transactionBufferClient;
    private HashedWheelTimer transactionTimer;

    private BrokerInterceptor brokerInterceptor;
    private AdditionalServlets brokerAdditionalServlets;

    // packages management service
    private PackagesManagement packagesManagement = null;
    private PulsarPrometheusMetricsServlet metricsServlet;
    private List<PrometheusRawMetricsProvider> pendingMetricsProviders;

    private MetadataStoreExtended localMetadataStore;
    private PulsarMetadataEventSynchronizer localMetadataSynchronizer;
    private CoordinationService coordinationService;
    private TransactionBufferSnapshotServiceFactory transactionBufferSnapshotServiceFactory;
    private MetadataStore configurationMetadataStore;
    private PulsarMetadataEventSynchronizer configMetadataSynchronizer;
    private boolean shouldShutdownConfigurationMetadataStore;

    private PulsarResources pulsarResources;

    private TransactionPendingAckStoreProvider transactionPendingAckStoreProvider;
    private final ExecutorProvider transactionExecutorProvider;
    private final ExecutorProvider transactionSnapshotRecoverExecutorProvider;
    private final MonotonicClock monotonicClock;
    private String brokerId;
    private final CompletableFuture<Void> readyForIncomingRequestsFuture = new CompletableFuture<>();
    private final List<Runnable> pendingTasksBeforeReadyForIncomingRequests = new ArrayList<>();

    public enum State {
        Init, Started, Closing, Closed
    }

    private volatile State state;

    private final ReentrantLock mutex = new ReentrantLock();
    private final Condition isClosedCondition = mutex.newCondition();
    private volatile CompletableFuture<Void> closeFuture;
    // key is listener name, value is pulsar address and pulsar ssl address
    private Map<String, AdvertisedListener> advertisedListeners;
    private volatile HealthChecker healthChecker;

    public PulsarService(ServiceConfiguration config) {
        this(config, Optional.empty(), (exitCode) -> LOG.info("Process termination requested with code {}. "
                 + "Ignoring, as this constructor is intended for tests. ", exitCode));
    }

    public PulsarService(ServiceConfiguration config, Optional<WorkerService> functionWorkerService,
                         Consumer<Integer> processTerminator) {
        this(config, new WorkerConfig(), functionWorkerService, processTerminator);
    }

    public PulsarService(ServiceConfiguration config,
                         WorkerConfig workerConfig,
                         Optional<WorkerService> functionWorkerService,
                         Consumer<Integer> processTerminator) {
        this(config, workerConfig,  functionWorkerService, processTerminator, null);
    }

    public PulsarService(ServiceConfiguration config,
                         WorkerConfig workerConfig,
                         Optional<WorkerService> functionWorkerService,
                         Consumer<Integer> processTerminator,
                         Consumer<AutoConfiguredOpenTelemetrySdkBuilder> openTelemetrySdkBuilderCustomizer) {
        state = State.Init;

        // Validate correctness of configuration
        PulsarConfigurationLoader.isComplete(config);
        TransactionBatchedWriteValidator.validate(config);
        this.config = config;
        this.clock = Clock.systemUTC();

        this.openTelemetry = new PulsarBrokerOpenTelemetry(config, openTelemetrySdkBuilderCustomizer);

        // validate `advertisedAddress`, `advertisedListeners`, `internalListenerName`
        this.advertisedListeners = MultipleListenerValidator.validateAndAnalysisAdvertisedListener(config);

        // the advertised address is defined as the host component of the broker's canonical name.
        this.advertisedAddress = ServiceConfigurationUtils.getDefaultOrConfiguredAddress(config.getAdvertisedAddress());

        // use `internalListenerName` listener as `advertisedAddress`
        this.bindAddress = ServiceConfigurationUtils.getDefaultOrConfiguredAddress(config.getBindAddress());
        this.brokerVersion = PulsarVersion.getVersion();
        this.processTerminator = processTerminator;
        this.loadManagerExecutor = Executors
                .newSingleThreadScheduledExecutor(new ExecutorProvider.ExtendedThreadFactory("pulsar-load-manager"));
        this.workerConfig = workerConfig;
        this.functionWorkerService = functionWorkerService;
        this.executor = Executors.newScheduledThreadPool(config.getNumExecutorThreadPoolSize(),
                new ExecutorProvider.ExtendedThreadFactory("pulsar"));

        if (config.isTransactionCoordinatorEnabled()) {
            this.transactionExecutorProvider = new ExecutorProvider(this.getConfiguration()
                    .getNumTransactionReplayThreadPoolSize(), "pulsar-transaction-executor");
            this.transactionSnapshotRecoverExecutorProvider = new ExecutorProvider(this.getConfiguration()
                    .getNumTransactionReplayThreadPoolSize(), "pulsar-transaction-snapshot-recover");
        } else {
            this.transactionExecutorProvider = null;
            this.transactionSnapshotRecoverExecutorProvider = null;
        }

        this.ioEventLoopGroup = EventLoopUtil.newEventLoopGroup(config.getNumIOThreads(), config.isEnableBusyWait(),
                new DefaultThreadFactory("pulsar-io"));
        // the internal executor is not used in the broker client or replication clients since this executor is
        // used for consumers and the transaction support in the client.
        // since an instance is required, a single threaded shared instance is used for all broker client instances
        this.brokerClientSharedInternalExecutorProvider =
                new ExecutorProvider(1, "broker-client-shared-internal-executor");
        // the external executor is not used in the broker client or replication clients since this executor is
        // used for consumer listeners.
        // since an instance is required, a single threaded shared instance is used for all broker client instances
        this.brokerClientSharedExternalExecutorProvider =
                new ExecutorProvider(1, "broker-client-shared-external-executor");
        this.brokerClientSharedScheduledExecutorProvider =
                new ScheduledExecutorProvider(1, "broker-client-shared-scheduled-executor");
        this.brokerClientSharedTimer =
                new HashedWheelTimer(new DefaultThreadFactory("broker-client-shared-timer"), 1, TimeUnit.MILLISECONDS);
        this.brokerClientSharedLookupExecutorProvider =
                new ScheduledExecutorProvider(1, "broker-client-shared-lookup-executor");

        // here in the constructor we don't have the offloader scheduler yet
        this.offloaderStats = LedgerOffloaderStats.create(false, false, null, 0);

        this.monotonicClock = createMonotonicClock(config);
    }

    protected MonotonicClock createMonotonicClock(ServiceConfiguration config) {
        return new DefaultMonotonicClock();
    }

    public MetadataStore createConfigurationMetadataStore(PulsarMetadataEventSynchronizer synchronizer,
                                                          OpenTelemetry openTelemetry)
            throws MetadataStoreException {
        String configFilePath = config.getMetadataStoreConfigPath();
        if (StringUtils.isNotBlank(config.getConfigurationStoreConfigPath())) {
            configFilePath = config.getConfigurationStoreConfigPath();
        }
        return MetadataStoreFactory.create(config.getConfigurationMetadataStoreUrl(),
                MetadataStoreConfig.builder()
                        .sessionTimeoutMillis((int) config.getMetadataStoreSessionTimeoutMillis())
                        .allowReadOnlyOperations(config.isMetadataStoreAllowReadOnlyOperations())
                        .configFilePath(configFilePath)
                        .batchingEnabled(config.isMetadataStoreBatchingEnabled())
                        .batchingMaxDelayMillis(config.getMetadataStoreBatchingMaxDelayMillis())
                        .batchingMaxOperations(config.getMetadataStoreBatchingMaxOperations())
                        .batchingMaxSizeKb(config.getMetadataStoreBatchingMaxSizeKb())
                        .metadataStoreName(MetadataStoreConfig.CONFIGURATION_METADATA_STORE)
                        .synchronizer(synchronizer)
                        .openTelemetry(openTelemetry)
                        .build());
    }

    /**
     * Close the session to the metadata service.
     * <p>
     * This will immediately release all the resource locks held by this broker on the coordination service.
     *
     * @throws Exception if the close operation fails
     */
    public void closeMetadataServiceSession() throws Exception {
        localMetadataStore.close();
    }

    private void closeLeaderElectionService() throws Exception {
        if (ExtensibleLoadManagerImpl.isLoadManagerExtensionEnabled(this)) {
            ExtensibleLoadManagerImpl.get(loadManager.get()).getLeaderElectionService().close();
        } else {
            if (this.leaderElectionService != null) {
                this.leaderElectionService.close();
                this.leaderElectionService = null;
            }
        }
    }

    @Override
    public void close() throws PulsarServerException {
        try {
            closeAsync().get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PulsarServerException) {
                throw (PulsarServerException) cause;
            } else if (getConfiguration().getBrokerShutdownTimeoutMs() == 0
                    && (cause instanceof TimeoutException || cause instanceof CancellationException)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                        "Shutdown timeout ignored when timeout is 0, "
                            + "which is primarily used in tests to forcefully shutdown the broker",
                        cause);
                }
            } else {
                throw new PulsarServerException(cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Close the current pulsar service. All resources are released.
     */
    public CompletableFuture<Void> closeAsync() {
        mutex.lock();
        try {
            // Close protocol handler before unloading namespace bundles because protocol handlers might maintain
            // Pulsar clients that could send lookup requests that affect unloading.
            if (protocolHandlers != null) {
                protocolHandlers.close();
                protocolHandlers = null;
            }
            if (closeFuture != null) {
                return closeFuture;
            }
            LOG.info("Closing PulsarService");
            if (topicPoliciesService != null) {
                topicPoliciesService.close();
            }
            if (brokerService != null) {
                brokerService.unloadNamespaceBundlesGracefully();
            }
            // It only tells the Pulsar clients that this service is not ready to serve for the lookup requests
            state = State.Closing;

            if (healthChecker != null) {
                healthChecker.close();
                healthChecker = null;
            }

            // close the service in reverse order v.s. in which they are started
            if (this.resourceUsageTransportManager != null) {
                try {
                    this.resourceUsageTransportManager.close();
                } catch (Exception e) {
                    LOG.warn("ResourceUsageTransportManager closing failed {}", e.getMessage());
                }
                this.resourceUsageTransportManager = null;
            }
            if (this.resourceGroupServiceManager != null) {
                try {
                    this.resourceGroupServiceManager.close();
                } catch (Exception e) {
                    LOG.warn("ResourceGroupServiceManager closing failed {}", e.getMessage());
                }
                this.resourceGroupServiceManager = null;
            }

            if (this.webService != null) {
                try {
                    this.webService.close();
                    this.webService = null;
                } catch (Exception e) {
                    LOG.error("Web service closing failed", e);
                    // Even if the web service fails to close, the graceful shutdown process continues
                }
            }

            resetMetricsServlet();
            if (openTelemetry != null) {
                openTelemetry.close();
            }

            if (this.compactionServiceFactory != null) {
                try {
                    this.compactionServiceFactory.close();
                } catch (Exception e) {
                    LOG.warn("CompactionServiceFactory closing failed {}", e.getMessage());
                }
                this.compactionServiceFactory = null;
            }

            if (this.webSocketService != null) {
                this.webSocketService.close();
            }

            if (brokerAdditionalServlets != null) {
                brokerAdditionalServlets.close();
                brokerAdditionalServlets = null;
            }

            GracefulExecutorServicesShutdown executorServicesShutdown =
                    GracefulExecutorServicesShutdown
                            .initiate()
                            .timeout(
                                    Duration.ofMillis(
                                            (long) (GRACEFUL_SHUTDOWN_TIMEOUT_RATIO_OF_TOTAL_TIMEOUT
                                                    * getConfiguration()
                                                    .getBrokerShutdownTimeoutMs())));

            // cancel loadShedding task and shutdown the loadManager executor before shutting down the broker
            cancelLoadBalancerTasks();
            executorServicesShutdown.shutdown(loadManagerExecutor);

            List<CompletableFuture<Void>> asyncCloseFutures = new ArrayList<>();
            if (this.brokerService != null) {
                CompletableFuture<Void> brokerCloseFuture = this.brokerService.closeAsync();
                if (this.transactionMetadataStoreService != null) {
                    asyncCloseFutures.add(brokerCloseFuture.whenComplete((__, ___) -> {
                        // close transactionMetadataStoreService after the broker has been closed
                        this.transactionMetadataStoreService.close();
                        this.transactionMetadataStoreService = null;
                    }));
                } else {
                    asyncCloseFutures.add(brokerCloseFuture);
                }
                this.brokerService = null;
            }

            if (this.managedLedgerStorage != null) {
                try {
                    this.managedLedgerStorage.close();
                } catch (Exception e) {
                    LOG.warn("ManagedLedgerClientFactory closing failed {}", e.getMessage());
                }
                this.managedLedgerStorage = null;
            }

            if (bkClientFactory != null) {
                this.bkClientFactory.close();
                this.bkClientFactory = null;
            }

            closeLeaderElectionService();

            if (adminClient != null) {
                adminClient.close();
                adminClient = null;
            }

            if (transactionBufferSnapshotServiceFactory != null) {
                transactionBufferSnapshotServiceFactory.close();
                transactionBufferSnapshotServiceFactory = null;
            }

            if (transactionBufferClient != null) {
                transactionBufferClient.close();
            }


            if (client != null) {
                client.close();
                client = null;
            }

            if (nsService != null) {
                nsService.close();
                nsService = null;
            }

            executorServicesShutdown.shutdown(compactorExecutor);
            executorServicesShutdown.shutdown(offloaderScheduler);
            executorServicesShutdown.shutdown(offloaderReadExecutor);
            executorServicesShutdown.shutdown(executor);
            executorServicesShutdown.shutdown(orderedExecutor);

            LoadManager loadManager = this.loadManager.get();
            if (loadManager != null) {
                loadManager.stop();
            }

            if (schemaRegistryService != null) {
                schemaRegistryService.close();
            }

            offloadersCache.close();

            if (coordinationService != null) {
                try {
                    coordinationService.close();
                } catch (Exception e) {
                    Throwable cause = FutureUtil.unwrapCompletionException(e);
                    if (!(cause instanceof MetadataStoreException.AlreadyClosedException)) {
                        throw e;
                    }
                }
            }

            asyncCloseFutures.add(closeLocalMetadataStore());
            if (configMetadataSynchronizer != null) {
                asyncCloseFutures.add(configMetadataSynchronizer.closeAsync());
            }
            if (configurationMetadataStore != null && shouldShutdownConfigurationMetadataStore) {
                configurationMetadataStore.close();
            }

            if (transactionExecutorProvider != null) {
                transactionExecutorProvider.shutdownNow();
            }
            if (transactionSnapshotRecoverExecutorProvider != null) {
                transactionSnapshotRecoverExecutorProvider.shutdownNow();
            }
            if (transactionTimer != null) {
                transactionTimer.stop();
            }
            MLPendingAckStoreProvider.closeBufferedWriterMetrics();
            MLTransactionMetadataStoreProvider.closeBufferedWriterMetrics();
            if (this.offloaderStats != null) {
                this.offloaderStats.close();
            }

            brokerClientSharedExternalExecutorProvider.shutdownNow();
            brokerClientSharedInternalExecutorProvider.shutdownNow();
            brokerClientSharedScheduledExecutorProvider.shutdownNow();
            brokerClientSharedLookupExecutorProvider.shutdownNow();
            brokerClientSharedTimer.stop();
            if (monotonicClock instanceof AutoCloseable c) {
                c.close();
            }

            if (openTelemetryTransactionPendingAckStoreStats != null) {
                openTelemetryTransactionPendingAckStoreStats.close();
                openTelemetryTransactionPendingAckStoreStats = null;
            }
            if (openTelemetryTransactionCoordinatorStats != null) {
                openTelemetryTransactionCoordinatorStats.close();
                openTelemetryTransactionCoordinatorStats = null;
            }
            if (openTelemetryReplicatorStats != null) {
                openTelemetryReplicatorStats.close();
                openTelemetryReplicatorStats = null;
            }
            if (openTelemetryProducerStats != null) {
                openTelemetryProducerStats.close();
                openTelemetryProducerStats = null;
            }
            if (openTelemetryConsumerStats != null) {
                openTelemetryConsumerStats.close();
                openTelemetryConsumerStats = null;
            }
            if (openTelemetryTopicStats != null) {
                openTelemetryTopicStats.close();
                openTelemetryTopicStats = null;
            }

            asyncCloseFutures.add(EventLoopUtil.shutdownGracefully(ioEventLoopGroup));


            // add timeout handling for closing executors
            asyncCloseFutures.add(executorServicesShutdown.handle());

            closeFuture = addTimeoutHandling(FutureUtil.waitForAllAndSupportCancel(asyncCloseFutures));
            closeFuture.handle((v, t) -> {
                if (t == null) {
                    LOG.info("Closed");
                } else if (t instanceof CancellationException) {
                    LOG.info("Closed (shutdown cancelled)");
                } else if (t instanceof TimeoutException) {
                    LOG.info("Closed (shutdown timeout)");
                } else {
                    LOG.warn("Closed with errors", t);
                }
                state = State.Closed;
                isClosedCondition.signalAll();
                return null;
            });
            return closeFuture;
        } catch (Exception e) {
            PulsarServerException pse;
            if (e instanceof CompletionException && e.getCause() instanceof MetadataStoreException) {
                pse = new PulsarServerException(MetadataStoreException.unwrap(e));
            } else if (e.getCause() instanceof CompletionException
                    && e.getCause().getCause() instanceof MetadataStoreException) {
                pse = new PulsarServerException(MetadataStoreException.unwrap(e.getCause()));
            } else {
                pse = new PulsarServerException(e);
            }
            return FutureUtil.failedFuture(pse);
        } finally {
            mutex.unlock();
        }
    }

    private synchronized void resetMetricsServlet() {
        metricsServlet = null;
    }

    private CompletableFuture<Void> addTimeoutHandling(CompletableFuture<Void> future) {
        long brokerShutdownTimeoutMs = getConfiguration().getBrokerShutdownTimeoutMs();
        if (brokerShutdownTimeoutMs <= 0) {
            return future;
        }
        ScheduledExecutorService shutdownExecutor = Executors.newSingleThreadScheduledExecutor(
                new ExecutorProvider.ExtendedThreadFactory(getClass().getSimpleName() + "-shutdown"));
        FutureUtil.addTimeoutHandling(future,
                Duration.ofMillis(brokerShutdownTimeoutMs),
                shutdownExecutor, () -> FutureUtil.createTimeoutException("Timeout in close", getClass(), "close"));
        future.handle((v, t) -> {
            if (t instanceof TimeoutException) {
                LOG.info("Shutdown timed out after {} ms", brokerShutdownTimeoutMs);
                LOG.info(ThreadDumpUtil.buildThreadDiagnosticString());
            }
            // shutdown the shutdown executor
            shutdownExecutor.shutdownNow();
            return null;
        });
        return future;
    }

    /**
     * Get the current service configuration.
     *
     * @return the current service configuration
     */
    public ServiceConfiguration getConfiguration() {
        return this.config;
    }

    /**
     * Get the current function worker service configuration.
     *
     * @return the current function worker service configuration.
     */
    public Optional<WorkerConfig> getWorkerConfig() {
        return functionWorkerService.map(service -> workerConfig);
    }

    public Map<String, String> getProtocolDataToAdvertise() {
        if (null == protocolHandlers) {
            return Collections.emptyMap();
        } else {
            return protocolHandlers.getProtocolDataToAdvertise();
        }
    }

    /**
     * Start the pulsar service instance.
     */
    public void start() throws PulsarServerException {
        LOG.info("Starting Pulsar Broker service; version: '{}'",
                (brokerVersion != null ? brokerVersion : "unknown"));
        LOG.info("Git Revision {}", PulsarVersion.getGitSha());
        LOG.info("Git Branch {}", PulsarVersion.getGitBranch());
        LOG.info("Built by {} on {} at {}",
                PulsarVersion.getBuildUser(),
                PulsarVersion.getBuildHost(),
                PulsarVersion.getBuildTime());

        long startTimestamp = System.currentTimeMillis();  // start time mills

        mutex.lock();
        try {
            if (state != State.Init) {
                throw new PulsarServerException("Cannot start the service once it was stopped");
            }

            if (config.getWebServicePort().isEmpty() && config.getWebServicePortTls().isEmpty()) {
                throw new IllegalArgumentException("webServicePort/webServicePortTls must be present");
            }

            if (config.isAuthorizationEnabled() && !config.isAuthenticationEnabled()) {
                throw new IllegalStateException("Invalid broker configuration. Authentication must be enabled with "
                        + "authenticationEnabled=true when authorization is enabled with authorizationEnabled=true.");
            }

            if (config.getDefaultRetentionSizeInMB() > 0
                    && config.getBacklogQuotaDefaultLimitBytes() > 0
                    && config.getBacklogQuotaDefaultLimitBytes()
                    >= (config.getDefaultRetentionSizeInMB() * 1024L * 1024L)) {
                throw new IllegalArgumentException(String.format("The retention size must > the backlog quota limit "
                                + "size, but the configured backlog quota limit bytes is %d, the retention size is %d",
                        config.getBacklogQuotaDefaultLimitBytes(),
                        config.getDefaultRetentionSizeInMB() * 1024L * 1024L));
            }

            if (config.getDefaultRetentionTimeInMinutes() > 0
                    && config.getBacklogQuotaDefaultLimitSecond() > 0
                    && config.getBacklogQuotaDefaultLimitSecond() >= config.getDefaultRetentionTimeInMinutes() * 60) {
                throw new IllegalArgumentException(String.format("The retention time must > the backlog quota limit "
                                + "time, but the configured backlog quota limit time duration is %d, "
                                + "the retention time duration is %d",
                        config.getBacklogQuotaDefaultLimitSecond(),
                        config.getDefaultRetentionTimeInMinutes() * 60));
            }

            openTelemetryTopicStats = new OpenTelemetryTopicStats(this);
            openTelemetryConsumerStats = new OpenTelemetryConsumerStats(this);
            openTelemetryProducerStats = new OpenTelemetryProducerStats(this);
            openTelemetryReplicatorStats = new OpenTelemetryReplicatorStats(this);
            openTelemetryReplicatedSubscriptionStats = new OpenTelemetryReplicatedSubscriptionStats(this);

            localMetadataSynchronizer = StringUtils.isNotBlank(config.getMetadataSyncEventTopic())
                    ? new PulsarMetadataEventSynchronizer(this, config.getMetadataSyncEventTopic())
                    : null;
            localMetadataStore = createLocalMetadataStore(localMetadataSynchronizer,
                    openTelemetry.getOpenTelemetryService().getOpenTelemetry());
            localMetadataStore.registerSessionListener(this::handleMetadataSessionEvent);

            coordinationService = new CoordinationServiceImpl(localMetadataStore);

            if (config.isConfigurationStoreSeparated()) {
                configMetadataSynchronizer = StringUtils.isNotBlank(config.getConfigurationMetadataSyncEventTopic())
                        ? new PulsarMetadataEventSynchronizer(this, config.getConfigurationMetadataSyncEventTopic())
                        : null;
                configurationMetadataStore = createConfigurationMetadataStore(configMetadataSynchronizer,
                        openTelemetry.getOpenTelemetryService().getOpenTelemetry());
                shouldShutdownConfigurationMetadataStore = true;
            } else {
                configurationMetadataStore = localMetadataStore;
                shouldShutdownConfigurationMetadataStore = false;
            }
            pulsarResources = newPulsarResources();

            orderedExecutor = newOrderedExecutor();

            // Initialize the message protocol handlers
            protocolHandlers = ProtocolHandlers.load(config);
            protocolHandlers.initialize(config);

            // Now we are ready to start services
            this.bkClientFactory = newBookKeeperClientFactory();

            managedLedgerStorage = newManagedLedgerStorage();

            this.brokerService = newBrokerService(this);

            // Start load management service (even if load balancing is disabled)
            this.loadManager.set(LoadManager.create(this));

            // needs load management service and before start broker service,
            this.startNamespaceService();

            schemaStorage = createAndStartSchemaStorage();
            schemaRegistryService = SchemaRegistryService.create(
                    schemaStorage, config.getSchemaRegistryCompatibilityCheckers(), this);

            OffloadPoliciesImpl defaultOffloadPolicies =
                    OffloadPoliciesImpl.create(this.getConfiguration().getProperties());

            OrderedScheduler offloaderScheduler = getOffloaderScheduler(defaultOffloadPolicies);
            int interval = config.getManagedLedgerStatsPeriodSeconds();
            boolean exposeTopicMetrics = config.isExposeTopicLevelMetricsInPrometheus();

            offloaderStats = LedgerOffloaderStats.create(config.isExposeManagedLedgerMetricsInPrometheus(),
                    exposeTopicMetrics, offloaderScheduler, interval);
            this.defaultOffloader = createManagedLedgerOffloader(defaultOffloadPolicies);

            setBrokerInterceptor(newBrokerInterceptor());
            // use getter to support mocking getBrokerInterceptor method in tests
            BrokerInterceptor interceptor = getBrokerInterceptor();
            if (interceptor != null) {
                brokerService.setInterceptor(interceptor);
                interceptor.initialize(this);
            }
            brokerService.start();

            // Load additional servlets
            this.brokerAdditionalServlets = AdditionalServlets.load(config);

            this.webService = new WebService(this);
            createMetricsServlet();
            this.addWebServerHandlers(webService, metricsServlet, this.config);
            this.webService.start();

            // Refresh addresses and update configuration, since the port might have been dynamically assigned
            if (config.getBrokerServicePort().equals(Optional.of(0))) {
                config.setBrokerServicePort(brokerService.getListenPort());
            }
            if (config.getBrokerServicePortTls().equals(Optional.of(0))) {
                config.setBrokerServicePortTls(brokerService.getListenPortTls());
            }
            this.webServiceAddress = webAddress(config);
            this.webServiceAddressTls = webAddressTls(config);
            this.brokerServiceUrl = brokerUrl(config);
            this.brokerServiceUrlTls = brokerUrlTls(config);

            // the broker id is used in the load manager to identify the broker
            // it should not be used for making connections to the broker
            this.brokerId =
                    String.format("%s:%s", advertisedAddress, config.getWebServicePort()
                            .or(config::getWebServicePortTls).orElseThrow());

            if (this.compactionServiceFactory == null) {
                this.compactionServiceFactory = loadCompactionServiceFactory();
            }

            if (null != this.webSocketService) {
                ClusterDataImpl clusterData = ClusterDataImpl.builder()
                        .serviceUrl(webServiceAddress)
                        .serviceUrlTls(webServiceAddressTls)
                        .brokerServiceUrl(brokerServiceUrl)
                        .brokerServiceUrlTls(brokerServiceUrlTls)
                        .build();
                this.webSocketService.setLocalCluster(clusterData);
            }

            // Initialize namespace service, after service url assigned. Should init zk and refresh self owner info.
            this.nsService.initialize();

            // Start the leader election service
            startLeaderElectionService();

            // By starting the Load manager service, the broker will also become visible
            // to the rest of the broker by creating the registration z-node. This needs
            // to be done only when the broker is fully operative.
            //
            // The load manager service and its service unit state channel need to be initialized first
            // (namespace service depends on load manager)
            this.startLoadManagementService();

            // Start topic level policies service
            this.topicPoliciesService = initTopicPoliciesService();
            this.topicPoliciesService.start(this);

            // Register heartbeat and bootstrap namespaces.
            this.nsService.registerBootstrapNamespaces();

            // Register pulsar system namespaces and start transaction meta store service
            if (config.isTransactionCoordinatorEnabled()) {
                MLTransactionMetadataStoreProvider.initBufferedWriterMetrics(getAdvertisedAddress());
                MLPendingAckStoreProvider.initBufferedWriterMetrics(getAdvertisedAddress());

                this.transactionBufferSnapshotServiceFactory = new TransactionBufferSnapshotServiceFactory(this);

                this.transactionTimer =
                        new HashedWheelTimer(new DefaultThreadFactory("pulsar-transaction-timer"));
                transactionBufferClient = TransactionBufferClientImpl.create(this, transactionTimer,
                        config.getTransactionBufferClientMaxConcurrentRequests(),
                        config.getTransactionBufferClientOperationTimeoutInMills());

                transactionMetadataStoreService = new TransactionMetadataStoreService(TransactionMetadataStoreProvider
                        .newProvider(config.getTransactionMetadataStoreProviderClassName()), this,
                        transactionBufferClient, transactionTimer);

                transactionBufferProvider = TransactionBufferProvider
                        .newProvider(config.getTransactionBufferProviderClassName());
                transactionPendingAckStoreProvider = TransactionPendingAckStoreProvider
                        .newProvider(config.getTransactionPendingAckStoreProviderClassName());

                openTelemetryTransactionCoordinatorStats = new OpenTelemetryTransactionCoordinatorStats(this);
                openTelemetryTransactionPendingAckStoreStats = new OpenTelemetryTransactionPendingAckStoreStats(this);
            }

            this.metricsGenerator = new MetricsGenerator(this);

            // the broker is ready to accept incoming requests by Pulsar binary protocol and http/https
            final List<Runnable> runnables;
            synchronized (pendingTasksBeforeReadyForIncomingRequests) {
                runnables = new ArrayList<>(pendingTasksBeforeReadyForIncomingRequests);
                pendingTasksBeforeReadyForIncomingRequests.clear();
                readyForIncomingRequestsFuture.complete(null);
            }
            runnables.forEach(Runnable::run);

            // Initialize the message protocol handlers.
            // start the protocol handlers only after the broker is ready,
            // so that the protocol handlers can access broker service properly.
            this.protocolHandlers.start(brokerService);
            Map<String, Map<InetSocketAddress, ChannelInitializer<SocketChannel>>> protocolHandlerChannelInitializers =
                this.protocolHandlers.newChannelInitializers();
            this.brokerService.startProtocolHandlers(protocolHandlerChannelInitializers);

            acquireSLANamespace();

            // start function worker service if necessary
            this.startWorkerService(brokerService.getAuthenticationService(), brokerService.getAuthorizationService());

            // start packages management service if necessary
            if (config.isEnablePackagesManagement()) {
                this.startPackagesManagementService();
            }

            // Start the task to publish resource usage, if necessary
            this.resourceUsageTransportManager = DISABLE_RESOURCE_USAGE_TRANSPORT_MANAGER;
            if (isNotBlank(config.getResourceUsageTransportClassName())) {
                Class<?> clazz = Class.forName(config.getResourceUsageTransportClassName());
                Constructor<?> ctor = clazz.getConstructor(PulsarService.class);
                Object object = ctor.newInstance(this);
                this.resourceUsageTransportManager = (ResourceUsageTopicTransportManager) object;
            }
            this.resourceGroupServiceManager = new ResourceGroupService(this);
            if (localMetadataSynchronizer != null) {
                localMetadataSynchronizer.start();
            }
            if (configMetadataSynchronizer != null) {
                configMetadataSynchronizer.start();
            }

            long currentTimestamp = System.currentTimeMillis();
            final long bootstrapTimeSeconds = TimeUnit.MILLISECONDS.toSeconds(currentTimestamp - startTimestamp);

            final String bootstrapMessage = "bootstrap service "
                    + (config.getWebServicePort().isPresent() ? "port = " + config.getWebServicePort().get() : "")
                    + (config.getWebServicePortTls().isPresent() ? ", tls-port = " + config.getWebServicePortTls() : "")
                    + (StringUtils.isNotEmpty(brokerServiceUrl) ? ", broker url= " + brokerServiceUrl : "")
                    + (StringUtils.isNotEmpty(brokerServiceUrlTls) ? ", broker tls url= " + brokerServiceUrlTls : "");
            LOG.info("messaging service is ready, bootstrap_seconds={}, {}, cluster={}, configs={}",
                    bootstrapTimeSeconds, bootstrapMessage, config.getClusterName(), config);

            state = State.Started;
        } catch (Exception e) {
            LOG.error("Failed to start Pulsar service: {}", e.getMessage(), e);
            PulsarServerException startException = PulsarServerException.from(e);
            readyForIncomingRequestsFuture.completeExceptionally(startException);
            throw startException;
        } finally {
            mutex.unlock();
        }
    }

    public void runWhenReadyForIncomingRequests(Runnable runnable) {
        // Here we don't call the thenRun() methods because CompletableFuture maintains a stack for pending callbacks,
        // not a queue. Once the future is complete, the pending callbacks will be executed in reverse order of
        // when they were added.
        final boolean addedToPendingTasks;
        synchronized (pendingTasksBeforeReadyForIncomingRequests) {
            if (readyForIncomingRequestsFuture.isDone()) {
                addedToPendingTasks = false;
            } else {
                pendingTasksBeforeReadyForIncomingRequests.add(runnable);
                addedToPendingTasks = true;
            }
        }
        if (!addedToPendingTasks) {
            runnable.run();
        }
    }

    public void waitUntilReadyForIncomingRequests() throws ExecutionException, InterruptedException {
        readyForIncomingRequestsFuture.get();
    }

    protected BrokerInterceptor newBrokerInterceptor() throws IOException {
        return BrokerInterceptors.load(config);
    }

    @VisibleForTesting
    protected OrderedExecutor newOrderedExecutor() {
        return OrderedExecutor.newBuilder()
                .numThreads(config.getNumOrderedExecutorThreads())
                .name("pulsar-ordered")
                .build();
    }

    @VisibleForTesting
    protected ManagedLedgerStorage newManagedLedgerStorage() throws Exception {
        return ManagedLedgerStorage.create(
                config, localMetadataStore,
                bkClientFactory, ioEventLoopGroup, openTelemetry.getOpenTelemetryService().getOpenTelemetry()
        );
    }

    @VisibleForTesting
    protected PulsarResources newPulsarResources() {
        PulsarResources pulsarResources = new PulsarResources(localMetadataStore, configurationMetadataStore,
                config.getMetadataStoreOperationTimeoutSeconds(), getExecutor());

        pulsarResources.getClusterResources().getStore().registerListener(this::handleDeleteCluster);
        return pulsarResources;
    }

    private synchronized void createMetricsServlet() {
        this.metricsServlet = new PulsarPrometheusMetricsServlet(
                this, config.isExposeTopicLevelMetricsInPrometheus(),
                config.isExposeConsumerLevelMetricsInPrometheus(),
                config.isExposeProducerLevelMetricsInPrometheus(),
                config.isSplitTopicAndPartitionLabelInPrometheus());
        if (pendingMetricsProviders != null) {
            pendingMetricsProviders.forEach(provider -> metricsServlet.addRawMetricsProvider(provider));
            this.pendingMetricsProviders = null;
        }
    }

    private void addWebServerHandlers(WebService webService,
                                      PulsarPrometheusMetricsServlet metricsServlet,
                                      ServiceConfiguration config)
            throws PulsarServerException, PulsarClientException, MalformedURLException, ServletException,
            DeploymentException {
        Map<String, Object> attributeMap = new HashMap<>();
        attributeMap.put(WebService.ATTRIBUTE_PULSAR_NAME, this);

        Map<String, Object> vipAttributeMap = new HashMap<>();
        vipAttributeMap.put(VipStatus.ATTRIBUTE_STATUS_FILE_PATH, config.getStatusFilePath());
        vipAttributeMap.put(VipStatus.ATTRIBUTE_IS_READY_PROBE, (Supplier<Boolean>) () -> {
            // Ensure the VIP status is only visible when the broker is fully initialized
            return state == State.Started;
        });

        // Add admin rest resources
        webService.addRestResource("/",
                false, vipAttributeMap, false, VipStatus.class);
        webService.addRestResources("/admin",
                true, attributeMap, false, "org.apache.pulsar.broker.admin.v1");
        webService.addRestResources("/admin/v2",
                true, attributeMap, true, "org.apache.pulsar.broker.admin.v2");
        webService.addRestResources("/admin/v3",
                true, attributeMap, true, "org.apache.pulsar.broker.admin.v3");
        webService.addRestResource("/lookup",
                true, attributeMap, true,  TopicLookup.class,
                org.apache.pulsar.broker.lookup.v2.TopicLookup.class);
        webService.addRestResource("/topics",
                true, attributeMap, true, Topics.class);

        // Add metrics servlet
        webService.addServlet(PrometheusMetricsServlet.DEFAULT_METRICS_PATH,
                new ServletHolder(metricsServlet),
                config.isAuthenticateMetricsEndpoint(), attributeMap);

        // Add websocket service
        addWebSocketServiceHandler(webService, attributeMap, config);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Attempting to add static directory");
        }
        // Add static resources
        webService.addStaticResources("/static", "/static");

        // Add broker additional servlets
        addBrokerAdditionalServlets(webService, attributeMap, config);
    }

    private void handleMetadataSessionEvent(SessionEvent e) {
        LOG.info("Received metadata service session event: {}", e);
        if (e == SessionEvent.SessionLost
                && config.getZookeeperSessionExpiredPolicy() == MetadataSessionExpiredPolicy.shutdown) {
            LOG.warn("The session with metadata service was lost. Shutting down.\n{}\n",
                    ThreadDumpUtil.buildThreadDiagnosticString());
            shutdownNow();
        }
    }

    private void addBrokerAdditionalServlets(WebService webService,
                                             Map<String, Object> attributeMap,
                                             ServiceConfiguration config) {
        if (this.getBrokerAdditionalServlets() != null) {
            Collection<AdditionalServletWithClassLoader> additionalServletCollection =
                    this.getBrokerAdditionalServlets().getServlets().values();
            for (AdditionalServletWithClassLoader servletWithClassLoader : additionalServletCollection) {
                servletWithClassLoader.loadConfig(config);
                AdditionalServlet additionalServlet = servletWithClassLoader.getServlet();
                if (additionalServlet instanceof AdditionalServletWithPulsarService) {
                    ((AdditionalServletWithPulsarService) additionalServlet).setPulsarService(this);
                }
                webService.addServlet(servletWithClassLoader.getBasePath(), servletWithClassLoader.getServletHolder(),
                        config.isAuthenticationEnabled(), attributeMap);
                LOG.info("Broker add additional servlet basePath {} ", servletWithClassLoader.getBasePath());
            }
        }
    }

    private void addWebSocketServiceHandler(WebService webService,
                                            Map<String, Object> attributeMap,
                                            ServiceConfiguration config)
            throws PulsarServerException, PulsarClientException, MalformedURLException, ServletException,
            DeploymentException {
        if (config.isWebSocketServiceEnabled()) {
            // Use local broker address to avoid different IP address when using a VIP for service discovery
            this.webSocketService = new WebSocketService(null, config);
            this.webSocketService.start();

            final WebSocketServlet producerWebSocketServlet = new WebSocketProducerServlet(webSocketService);
            webService.addServlet(WebSocketProducerServlet.SERVLET_PATH,
                    new ServletHolder(producerWebSocketServlet), true, attributeMap);
            webService.addServlet(WebSocketProducerServlet.SERVLET_PATH_V2,
                    new ServletHolder(producerWebSocketServlet), true, attributeMap);

            final WebSocketServlet consumerWebSocketServlet = new WebSocketConsumerServlet(webSocketService);
            webService.addServlet(WebSocketConsumerServlet.SERVLET_PATH,
                    new ServletHolder(consumerWebSocketServlet), true, attributeMap);
            webService.addServlet(WebSocketConsumerServlet.SERVLET_PATH_V2,
                    new ServletHolder(consumerWebSocketServlet), true, attributeMap);

            final WebSocketServlet readerWebSocketServlet = new WebSocketReaderServlet(webSocketService);
            webService.addServlet(WebSocketReaderServlet.SERVLET_PATH,
                    new ServletHolder(readerWebSocketServlet), true, attributeMap);
            webService.addServlet(WebSocketReaderServlet.SERVLET_PATH_V2,
                    new ServletHolder(readerWebSocketServlet), true, attributeMap);

            final WebSocketMultiTopicConsumerServlet multiTopicConsumerWebSocketServlet =
                    new WebSocketMultiTopicConsumerServlet(webSocketService);
            webService.addServlet(WebSocketMultiTopicConsumerServlet.SERVLET_PATH,
                    new ServletHolder(multiTopicConsumerWebSocketServlet), true, attributeMap);
        }
    }

    private void handleDeleteCluster(Notification notification) {
        if (isRunning() && ClusterResources.pathRepresentsClusterName(notification.getPath())
                && notification.getType() == NotificationType.Deleted) {
            final String clusterName = ClusterResources.clusterNameFromPath(notification.getPath());
            getBrokerService().closeAndRemoveReplicationClient(clusterName);
        }
    }

    public MetadataStoreExtended createLocalMetadataStore(PulsarMetadataEventSynchronizer synchronizer,
                                                          OpenTelemetry openTelemetry)
            throws MetadataStoreException, PulsarServerException {
        return MetadataStoreExtended.create(config.getMetadataStoreUrl(),
                MetadataStoreConfig.builder()
                        .sessionTimeoutMillis((int) config.getMetadataStoreSessionTimeoutMillis())
                        .allowReadOnlyOperations(config.isMetadataStoreAllowReadOnlyOperations())
                        .configFilePath(config.getMetadataStoreConfigPath())
                        .batchingEnabled(config.isMetadataStoreBatchingEnabled())
                        .batchingMaxDelayMillis(config.getMetadataStoreBatchingMaxDelayMillis())
                        .batchingMaxOperations(config.getMetadataStoreBatchingMaxOperations())
                        .batchingMaxSizeKb(config.getMetadataStoreBatchingMaxSizeKb())
                        .synchronizer(synchronizer)
                        .metadataStoreName(MetadataStoreConfig.METADATA_STORE)
                        .openTelemetry(openTelemetry)
                        .build());
    }

    protected CompletableFuture<Void> closeLocalMetadataStore() throws Exception {
        if (localMetadataStore != null) {
            localMetadataStore.close();
        }
        if (localMetadataSynchronizer != null) {
            CompletableFuture<Void> closeSynchronizer = localMetadataSynchronizer.closeAsync();
            localMetadataSynchronizer = null;
            return closeSynchronizer;
        }
        return CompletableFuture.completedFuture(null);
    }

    protected void startLeaderElectionService() {
        if (ExtensibleLoadManagerImpl.isLoadManagerExtensionEnabled(this)) {
            LOG.info("The load manager extension is enabled. Skipping PulsarService LeaderElectionService.");
            return;
        }
        this.leaderElectionService =
                new LeaderElectionService(coordinationService, getBrokerId(), getSafeWebServiceAddress(),
                state -> {
                    if (state == LeaderElectionState.Leading) {
                        LOG.info("This broker {} was elected leader", getBrokerId());
                        if (getConfiguration().isLoadBalancerEnabled()) {
                            startLoadBalancerTasks();
                        }
                    } else {
                        if (leaderElectionService != null) {
                            final Optional<LeaderBroker> currentLeader = leaderElectionService.getCurrentLeader();
                            if (currentLeader.isPresent()) {
                                LOG.info("This broker {} is a follower. Current leader is {}", getBrokerId(),
                                        currentLeader);
                            } else {
                                LOG.info("This broker {} is a follower. No leader has been elected yet", getBrokerId());
                            }

                        }
                        cancelLoadBalancerTasks();
                    }
                });

        leaderElectionService.start();
    }

    private synchronized void cancelLoadBalancerTasks() {
        if (loadSheddingTask != null) {
            loadSheddingTask.cancel();
            loadSheddingTask = null;
        }
        if (loadResourceQuotaTask != null) {
            loadResourceQuotaTask.cancel(false);
            loadResourceQuotaTask = null;
        }
    }

    private synchronized void startLoadBalancerTasks() {
        cancelLoadBalancerTasks();
        if (isRunning()) {
            long resourceQuotaUpdateInterval = TimeUnit.MINUTES
                    .toMillis(getConfiguration().getLoadBalancerResourceQuotaUpdateIntervalMinutes());
            loadSheddingTask = new LoadSheddingTask(loadManager, loadManagerExecutor,
                    config, getDefaultManagedLedgerFactory());
            loadSheddingTask.start();
            loadResourceQuotaTask = loadManagerExecutor.scheduleAtFixedRate(
                    new LoadResourceQuotaUpdaterTask(loadManager), resourceQuotaUpdateInterval,
                    resourceQuotaUpdateInterval, TimeUnit.MILLISECONDS);
        }
    }

    protected void acquireSLANamespace() {
        try {
            // Namespace not created hence no need to unload it
            NamespaceName nsName = NamespaceService.getSLAMonitorNamespace(getBrokerId(), config);
            if (!this.pulsarResources.getNamespaceResources().namespaceExists(nsName)) {
                LOG.info("SLA Namespace = {} doesn't exist.", nsName);
                return;
            }

            boolean acquiredSLANamespace;
            try {
                acquiredSLANamespace = nsService.registerSLANamespace();
                LOG.info("Register SLA Namespace = {}, returned - {}.", nsName, acquiredSLANamespace);
            } catch (PulsarServerException e) {
                acquiredSLANamespace = false;
            }

            if (!acquiredSLANamespace) {
                this.nsService.unloadSLANamespace();
            }
        } catch (Exception ex) {
            LOG.warn(
                    "Exception while trying to unload the SLA namespace,"
                            + " will try to unload the namespace again after 1 minute. Exception:",
                    ex);
            executor.schedule(this::acquireSLANamespace, 1, TimeUnit.MINUTES);
        } catch (Throwable ex) {
            // To make sure SLA monitor doesn't interfere with the normal broker flow
            LOG.warn(
                    "Exception while trying to unload the SLA namespace,"
                            + " will not try to unload the namespace again. Exception:",
                    ex);
        }
    }

    /**
     * Block until the service is finally closed.
     */
    public void waitUntilClosed() throws InterruptedException {
        mutex.lock();

        try {
            while (state != State.Closed) {
                isClosedCondition.await();
            }
        } finally {
            mutex.unlock();
        }
    }

    protected void startNamespaceService() throws PulsarServerException {

        LOG.info("Starting name space service, bootstrap namespaces=" + config.getBootstrapNamespaces());

        this.nsService = getNamespaceServiceProvider().get();
    }

    public Supplier<NamespaceService> getNamespaceServiceProvider() throws PulsarServerException {
        return () -> new NamespaceService(PulsarService.this);
    }

    protected void startLoadManagementService() throws PulsarServerException {
        LOG.info("Starting load management service ...");
        this.loadManager.get().start();

        if (config.isLoadBalancerEnabled() && !ExtensibleLoadManagerImpl.isLoadManagerExtensionEnabled(this)) {
            LOG.info("Starting load balancer");
            if (this.loadReportTask == null) {
                long loadReportMinInterval = config.getLoadBalancerReportUpdateMinIntervalMillis();
                this.loadReportTask = this.loadManagerExecutor.scheduleAtFixedRate(
                        new LoadReportUpdaterTask(loadManager), loadReportMinInterval, loadReportMinInterval,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Load all the topics contained in a namespace.
     *
     * @param bundle <code>NamespaceBundle</code> to identify the service unit
     */
    public void loadNamespaceTopics(NamespaceBundle bundle) {
        executor.submit(() -> {
            LOG.info("Loading all topics on bundle: {}", bundle);

            NamespaceName nsName = bundle.getNamespaceObject();
            List<CompletableFuture<Optional<Topic>>> persistentTopics = new ArrayList<>();
            long topicLoadStart = System.nanoTime();

            for (String topic : getNamespaceService().getListOfPersistentTopics(nsName)
                    .get(config.getMetadataStoreOperationTimeoutSeconds(), TimeUnit.SECONDS)) {
                try {
                    TopicName topicName = TopicName.get(topic);
                    if (bundle.includes(topicName) && !isTransactionInternalName(topicName)) {
                        CompletableFuture<Optional<Topic>> future = brokerService.getTopicIfExists(topic);
                        if (future != null) {
                            persistentTopics.add(future);
                        }
                    }
                } catch (Throwable t) {
                    LOG.warn("Failed to preload topic {}", topic, t);
                }
            }

            if (!persistentTopics.isEmpty()) {
                FutureUtil.waitForAll(persistentTopics).thenRun(() -> {
                    double topicLoadTimeSeconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - topicLoadStart)
                            / 1000.0;
                    long numTopicsLoaded = persistentTopics.stream()
                            .filter(optionalTopicFuture -> optionalTopicFuture.getNow(Optional.empty()).isPresent())
                            .count();
                    LOG.info("Loaded {} topics on {} -- time taken: {} seconds", numTopicsLoaded, bundle,
                            topicLoadTimeSeconds);
                });
            }
            return null;
        });
    }

    // No need to synchronize since config is only init once
    // We only read this from memory later
    public String getStatusFilePath() {
        if (config == null) {
            return null;
        }
        return config.getStatusFilePath();
    }

    public InternalConfigurationData getInternalConfigurationData() {
        return new InternalConfigurationData(
            config.getMetadataStoreUrl(),
            config.getConfigurationMetadataStoreUrl(),
            new ClientConfiguration().getZkLedgersRootPath(),
            config.isBookkeeperMetadataStoreSeparated() ? config.getBookkeeperMetadataStoreUrl() : null,
            this.getWorkerConfig().map(WorkerConfig::getStateStorageServiceUrl).orElse(null));
    }


    /**
     * check the current pulsar service is running, including Started and Init state.
     */
    public boolean isRunning() {
        return this.state == State.Started || this.state == State.Init;
    }

    /**
     * Get a reference of the current <code>LeaderElectionService</code> instance associated with the current
     * <code>PulsarService</code> instance.
     *
     * @return a reference of the current <code>LeaderElectionService</code> instance.
     */
    public LeaderElectionService getLeaderElectionService() {
        if (ExtensibleLoadManagerImpl.isLoadManagerExtensionEnabled(this)) {
            return ExtensibleLoadManagerImpl.get(loadManager.get()).getLeaderElectionService();
        } else {
            return this.leaderElectionService;
        }
    }

    /**
     * Get a reference of the current namespace service instance.
     *
     * @return a reference of the current namespace service instance.
     */
    public NamespaceService getNamespaceService() {
        return this.nsService;
    }


    public Optional<WorkerService> getWorkerServiceOpt() {
        return functionWorkerService;
    }

    public WorkerService getWorkerService() throws UnsupportedOperationException {
        return functionWorkerService.orElseThrow(() -> new UnsupportedOperationException("Pulsar Function Worker "
                + "is not enabled, probably functionsWorkerEnabled is set to false"));
    }

    public BookKeeper getBookKeeperClient() {
        ManagedLedgerStorageClass defaultStorageClass = getManagedLedgerStorage().getDefaultStorageClass();
        if (defaultStorageClass instanceof BookkeeperManagedLedgerStorageClass bkStorageClass) {
            return bkStorageClass.getBookKeeperClient();
        } else {
            // TODO: Refactor code to support other than default bookkeeper based storage class
            throw new UnsupportedOperationException("BookKeeper client is not available");
        }
    }

    public ManagedLedgerFactory getDefaultManagedLedgerFactory() {
        return getManagedLedgerStorage().getDefaultStorageClass().getManagedLedgerFactory();
    }

    /**
     * First, get <code>LedgerOffloader</code> from local map cache,
     * create new <code>LedgerOffloader</code> if not in cache or
     * the <code>OffloadPolicies</code> changed, return the <code>LedgerOffloader</code> directly if exist in cache
     * and the <code>OffloadPolicies</code> not changed.
     *
     * @param namespaceName NamespaceName
     * @param offloadPolicies the OffloadPolicies
     * @return LedgerOffloader
     */
    public LedgerOffloader getManagedLedgerOffloader(NamespaceName namespaceName, OffloadPoliciesImpl offloadPolicies) {
        if (offloadPolicies == null) {
            return getDefaultOffloader();
        }
        return ledgerOffloaderMap.compute(namespaceName, (ns, offloader) -> {
            try {
                if (offloader != null && Objects.equals(offloader.getOffloadPolicies(), offloadPolicies)) {
                    return offloader;
                } else {
                    if (offloader != null) {
                        offloader.close();
                    }
                    return createManagedLedgerOffloader(offloadPolicies);
                }
            } catch (PulsarServerException e) {
                LOG.error("create ledgerOffloader failed for namespace {}", namespaceName.toString(), e);
                return new NullLedgerOffloader();
            }
        });
    }

    public LedgerOffloader createManagedLedgerOffloader(OffloadPoliciesImpl offloadPolicies)
            throws PulsarServerException {
        try {
            if (StringUtils.isNotBlank(offloadPolicies.getManagedLedgerOffloadDriver())) {
                checkNotNull(offloadPolicies.getOffloadersDirectory(),
                    "Offloader driver is configured to be '%s' but no offloaders directory is configured.",
                        offloadPolicies.getManagedLedgerOffloadDriver());

                synchronized (this) {
                    Offloaders offloaders = offloadersCache.getOrLoadOffloaders(
                            offloadPolicies.getOffloadersDirectory(), config.getNarExtractionDirectory());

                    LedgerOffloaderFactory<?> offloaderFactory = offloaders.getOffloaderFactory(
                            offloadPolicies.getManagedLedgerOffloadDriver());
                    try {
                        return offloaderFactory.create(
                                offloadPolicies,
                                Map.of(
                                        LedgerOffloader.METADATA_SOFTWARE_VERSION_KEY.toLowerCase(),
                                        PulsarVersion.getVersion(),
                                        LedgerOffloader.METADATA_SOFTWARE_GITSHA_KEY.toLowerCase(),
                                        PulsarVersion.getGitSha(),
                                        LedgerOffloader.METADATA_PULSAR_CLUSTER_NAME.toLowerCase(),
                                        config.getClusterName()
                                ),
                                schemaStorage, getOffloaderScheduler(offloadPolicies),
                                getOffloaderReadScheduler(offloadPolicies), this.offloaderStats);
                    } catch (IOException ioe) {
                        throw new PulsarServerException(ioe.getMessage(), ioe.getCause());
                    }
                }
            } else {
                LOG.debug("No ledger offloader configured, using NULL instance");
                return NullLedgerOffloader.INSTANCE;
            }
        } catch (Throwable t) {
            throw new PulsarServerException(t);
        }
    }

    private SchemaStorage createAndStartSchemaStorage() throws Exception {
        SchemaStorageFactory factoryInstance = Reflections.createInstance(config.getSchemaRegistryStorageClassName(),
                SchemaStorageFactory.class, Thread.currentThread().getContextClassLoader());
        SchemaStorage schemaStorage = factoryInstance.create(this);
        schemaStorage.start();
        return schemaStorage;
    }

    public BookKeeperClientFactory newBookKeeperClientFactory() {
        return new BookKeeperClientFactoryImpl();
    }

    public BookKeeperClientFactory getBookKeeperClientFactory() {
        return bkClientFactory;
    }

    public synchronized ScheduledExecutorService getCompactorExecutor() {
        if (this.compactorExecutor == null) {
            compactorExecutor = Executors.newSingleThreadScheduledExecutor(
                    new ExecutorProvider.ExtendedThreadFactory("compaction"));
        }
        return this.compactorExecutor;
    }

    // This method is used for metrics, which is allowed to as null
    // Because it's no operation on the compactor, so let's remove the  synchronized on this method
    // to avoid unnecessary lock competition.
    // Only the pulsar's compaction service provides the compaction stats. The compaction service plugin,
    // it should be done by the plugin itself to expose the compaction metrics.
    public Compactor getNullableCompactor() {
        if (this.compactionServiceFactory instanceof PulsarCompactionServiceFactory pulsarCompactedServiceFactory) {
            return pulsarCompactedServiceFactory.getNullableCompactor();
        }
        return null;
    }

    public StrategicTwoPhaseCompactor newStrategicCompactor() throws PulsarServerException {
        return new StrategicTwoPhaseCompactor(this.getConfiguration(),
                getClient(), getBookKeeperClient(),
                getCompactorExecutor());
    }

    public synchronized StrategicTwoPhaseCompactor getStrategicCompactor() throws PulsarServerException {
        if (this.strategicCompactor == null) {
            this.strategicCompactor = newStrategicCompactor();
        }
        return this.strategicCompactor;
    }

    protected synchronized OrderedScheduler getOffloaderScheduler(OffloadPoliciesImpl offloadPolicies) {
        if (this.offloaderScheduler == null) {
            this.offloaderScheduler = OrderedScheduler.newSchedulerBuilder()
                    .numThreads(offloadPolicies.getManagedLedgerOffloadMaxThreads())
                    .name("offloader").build();
        }
        return this.offloaderScheduler;
    }

    protected synchronized OrderedScheduler getOffloaderReadScheduler(OffloadPoliciesImpl offloadPolicies) {
        if (this.offloaderReadExecutor == null) {
            this.offloaderReadExecutor = OrderedScheduler.newSchedulerBuilder()
                    .numThreads(offloadPolicies.getManagedLedgerOffloadReadThreads())
                    .name("offloader-read").build();
        }
        return this.offloaderReadExecutor;
    }

    public PulsarClientImpl createClientImpl(ClientConfigurationData conf) throws PulsarClientException {
        return createClientImpl(conf, null);
    }

    public PulsarClientImpl createClientImpl(Consumer<PulsarClientImpl.PulsarClientImplBuilder> customizer)
            throws PulsarClientException {
        return createClientImpl(null, customizer);
    }

    public PulsarClientImpl createClientImpl(ClientConfigurationData conf,
                                             Consumer<PulsarClientImpl.PulsarClientImplBuilder> customizer)
            throws PulsarClientException {
        PulsarClientImpl.PulsarClientImplBuilder pulsarClientImplBuilder = PulsarClientImpl.builder()
                .conf(conf != null ? conf : createClientConfigurationData())
                .eventLoopGroup(ioEventLoopGroup)
                .timer(brokerClientSharedTimer)
                .internalExecutorProvider(brokerClientSharedInternalExecutorProvider)
                .externalExecutorProvider(brokerClientSharedExternalExecutorProvider)
                .scheduledExecutorProvider(brokerClientSharedScheduledExecutorProvider)
                .lookupExecutorProvider(brokerClientSharedLookupExecutorProvider);
        if (customizer != null) {
            customizer.accept(pulsarClientImplBuilder);
        }
        return pulsarClientImplBuilder.build();
    }

    public synchronized PulsarClient getClient() throws PulsarServerException {
        if (this.client == null) {
            try {
                this.client = createClientImpl(null, null);
            } catch (Exception e) {
                throw new PulsarServerException(e);
            }
        }
        return this.client;
    }

    protected ClientConfigurationData createClientConfigurationData()
            throws PulsarClientException.UnsupportedAuthenticationException {
        ClientConfigurationData initialConf = new ClientConfigurationData();

        // Disable memory limit for broker client and disable stats
        initialConf.setMemoryLimitBytes(0);
        initialConf.setStatsIntervalSeconds(0);

        // Apply all arbitrary configuration. This must be called before setting any fields annotated as
        // @Secret on the ClientConfigurationData object because of the way they are serialized.
        // See https://github.com/apache/pulsar/issues/8509 for more information.
        Map<String, Object> overrides = PropertiesUtils
                .filterAndMapProperties(this.getConfiguration().getProperties(), "brokerClient_");
        ClientConfigurationData conf =
                ConfigurationDataUtils.loadData(overrides, initialConf, ClientConfigurationData.class);

        // Disabled auto release useless connections
        // The automatic release connection feature is not yet perfect for transaction scenarios, so turn it
        // off first.
        conf.setConnectionMaxIdleSeconds(-1);

        boolean tlsEnabled = this.getConfiguration().isBrokerClientTlsEnabled();
        conf.setServiceUrl(tlsEnabled ? this.brokerServiceUrlTls : this.brokerServiceUrl);

        if (tlsEnabled) {
            conf.setTlsCiphers(this.getConfiguration().getBrokerClientTlsCiphers());
            conf.setTlsProtocols(this.getConfiguration().getBrokerClientTlsProtocols());
            conf.setTlsAllowInsecureConnection(this.getConfiguration().isTlsAllowInsecureConnection());
            conf.setTlsHostnameVerificationEnable(this.getConfiguration().isTlsHostnameVerificationEnabled());
            conf.setSslFactoryPlugin(this.getConfiguration().getBrokerClientSslFactoryPlugin());
            conf.setSslFactoryPluginParams(this.getConfiguration().getBrokerClientSslFactoryPluginParams());
            if (this.getConfiguration().isBrokerClientTlsEnabledWithKeyStore()) {
                conf.setUseKeyStoreTls(true);
                conf.setTlsTrustStoreType(this.getConfiguration().getBrokerClientTlsTrustStoreType());
                conf.setTlsTrustStorePath(this.getConfiguration().getBrokerClientTlsTrustStore());
                conf.setTlsTrustStorePassword(this.getConfiguration().getBrokerClientTlsTrustStorePassword());
                conf.setTlsKeyStoreType(this.getConfiguration().getBrokerClientTlsKeyStoreType());
                conf.setTlsKeyStorePath(this.getConfiguration().getBrokerClientTlsKeyStore());
                conf.setTlsKeyStorePassword(this.getConfiguration().getBrokerClientTlsKeyStorePassword());
            } else {
                conf.setTlsTrustCertsFilePath(
                        isNotBlank(this.getConfiguration().getBrokerClientTrustCertsFilePath())
                                ? this.getConfiguration().getBrokerClientTrustCertsFilePath()
                                : this.getConfiguration().getTlsTrustCertsFilePath());
                conf.setTlsKeyFilePath(this.getConfiguration().getBrokerClientKeyFilePath());
                conf.setTlsCertificateFilePath(this.getConfiguration().getBrokerClientCertificateFilePath());
            }
        }

        if (isNotBlank(this.getConfiguration().getBrokerClientAuthenticationPlugin())) {
            conf.setAuthPluginClassName(this.getConfiguration().getBrokerClientAuthenticationPlugin());
            conf.setAuthParams(this.getConfiguration().getBrokerClientAuthenticationParameters());
            conf.setAuthParamMap(null);
            conf.setAuthentication(AuthenticationFactory.create(
                    this.getConfiguration().getBrokerClientAuthenticationPlugin(),
                    this.getConfiguration().getBrokerClientAuthenticationParameters()));
        }
        return conf;
    }

    public synchronized PulsarAdmin getAdminClient() throws PulsarServerException {
        if (this.adminClient == null) {
            try {
                this.adminClient = getCreateAdminClientBuilder().build();
                LOG.info("created admin with url {} ", adminClient.getServiceUrl());
            } catch (Exception e) {
                throw new PulsarServerException(e);
            }
        }
        return this.adminClient;
    }

    protected PulsarAdminBuilder getCreateAdminClientBuilder()
            throws PulsarClientException.UnsupportedAuthenticationException {
        ServiceConfiguration conf = this.getConfiguration();
        final String adminApiUrl = conf.isBrokerClientTlsEnabled() ? webServiceAddressTls : webServiceAddress;
        if (adminApiUrl == null) {
            throw new IllegalArgumentException("Web service address was not set properly "
                    + ", isBrokerClientTlsEnabled: " + conf.isBrokerClientTlsEnabled()
                    + ", webServiceAddressTls: " + webServiceAddressTls
                    + ", webServiceAddress: " + webServiceAddress);
        }
        PulsarAdminBuilder builder = PulsarAdmin.builder().serviceHttpUrl(adminApiUrl);

        // Apply all arbitrary configuration. This must be called before setting any fields annotated as
        // @Secret on the ClientConfigurationData object because of the way they are serialized.
        // See https://github.com/apache/pulsar/issues/8509 for more information.
        builder.loadConf(PropertiesUtils.filterAndMapProperties(conf.getProperties(), "brokerClient_"));

        builder.authentication(
                conf.getBrokerClientAuthenticationPlugin(),
                conf.getBrokerClientAuthenticationParameters());

        if (conf.isBrokerClientTlsEnabled()) {
            builder.tlsCiphers(conf.getBrokerClientTlsCiphers())
                    .tlsProtocols(conf.getBrokerClientTlsProtocols())
                    .sslFactoryPlugin(conf.getBrokerClientSslFactoryPlugin())
                    .sslFactoryPluginParams(conf.getBrokerClientSslFactoryPluginParams());
            if (conf.isBrokerClientTlsEnabledWithKeyStore()) {
                builder.useKeyStoreTls(true).tlsTrustStoreType(conf.getBrokerClientTlsTrustStoreType())
                        .tlsTrustStorePath(conf.getBrokerClientTlsTrustStore())
                        .tlsTrustStorePassword(conf.getBrokerClientTlsTrustStorePassword())
                        .tlsKeyStoreType(conf.getBrokerClientTlsKeyStoreType())
                        .tlsKeyStorePath(conf.getBrokerClientTlsKeyStore())
                        .tlsKeyStorePassword(conf.getBrokerClientTlsKeyStorePassword());
            } else {
                builder.tlsTrustCertsFilePath(conf.getBrokerClientTrustCertsFilePath())
                        .tlsKeyFilePath(conf.getBrokerClientKeyFilePath())
                        .tlsCertificateFilePath(conf.getBrokerClientCertificateFilePath());
            }
            builder.allowTlsInsecureConnection(conf.isTlsAllowInsecureConnection())
                    .enableTlsHostnameVerification(conf.isTlsHostnameVerificationEnabled());
        }

        // most of the admin request requires to make zk-call so, keep the max read-timeout based on
        // zk-operation timeout
        builder.readTimeout(conf.getMetadataStoreOperationTimeoutSeconds(), TimeUnit.SECONDS);
        return builder;
    }

    /**
     * Gets the broker service URL (non-TLS) associated with the internal listener.
     */
    protected String brokerUrl(ServiceConfiguration config) {
        AdvertisedListener internalListener = ServiceConfigurationUtils.getInternalListener(config, "pulsar");
        return internalListener.getBrokerServiceUrl() != null
                ? internalListener.getBrokerServiceUrl().toString() : null;
    }

    public static String brokerUrl(String host, int port) {
        return ServiceConfigurationUtils.brokerUrl(host, port);
    }

    /**
     * Gets the broker service URL (TLS) associated with the internal listener.
     */
    public String brokerUrlTls(ServiceConfiguration config) {
        AdvertisedListener internalListener = ServiceConfigurationUtils.getInternalListener(config, "pulsar+ssl");
        return internalListener.getBrokerServiceUrlTls() != null
                ? internalListener.getBrokerServiceUrlTls().toString() : null;
    }

    public static String brokerUrlTls(String host, int port) {
        return ServiceConfigurationUtils.brokerUrlTls(host, port);
    }

    public String webAddress(ServiceConfiguration config) {
        if (config.getWebServicePort().isPresent()) {
            AdvertisedListener internalListener = ServiceConfigurationUtils.getInternalListener(config, "http");
            return internalListener.getBrokerHttpUrl() != null
                    ? internalListener.getBrokerHttpUrl().toString()
                    : webAddress(ServiceConfigurationUtils.getWebServiceAddress(config),
                            getListenPortHTTP().orElseThrow());
        } else {
            return null;
        }
    }

    public static String webAddress(String host, int port) {
        return String.format("http://%s:%d", host, port);
    }

    public String webAddressTls(ServiceConfiguration config) {
        if (config.getWebServicePortTls().isPresent()) {
            AdvertisedListener internalListener = ServiceConfigurationUtils.getInternalListener(config, "https");
            return internalListener.getBrokerHttpsUrl() != null
                    ? internalListener.getBrokerHttpsUrl().toString()
                    : webAddressTls(ServiceConfigurationUtils.getWebServiceAddress(config),
                            getListenPortHTTPS().orElseThrow());
        } else {
            return null;
        }
    }

    public static String webAddressTls(String host, int port) {
        return String.format("https://%s:%d", host, port);
    }

    public String getSafeWebServiceAddress() {
        return webServiceAddressTls != null ? webServiceAddressTls : webServiceAddress;
    }

    @Deprecated
    public String getSafeBrokerServiceUrl() {
        return brokerServiceUrlTls != null ? brokerServiceUrlTls : brokerServiceUrl;
    }

    /**
     * Return the broker id. The broker id is used in the load manager to uniquely identify the broker at runtime.
     * It should not be used for making connections to the broker. The broker id is available after {@link #start()}
     * has been called.
     *
     * @return broker id
     */
    public String getBrokerId() {
        return Objects.requireNonNull(brokerId,
                "brokerId is not initialized before start has been called");
    }

    public synchronized void addPrometheusRawMetricsProvider(PrometheusRawMetricsProvider metricsProvider) {
        if (metricsServlet == null) {
            if (pendingMetricsProviders == null) {
                pendingMetricsProviders = new LinkedList<>();
            }
            pendingMetricsProviders.add(metricsProvider);
        } else {
            this.metricsServlet.addRawMetricsProvider(metricsProvider);
        }
    }

    private void startWorkerService(AuthenticationService authenticationService,
                                    AuthorizationService authorizationService)
            throws Exception {
        if (functionWorkerService.isPresent()) {
            if (workerConfig.isUseTls() || brokerServiceUrl == null) {
                workerConfig.setPulsarServiceUrl(brokerServiceUrlTls);
            } else {
                workerConfig.setPulsarServiceUrl(brokerServiceUrl);
            }
            if (workerConfig.isUseTls() || webServiceAddress == null) {
                workerConfig.setPulsarWebServiceUrl(webServiceAddressTls);
                workerConfig.setFunctionWebServiceUrl(webServiceAddressTls);
            } else {
                workerConfig.setPulsarWebServiceUrl(webServiceAddress);
                workerConfig.setFunctionWebServiceUrl(webServiceAddress);
            }

            LOG.info("Starting function worker service: serviceUrl = {},"
                + " webServiceUrl = {}, functionWebServiceUrl = {}",
                workerConfig.getPulsarServiceUrl(),
                workerConfig.getPulsarWebServiceUrl(),
                workerConfig.getFunctionWebServiceUrl());

            functionWorkerService.get().initInBroker(
                config,
                workerConfig,
                pulsarResources,
                getInternalConfigurationData()
            );

            // TODO figure out how to handle errors from function worker service
            functionWorkerService.get().start(
                authenticationService,
                authorizationService,
                ErrorNotifier.getShutdownServiceImpl(this));
            LOG.info("Function worker service started");
        }
    }

    public PackagesManagement getPackagesManagement() throws UnsupportedOperationException {
        if (packagesManagement == null) {
            throw new UnsupportedOperationException("Package Management Service is not enabled in the broker.");
        }
        return packagesManagement;
    }

    private void startPackagesManagementService() throws IOException {
        // TODO: using provider to initialize the packages management service.
        this.packagesManagement = new PackagesManagementImpl();
        PackagesStorageProvider storageProvider = PackagesStorageProvider
            .newProvider(config.getPackagesManagementStorageProvider());
        DefaultPackagesStorageConfiguration storageConfiguration = new DefaultPackagesStorageConfiguration();
        storageConfiguration.setProperty(config.getProperties());
        PackagesStorage storage = storageProvider.getStorage(storageConfiguration);
        storage.initialize();
        this.packagesManagement.initialize(storage);
    }

    public Optional<Integer> getListenPortHTTP() {
        return webService.getListenPortHTTP();
    }

    public Optional<Integer> getListenPortHTTPS() {
        return webService.getListenPortHTTPS();
    }

    public Optional<Integer> getBrokerListenPort() {
        return brokerService.getListenPort();
    }

    public Optional<Integer> getBrokerListenPortTls() {
        return brokerService.getListenPortTls();
    }

    public MonotonicClock getMonotonicClock() {
        return monotonicClock;
    }

    public static WorkerConfig initializeWorkerConfigFromBrokerConfig(ServiceConfiguration brokerConfig,
                                                                      String workerConfigFile) throws IOException {
        WorkerConfig workerConfig = WorkerConfig.load(workerConfigFile);

        workerConfig.setWorkerPort(brokerConfig.getWebServicePort().orElse(null));
        workerConfig.setWorkerPortTls(brokerConfig.getWebServicePortTls().orElse(null));

        // worker talks to local broker
        String hostname = ServiceConfigurationUtils.getDefaultOrConfiguredAddress(
                brokerConfig.getAdvertisedAddress());
        workerConfig.setWorkerHostname(hostname);
        workerConfig.setPulsarFunctionsCluster(brokerConfig.getClusterName());
        // inherit broker authorization setting
        workerConfig.setAuthenticationEnabled(brokerConfig.isAuthenticationEnabled());
        workerConfig.setAuthenticationProviders(brokerConfig.getAuthenticationProviders());
        workerConfig.setAuthorizationEnabled(brokerConfig.isAuthorizationEnabled());
        workerConfig.setAuthorizationProvider(brokerConfig.getAuthorizationProvider());
        workerConfig.setConfigurationMetadataStoreUrl(brokerConfig.getConfigurationMetadataStoreUrl());
        workerConfig.setMetadataStoreSessionTimeoutMillis(brokerConfig.getMetadataStoreSessionTimeoutMillis());
        workerConfig.setMetadataStoreOperationTimeoutSeconds(brokerConfig.getMetadataStoreOperationTimeoutSeconds());
        workerConfig.setMetadataStoreCacheExpirySeconds(brokerConfig.getMetadataStoreCacheExpirySeconds());
        workerConfig.setTlsAllowInsecureConnection(brokerConfig.isTlsAllowInsecureConnection());
        workerConfig.setTlsEnabled(brokerConfig.isTlsEnabled());
        workerConfig.setTlsEnableHostnameVerification(brokerConfig.isTlsHostnameVerificationEnabled());
        workerConfig.setBrokerClientTrustCertsFilePath(brokerConfig.getBrokerClientTrustCertsFilePath());
        workerConfig.setTlsTrustCertsFilePath(brokerConfig.getTlsTrustCertsFilePath());

        // client in worker will use this config to authenticate with broker
        workerConfig.setBrokerClientAuthenticationPlugin(brokerConfig.getBrokerClientAuthenticationPlugin());
        workerConfig.setBrokerClientAuthenticationParameters(brokerConfig.getBrokerClientAuthenticationParameters());

        // inherit super users
        workerConfig.setSuperUserRoles(brokerConfig.getSuperUserRoles());
        workerConfig.setProxyRoles(brokerConfig.getProxyRoles());
        workerConfig.setFunctionsWorkerEnablePackageManagement(brokerConfig.isFunctionsWorkerEnablePackageManagement());

        // inherit the nar package locations
        if (isBlank(workerConfig.getFunctionsWorkerServiceNarPackage())) {
            workerConfig.setFunctionsWorkerServiceNarPackage(
                brokerConfig.getFunctionsWorkerServiceNarPackage());
        }

        workerConfig.setWorkerId(
                "c-" + brokerConfig.getClusterName()
                        + "-fw-" + hostname
                        + "-" + (workerConfig.getTlsEnabled()
                        ? workerConfig.getWorkerPortTls() : workerConfig.getWorkerPort()));
        return workerConfig;
    }

    /**
     * Shutdown the broker immediately, without waiting for all resources to be released.
     * This possibly is causing the JVM process to exit, depending on how th PulsarService
     * was constructed.
     */
    @Override
    public void shutdownNow() {
        LOG.info("Invoking Pulsar service immediate shutdown");
        try {
            // Try to close metadata service session to ensure all ephemeral locks get released immediately
            closeMetadataServiceSession();
        } catch (Exception e) {
            LOG.warn("Failed to close metadata service session: {}", e.getMessage());
        }

        processTerminator.accept(-1);
    }

    @VisibleForTesting
    protected BrokerService newBrokerService(PulsarService pulsar) throws Exception {
        return new BrokerService(pulsar, ioEventLoopGroup);
    }

    @VisibleForTesting
    public void setTransactionBufferProvider(TransactionBufferProvider transactionBufferProvider) {
        this.transactionBufferProvider = transactionBufferProvider;
    }

    private CompactionServiceFactory loadCompactionServiceFactory() {
        String compactionServiceFactoryClassName = config.getCompactionServiceFactoryClassName();
        var compactionServiceFactory =
                Reflections.createInstance(compactionServiceFactoryClassName, CompactionServiceFactory.class,
                        Thread.currentThread().getContextClassLoader());
        compactionServiceFactory.initialize(this).join();
        return compactionServiceFactory;
    }

    public CompletableFuture<TopicCompactionService> newTopicCompactionService(String topic) {
        try {
            CompactionServiceFactory compactionServiceFactory = this.getCompactionServiceFactory();
            return compactionServiceFactory.newTopicCompactionService(topic);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public void initConfigMetadataSynchronizerIfNeeded() {
        mutex.lock();
        try {
            final String newTopic = config.getConfigurationMetadataSyncEventTopic();
            final PulsarMetadataEventSynchronizer oldSynchronizer = configMetadataSynchronizer;
            // Skip if not support.
            if (!(configurationMetadataStore instanceof MetadataStoreExtended)) {
                LOG.info(
                        "Skip to update Metadata Synchronizer because of the Configuration Metadata Store using[{}]"
                                + " does not support.", configurationMetadataStore.getClass().getName());
                return;
            }
            // Skip if no changes.
            //   case-1: both null.
            //   case-2: both topics are the same.
            if ((oldSynchronizer == null && StringUtils.isBlank(newTopic))) {
                LOG.info("Skip to update Metadata Synchronizer because the topic[null] does not changed.");
            }
            if (StringUtils.isNotBlank(newTopic) && oldSynchronizer != null) {
                TopicName newTopicName = TopicName.get(newTopic);
                TopicName oldTopicName = TopicName.get(oldSynchronizer.getTopicName());
                if (newTopicName.equals(oldTopicName)) {
                    LOG.info("Skip to update Metadata Synchronizer because the topic[{}] does not changed.",
                            oldTopicName);
                }
            }
            // Update(null or not null).
            //   1.set the new one.
            //   2.close the old one.
            //   3.async start the new one.
            if (StringUtils.isBlank(newTopic)) {
                configMetadataSynchronizer = null;
            } else {
                configMetadataSynchronizer = new PulsarMetadataEventSynchronizer(this, newTopic);
            }
            // close the old one and start the new one.
            PulsarMetadataEventSynchronizer newSynchronizer = configMetadataSynchronizer;
            MetadataStoreExtended metadataStoreExtended = (MetadataStoreExtended) configurationMetadataStore;
            metadataStoreExtended.updateMetadataEventSynchronizer(newSynchronizer);
            Runnable startNewSynchronizer = () -> {
                if (newSynchronizer == null) {
                    return;
                }
                try {
                    newSynchronizer.start();
                } catch (Exception e) {
                    // It only occurs when get internal client fails.
                    LOG.error("Start Metadata Synchronizer with topic {} failed.",
                            newTopic, e);
                }
            };
            executor.submit(() -> {
                if (oldSynchronizer != null) {
                    oldSynchronizer.closeAsync().whenComplete((ignore, ex) -> {
                        startNewSynchronizer.run();
                    });
                } else {
                    startNewSynchronizer.run();
                }
            });
        } finally {
            mutex.unlock();
        }
    }

    private TopicPoliciesService initTopicPoliciesService() throws Exception {
        if (!config.isTopicLevelPoliciesEnabled()) {
            return TopicPoliciesService.DISABLED;
        }
        final var className = Optional.ofNullable(config.getTopicPoliciesServiceClassName())
                .orElse(SystemTopicBasedTopicPoliciesService.class.getName());
        if (className.equals(SystemTopicBasedTopicPoliciesService.class.getName())) {
            if (config.isSystemTopicEnabled()) {
                return new SystemTopicBasedTopicPoliciesService(this);
            } else {
                LOG.warn("System topic is disabled while the topic policies service is {}, disable it", className);
                return TopicPoliciesService.DISABLED;
            }
        }
        return (TopicPoliciesService) Reflections.createInstance(className,
                Thread.currentThread().getContextClassLoader());
    }

    /**
     * Run health check for the broker.
     *
     * @return CompletableFuture
     */
    public CompletableFuture<Void> runHealthCheck(TopicVersion topicVersion, String clientId) {
        if (!isRunning()) {
            return CompletableFuture.failedFuture(new PulsarServerException("Broker is not running"));
        }
        HealthChecker localHealthChecker = getHealthChecker();
        if (localHealthChecker == null) {
            return CompletableFuture.failedFuture(new PulsarServerException("Broker is not running"));
        }
        return localHealthChecker.checkHealth(topicVersion, clientId);
    }

    @VisibleForTesting
    public HealthChecker getHealthChecker() {
        if (healthChecker == null) {
            synchronized (this) {
                if (healthChecker == null) {
                    if (!isRunning()) {
                        return null;
                    }
                    try {
                        healthChecker = new HealthChecker(this);
                    } catch (PulsarServerException e) {
                        LOG.error("Failed to create health checker", e);
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return healthChecker;
    }
}
