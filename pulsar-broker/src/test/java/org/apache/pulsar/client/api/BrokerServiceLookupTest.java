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
package org.apache.pulsar.client.api;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static org.apache.pulsar.broker.namespace.NamespaceService.LOOKUP_REQUEST_DURATION_METRIC_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.prometheus.client.CollectorRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.naming.AuthenticationException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import lombok.Cleanup;
import org.apache.pulsar.broker.BrokerTestUtil;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.authentication.AuthenticationProvider;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.ResourceUnit;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerImpl;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerWrapper;
import org.apache.pulsar.broker.loadbalance.impl.SimpleResourceUnit;
import org.apache.pulsar.broker.namespace.NamespaceEphemeralData;
import org.apache.pulsar.broker.namespace.NamespaceService;
import org.apache.pulsar.broker.namespace.OwnedBundle;
import org.apache.pulsar.broker.namespace.OwnershipCache;
import org.apache.pulsar.broker.namespace.ServiceUnitUtils;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.testcontext.PulsarTestContext;
import org.apache.pulsar.client.impl.BinaryProtoLookupService;
import org.apache.pulsar.client.impl.ClientCnx;
import org.apache.pulsar.client.impl.LookupService;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.ServiceUnitId;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.PoliciesUtil;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.common.util.SecurityUtility;
import org.apache.pulsar.policies.data.loadbalancer.LocalBrokerData;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.apache.zookeeper.KeeperException;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.DefaultKeepAliveStrategy;
import org.awaitility.Awaitility;
import org.awaitility.reflect.WhiteboxImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITest;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@Test(groups = "broker-api")
public class BrokerServiceLookupTest extends ProducerConsumerBase implements ITest {
    private static final Logger log = LoggerFactory.getLogger(BrokerServiceLookupTest.class);
    private String testName;

    @DataProvider
    private static Object[] booleanValues() {
        return new Object[]{ true, false };
    }

    @Factory(dataProvider = "booleanValues")
    public BrokerServiceLookupTest(boolean useTestZookeeper) {
        // when set to true, TestZKServer is used which is a real ZooKeeper implementation
        this.useTestZookeeper = useTestZookeeper;
    }

    @Override
    public String getTestName() {
        return testName;
    }

    @BeforeMethod
    public void applyTestName(Method method) {
        testName = method.getName() + " with " + (useTestZookeeper ? "TestZKServer" : "MockZooKeeper");
    }

    @BeforeMethod(dependsOnMethods = "setTestMethodName")
    @Override
    protected void setup() throws Exception {
        conf.setDefaultNumberOfNamespaceBundles(1);
        isTcpLookup = true;
        internalSetup();
        producerBaseSetup();
    }

    @Override
    protected void doInitConf() throws Exception {
        super.doInitConf();
        switch (methodName) {
            case "testMultipleBrokerDifferentClusterLookup" -> {
                conf.setAuthenticationEnabled(true);
            }
            case "testWebserviceServiceTls" -> {
                // broker1 with tls enabled
                conf.setBrokerServicePortTls(Optional.of(0));
                conf.setWebServicePortTls(Optional.of(0));
                conf.setTlsTrustCertsFilePath(CA_CERT_FILE_PATH);
                conf.setTlsRequireTrustedClientCertOnConnect(true);
                conf.setTlsCertificateFilePath(BROKER_CERT_FILE_PATH);
                conf.setTlsKeyFilePath(BROKER_KEY_FILE_PATH);
                conf.setNumExecutorThreadPoolSize(5);
                // Not in use, and because TLS is not configured, it will fail to start
                conf.setSystemTopicEnabled(false);
            }
            case "testSkipSplitBundleIfOnlyOneBroker" -> {
                conf.setDefaultNumberOfNamespaceBundles(1);
                conf.setLoadBalancerNamespaceBundleMaxTopics(1);
                conf.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
            }
            case "testPartitionedMetadataWithDeprecatedVersion" -> {
                conf.setBrokerServicePortTls(Optional.empty());
                conf.setWebServicePortTls(Optional.empty());
                conf.setClientLibraryVersionCheckEnabled(true);
            }
        }
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        internalCleanup();
        testName = null;
    }

    @Override
    protected void customizeMainPulsarTestContextBuilder(PulsarTestContext.Builder builder) {
        super.customizeMainPulsarTestContextBuilder(builder);
        builder.enableOpenTelemetry(true);
    }

