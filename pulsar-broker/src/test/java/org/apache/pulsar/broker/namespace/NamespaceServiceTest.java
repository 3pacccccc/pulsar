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
package org.apache.pulsar.broker.namespace;

import static org.apache.pulsar.broker.resources.LoadBalanceResources.BUNDLE_DATA_BASE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.broker.loadbalance.LoadData;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.ResourceUnit;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerImpl;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerWrapper;
import org.apache.pulsar.broker.loadbalance.impl.SimpleResourceUnit;
import org.apache.pulsar.broker.lookup.LookupResult;
import org.apache.pulsar.broker.service.BrokerTestBase;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.admin.Namespaces;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceBundleFactory;
import org.apache.pulsar.common.naming.NamespaceBundleSplitAlgorithm;
import org.apache.pulsar.common.naming.NamespaceBundles;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.ServiceUnitId;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.BundlesData;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.LocalPolicies;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.policies.data.TopicType;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.metadata.api.GetResult;
import org.apache.pulsar.metadata.api.MetadataCache;
import org.apache.pulsar.metadata.api.Notification;
import org.apache.pulsar.metadata.api.extended.CreateOption;
import org.apache.pulsar.policies.data.loadbalancer.AdvertisedListener;
import org.apache.pulsar.policies.data.loadbalancer.BundleData;
import org.apache.pulsar.policies.data.loadbalancer.LoadReport;
import org.apache.pulsar.policies.data.loadbalancer.LocalBrokerData;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.awaitility.Awaitility;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Test(groups = "flaky")
public class NamespaceServiceTest extends BrokerTestBase {

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        super.baseSetup();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testSplitAndOwnBundles() throws Exception {

        OwnershipCache mockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
        doReturn(CompletableFuture.completedFuture(null)).when(mockOwnershipCache)
                .disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), mockOwnershipCache);
        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get("pulsar/global/ns1");
        TopicName topicName = TopicName.get("persistent://pulsar/global/ns1/topic-1");
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);
        NamespaceBundle originalBundle = bundles.findBundle(topicName);

        // Split bundle and take ownership of split bundles
        CompletableFuture<Void> result = namespaceService.splitAndOwnBundle(
                originalBundle,
                false,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null);

        try {
            result.get();
        } catch (Exception e) {
            // make sure: no failure
            fail("split bundle failed", e);
        }
        NamespaceBundleFactory bundleFactory = this.pulsar.getNamespaceService().getNamespaceBundleFactory();
        NamespaceBundles updatedNsBundles = bundleFactory.getBundles(nsname);

        // new updated bundles shouldn't be null
        assertNotNull(updatedNsBundles);
        List<NamespaceBundle> bundleList = updatedNsBundles.getBundles();
        assertNotNull(bundles);

        NamespaceBundleFactory utilityFactory = NamespaceBundleFactory.createFactory(pulsar, Hashing.crc32());

        // (1) validate bundleFactory-cache has newly split bundles and removed old parent bundle
        Pair<NamespaceBundles, List<NamespaceBundle>> splitBundles = splitBundles(utilityFactory, nsname, bundles,
                originalBundle);
        assertNotNull(splitBundles);
        Set<NamespaceBundle> splitBundleSet = new HashSet<>(splitBundles.getRight());
        splitBundleSet.removeAll(bundleList);
        assertTrue(splitBundleSet.isEmpty());

        // (2) validate localPolicies and policies  updated with newly created split
        // bundles
        LocalPolicies localPolicies = pulsar.getPulsarResources().getLocalPolicies().getLocalPolicies(nsname).get();
        NamespaceBundles localZkBundles = bundleFactory.getBundles(nsname, localPolicies.bundles);
        assertEquals(localZkBundles, updatedNsBundles);
        log.info("LocalPolicies: {}", localPolicies);

        Policies policies = pulsar.getPulsarResources().getNamespaceResources().getPolicies(nsname).get();
        NamespaceBundles zkBundles = bundleFactory.getBundles(nsname, policies.bundles);
        assertEquals(zkBundles, updatedNsBundles);
        log.info("Policies: {}", policies);

        // (3) validate ownership of new split bundles by local owner
        bundleList.forEach(b -> {
            try {
                byte[] data = this.pulsar.getLocalMetadataStore().get(ServiceUnitUtils.path(b)).join().get().getValue();
                NamespaceEphemeralData node = ObjectMapperFactory.getMapper().reader().readValue(data,
                        NamespaceEphemeralData.class);
                Assert.assertEquals(node.getNativeUrl(), this.pulsar.getBrokerServiceUrl());
            } catch (Exception e) {
                fail("failed to setup ownership", e);
            }
        });

    }

    @Test
    public void testSplitMapWithRefreshedStatMap() throws Exception {

        OwnershipCache mockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());

        ManagedLedger ledger = mock(ManagedLedger.class);
        when(ledger.getCursors()).thenReturn(new ArrayList<>());
        when(ledger.getConfig()).thenReturn(new ManagedLedgerConfig());

        doReturn(CompletableFuture.completedFuture(null)).when(mockOwnershipCache)
                .disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), mockOwnershipCache);

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get("pulsar/global/ns1");
        TopicName topicName = TopicName.get("persistent://pulsar/global/ns1/topic-1");
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);
        NamespaceBundle originalBundle = bundles.findBundle(topicName);

        PersistentTopic topic = new PersistentTopic(topicName.toString(), ledger, pulsar.getBrokerService());
        topic.initialize().join();
        Method method = pulsar.getBrokerService().getClass().getDeclaredMethod("addTopicToStatsMaps",
                TopicName.class, Topic.class);
        method.setAccessible(true);
        method.invoke(pulsar.getBrokerService(), topicName, topic);
        String nspace = originalBundle.getNamespaceObject().toString();
        List<Topic> list = this.pulsar.getBrokerService().getAllTopicsFromNamespaceBundle(nspace,
                originalBundle.toString());
        assertNotNull(list);

        // Split bundle and take ownership of split bundles
        CompletableFuture<Void> result = namespaceService.splitAndOwnBundle(
                originalBundle,
                false,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null);
        try {
            result.get();
        } catch (Exception e) {
            // make sure: no failure
            fail("split bundle failed", e);
        }

        // old bundle should be removed from status-map
        list = this.pulsar.getBrokerService().getAllTopicsFromNamespaceBundle(nspace, originalBundle.toString());
        assertTrue(list.isEmpty());

        // status-map should be updated with new split bundles
        NamespaceBundle splitBundle = pulsar.getNamespaceService().getBundle(topicName);
        assertFalse(CollectionUtils.isEmpty(
            this.pulsar.getBrokerService()
                .getAllTopicsFromNamespaceBundle(nspace, splitBundle.toString())));

    }

    @Test
    public void testIsServiceUnitDisabled() throws Exception {

        OwnershipCache mockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());

        ManagedLedger ledger = mock(ManagedLedger.class);
        when(ledger.getCursors()).thenReturn(new ArrayList<>());

        doReturn(CompletableFuture.completedFuture(null)).when(mockOwnershipCache)
                .disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), mockOwnershipCache);

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get("pulsar/global/ns1");
        TopicName topicName = TopicName.get("persistent://pulsar/global/ns1/topic-1");
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);
        NamespaceBundle originalBundle = bundles.findBundle(topicName);

        assertFalse(namespaceService.isNamespaceBundleDisabled(originalBundle));

    }

    @Test
    public void testRemoveOwnershipNamespaceBundle() throws Exception {

        OwnershipCache ownershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());

        ManagedLedger ledger = mock(ManagedLedger.class);
        when(ledger.getCursors()).thenReturn(new ArrayList<>());

        doReturn(CompletableFuture.completedFuture(null)).when(ownershipCache)
                .disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), ownershipCache);

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get("prop/use/ns1");
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);

        NamespaceBundle bundle = bundles.getBundles().get(0);
        ownershipCache.tryAcquiringOwnership(bundle).get();
        assertNotNull(ownershipCache.getOwnedBundle(bundle));
        ownershipCache.removeOwnership(bundles).get();
        assertNull(ownershipCache.getOwnedBundle(bundle));
    }

    @Test
    public void testUnloadNamespaceBundleFailure() throws Exception {

        final String topicName = "persistent://my-property/use/my-ns/my-topic1";
        pulsarClient.newConsumer().topic(topicName).subscriptionName("my-subscriber-name").subscribe();

        final var topics = pulsar.getBrokerService().getTopics();
        Topic spyTopic = spy(topics.get(topicName).get().get());
        topics.clear();
        CompletableFuture<Optional<Topic>> topicFuture = CompletableFuture.completedFuture(Optional.of(spyTopic));
        // add mock topic
        topics.put(topicName, topicFuture);
        doAnswer((Answer<CompletableFuture<Void>>) invocation -> {
            CompletableFuture<Void> result = new CompletableFuture<>();
            result.completeExceptionally(new RuntimeException("first time failed"));
            return result;
        }).when(spyTopic).close(false);
        NamespaceBundle bundle = pulsar.getNamespaceService().getBundle(TopicName.get(topicName));

        pulsar.getNamespaceService().unloadNamespaceBundle(bundle).join();

        Optional<GetResult> res = this.pulsar.getLocalMetadataStore().get(ServiceUnitUtils.path(bundle)).join();
        assertFalse(res.isPresent());
    }

    /**
     * It verifies that unloading bundle will timeout and will not hung even if one of the topic-unloading stuck.
     *
     * @throws Exception
     */
    @Test(timeOut = 6000)
    public void testUnloadNamespaceBundleWithStuckTopic() throws Exception {

        final String topicName = "persistent://my-property/use/my-ns/my-topic1";
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName("my-subscriber-name")
                .subscribe();
        final var topics = pulsar.getBrokerService().getTopics();
        Topic spyTopic = spy(topics.get(topicName).get().get());
        topics.clear();
        CompletableFuture<Optional<Topic>> topicFuture = CompletableFuture.completedFuture(Optional.of(spyTopic));
        // add mock topic
        topics.put(topicName, topicFuture);
        // return uncompleted future as close-topic result.
        doAnswer((Answer<CompletableFuture<Void>>) invocation -> new CompletableFuture<Void>())
                .when(spyTopic).close(false);
        NamespaceBundle bundle = pulsar.getNamespaceService().getBundle(TopicName.get(topicName));

        // try to unload bundle whose topic will be stuck
        pulsar.getNamespaceService().unloadNamespaceBundle(bundle, 1, TimeUnit.SECONDS).join();

        Optional<GetResult> res = this.pulsar.getLocalMetadataStore().get(ServiceUnitUtils.path(bundle)).join();
        assertFalse(res.isPresent());
        consumer.close();
    }

    /**
     * <pre>
     *  It verifies that namespace service deserialize the load-report based on load-manager which active.
     *  1. write candidate1- load report using {@link LoadReport} which is used by SimpleLoadManagerImpl
     *  2. Write candidate2- load report using {@link LocalBrokerData} which is used by ModularLoadManagerImpl
     *  3. try to get Lookup Result based on active load-manager
     * </pre>
     * @throws Exception
     */
    @Test
    public void testLoadReportDeserialize() throws Exception {

        final String candidateBroker1 = "localhost:8000";
        String broker1Url = "pulsar://localhost:6650";
        final String candidateBroker2 = "localhost:3000";
        String broker2Url = "pulsar://localhost:6660";
        LoadReport lr = new LoadReport("http://" + candidateBroker1, null, broker1Url, null);
        LocalBrokerData ld = new LocalBrokerData("http://" + candidateBroker2, null, broker2Url, null);
        String path1 = String.format("%s/%s", LoadManager.LOADBALANCE_BROKERS_ROOT, candidateBroker1);
        String path2 = String.format("%s/%s", LoadManager.LOADBALANCE_BROKERS_ROOT, candidateBroker2);

        pulsar.getLocalMetadataStore().put(path1,
                ObjectMapperFactory.getMapper().writer().writeValueAsBytes(lr),
                Optional.empty(),
                EnumSet.of(CreateOption.Ephemeral)
                ).join();
        pulsar.getLocalMetadataStore().put(path2,
                ObjectMapperFactory.getMapper().writer().writeValueAsBytes(ld),
                Optional.empty(),
                EnumSet.of(CreateOption.Ephemeral)
        ).join();
        LookupResult result1 = pulsar.getNamespaceService().createLookupResult(candidateBroker1, false, null).get();

        // update to new load manager
        LoadManager oldLoadManager = pulsar.getLoadManager()
                .getAndSet(new ModularLoadManagerWrapper(new ModularLoadManagerImpl()));
        oldLoadManager.stop();
        LookupResult result2 = pulsar.getNamespaceService().createLookupResult(candidateBroker2, false, null).get();
        Assert.assertEquals(result1.getLookupData().getBrokerUrl(), broker1Url);
        Assert.assertEquals(result2.getLookupData().getBrokerUrl(), broker2Url);
        System.out.println(result2);
    }

    @Test
    public void testCreateLookupResult() throws Exception {

        final String candidateBroker = "localhost:8080";
        final String brokerUrl = "pulsar://localhost:6650";
        final String listenerUrl = "pulsar://localhost:7000";
        final String listenerUrlTls = "pulsar://localhost:8000";
        final String listener = "listenerName";
        Map<String, AdvertisedListener> advertisedListeners = new HashMap<>();
        advertisedListeners.put(listener, AdvertisedListener.builder()
                .brokerServiceUrl(new URI(listenerUrl)).brokerServiceUrlTls(new URI(listenerUrlTls)).build());
        LocalBrokerData ld = new LocalBrokerData("http://" + candidateBroker,
                null, brokerUrl, null, advertisedListeners);
        String path = String.format("%s/%s", LoadManager.LOADBALANCE_BROKERS_ROOT, candidateBroker);

        pulsar.getLocalMetadataStore().put(path,
                ObjectMapperFactory.getMapper().writer().writeValueAsBytes(ld),
                Optional.empty(),
                EnumSet.of(CreateOption.Ephemeral)).join();

        LookupResult noListener = pulsar.getNamespaceService()
                .createLookupResult(candidateBroker, false, null).get();
        LookupResult withListener = pulsar.getNamespaceService()
                .createLookupResult(candidateBroker, false, listener).get();

        Assert.assertEquals(noListener.getLookupData().getBrokerUrl(), brokerUrl);
        Assert.assertEquals(withListener.getLookupData().getBrokerUrl(), listenerUrl);
        Assert.assertEquals(withListener.getLookupData().getBrokerUrlTls(), listenerUrlTls);
        System.out.println(withListener);
    }

    @Test
    public void testCreateNamespaceWithDefaultNumberOfBundles() throws Exception {
        OwnershipCache mockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
        doReturn(CompletableFuture.completedFuture(null)).when(mockOwnershipCache)
                .disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), mockOwnershipCache);
        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get("pulsar/global/ns1");
        TopicName topicName = TopicName.get("persistent://pulsar/global/ns1/topic-1");
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);
        NamespaceBundle originalBundle = bundles.findBundle(topicName);

        // Split bundle and take ownership of split bundles
        CompletableFuture<Void> result = namespaceService.splitAndOwnBundle(originalBundle, false,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null);

        try {
            result.get();
        } catch (Exception e) {
            // make sure: no failure
            fail("split bundle failed", e);
        }
        NamespaceBundleFactory bundleFactory = this.pulsar.getNamespaceService().getNamespaceBundleFactory();
        NamespaceBundles updatedNsBundles = bundleFactory.getBundles(nsname);

        // new updated bundles shouldn't be null
        assertNotNull(updatedNsBundles);
        List<NamespaceBundle> bundleList = updatedNsBundles.getBundles();
        assertNotNull(bundles);

        NamespaceBundleFactory utilityFactory = NamespaceBundleFactory.createFactory(pulsar, Hashing.crc32());

        // (1) validate bundleFactory-cache has newly split bundles and removed old parent bundle
        Pair<NamespaceBundles, List<NamespaceBundle>> splitBundles = splitBundles(utilityFactory, nsname, bundles,
                originalBundle);
        assertNotNull(splitBundles);
        Set<NamespaceBundle> splitBundleSet = new HashSet<>(splitBundles.getRight());
        splitBundleSet.removeAll(bundleList);
        assertTrue(splitBundleSet.isEmpty());

        // (2) validate LocalZookeeper policies updated with newly created split
        // bundles
        LocalPolicies policies = this.pulsar.getPulsarResources().getLocalPolicies().getLocalPolicies(nsname).get();
        NamespaceBundles localZkBundles = bundleFactory.getBundles(nsname, policies.bundles);
        assertEquals(localZkBundles, updatedNsBundles);
        log.info("Policies: {}", policies);

        // (3) validate ownership of new split bundles by local owner
        bundleList.forEach(b -> {
            try {
                byte[] data = this.pulsar.getLocalMetadataStore().get(ServiceUnitUtils.path(b)).join().get().getValue();
                NamespaceEphemeralData node = ObjectMapperFactory.getMapper().reader().readValue(data,
                        NamespaceEphemeralData.class);
                Assert.assertEquals(node.getNativeUrl(), this.pulsar.getBrokerServiceUrl());
            } catch (Exception e) {
                fail("failed to setup ownership", e);
            }
        });

    }

    @Test
    public void testRemoveOwnershipAndSplitBundle() throws Exception {
        OwnershipCache ownershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
        doReturn(CompletableFuture.completedFuture(null)).when(ownershipCache)
                .disableOwnership(any(NamespaceBundle.class));

        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), ownershipCache);

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get("pulsar/global/ns1");
        TopicName topicName = TopicName.get("persistent://pulsar/global/ns1/topic-1");
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);
        NamespaceBundle originalBundle = bundles.findBundle(topicName);

        CompletableFuture<Void> result1 = namespaceService.splitAndOwnBundle(originalBundle, false,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null);
        try {
            result1.get();
        } catch (Exception e) {
            fail("split bundle failed", e);
        }

        NamespaceBundles updatedNsBundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);
        assertNotNull(updatedNsBundles);
        NamespaceBundle splittedBundle = updatedNsBundles.findBundle(topicName);

        updatedNsBundles.getBundles().stream().filter(bundle -> !bundle.equals(splittedBundle)).forEach(bundle -> {
            try {
                ownershipCache.removeOwnership(bundle).get();
            } catch (Exception e) {
                fail("failed to remove ownership", e);
            }
        });

        CompletableFuture<Void> result2 = namespaceService.splitAndOwnBundle(splittedBundle, true,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null);
        try {
            result2.get();
        } catch (Exception e) {
            // make sure: NPE does not occur
            fail("split bundle failed", e);
        }
    }


    @Test
    public void testSplitBundleAndRemoveOldBundleFromOwnerShipCache() throws Exception {
        OwnershipCache ownershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
        doReturn(CompletableFuture.completedFuture(null)).when(ownershipCache)
                .disableOwnership(any(NamespaceBundle.class));

        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), ownershipCache);

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get("pulsar/global/ns1");
        TopicName topicName = TopicName.get("persistent://pulsar/global/ns1/topic-1");
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);

        NamespaceBundle splitBundle1 = bundles.findBundle(topicName);
        ownershipCache.tryAcquiringOwnership(splitBundle1);
        CompletableFuture<Void> result1 = namespaceService.splitAndOwnBundle(splitBundle1, false,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null);
        try {
            result1.get();
        } catch (Exception e) {
            fail("split bundle failed", e);
        }
        Awaitility.await().untilAsserted(()
                -> assertNull(namespaceService.getOwnershipCache().getOwnedBundles().get(splitBundle1)));

        //unload split
        bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);
        assertNotNull(bundles);
        NamespaceBundle splitBundle2 = bundles.findBundle(topicName);
        CompletableFuture<Void> result2 = namespaceService.splitAndOwnBundle(splitBundle2, true,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null);
        try {
            result2.get();
        } catch (Exception e) {
            // make sure: NPE does not occur
            fail("split bundle failed", e);
        }
        Awaitility.await().untilAsserted(()
                -> assertNull(namespaceService.getOwnershipCache().getOwnedBundles().get(splitBundle2)));
    }


    @Test
    public void testSplitLargestBundle() throws Exception {
        String namespace = "prop/test/ns-abc2";
        String topic = "persistent://" + namespace + "/t1-";
        int totalTopics = 100;

        BundlesData bundleData = BundlesData.builder().numBundles(10).build();
        admin.namespaces().createNamespace(namespace, bundleData);
        Consumer<byte[]>[] consumers = new Consumer[totalTopics];
        for (int i = 0; i < totalTopics; i++) {
            consumers[i] = pulsarClient.newConsumer().topic(topic + i).subscriptionName("my-subscriber-name")
                    .subscribe();
        }

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get(namespace);
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);

        Map<String, Integer> topicCount = new HashMap<>();
        int maxTopics = 0;
        String maxBundle = null;
        for (int i = 0; i < totalTopics; i++) {
            String bundle = bundles.findBundle(TopicName.get(topic + i)).getBundleRange();
            int count = topicCount.getOrDefault(bundle, 0) + 1;
            topicCount.put(bundle, count);
            if (count > maxTopics) {
                maxTopics = count;
                maxBundle = bundle;
            }
        }

        String largestBundle = namespaceService.getNamespaceBundleFactory().getBundleWithHighestTopics(nsname)
                .getBundleRange();

        assertEquals(maxBundle, largestBundle);

        for (int i = 0; i < totalTopics; i++) {
            consumers[i].close();
        }

        admin.namespaces().splitNamespaceBundle(namespace, Policies.BundleType.LARGEST.toString(), false, null);

        for (NamespaceBundle bundle : namespaceService.getNamespaceBundleFactory().getBundles(nsname).getBundles()) {
            assertNotEquals(bundle.getBundleRange(), maxBundle);
        }
    }

    public void testSplitBUndleWithNoBundle() throws  Exception {
        conf.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
        restartBroker();
        String namespace = "prop/test/ns-abc2";

        BundlesData bundleData = BundlesData.builder().numBundles(10).build();
        admin.namespaces().createNamespace(namespace, bundleData);

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get(namespace);
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);

        try {
            admin.namespaces().splitNamespaceBundle(namespace, Policies.BundleType.HOT.toString(), false, null);
            fail("should have failed.");
        } catch (Exception ex) {
            Assert.assertEquals(404, ((PulsarAdminException) ex).getStatusCode());
            Assert.assertEquals("Bundle range HOT not found", ex.getMessage());
        }
    }
    /**
     * Test bundle split with hot bundle which is serving highest load.
     *
     * @throws Exception
     */
    @Test
    public void testSplitBundleWithHighestThroughput() throws Exception {

        conf.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
        restartBroker();
        String namespace = "prop/test/ns-abc2";
        String topic = "persistent://" + namespace + "/t1-";
        int totalTopics = 100;

        BundlesData bundleData = BundlesData.builder().numBundles(10).build();
        admin.namespaces().createNamespace(namespace, bundleData);
        Consumer<byte[]>[] consumers = new Consumer[totalTopics];
        for (int i = 0; i < totalTopics; i++) {
            consumers[i] = pulsarClient.newConsumer().topic(topic + i).subscriptionName("my-subscriber-name")
                    .subscribe();
        }

        NamespaceService namespaceService = pulsar.getNamespaceService();
        NamespaceName nsname = NamespaceName.get(namespace);
        NamespaceBundles bundles = namespaceService.getNamespaceBundleFactory().getBundles(nsname);


        NamespaceBundle targetNamespaceBundle =  bundles.findBundle(TopicName.get(topic + "0"));
        String bundle = targetNamespaceBundle.getBundleRange();
        String path = BUNDLE_DATA_BASE_PATH + "/" + namespace + "/" + bundle;
        NamespaceBundleStats defaultStats = new NamespaceBundleStats();
        defaultStats.msgThroughputIn = 100000;
        defaultStats.msgThroughputOut = 100000;
        BundleData bd = new BundleData(10, 19, defaultStats);
        bd.setTopics(10);
        byte[] data = ObjectMapperFactory.getMapper().writer().writeValueAsBytes(bd);
        pulsar.getLocalMetadataStore().put(path, data, Optional.empty());

        LoadManager loadManager = pulsar.getLoadManager().get();
        Awaitility.await().untilAsserted(() -> {
            BundleData targetBundleData = ((ModularLoadManagerWrapper) loadManager).getLoadManager()
                    .getBundleDataOrDefault(namespace + "/" + bundle);
            assertEquals(targetBundleData.getTopics(), 10);
        });

        String hotBundle = namespaceService.getNamespaceBundleFactory().getBundleWithHighestThroughputAsync(nsname)
                .get().getBundleRange();

        assertEquals(bundle, hotBundle);

        for (int i = 0; i < totalTopics; i++) {
            consumers[i].close();
        }

        admin.namespaces().splitNamespaceBundle(namespace, Policies.BundleType.HOT.toString(), false, null);

        for (NamespaceBundle b : namespaceService.getNamespaceBundleFactory().getBundles(nsname).getBundles()) {
            assertNotEquals(b.getBundleRange(), hotBundle);
        }
    }

    @Test
    public void testHeartbeatNamespaceMatch() throws Exception {
        NamespaceName namespaceName = NamespaceService.getHeartbeatNamespace(pulsar.getBrokerId(), conf);
        NamespaceBundle namespaceBundle = pulsar.getNamespaceService().getNamespaceBundleFactory()
                .getFullBundle(namespaceName);
        assertTrue(NamespaceService.isSystemServiceNamespace(
                        NamespaceBundle.getBundleNamespace(namespaceBundle.toString())));
    }

    @Test
    public void testModularLoadManagerRemoveInactiveBundleFromLoadData() throws Exception {
        final String namespace = "pulsar/test/ns1";
        final String topic1 = "persistent://" + namespace + "/topic1";
        final String topic2 = "persistent://" + namespace + "/topic2";

        // configure broker with ModularLoadManager
        conf.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
        restartBroker();

        LoadManager loadManager = spy(pulsar.getLoadManager().get());
        Field loadManagerField = NamespaceService.class.getDeclaredField("loadManager");
        loadManagerField.setAccessible(true);
        doReturn(true).when(loadManager).isCentralized();
        SimpleResourceUnit resourceUnit = new SimpleResourceUnit(pulsar.getBrokerId(), null);
        Optional<ResourceUnit> res = Optional.of(resourceUnit);
        doReturn(res).when(loadManager).getLeastLoaded(any(ServiceUnitId.class));
        loadManagerField.set(pulsar.getNamespaceService(), new AtomicReference<>(loadManager));

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsar.getBrokerServiceUrl()).build();
        @Cleanup
        Consumer<byte[]> consumer1 = pulsarClient.newConsumer().topic(topic1)
                .subscriptionName("my-subscriber-name1").subscribe();
        @Cleanup
        Consumer<byte[]> consumer2 = pulsarClient.newConsumer().topic(topic2)
                .subscriptionName("my-subscriber-name2").subscribe();

        //create znode for bundle-data
        pulsar.getBrokerService().updateRates();
        loadManager.writeLoadReportOnZookeeper();
        loadManager.writeResourceQuotasToZooKeeper();

        //split bundle
        NamespaceName nsname = NamespaceName.get(namespace);
        NamespaceBundles bundles = pulsar.getNamespaceService().getNamespaceBundleFactory().getBundles(nsname);
        NamespaceBundle oldBundle = bundles.findBundle(TopicName.get(topic1));
        pulsar.getNamespaceService().splitAndOwnBundle(oldBundle, false,
                NamespaceBundleSplitAlgorithm.RANGE_EQUALLY_DIVIDE_ALGO, null).get();

        // update broker bundle report to zk
        pulsar.getBrokerService().updateRates();
        loadManager.writeLoadReportOnZookeeper();
        loadManager.writeResourceQuotasToZooKeeper();

        Field loadDataFiled = ModularLoadManagerImpl.class.getDeclaredField("loadData");
        loadDataFiled.setAccessible(true);
        LoadData loadData = (LoadData) loadDataFiled
                .get((ModularLoadManagerImpl) ((ModularLoadManagerWrapper) loadManager).getLoadManager());
        MetadataCache<BundleData> bundlesCache = pulsar.getLocalMetadataStore().getMetadataCache(BundleData.class);

        Awaitility.await().untilAsserted(() -> {
            assertNull(loadData.getBundleData().get(oldBundle.toString()));
            assertFalse(bundlesCache.exists(BUNDLE_DATA_BASE_PATH + "/" + oldBundle.toString()).get());
        });
    }

    @Test
    public void testModularLoadManagerRemoveBundleAndLoad() throws Exception {
        final String namespace = "prop/ns-abc";
        final String bundleName = namespace + "/0x00000000_0xffffffff";
        final String topic1 = "persistent://" + namespace + "/topic1";
        final String topic2 = "persistent://" + namespace + "/topic2";

        // configure broker with ModularLoadManager
        conf.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
        conf.setForceDeleteNamespaceAllowed(true);
        // Make sure LoadReportUpdaterTask has a 100% chance to write ZK.
        conf.setLoadBalancerReportUpdateMaxIntervalMinutes(-1);
        restartBroker();

        LoadManager loadManager = pulsar.getLoadManager().get();
        NamespaceName nsname = NamespaceName.get(namespace);

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsar.getBrokerServiceUrl()).build();
        @Cleanup
        Consumer<byte[]> consumer1 = pulsarClient.newConsumer().topic(topic1)
                .subscriptionName("my-subscriber-name1").subscribe();
        @Cleanup
        Consumer<byte[]> consumer2 = pulsarClient.newConsumer().topic(topic2)
                .subscriptionName("my-subscriber-name2").subscribe();


        NamespaceBundle bundle =
                pulsar.getNamespaceService().getNamespaceBundleFactory().getBundle(TopicName.get(topic1));

        loadManager.getLeastLoaded(bundle);

        //create znode for bundle-data
        pulsar.getBrokerService().updateRates();

        waitResourceDataUpdateToZK(loadManager);
        String path = BUNDLE_DATA_BASE_PATH + "/" + bundleName;

        Optional<GetResult> getResult = pulsar.getLocalMetadataStore().get(path).get();
        assertTrue(getResult.isPresent());

        //delete namespace which will remove bundle and load
        pulsar.getAdminClient().namespaces().deleteNamespace(nsname.toString(), true);

        TimeUnit.SECONDS.sleep(5);

        // update broker bundle report to zk
        waitResourceDataUpdateToZK(loadManager);

        getResult = pulsar.getLocalMetadataStore().get(path).get();
        assertFalse(getResult.isPresent());
    }

    @DataProvider(name = "topicDomain")
    public Object[] topicDomain() {
        return new Object[]{
                TopicDomain.persistent.value(),
                TopicDomain.non_persistent.value()
        };
    }

    @Test
    public void testAllowedClustersAtNamespaceLevelShouldBeIncludedInAllowedClustersAtTenantLevel() throws Exception {
        // 1. Setup
        pulsar.getConfiguration().setForceDeleteNamespaceAllowed(true);
        pulsar.getConfiguration().setForceDeleteTenantAllowed(true);
        Set<String> tenantAllowedClusters = Set.of("test", "r1", "r2");
        Set<String> allowedClusters1 = Set.of("test", "r1", "r2", "r3");
        Set<String> allowedClusters2 = Set.of("test", "r1", "r2");
        Set<String> clusters = Set.of("r1", "r2", "r3", "r4");
        final String tenant = "my-tenant";
        final String namespace = tenant + "/testAllowedCluster";
        admin.tenants().createTenant(tenant,
                new TenantInfoImpl(Sets.newHashSet("appid1"), Sets.newHashSet("test")));
        admin.namespaces().createNamespace(namespace);
        pulsar.getPulsarResources().getTenantResources().updateTenantAsync(tenant, tenantInfo ->
                TenantInfo.builder().allowedClusters(tenantAllowedClusters).build());
        for (String cluster : clusters) {
            pulsar.getPulsarResources().getClusterResources().createCluster(cluster, ClusterData.builder().build());
        }
        // 2. Verify
        admin.namespaces().setNamespaceAllowedClusters(namespace, allowedClusters2);

        try {
            admin.namespaces().setNamespaceAllowedClusters(namespace, allowedClusters1);
            fail();
        } catch (PulsarAdminException e) {
            assertEquals(e.getStatusCode(), 403);
            assertEquals(e.getMessage(),
                    "Cluster [r3] is not in the list of allowed clusters list for tenant [my-tenant]");
        }
        // 3. Clean up
        admin.namespaces().deleteNamespace(namespace, true);
        admin.tenants().deleteTenant(tenant, true);
        for (String cluster : clusters) {
            pulsar.getPulsarResources().getClusterResources().deleteCluster(cluster);
        }
        pulsar.getConfiguration().setForceDeleteNamespaceAllowed(false);
        pulsar.getConfiguration().setForceDeleteTenantAllowed(false);
    }

    /**
     * Test case:
     *      1. Replication clusters should be included in the allowed clusters. For compatibility, the replication
     *      clusters could be set before the allowed clusters are set.
     *      2. Peer cluster can not be a part of the allowed clusters.
     */
    @Test
    public void testNewAllowedClusterAdminAPIAndItsImpactOnReplicationClusterAPI() throws Exception {
        // 1. Setup
        pulsar.getConfiguration().setForceDeleteNamespaceAllowed(true);
        pulsar.getConfiguration().setForceDeleteTenantAllowed(true);
        // Setup: Prepare cluster resource, tenant and namespace
        Set<String> replicationClusters = Set.of("test", "r1", "r2");
        Set<String> tenantAllowedClusters = Set.of("test", "r1", "r2", "r3");
        Set<String> allowedClusters = Set.of("test", "r1", "r2", "r3");
        Set<String> clusters = Set.of("r1", "r2", "r3", "r4");
        final String tenant = "my-tenant";
        final String namespace = tenant + "/testAllowedCluster";
        admin.tenants().createTenant(tenant,
                new TenantInfoImpl(Sets.newHashSet("appid1"), Sets.newHashSet("test")));
        admin.namespaces().createNamespace(namespace);
        pulsar.getPulsarResources().getTenantResources().updateTenantAsync(tenant, tenantInfo ->
                TenantInfo.builder().allowedClusters(tenantAllowedClusters).build());

        Namespaces namespaces = admin.namespaces();
        for (String cluster : clusters) {
            pulsar.getPulsarResources().getClusterResources()
                    .createCluster(cluster, ClusterData.builder().build());
        }
        // 2. Verify
        // 2.1 Replication clusters should be included in the allowed clusters.

        // SUCCESS
        // 2.1.1. Set replication clusters without allowed clusters at namespace level.
        namespaces.setNamespaceReplicationClusters(namespace, replicationClusters);
        // 2..1.2 Set allowed clusters.
        namespaces.setNamespaceAllowedClusters(namespace, allowedClusters);
        // 2.1.3. Get allowed clusters and replication clusters.
        List<String> allowedClustersResponse = namespaces.getNamespaceAllowedClusters(namespace);

        List<String> replicationClustersResponse = namespaces.getNamespaceReplicationClusters(namespace);

        assertEquals(replicationClustersResponse.size(), replicationClusters.size());
        assertEquals(allowedClustersResponse.size(), allowedClusters.size());

        // FAIL
        // 2.1.4. Fail: Set allowed clusters whose scope is smaller than replication clusters.
        Set<String> allowedClustersSmallScope = Set.of("r1", "r3");
        try {
            namespaces.setNamespaceAllowedClusters(namespace, allowedClustersSmallScope);
            fail();
        } catch (PulsarAdminException ignore) {}
        // 2.1.5. Fail: Set replication clusters whose scope is excel the allowed clusters.
        Set<String> replicationClustersExcel = Set.of("r1", "r4");
        try {
            namespaces.setNamespaceReplicationClusters(namespace, replicationClustersExcel);
            fail();
            //Todo: The status code in the old implementation is confused.
        } catch (PulsarAdminException.NotAuthorizedException ignore) {}

        // 2.2 Peer cluster can not be a part of the allowed clusters.
        LinkedHashSet<String> peerCluster = new LinkedHashSet<>();
        peerCluster.add("r2");
        pulsar.getPulsarResources().getClusterResources().deleteCluster("r1");
        pulsar.getPulsarResources().getClusterResources().createCluster("r1",
                ClusterData.builder().peerClusterNames(peerCluster).build());
        try {
            namespaces.setNamespaceAllowedClusters(namespace, Set.of("test", "r1", "r2", "r3"));
            fail();
        } catch (PulsarAdminException.ConflictException ignore) {}

        // CleanUp: Namespace with replication clusters can not be deleted by force.
        namespaces.setNamespaceReplicationClusters(namespace, Set.of(conf.getClusterName()));
        admin.namespaces().deleteNamespace(namespace, true);
        admin.tenants().deleteTenant(tenant, true);
        for (String cluster : clusters) {
            pulsar.getPulsarResources().getClusterResources().deleteCluster(cluster);
        }
        pulsar.getConfiguration().setForceDeleteNamespaceAllowed(false);
        pulsar.getConfiguration().setForceDeleteTenantAllowed(false);
    }


    @Test(dataProvider = "topicDomain")
    public void checkTopicExistsForNonPartitionedTopic(String topicDomain) throws Exception {
        TopicName topicName = TopicName.get(topicDomain, "prop", "ns-abc", "topic-" + UUID.randomUUID());
        admin.topics().createNonPartitionedTopic(topicName.toString());
        CompletableFuture<TopicExistsInfo> result = pulsar.getNamespaceService().checkTopicExistsAsync(topicName);
        assertThat(result)
                .succeedsWithin(3, TimeUnit.SECONDS)
                .satisfies(n -> {
                    assertTrue(n.isExists());
                    assertEquals(n.getPartitions(), 0);
                    assertEquals(n.getTopicType(), TopicType.NON_PARTITIONED);
                    n.recycle();
                });
    }

    @Test(dataProvider = "topicDomain")
    public void checkTopicExistsForPartitionedTopic(String topicDomain) throws Exception {
        TopicName topicName = TopicName.get(topicDomain, "prop", "ns-abc", "topic-" + UUID.randomUUID());
        admin.topics().createPartitionedTopic(topicName.toString(), 3);

        // Check the topic exists by the partitions.
        CompletableFuture<TopicExistsInfo> result = pulsar.getNamespaceService().checkTopicExistsAsync(topicName);
        assertThat(result)
                .succeedsWithin(3, TimeUnit.SECONDS)
                .satisfies(n -> {
                    assertTrue(n.isExists());
                    assertEquals(n.getPartitions(), 3);
                    assertEquals(n.getTopicType(), TopicType.PARTITIONED);
                    n.recycle();
                });

        // Check the specific partition.
        result = pulsar.getNamespaceService().checkTopicExistsAsync(topicName.getPartition(2));
        assertThat(result)
                .succeedsWithin(3, TimeUnit.SECONDS)
                .satisfies(n -> {
                    assertTrue(n.isExists());
                    assertEquals(n.getPartitions(), 0);
                    assertEquals(n.getTopicType(), TopicType.NON_PARTITIONED);
                    n.recycle();
                });

        // Partition index is out of range.
        result = pulsar.getNamespaceService().checkTopicExistsAsync(topicName.getPartition(10));
        assertThat(result)
                .succeedsWithin(3, TimeUnit.SECONDS)
                .satisfies(n -> {
                    assertFalse(n.isExists());
                    assertEquals(n.getPartitions(), 0);
                    assertEquals(n.getTopicType(), TopicType.NON_PARTITIONED);
                    n.recycle();
                });
    }

    @Test(dataProvider = "topicDomain")
    public void checkTopicExistsForNonExistentNonPartitionedTopic(String topicDomain) {
        TopicName topicName = TopicName.get(topicDomain, "prop", "ns-abc", "topic-" + UUID.randomUUID());
        CompletableFuture<TopicExistsInfo> result = pulsar.getNamespaceService().checkTopicExistsAsync(topicName);
        assertThat(result)
                .succeedsWithin(3, TimeUnit.SECONDS)
                .satisfies(n -> {
                    // when using the pulsar client to check non_persistent topic, always return true, so ignore to
                    // check that.
                    if (topicDomain.equals(TopicDomain.persistent)) {
                        assertFalse(n.isExists());
                    }
                    n.recycle();
                });
    }

    @Test(dataProvider = "topicDomain")
    public void checkTopicExistsForNonExistentPartitionTopic(String topicDomain) {
        TopicName topicName =
                TopicName.get(topicDomain, "prop", "ns-abc", "topic-" + UUID.randomUUID() + "-partition-10");
        CompletableFuture<TopicExistsInfo> result = pulsar.getNamespaceService().checkTopicExistsAsync(topicName);
        assertThat(result)
                .succeedsWithin(3, TimeUnit.SECONDS)
                .satisfies(n -> {
                    // when using the pulsar client to check non_persistent topic, always return true, so ignore to
                    // check that.
                    if (topicDomain.equals(TopicDomain.persistent)) {
                        assertFalse(n.isExists());
                    }
                    n.recycle();
                });
    }

    /**
     * 1. Manually trigger "LoadReportUpdaterTask"
     * 2. Registry another new zk-node-listener "waitForBrokerChangeNotice".
     * 3. Wait "waitForBrokerChangeNotice" is done, this task will be executed after
     *    {@link ModularLoadManagerImpl#handleDataNotification(Notification)}, because it is registry later than
     *    {@link ModularLoadManagerImpl#handleDataNotification(Notification)}. So if "waitForBrokerChangeNotice" is done
     *    we can guarantee {@link ModularLoadManagerImpl#handleDataNotification(Notification)} is done. At this time
     *    we still could not guarantee {@link ModularLoadManagerImpl#handleDataNotification(Notification)} has
     *    finished all things, because there has a async task be submitted to "ModularLoadManagerImpl.scheduler" by
     *    {@link ModularLoadManagerImpl#handleDataNotification(Notification)}.
     * 4. Submit a new task to "scheduler"(it is a singleton thread executor).
     * 5. Wait the new task done, if the new task done, we can guarantee
     *    {@link ModularLoadManagerImpl#handleDataNotification(Notification)} has finished all things.
     * 6. Manually trigger "LoadResourceQuotaUpdaterTask".
     */
    private void waitResourceDataUpdateToZK(LoadManager loadManager) throws Exception {
        CompletableFuture<Void> waitForBrokerChangeNotice = registryBrokerDataChangeNotice();
        // Manually trigger "LoadReportUpdaterTask"
        loadManager.writeLoadReportOnZookeeper();
        waitForBrokerChangeNotice.join();
        // Wait until "ModularLoadManager" completes processing the ZK notification.
        ModularLoadManagerWrapper modularLoadManagerWrapper = (ModularLoadManagerWrapper) loadManager;
        ModularLoadManagerImpl modularLoadManager = (ModularLoadManagerImpl) modularLoadManagerWrapper.getLoadManager();
        ExecutorService executors = (ExecutorService) FieldUtils.readField(
                modularLoadManager, "executors", true);
        CompletableFuture<Void> waitForNoticeHandleFinishByLoadManager = new CompletableFuture<>();
        executors.execute(() -> waitForNoticeHandleFinishByLoadManager.complete(null));
        waitForNoticeHandleFinishByLoadManager.join();
        // Manually trigger "LoadResourceQuotaUpdaterTask"
        loadManager.writeResourceQuotasToZooKeeper();
    }

    public CompletableFuture<Void> registryBrokerDataChangeNotice() {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        String brokerDataPath = LoadManager.LOADBALANCE_BROKERS_ROOT + "/" + pulsar.getBrokerId();
        pulsar.getLocalMetadataStore().registerListener(notice -> {
            if (brokerDataPath.equals(notice.getPath())){
                if (!completableFuture.isDone()) {
                    completableFuture.complete(null);
                }
            }
        });
        return completableFuture;
    }

    @SuppressWarnings("unchecked")
    private Pair<NamespaceBundles, List<NamespaceBundle>> splitBundles(NamespaceBundleFactory utilityFactory,
            NamespaceName nsname, NamespaceBundles bundles, NamespaceBundle targetBundle) throws Exception {
        Field bCacheField = NamespaceBundleFactory.class.getDeclaredField("bundlesCache");
        bCacheField.setAccessible(true);
        ((AsyncLoadingCache<NamespaceName, NamespaceBundles>) bCacheField.get(utilityFactory)).put(nsname,
                CompletableFuture.completedFuture(bundles));
        return utilityFactory.splitBundles(targetBundle, 2, null).join();
    }

    private static final Logger log = LoggerFactory.getLogger(NamespaceServiceTest.class);
}