    /**
     * Usecase Multiple Broker => Lookup Redirection test
     *
     * 1. Broker1 is a leader 2. Lookup request reaches to Broker2 which redirects to leader (Broker1) with
     * authoritative = false 3. Leader (Broker1) finds out least loaded broker as Broker2 and redirects request to
     * Broker2 with authoritative = true 4. Broker2 receives final request to own a bundle with authoritative = true and
     * client connects to Broker2
     *
     * @throws Exception
     */
    @Test(timeOut = 30_000)
    public void testMultipleBrokerLookup() throws Exception {
        log.info("-- Starting {} test --", methodName);

        /**** start broker-2 ****/
        ServiceConfiguration conf2 = new ServiceConfiguration();
        conf2.setBrokerShutdownTimeoutMs(0L);
        conf2.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        conf2.setBrokerServicePort(Optional.of(0));
        conf2.setWebServicePort(Optional.of(0));
        conf2.setAdvertisedAddress("localhost");
        conf2.setClusterName(conf.getClusterName());
        conf2.setMetadataStoreUrl("zk:localhost:2181");
        conf2.setConfigurationMetadataStoreUrl("zk:localhost:3181");

        @Cleanup
        PulsarTestContext pulsarTestContext2 = createAdditionalPulsarTestContext(conf2,
                builder -> builder.enableOpenTelemetry(true));
        PulsarService pulsar2 = pulsarTestContext2.getPulsarService();
        pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
        pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

        LoadManager loadManager1 = spy(pulsar.getLoadManager().get());
        LoadManager loadManager2 = spy(pulsar2.getLoadManager().get());
        Field loadManagerField = NamespaceService.class.getDeclaredField("loadManager");
        loadManagerField.setAccessible(true);

        // mock: redirect request to leader [2]
        doReturn(true).when(loadManager2).isCentralized();
        loadManagerField.set(pulsar2.getNamespaceService(), new AtomicReference<>(loadManager2));

        // mock: return Broker2 as a Least-loaded broker when leader receives request [3]
        doReturn(true).when(loadManager1).isCentralized();
        SimpleResourceUnit resourceUnit = new SimpleResourceUnit(pulsar2.getBrokerId(), null);
        doReturn(Optional.of(resourceUnit)).when(loadManager1).getLeastLoaded(any(ServiceUnitId.class));
        doReturn(Optional.of(resourceUnit)).when(loadManager2).getLeastLoaded(any(ServiceUnitId.class));
        loadManagerField.set(pulsar.getNamespaceService(), new AtomicReference<>(loadManager1));

        // Disable collecting topic stats during this test, as it deadlocks on access to map BrokerService.topics.
        pulsar2.getOpenTelemetryTopicStats().close();
        pulsar2.getOpenTelemetryConsumerStats().close();
        pulsar2.getOpenTelemetryProducerStats().close();
        pulsar2.getOpenTelemetryReplicatorStats().close();

        var metricReader = pulsarTestContext.getOpenTelemetryMetricReader();
        var lookupRequestSemaphoreField = BrokerService.class.getDeclaredField("lookupRequestSemaphore");
        lookupRequestSemaphoreField.setAccessible(true);
        var lookupRequestSemaphoreSpy = spy(pulsar.getBrokerService().getLookupRequestSemaphore());
        var cdlAfterLookupSemaphoreAcquire = new CountDownLatch(1);
        var cdlLookupSemaphoreVerification = new CountDownLatch(1);
        doAnswer(invocation -> {
            var ret = invocation.callRealMethod();
            if (Boolean.TRUE.equals(ret)) {
                cdlAfterLookupSemaphoreAcquire.countDown();
                cdlLookupSemaphoreVerification.await();
            }
            return ret;
        }).doCallRealMethod().when(lookupRequestSemaphoreSpy).tryAcquire();
        lookupRequestSemaphoreField.set(pulsar.getBrokerService(), new AtomicReference<>(lookupRequestSemaphoreSpy));

        var topicLoadRequestSemaphoreField = BrokerService.class.getDeclaredField("topicLoadRequestSemaphore");
        topicLoadRequestSemaphoreField.setAccessible(true);
        var topicLoadRequestSemaphoreSpy = spy(pulsar2.getBrokerService().getTopicLoadRequestSemaphore().get());

        var cdlAfterTopicLoadSemaphoreAcquire = new CountDownLatch(1);
        var cdlTopicLoadSemaphoreVerification = new CountDownLatch(1);

        doAnswer(invocation -> {
            var ret = invocation.callRealMethod();
            if (Boolean.TRUE.equals(ret)) {
                cdlAfterTopicLoadSemaphoreAcquire.countDown();
                cdlTopicLoadSemaphoreVerification.await();
            }
            return ret;
        }).doCallRealMethod().when(topicLoadRequestSemaphoreSpy).tryAcquire();
        topicLoadRequestSemaphoreField.set(pulsar2.getBrokerService(),
                new AtomicReference<>(topicLoadRequestSemaphoreSpy));

        assertThat(pulsarTestContext.getOpenTelemetryMetricReader().collectAllMetrics())
                .noneSatisfy(metric -> assertThat(metric).hasName(LOOKUP_REQUEST_DURATION_METRIC_NAME));

        /**** started broker-2 ****/
        @Cleanup
        PulsarClient pulsarClient2 = PulsarClient.builder().serviceUrl(pulsar2.getBrokerServiceUrl()).build();

        var consumerFuture = pulsarClient2.newConsumer().topic("persistent://my-property/my-ns/my-topic1")
                .subscriptionName("my-subscriber-name").subscribeAsync();

        cdlAfterLookupSemaphoreAcquire.await();
        assertThat(metricReader.collectAllMetrics())
                .anySatisfy(metric -> assertThat(metric)
                        .hasName(BrokerService.TOPIC_LOOKUP_USAGE_METRIC_NAME)
                        .hasLongSumSatisfying(
                                sum -> sum.hasPointsSatisfying(point -> point.hasValue(1))));
        cdlLookupSemaphoreVerification.countDown();

        cdlAfterTopicLoadSemaphoreAcquire.await();
        assertThat(pulsarTestContext2.getOpenTelemetryMetricReader().collectAllMetrics())
                .anySatisfy(metric -> assertThat(metric)
                        .hasName(BrokerService.TOPIC_LOAD_USAGE_METRIC_NAME)
                        .hasLongSumSatisfying(
                                sum -> sum.hasPointsSatisfying(point -> point.hasValue(1))));
        cdlTopicLoadSemaphoreVerification.countDown();

        // load namespace-bundle by calling Broker2
        @Cleanup
        var consumer = consumerFuture.get();
        @Cleanup
        var producer = pulsarClient.newProducer().topic("persistent://my-property/my-ns/my-topic1").create();

        for (int i = 0; i < 10; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            String receivedMessage = new String(msg.getData());
            log.debug("Received message: [{}]", receivedMessage);
            String expectedMessage = "my-message-" + i;
            testMessageOrderAndDuplicates(messageSet, receivedMessage, expectedMessage);
        }

        var metrics = metricReader.collectAllMetrics();
        assertThat(metrics)
                .anySatisfy(metric -> assertThat(metric)
                        .hasName(LOOKUP_REQUEST_DURATION_METRIC_NAME)
                        .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                                point -> point
                                        .hasAttributes(NamespaceService.PULSAR_LOOKUP_RESPONSE_REDIRECT_ATTRIBUTES)
                                        .hasCount(1),
                                point -> point
                                        .hasAttributes(NamespaceService.PULSAR_LOOKUP_RESPONSE_BROKER_ATTRIBUTES)
                                        .hasCount(1))));

        // Acknowledge the consumption of all messages at once
        consumer.acknowledgeCumulative(msg);
    }

    @Test
    public void testConcurrentWriteBrokerData() throws Exception {
        Map<String, NamespaceBundleStats> map = new ConcurrentHashMap<>();
        List<String> boundaries = PoliciesUtil.getBundles(100).getBoundaries();
        for (int i = 0; i < 100; i++) {
            map.put("my-property/my-ns/" + boundaries.get(i), new NamespaceBundleStats());
        }
        BrokerService originalBrokerService = pulsar.getBrokerService();
        BrokerService brokerService = mock(BrokerService.class);
        doReturn(brokerService).when(pulsar).getBrokerService();
        doReturn(map).when(brokerService).getBundleStats();
        ModularLoadManagerWrapper loadManager = (ModularLoadManagerWrapper) pulsar.getLoadManager().get();

        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<?>> list = new ArrayList<>();
        LocalBrokerData data = loadManager.getLoadManager().updateLocalBrokerData();
        data.cleanDeltas();
        data.getBundles().clear();
        for (int i = 0; i < 1000; i++) {
            list.add(executor.submit(() -> {
                try {
                    assertNotNull(loadManager.generateLoadReport());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            list.add(executor.submit(() -> {
                try {
                    loadManager.writeLoadReportOnZookeeper();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> future : list) {
            future.get();
        }
        // allow proper shutdown so that resources aren't leaked
        doReturn(originalBrokerService).when(pulsar).getBrokerService();
    }

    /**
     * Usecase: Redirection due to different cluster 1. Broker1 runs on cluster: "use" and Broker2 runs on cluster:
     * "use2" 2. Broker1 receives "use2" cluster request => Broker1 reads "/clusters" from global-zookeeper and
     * redirects request to Broker2 which serves "use2" 3. Broker2 receives redirect request and own namespace bundle
     *
     * @throws Exception
     */
    @Test(enabled = false) // See https://github.com/apache/pulsar/issues/5437
    public void testMultipleBrokerDifferentClusterLookup() throws Exception {
        log.info("-- Starting {} test --", methodName);

        /**** start broker-2 ****/
        final String newCluster = "use2";
        final String property = "my-property2";
        ServiceConfiguration conf2 = new ServiceConfiguration();
        conf2.setAdvertisedAddress("localhost");
        conf2.setBrokerShutdownTimeoutMs(0L);
        conf2.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        conf2.setBrokerServicePort(Optional.of(0));
        conf2.setWebServicePort(Optional.of(0));
        conf2.setAdvertisedAddress("localhost");
        conf2.setClusterName(newCluster); // Broker2 serves newCluster
        conf2.setMetadataStoreUrl("zk:localhost:2181");
        conf2.setConfigurationMetadataStoreUrl("zk:localhost:3181");
        String broker2ServiceUrl = "pulsar://localhost:" + conf2.getBrokerServicePort().get();

        admin.clusters().createCluster(newCluster,
                ClusterData.builder()
                        .serviceUrl(pulsar.getWebServiceAddress())
                        .brokerServiceUrl(broker2ServiceUrl)
                        .build());
        admin.tenants().createTenant(property,
                new TenantInfoImpl(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet(newCluster)));
        admin.namespaces().createNamespace(property + "/" + newCluster + "/my-ns");

        @Cleanup
        PulsarTestContext pulsarTestContext2 = createAdditionalPulsarTestContext(conf2);
        PulsarService pulsar2 = pulsarTestContext2.getPulsarService();
        pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
        pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

        URI brokerServiceUrl = new URI(broker2ServiceUrl);
        @Cleanup
        PulsarClient pulsarClient2 = PulsarClient.builder().serviceUrl(brokerServiceUrl.toString()).build();

        LoadManager loadManager2 = spy(pulsar2.getLoadManager().get());
        Field loadManagerField = NamespaceService.class.getDeclaredField("loadManager");
        loadManagerField.setAccessible(true);

        // mock: return Broker2 as a Least-loaded broker when leader receives request
        doReturn(true).when(loadManager2).isCentralized();
        SimpleResourceUnit resourceUnit = new SimpleResourceUnit(pulsar2.getBrokerId(), null);
        doReturn(Optional.of(resourceUnit)).when(loadManager2).getLeastLoaded(any(ServiceUnitId.class));
        loadManagerField.set(pulsar.getNamespaceService(), new AtomicReference<>(loadManager2));
        /**** started broker-2 ****/

        // load namespace-bundle by calling Broker2
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://my-property2/use2/my-ns/my-topic1")
                .subscriptionName("my-subscriber-name").subscribe();
        Producer<byte[]> producer = pulsarClient2.newProducer(Schema.BYTES)
            .topic("persistent://my-property2/use2/my-ns/my-topic1")
            .create();

        for (int i = 0; i < 10; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            String receivedMessage = new String(msg.getData());
            log.debug("Received message: [{}]", receivedMessage);
            String expectedMessage = "my-message-" + i;
            testMessageOrderAndDuplicates(messageSet, receivedMessage, expectedMessage);
        }
        // Acknowledge the consumption of all messages at once
        consumer.acknowledgeCumulative(msg);
        consumer.close();
        producer.close();
    }

    /**
     * Create #PartitionedTopic and let it served by multiple brokers which requires a. tcp partitioned-metadata-lookup
     * b. multiple topic-lookup c. partitioned producer-consumer
     *
     * @throws Exception
     */
    @Test
    public void testPartitionTopicLookup() throws Exception {
        log.info("-- Starting {} test --", methodName);

        int numPartitions = 8;
        TopicName topicName = TopicName.get("persistent://my-property/my-ns/my-partitionedtopic1");

        admin.topics().createPartitionedTopic(topicName.toString(), numPartitions);

        /**** start broker-2 ****/
        ServiceConfiguration conf2 = new ServiceConfiguration();
        conf2.setAdvertisedAddress("localhost");
        conf2.setBrokerShutdownTimeoutMs(0L);
        conf2.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        conf2.setBrokerServicePort(Optional.of(0));
        conf2.setWebServicePort(Optional.of(0));
        conf2.setAdvertisedAddress("localhost");
        conf2.setClusterName(pulsar.getConfiguration().getClusterName());
        conf2.setMetadataStoreUrl("zk:localhost:2181");
        conf2.setConfigurationMetadataStoreUrl("zk:localhost:3181");

        @Cleanup
        PulsarTestContext pulsarTestContext2 = createAdditionalPulsarTestContext(conf2);
        PulsarService pulsar2 = pulsarTestContext2.getPulsarService();
        pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
        pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

        LoadManager loadManager1 = spy(pulsar.getLoadManager().get());
        LoadManager loadManager2 = spy(pulsar2.getLoadManager().get());
        Field loadManagerField = NamespaceService.class.getDeclaredField("loadManager");
        loadManagerField.setAccessible(true);

        // mock: return Broker2 as a Least-loaded broker when leader receives request
        doReturn(true).when(loadManager1).isCentralized();
        loadManagerField.set(pulsar.getNamespaceService(), new AtomicReference<>(loadManager1));

        // mock: redirect request to leader
        doReturn(true).when(loadManager2).isCentralized();
        loadManagerField.set(pulsar2.getNamespaceService(), new AtomicReference<>(loadManager2));
        /**** broker-2 started ****/

        Producer<byte[]> producer = pulsarClient.newProducer(Schema.BYTES)
            .topic(topicName.toString())
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.RoundRobinPartition).create();

        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName.toString())
                .subscriptionName("my-partitioned-subscriber").subscribe();

        for (int i = 0; i < 20; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            assertNotNull(msg, "Message should not be null");
            consumer.acknowledge(msg);
            String receivedMessage = new String(msg.getData());
            log.debug("Received message: [{}]", receivedMessage);
            assertTrue(messageSet.add(receivedMessage), "Message " + receivedMessage + " already received");
        }

        producer.close();
        consumer.unsubscribe();
        consumer.close();
        admin.topics().deletePartitionedTopic(topicName.toString());

        loadManager2 = null;

        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * 1. Start broker1 and broker2 with tls enable 2. Hit HTTPS lookup url at broker2 which redirects to HTTPS broker1
     *
     * @throws Exception
     */
    @Test
    public void testWebserviceServiceTls() throws Exception {
        log.info("-- Starting {} test --", methodName);

        /**** start broker-2 ****/
        ServiceConfiguration conf2 = new ServiceConfiguration();
        conf2.setBrokerShutdownTimeoutMs(0L);
        conf2.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        conf2.setAdvertisedAddress("localhost");
        conf2.setBrokerShutdownTimeoutMs(0L);
        conf2.setBrokerServicePort(Optional.of(0));
        conf2.setBrokerServicePortTls(Optional.of(0));
        conf2.setWebServicePort(Optional.of(0));
        conf2.setWebServicePortTls(Optional.of(0));
        conf2.setAdvertisedAddress("localhost");
        conf2.setTlsTrustCertsFilePath(CA_CERT_FILE_PATH);
        conf2.setTlsRequireTrustedClientCertOnConnect(true);
        conf2.setTlsCertificateFilePath(BROKER_CERT_FILE_PATH);
        conf2.setTlsKeyFilePath(BROKER_KEY_FILE_PATH);
        conf2.setClusterName(conf.getClusterName());
        conf2.setMetadataStoreUrl("zk:localhost:2181");
        conf2.setConfigurationMetadataStoreUrl("zk:localhost:3181");
        // Not in use, and because TLS is not configured, it will fail to start
        conf2.setSystemTopicEnabled(false);

        @Cleanup
        PulsarTestContext pulsarTestContext2 = createAdditionalPulsarTestContext(conf2);
        PulsarService pulsar2 = pulsarTestContext2.getPulsarService();

        pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
        pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

        LoadManager loadManager1 = spy(pulsar.getLoadManager().get());
        LoadManager loadManager2 = spy(pulsar2.getLoadManager().get());
        Field loadManagerField = NamespaceService.class.getDeclaredField("loadManager");
        loadManagerField.setAccessible(true);

        // mock: redirect request to leader [2]
        doReturn(true).when(loadManager2).isCentralized();
        loadManagerField.set(pulsar2.getNamespaceService(), new AtomicReference<>(loadManager2));
        loadManagerField.set(pulsar.getNamespaceService(), new AtomicReference<>(loadManager1));

        // mock: return Broker2 as a Least-loaded broker when leader receives
        // request [3]
        doReturn(true).when(loadManager1).isCentralized();
        doReturn(true).when(loadManager2).isCentralized();
        SimpleResourceUnit resourceUnit = new SimpleResourceUnit(pulsar.getBrokerId(), null);
        doReturn(Optional.of(resourceUnit)).when(loadManager2).getLeastLoaded(any(ServiceUnitId.class));
        doReturn(Optional.of(resourceUnit)).when(loadManager1).getLeastLoaded(any(ServiceUnitId.class));


        /**** started broker-2 ****/

        URI brokerServiceUrl = new URI("pulsar://localhost:" + conf2.getBrokerServicePort().get());
        @Cleanup
        PulsarClient pulsarClient2 = PulsarClient.builder().serviceUrl(brokerServiceUrl.toString()).build();

        final String lookupResourceUrl = "/lookup/v2/topic/persistent/my-property/my-ns/my-topic1";

        // set client cert_key file
        SSLContext sslCtx = SecurityUtility.createSslContext(false, CA_CERT_FILE_PATH,
                getTlsFileForClient("admin.cert"), getTlsFileForClient("admin.key-pk8"), "");
        HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());

        // hit broker2 url
        URLConnection con = new URL(pulsar2.getWebServiceAddressTls() + lookupResourceUrl).openConnection();
        log.info("orignal url: {}", con.getURL());
        con.connect();
        log.info("connected url: {} ", con.getURL());
        // assert connect-url: broker2-https
        assertEquals(Integer.valueOf(con.getURL().getPort()), conf2.getWebServicePortTls().get());
        InputStream is = con.getInputStream();
        // assert redirect-url: broker1-https only
        log.info("redirected url: {}", con.getURL());
        assertEquals(Integer.valueOf(con.getURL().getPort()), conf.getWebServicePortTls().get());
        is.close();

        loadManager1 = null;
        loadManager2 = null;

        conf.setBrokerServicePortTls(Optional.empty());
        conf.setWebServicePortTls(Optional.empty());
    }

    /**
     *
     * <pre>
     * When broker-1's load-manager splits the bundle and update local-policies, broker-2 should get watch of
     * local-policies and update bundleCache so, new lookup can be redirected properly.
     *
     * (1) Start broker-1 and broker-2
     * (2) Make sure broker-2 always assign bundle to broker1
     * (3) Broker-2 receives topic-1 request, creates local-policies and sets the watch
     * (4) Broker-1 will own topic-1
     * (5) Split the bundle for topic-1
     * (6) Broker-2 should get the watch and update bundle cache
     * (7) Make lookup request again to Broker-2 which should succeed.
     *
     * </pre>
     *
     * @throws Exception
     */
    @Test(timeOut = 20000)
    public void testSplitUnloadLookupTest() throws Exception {

        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/my-ns";
        // (1) Start broker-1
        ServiceConfiguration conf2 = new ServiceConfiguration();
        conf2.setAdvertisedAddress("localhost");
        conf2.setBrokerShutdownTimeoutMs(0L);
        conf2.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        conf2.setBrokerServicePort(Optional.of(0));
        conf2.setWebServicePort(Optional.of(0));
        conf2.setAdvertisedAddress("localhost");
        conf2.setClusterName(conf.getClusterName());
        conf2.setMetadataStoreUrl("zk:localhost:2181");
        conf2.setConfigurationMetadataStoreUrl("zk:localhost:3181");

        @Cleanup
        PulsarTestContext pulsarTestContext2 = createAdditionalPulsarTestContext(conf2);
        PulsarService pulsar2 = pulsarTestContext2.getPulsarService();
        pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
        pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

        pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
        pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

        LoadManager loadManager1 = spy(pulsar.getLoadManager().get());
        LoadManager loadManager2 = spy(pulsar2.getLoadManager().get());
        Field loadManagerField = NamespaceService.class.getDeclaredField("loadManager");
        loadManagerField.setAccessible(true);

        // (2) Make sure broker-2 always assign bundle to broker1
        // mock: redirect request to leader [2]
        doReturn(true).when(loadManager2).isCentralized();
        loadManagerField.set(pulsar2.getNamespaceService(), new AtomicReference<>(loadManager2));
        // mock: return Broker1 as a Least-loaded broker when leader receives request [3]
        doReturn(true).when(loadManager1).isCentralized();
        SimpleResourceUnit resourceUnit = new SimpleResourceUnit(pulsar.getBrokerId(), null);
        doReturn(Optional.of(resourceUnit)).when(loadManager1).getLeastLoaded(any(ServiceUnitId.class));
        doReturn(Optional.of(resourceUnit)).when(loadManager2).getLeastLoaded(any(ServiceUnitId.class));
        loadManagerField.set(pulsar.getNamespaceService(), new AtomicReference<>(loadManager1));

        @Cleanup
        PulsarClient pulsarClient2 = PulsarClient.builder().serviceUrl(pulsar2.getBrokerServiceUrl()).build();

        // (3) Broker-2 receives topic-1 request, creates local-policies and sets the watch
        final String topic1 = "persistent://" + namespace + "/topic1";
        Consumer<byte[]> consumer1 = pulsarClient2.newConsumer().topic(topic1).subscriptionName("my-subscriber-name")
                .subscribe();

        Set<String> serviceUnits1 = pulsar.getNamespaceService().getOwnedServiceUnits().stream()
                .map(nb -> nb.toString()).collect(Collectors.toSet());

        // (4) Broker-1 will own topic-1
        final String unsplitBundle = namespace + "/0x00000000_0xffffffff";
        assertTrue(serviceUnits1.contains(unsplitBundle));
        // broker-2 should have this bundle into the cache
        TopicName topicName = TopicName.get(topic1);
        NamespaceBundle bundleInBroker2 = pulsar2.getNamespaceService().getBundle(topicName);
        assertEquals(bundleInBroker2.toString(), unsplitBundle);

        // (5) Split the bundle for topic-1
        admin.namespaces().splitNamespaceBundle(namespace, "0x00000000_0xffffffff", true, null);

        // (6) Broker-2 should get the watch and update bundle cache
        final int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (pulsar2.getNamespaceService().getBundle(topicName).equals(bundleInBroker2) && i != retry - 1) {
                Thread.sleep(200);
            } else {
                break;
            }
        }

        // (7) Make lookup request again to Broker-2 which should succeed.
        final String topic2 = "persistent://" + namespace + "/topic2";
        Consumer<byte[]> consumer2 = pulsarClient.newConsumer().topic(topic2).subscriptionName("my-subscriber-name")
                .subscribe();

        NamespaceBundle bundleInBroker1AfterSplit = pulsar2.getNamespaceService().getBundle(TopicName.get(topic2));
        assertNotEquals(unsplitBundle, bundleInBroker1AfterSplit.toString());

        consumer1.close();
        consumer2.close();
    }

    /**
     *
     * <pre>
     * When broker-1's Modular-load-manager splits the bundle and update local-policies, broker-2 should get watch of
     * local-policies and update bundleCache so, new lookup can be redirected properly.
     *
     * (1) Start broker-1 and broker-2
     * (2) Make sure broker-2 always assign bundle to broker1
     * (3) Broker-2 receives topic-1 request, creates local-policies and sets the watch
     * (4) Broker-1 will own topic-1
     * (5) Broker-2 will be a leader and trigger Split the bundle for topic-1
     * (6) Broker-2 should get the watch and update bundle cache
     * (7) Make lookup request again to Broker-2 which should succeed.
     *
     * </pre>
     *
     * @throws Exception
     */
    @Test(timeOut = 20000)
    public void testModularLoadManagerSplitBundle() throws Exception {

        log.info("-- Starting {} test --", methodName);
        final String loadBalancerName = conf.getLoadManagerClassName();

        try {
            final String namespace = "my-property/my-ns";
            // (1) Start broker-1
            ServiceConfiguration conf2 = new ServiceConfiguration();
            conf2.setBrokerShutdownTimeoutMs(0L);
            conf2.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
            conf2.setAdvertisedAddress("localhost");
            conf2.setBrokerShutdownTimeoutMs(0L);
            conf2.setBrokerServicePort(Optional.of(0));
            conf2.setWebServicePort(Optional.of(0));
            conf2.setAdvertisedAddress("localhost");
            conf2.setClusterName(conf.getClusterName());
            conf2.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
            conf2.setMetadataStoreUrl("zk:localhost:2181");
            conf2.setConfigurationMetadataStoreUrl("zk:localhost:3181");
            conf2.setLoadBalancerAutoBundleSplitEnabled(true);
            conf2.setLoadBalancerAutoUnloadSplitBundlesEnabled(true);
            conf2.setLoadBalancerNamespaceBundleMaxTopics(1);

            @Cleanup
            PulsarTestContext pulsarTestContext2 = createAdditionalPulsarTestContext(conf2);
            PulsarService pulsar2 = pulsarTestContext2.getPulsarService();

            pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
            pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

            LoadManager loadManager1 = spy(pulsar.getLoadManager().get());
            LoadManager loadManager2 = spy(pulsar2.getLoadManager().get());
            Field loadManagerField = NamespaceService.class.getDeclaredField("loadManager");
            loadManagerField.setAccessible(true);

            // (2) Make sure broker-2 always assign bundle to broker1
            // mock: redirect request to leader [2]
            doReturn(true).when(loadManager2).isCentralized();
            loadManagerField.set(pulsar2.getNamespaceService(), new AtomicReference<>(loadManager2));
            // mock: return Broker1 as a Least-loaded broker when leader receives request [3]
            doReturn(true).when(loadManager1).isCentralized();
            SimpleResourceUnit resourceUnit = new SimpleResourceUnit(pulsar.getBrokerId(), null);
            Optional<ResourceUnit> res = Optional.of(resourceUnit);
            doReturn(res).when(loadManager1).getLeastLoaded(any(ServiceUnitId.class));
            doReturn(res).when(loadManager2).getLeastLoaded(any(ServiceUnitId.class));
            loadManagerField.set(pulsar.getNamespaceService(), new AtomicReference<>(loadManager1));

            @Cleanup
            PulsarClient pulsarClient2 = PulsarClient.builder().serviceUrl(pulsar2.getBrokerServiceUrl()).build();

            // (3) Broker-2 receives topic-1 request, creates local-policies and sets the watch
            final String topic1 = "persistent://" + namespace + "/topic1";
            @Cleanup
            Consumer<byte[]> consumer1 = pulsarClient2.newConsumer().topic(topic1)
                    .subscriptionName("my-subscriber-name").subscribe();

            // there should be more than one topic to trigger split
            final String topic2 = "persistent://" + namespace + "/topic2";
            @Cleanup
            Consumer<byte[]> consumer2 = pulsarClient2.newConsumer().topic(topic2)
                    .subscriptionName("my-subscriber-name")
                    .subscribe();

            // (4) Broker-1 will own topic-1
            final String unsplitBundle = namespace + "/0x00000000_0xffffffff";

            Awaitility.await().until(() ->
                    pulsar.getNamespaceService().getOwnedServiceUnits()
                    .stream()
                    .map(nb -> nb.toString())
                    .collect(Collectors.toSet())
                    .contains(unsplitBundle));

            // broker-2 should have this bundle into the cache
            TopicName topicName = TopicName.get(topic1);
            NamespaceBundle bundleInBroker2 = pulsar2.getNamespaceService().getBundle(topicName);
            assertEquals(bundleInBroker2.toString(), unsplitBundle);

            // update broker-1 bundle report to zk
            pulsar.getBrokerService().updateRates();
            pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
            // this will create znode for bundle-data
            pulsar.getLoadManager().get().writeResourceQuotasToZooKeeper();
            pulsar2.getLoadManager().get().writeLoadReportOnZookeeper();

            // (5) Modular-load-manager will split the bundle due to max-topic threshold reached
            Method updateAllMethod = ModularLoadManagerImpl.class.getDeclaredMethod("updateAll");
            updateAllMethod.setAccessible(true);

            // broker-2 loadManager is a leader and let it refresh load-report from all the brokers
            pulsar.getLeaderElectionService().close();

            Awaitility.await()
                    .untilAsserted(() -> pulsar2.getLeaderElectionService().isLeader());

            ModularLoadManagerImpl loadManager = (ModularLoadManagerImpl) ((ModularLoadManagerWrapper) pulsar2
                    .getLoadManager().get()).getLoadManager();

            updateAllMethod.invoke(loadManager);
            loadManager.checkNamespaceBundleSplit();

            // (6) Broker-2 should get the watch and update bundle cache
            Awaitility.await().untilAsserted(() -> {
                assertNotEquals(pulsar2.getNamespaceService().getBundle(topicName), bundleInBroker2);
            });

            // Unload the NamespacePolicies and AntiAffinity check.
            String currentBroker = pulsar.getBrokerId();
            assertTrue(loadManager.shouldNamespacePoliciesUnload(namespace,
                    "0x00000000_0xffffffff", currentBroker));
            assertTrue(loadManager.shouldAntiAffinityNamespaceUnload(namespace,
                    "0x00000000_0xffffffff", currentBroker));

            // (7) Make lookup request again to Broker-2 which should succeed.
            final String topic3 = "persistent://" + namespace + "/topic3";
            @Cleanup
            Consumer<byte[]> consumer3 = pulsarClient2.newConsumer().topic(topic3)
                    .subscriptionName("my-subscriber-name")
                    .subscribe();

            Awaitility.await().untilAsserted(() -> {
                NamespaceBundle bundleInBroker1AfterSplit = pulsar2.getNamespaceService()
                        .getBundle(TopicName.get(topic3));
                assertNotEquals(bundleInBroker1AfterSplit.toString(), unsplitBundle);
            });
        } finally {
            conf.setLoadManagerClassName(loadBalancerName);
        }
    }

    @Test(timeOut = 20000)
    public void testSkipSplitBundleIfOnlyOneBroker() throws Exception {

        log.info("-- Starting {} test --", methodName);
        final String loadBalancerName = conf.getLoadManagerClassName();
        final int defaultNumberOfNamespaceBundles = conf.getDefaultNumberOfNamespaceBundles();
        final int loadBalancerNamespaceBundleMaxTopics = conf.getLoadBalancerNamespaceBundleMaxTopics();

        final String namespace = "my-property/my-ns";
        final String topicName1 = BrokerTestUtil.newUniqueName("persistent://" + namespace + "/tp_");
        final String topicName2 = BrokerTestUtil.newUniqueName("persistent://" + namespace + "/tp_");
        try {
            final ModularLoadManagerWrapper modularLoadManagerWrapper =
                    (ModularLoadManagerWrapper) pulsar.getLoadManager().get();
            final ModularLoadManagerImpl modularLoadManager =
                    (ModularLoadManagerImpl) modularLoadManagerWrapper.getLoadManager();

            // Create one topic and trigger tasks, then verify there is only one bundle now.
            Consumer<byte[]> consumer1 = pulsarClient.newConsumer().topic(topicName1)
                    .subscriptionName("my-subscriber-name").subscribe();
            List<NamespaceBundle> bounldes1 = pulsar.getNamespaceService().getNamespaceBundleFactory()
                    .getBundles(NamespaceName.get(namespace)).getBundles();
            pulsar.getBrokerService().updateRates();
            pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
            pulsar.getLoadManager().get().writeResourceQuotasToZooKeeper();
            modularLoadManager.updateAll();
            assertEquals(bounldes1.size(), 1);

            // Create the second topic and trigger tasks, then verify the split task will be skipped.
            Consumer<byte[]> consumer2 = pulsarClient.newConsumer().topic(topicName2)
                    .subscriptionName("my-subscriber-name").subscribe();
            pulsar.getBrokerService().updateRates();
            pulsar.getLoadManager().get().writeLoadReportOnZookeeper();
            pulsar.getLoadManager().get().writeResourceQuotasToZooKeeper();
            modularLoadManager.updateAll();
            List<NamespaceBundle> bounldes2 = pulsar.getNamespaceService().getNamespaceBundleFactory()
                    .getBundles(NamespaceName.get(namespace)).getBundles();
            assertEquals(bounldes2.size(), 1);

            consumer1.close();
            consumer2.close();
            admin.topics().delete(topicName1, false);
            admin.topics().delete(topicName2, false);
        } finally {
            conf.setDefaultNumberOfNamespaceBundles(defaultNumberOfNamespaceBundles);
            conf.setLoadBalancerNamespaceBundleMaxTopics(loadBalancerNamespaceBundleMaxTopics);
            conf.setLoadManagerClassName(loadBalancerName);
        }
    }

    @Test
    public void testMergeGetPartitionedMetadataRequests() throws Exception {
        // Assert the lookup service is a "BinaryProtoLookupService".
        final PulsarClientImpl pulsarClientImpl = (PulsarClientImpl) pulsarClient;
        final LookupService lookupService = pulsarClientImpl.getLookup();
        assertTrue(lookupService instanceof BinaryProtoLookupService);

        final String tpName = BrokerTestUtil.newUniqueName("persistent://public/default/tp");
        final int topicPartitions = 10;
        admin.topics().createPartitionedTopic(tpName, topicPartitions);

        // Verify the request is works after merge the requests.
        List<CompletableFuture<PartitionedTopicMetadata>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(lookupService.getPartitionedTopicMetadata(TopicName.get(tpName), false));
        }
        for (CompletableFuture<PartitionedTopicMetadata> future : futures) {
            assertEquals(future.join().partitions, topicPartitions);
        }

        // cleanup.
        admin.topics().deletePartitionedTopic(tpName);
    }

    @Test
    public void testMergeLookupRequests() throws Exception {
        // Assert the lookup service is a "BinaryProtoLookupService".
        final PulsarClientImpl pulsarClientImpl = (PulsarClientImpl) pulsarClient;
        final LookupService lookupService = pulsarClientImpl.getLookup();
        assertTrue(lookupService instanceof BinaryProtoLookupService);

        final String tpName = BrokerTestUtil.newUniqueName("persistent://public/default/tp");
        admin.topics().createNonPartitionedTopic(tpName);

        // Create 1 producer and 100 consumers.
        List<Producer<String>> producers = new ArrayList<>();
        List<Consumer<String>> consumers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            producers.add(pulsarClient.newProducer(Schema.STRING).topic(tpName).create());
        }
        for (int i = 0; i < 20; i++) {
            consumers.add(pulsarClient.newConsumer(Schema.STRING).topic(tpName).subscriptionName("s" + i).subscribe());
        }

        // Verify the lookup count will be smaller than before improve.
        int lookupCountBeforeUnload = calculateLookupRequestCount();
        admin.namespaces().unload(TopicName.get(tpName).getNamespace());
        Awaitility.await().untilAsserted(() -> {
            for (Producer p : producers) {
                assertEquals(WhiteboxImpl.getInternalState(p, "state").toString(), "Ready");
            }
            for (Consumer c : consumers) {
                assertEquals(WhiteboxImpl.getInternalState(c, "state").toString(), "Ready");
            }
        });
        int lookupCountAfterUnload = calculateLookupRequestCount();
        log.info("lookup count before unload: {}, after unload: {}", lookupCountBeforeUnload, lookupCountAfterUnload);
        assertTrue(lookupCountAfterUnload < lookupCountBeforeUnload * 2,
                "the lookup count should be smaller than before improve");

        // Verify the producers and consumers is still works.
        List<String> messagesSent = new ArrayList<>();
        int index = 0;
        for (Producer producer: producers) {
            String message = Integer.valueOf(index++).toString();
            producer.send(message);
            messagesSent.add(message);
        }
        HashSet<String> messagesReceived = new HashSet<>();
        for (Consumer<String> consumer : consumers) {
            while (true) {
                Message<String> msg = consumer.receive(2, TimeUnit.SECONDS);
                if (msg == null) {
                    break;
                }
                messagesReceived.add(msg.getValue());
            }
        }
        assertEquals(messagesReceived.size(), producers.size());

        // cleanup.
        for (Producer producer: producers) {
            producer.close();
        }
        for (Consumer consumer : consumers) {
            consumer.close();
        }
        admin.topics().delete(tpName);
    }

    private int calculateLookupRequestCount() throws Exception {
        int failures = CollectorRegistry.defaultRegistry.getSampleValue("pulsar_broker_lookup_failures_total")
                .intValue();
        int answers = CollectorRegistry.defaultRegistry.getSampleValue("pulsar_broker_lookup_answers_total")
                .intValue();
        return failures + answers;
    }

    @Test(timeOut = 10000)
    public void testPartitionedMetadataWithDeprecatedVersion() throws Exception {

        final String cluster = "use2";
        final String property = "my-property2";
        final String namespace = "my-ns";
        final String topicName = "my-partitioned";
        final int totalPartitions = 10;
        final TopicName dest = TopicName.get("persistent", property, cluster, namespace, topicName);
        admin.clusters().createCluster(cluster,
                ClusterData.builder().serviceUrl(pulsar.getWebServiceAddress()).build());
        admin.tenants().createTenant(property,
                new TenantInfoImpl(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet(cluster)));
        admin.namespaces().createNamespace(property + "/" + cluster + "/" + namespace);
        admin.topics().createPartitionedTopic(dest.toString(), totalPartitions);

        URI brokerServiceUrl = new URI(pulsar.getSafeWebServiceAddress());

        URL url = brokerServiceUrl.toURL();
        String path = String.format("admin/%s/partitions", dest.getLookupName());

        AsyncHttpClient httpClient = getHttpClient("Pulsar-Java-1.20");
        PartitionedTopicMetadata metadata = getPartitionedMetadata(httpClient, url, path);
        assertEquals(metadata.partitions, totalPartitions);
        httpClient.close();

        httpClient = getHttpClient("Pulsar-CPP-v1.21");
        metadata = getPartitionedMetadata(httpClient, url, path);
        assertEquals(metadata.partitions, totalPartitions);
        httpClient.close();

        httpClient = getHttpClient("Pulsar-CPP-v1.21-SNAPSHOT");
        metadata = getPartitionedMetadata(httpClient, url, path);
        assertEquals(metadata.partitions, totalPartitions);
        httpClient.close();

        httpClient = getHttpClient("");
        try {
            metadata = getPartitionedMetadata(httpClient, url, path);
            fail("should have failed due to invalid version");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PulsarClientException);
        }
        httpClient.close();

        httpClient = getHttpClient("Pulsar-CPP-v1.20-SNAPSHOT");
        try {
            metadata = getPartitionedMetadata(httpClient, url, path);
            fail("should have failed due to invalid version");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PulsarClientException);
        }
        httpClient.close();

        httpClient = getHttpClient("Pulsar-CPP-v1.20");
        try {
            metadata = getPartitionedMetadata(httpClient, url, path);
            fail("should have failed due to invalid version");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PulsarClientException);
        }
        httpClient.close();
    }

    private PartitionedTopicMetadata getPartitionedMetadata(AsyncHttpClient httpClient, URL url, String path)
            throws Exception {
        final CompletableFuture<PartitionedTopicMetadata> future = new CompletableFuture<>();
        try {

            String requestUrl = new URL(url, path).toString();
            BoundRequestBuilder builder = httpClient.prepareGet(requestUrl);

            final ListenableFuture<Response> responseFuture = builder.setHeader("Accept", "application/json")
                    .execute(new AsyncCompletionHandler<Response>() {

                        @Override
                        public Response onCompleted(Response response) throws Exception {
                            return response;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            log.warn("[{}] Failed to perform http request: {}", requestUrl, t.getMessage());
                            future.completeExceptionally(new PulsarClientException(t));
                        }
                    });

            responseFuture.addListener(() -> {
                try {
                    Response response = responseFuture.get();
                    if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                        log.warn("[{}] HTTP get request failed: {}", requestUrl, response.getStatusText());
                        future.completeExceptionally(
                                new PulsarClientException("HTTP get request failed: " + response.getStatusText()));
                        return;
                    }

                    PartitionedTopicMetadata data = ObjectMapperFactory.getMapper().getObjectMapper()
                            .readValue(response.getResponseBodyAsBytes(), PartitionedTopicMetadata.class);
                    future.complete(data);
                } catch (Exception e) {
                    log.warn("[{}] Error during HTTP get request: {}", requestUrl, e.getMessage());
                    future.completeExceptionally(new PulsarClientException(e));
                }
            }, MoreExecutors.directExecutor());

        } catch (Exception e) {
            log.warn("[{}] Failed to get authentication data for lookup: {}", path, e.getMessage());
            if (e instanceof PulsarClientException) {
                future.completeExceptionally(e);
            } else {
                future.completeExceptionally(new PulsarClientException(e));
            }
        }
        return future.get();
    }

    private AsyncHttpClient getHttpClient(String version) {
        DefaultAsyncHttpClientConfig.Builder confBuilder = new DefaultAsyncHttpClientConfig.Builder();
        confBuilder.setCookieStore(null);
        confBuilder.setUseProxyProperties(true);
        confBuilder.setFollowRedirect(true);
        confBuilder.setUserAgent(version);
        confBuilder.setKeepAliveStrategy(new DefaultKeepAliveStrategy() {
            @Override
            public boolean keepAlive(InetSocketAddress remoteAddress, Request ahcRequest,
                                     HttpRequest request, HttpResponse response) {
                // Close connection upon a server error or per HTTP spec
                return (response.status().code() / 100 != 5)
                       && super.keepAlive(remoteAddress, ahcRequest, request, response);
            }
        });
        AsyncHttpClientConfig config = confBuilder.build();
        return new DefaultAsyncHttpClient(config);
    }

    /**** helper classes. ****/

    public static class MockAuthenticationProvider implements AuthenticationProvider {
        @Override
        public void close() throws IOException {
        }

        @Override
        public void initialize(ServiceConfiguration config) throws IOException {
        }

        @Override
        public String getAuthMethodName() {
            return "auth";
        }

        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            return "appid1";
        }
    }

    public static class MockAuthenticationProviderFail extends MockAuthenticationProvider {
        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            throw new AuthenticationException("authentication failed");
        }
    }

    public static class MockAuthorizationProviderFail extends MockAuthenticationProvider {
        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            return "invalid";
        }
    }

    @Test
    public void testLookupConnectionNotCloseIfGetUnloadingExOrMetadataEx() throws Exception {
        if (useTestZookeeper) {
            throw new SkipException("This test case depends on MockZooKeeper");
        }
        String tpName = BrokerTestUtil.newUniqueName("persistent://public/default/tp");
        admin.topics().createNonPartitionedTopic(tpName);
        PulsarClientImpl pulsarClientImpl = (PulsarClientImpl) pulsarClient;
        Producer<String> producer = pulsarClientImpl.newProducer(Schema.STRING).topic(tpName).create();
        Consumer<String> consumer = pulsarClientImpl.newConsumer(Schema.STRING).topic(tpName)
                .subscriptionName("s1").isAckReceiptEnabled(true).subscribe();
        LookupService lookupService = pulsarClientImpl.getLookup();
        assertTrue(lookupService instanceof BinaryProtoLookupService);
        ClientCnx lookupConnection = pulsarClientImpl.getCnxPool().getConnection(lookupService.resolveHost()).join();

        var metricReader = pulsarTestContext.getOpenTelemetryMetricReader();
        assertThat(metricReader.collectAllMetrics())
                .noneSatisfy(metric -> assertThat(metric)
                        .hasName(LOOKUP_REQUEST_DURATION_METRIC_NAME)
                        .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                                point -> point
                                        .hasAttributes(NamespaceService.PULSAR_LOOKUP_RESPONSE_FAILURE_ATTRIBUTES),
                                point -> point
                                        .hasAttributes(NamespaceService.PULSAR_LOOKUP_RESPONSE_BROKER_ATTRIBUTES))));

        // Verify the socket will not be closed if the bundle is unloading.
        BundleOfTopic bundleOfTopic = new BundleOfTopic(tpName);
        bundleOfTopic.setBundleIsUnloading();
        try {
            lookupService.getBroker(TopicName.get(tpName)).get();
            fail("It should failed due to the namespace bundle is unloading.");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("is being unloaded"));
        }

        assertThat(metricReader.collectAllMetrics())
                .anySatisfy(metric -> assertThat(metric)
                        .hasName(LOOKUP_REQUEST_DURATION_METRIC_NAME)
                        .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                                point -> point
                                        .hasAttributes(NamespaceService.PULSAR_LOOKUP_RESPONSE_FAILURE_ATTRIBUTES)
                                        .hasCount(1),
                                point -> point
                                        .hasAttributes(NamespaceService.PULSAR_LOOKUP_RESPONSE_BROKER_ATTRIBUTES))));
        // Do unload topic, trigger producer & consumer reconnection.
        pulsar.getBrokerService().getTopic(tpName, false).join().get().close(true);
        assertTrue(lookupConnection.ctx().channel().isActive());
        bundleOfTopic.setBundleIsNotUnloading();
        //  Assert producer & consumer could reconnect successful.
        producer.send("1");
        HashSet<String> messagesReceived = new HashSet<>();
        while (true) {
            Message<String> msg = consumer.receive(2, TimeUnit.SECONDS);
            if (msg == null) {
                break;
            }
            messagesReceived.add(msg.getValue());
        }
        assertTrue(messagesReceived.contains("1"));

        // Verify the socket will not be closed if get a metadata ex.
        bundleOfTopic.releaseBundleLockAndMakeAcquireFail();
        try {
            lookupService.getBroker(TopicName.get(tpName)).get();
            fail("It should failed due to the acquire bundle lock fail.");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("OperationTimeout"));
        }
        // Do unload topic, trigger producer & consumer reconnection.
        pulsar.getBrokerService().getTopic(tpName, false).join().get().close(true);
        assertTrue(lookupConnection.ctx().channel().isActive());
        bundleOfTopic.makeAcquireBundleLockSuccess();
        // Assert producer could reconnect successful.
        producer.send("2");
        while (true) {
            Message<String> msg = consumer.receive(2, TimeUnit.SECONDS);
            if (msg == null) {
                break;
            }
            messagesReceived.add(msg.getValue());
        }
        assertTrue(messagesReceived.contains("2"));

        // cleanup.
        producer.close();
        consumer.close();
        admin.topics().delete(tpName);
    }

    private class BundleOfTopic {

        private NamespaceBundle namespaceBundle;
        private OwnershipCache ownershipCache;
        private AsyncLoadingCache<NamespaceBundle, OwnedBundle> ownedBundlesCache;

        public BundleOfTopic(String tpName) {
            namespaceBundle = pulsar.getNamespaceService().getBundle(TopicName.get(tpName));
            ownershipCache = pulsar.getNamespaceService().getOwnershipCache();
            ownedBundlesCache = WhiteboxImpl.getInternalState(ownershipCache, "ownedBundlesCache");
        }

        private void setBundleIsUnloading() {
            ownedBundlesCache.get(namespaceBundle).join().setActive(false);
        }

        private void setBundleIsNotUnloading() {
            ownedBundlesCache.get(namespaceBundle).join().setActive(true);
        }

        private void releaseBundleLockAndMakeAcquireFail() throws Exception {
            ownedBundlesCache.synchronous().invalidateAll();
            mockZooKeeper.delete(ServiceUnitUtils.path(namespaceBundle), -1);
            mockZooKeeper.setAlwaysFail(KeeperException.Code.OPERATIONTIMEOUT);
        }

        private void makeAcquireBundleLockSuccess() throws Exception {
            mockZooKeeper.unsetAlwaysFail();
        }
    }

    // TODO: This test is disabled since it's invalid. The test fails for both TestZKServer and MockZooKeeper.
    @Test(timeOut = 30000, enabled = false)
    public void testLookupConnectionNotCloseIfFailedToAcquireOwnershipOfBundle() throws Exception {
        String tpName = BrokerTestUtil.newUniqueName("persistent://public/default/tp");
        admin.topics().createNonPartitionedTopic(tpName);
        final var pulsarClientImpl = (PulsarClientImpl) pulsarClient;
        final var cache = pulsar.getNamespaceService().getOwnershipCache();
        final var bundle = pulsar.getNamespaceService().getBundle(TopicName.get(tpName));
        final var value = cache.getOwnerAsync(bundle).get().orElse(null);
        assertNotNull(value);

        cache.invalidateLocalOwnerCache();
        final var lock = pulsar.getCoordinationService().getLockManager(NamespaceEphemeralData.class)
                .acquireLock(ServiceUnitUtils.path(bundle), new NamespaceEphemeralData()).join();
        lock.updateValue(null);
        log.info("Updated bundle {} with null", bundle.getBundleRange());

        // wait for the system topic reader to __change_events is closed, otherwise the test will be affected
        Thread.sleep(500);

        final var future = pulsarClientImpl.getLookup().getBroker(TopicName.get(tpName));
        final var cnx = pulsarClientImpl.getCnxPool().getConnections().stream().findAny()
                .map(CompletableFuture::join).orElse(null);
        assertNotNull(cnx);

        try {
            future.get();
            fail();
        } catch (ExecutionException e) {
            log.info("getBroker failed with {}: {}", e.getCause().getClass().getName(), e.getMessage());
            assertTrue(e.getCause() instanceof PulsarClientException.BrokerMetadataException);
            assertTrue(cnx.ctx().channel().isActive());
            lock.updateValue(value);
            lock.release();
            assertTrue(e.getMessage().contains("Failed to acquire ownership"));
            pulsarClientImpl.getLookup().getBroker(TopicName.get(tpName)).get();
        }
    }
}
