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
package org.apache.pulsar.broker.service;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.bookkeeper.mledger.Position;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.TransactionMetadataStoreService;
import org.apache.pulsar.client.api.transaction.TxnID;
import org.apache.pulsar.common.api.proto.TxnAction;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.SystemTopicNames;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.transaction.coordinator.TransactionCoordinatorID;
import org.apache.pulsar.transaction.coordinator.TransactionMetadataStoreState;
import org.apache.pulsar.transaction.coordinator.TransactionSubscription;
import org.apache.pulsar.transaction.coordinator.TxnMeta;
import org.apache.pulsar.transaction.coordinator.exceptions.CoordinatorException;
import org.apache.pulsar.transaction.coordinator.impl.MLTransactionMetadataStore;
import org.apache.pulsar.transaction.coordinator.proto.TxnStatus;
import org.awaitility.Awaitility;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class TransactionMetadataStoreServiceTest extends BrokerTestBase {

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        ServiceConfiguration configuration = getDefaultConf();
        configuration.setTransactionCoordinatorEnabled(true);
        super.baseSetup(configuration);
        admin.tenants().createTenant("pulsar", new TenantInfoImpl(Sets.newHashSet("appid1"), Sets.newHashSet("test")));
        admin.namespaces().createNamespace(NamespaceName.SYSTEM_NAMESPACE.toString());
        createTransactionCoordinatorAssign(16);
        admin.lookups().lookupPartitionedTopic(SystemTopicNames.TRANSACTION_COORDINATOR_ASSIGN.toString());
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testAddAndRemoveTransactionMetadataStore() throws Exception {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        Assert.assertNotNull(transactionMetadataStoreService);

        admin.lookups().lookupTopic(SystemTopicNames.TRANSACTION_COORDINATOR_ASSIGN.getPartition(0).toString());
        transactionMetadataStoreService.handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await().until(() ->
                transactionMetadataStoreService.getStores().size() == 1);

        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Awaitility.await().until(() ->
                transactionMetadataStoreService.getStores().size() == 0);
    }

    @Test
    public void testNewTransaction() throws Exception {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        transactionMetadataStoreService.handleTcClientConnect(TransactionCoordinatorID.get(0));
        transactionMetadataStoreService.handleTcClientConnect(TransactionCoordinatorID.get(1));
        transactionMetadataStoreService.handleTcClientConnect(TransactionCoordinatorID.get(2));
        Awaitility.await().until(() ->
                transactionMetadataStoreService.getStores().size() == 3);
        checkTransactionMetadataStoreReady((MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                .getStores().get(TransactionCoordinatorID.get(0)));
        checkTransactionMetadataStoreReady((MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                .getStores().get(TransactionCoordinatorID.get(1)));
        checkTransactionMetadataStoreReady((MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                .getStores().get(TransactionCoordinatorID.get(2)));
        TxnID txnID0 = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(0), 5, null).get();
        TxnID txnID1 = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(1), 5, null).get();
        TxnID txnID2 = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(2), 5, null).get();
        Assert.assertEquals(txnID0.getMostSigBits(), 0);
        Assert.assertEquals(txnID1.getMostSigBits(), 1);
        Assert.assertEquals(txnID2.getMostSigBits(), 2);
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(1));
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(2));
        Assert.assertEquals(transactionMetadataStoreService.getStores().size(), 0);
    }

    @Test
    public void testAddProducedPartitionToTxn() throws Exception {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        transactionMetadataStoreService.handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await().until(() ->
                transactionMetadataStoreService.getStores().size() == 1);

        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));

        checkTransactionMetadataStoreReady(transactionMetadataStore);
        TxnID txnID = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(0), 5000, null).get();
        List<String> partitions = new ArrayList<>();
        partitions.add("ptn-0");
        partitions.add("ptn-1");
        partitions.add("ptn-2");
        transactionMetadataStoreService.addProducedPartitionToTxn(txnID, partitions);
        TxnMeta txn = transactionMetadataStoreService.getTxnMeta(txnID).get();
        assertEquals(txn.status(), TxnStatus.OPEN);
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Assert.assertEquals(transactionMetadataStoreService.getStores().size(), 0);
    }

    @Test
    public void testAddAckedPartitionToTxn() throws Exception {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        transactionMetadataStoreService.handleTcClientConnect(TransactionCoordinatorID.get(0)).get();
        Awaitility.await().until(() ->
                transactionMetadataStoreService.getStores().size() == 1);

        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));
        checkTransactionMetadataStoreReady(transactionMetadataStore);
        TxnID txnID = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(0), 5000, null).get();
        List<TransactionSubscription> partitions = new ArrayList<>();
        partitions.add(TransactionSubscription.builder().topic("ptn-1").subscription("sub-1").build());
        partitions.add(TransactionSubscription.builder().topic("ptn-2").subscription("sub-1").build());
        partitions.add(TransactionSubscription.builder().topic("ptn-3").subscription("sub-1").build());
        transactionMetadataStoreService.addAckedPartitionToTxn(txnID, partitions);
        TxnMeta txn = transactionMetadataStoreService.getTxnMeta(txnID).get();
        assertEquals(txn.status(), TxnStatus.OPEN);
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Assert.assertEquals(transactionMetadataStoreService.getStores().size(), 0);
    }

    @Test
    public void testTimeoutTracker() throws Exception {
        pulsar.getTransactionMetadataStoreService().handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await()
                .until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));
        checkTransactionMetadataStoreReady(transactionMetadataStore);
        Field field = MLTransactionMetadataStore.class.getDeclaredField("txnMetaMap");
        field.setAccessible(true);
        ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>> txnMap =
                (ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>>) field.get(transactionMetadataStore);
        int i = -1;
        while (++i < 1000) {
            try {
                newTransactionWithTimeoutOf(2000);
            } catch (Exception e) {
                //no operation
            }
        }

        txnMap.forEach((txnID, txnMetaListPair) ->
                Assert.assertEquals(txnMetaListPair.getLeft().status(), TxnStatus.OPEN));
        Awaitility.await().atLeast(1000, TimeUnit.MICROSECONDS)
                .until(() -> txnMap.size() == 0);
    }

    private TxnID newTransactionWithTimeoutOf(long timeout)
            throws InterruptedException, ExecutionException {
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));
        return transactionMetadataStore.newTransaction(timeout, null).get();
    }

    @Test
    public void testTimeoutTrackerExpired() throws Exception {
        pulsar.getTransactionMetadataStoreService().handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await().until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));
        checkTransactionMetadataStoreReady(transactionMetadataStore);
        Field field = MLTransactionMetadataStore.class.getDeclaredField("txnMetaMap");
        field.setAccessible(true);
        ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>> txnMap =
                (ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>>) field.get(transactionMetadataStore);

        newTransactionWithTimeoutOf(2000);

        assertEquals(txnMap.size(), 1);

        txnMap.forEach((txnID, txnMetaListPair) ->
                Assert.assertEquals(txnMetaListPair.getLeft().status(), TxnStatus.OPEN));
        Awaitility.await().atLeast(1000, TimeUnit.MICROSECONDS).until(() -> txnMap.size() == 0);

        newTransactionWithTimeoutOf(2000);
        assertEquals(txnMap.size(), 1);

        txnMap.forEach((txnID, txnMetaListPair) ->
                Assert.assertEquals(txnMetaListPair.getLeft().status(), TxnStatus.OPEN));
        Awaitility.await().atLeast(1000, TimeUnit.MICROSECONDS).until(() -> txnMap.size() == 0);
    }

    @Test
    public void testTimeoutTrackerMultiThreading() throws Exception {
        pulsar.getTransactionMetadataStoreService().handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await()
                .until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));

        checkTransactionMetadataStoreReady(transactionMetadataStore);
        Field field = MLTransactionMetadataStore.class.getDeclaredField("txnMetaMap");
        field.setAccessible(true);
        ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>> txnMap =
                (ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>>) field.get(transactionMetadataStore);
        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    newTransactionWithTimeoutOf(1000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();

        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    newTransactionWithTimeoutOf(2000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();

        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    newTransactionWithTimeoutOf(3000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();

        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    newTransactionWithTimeoutOf(4000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();

        checkoutTimeout(txnMap, 300);
        checkoutTimeout(txnMap, 200);
        checkoutTimeout(txnMap, 100);
        checkoutTimeout(txnMap, 0);
    }

    private void checkoutTimeout(ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>> txnMap, int time) {
        Awaitility.await().atLeast(1000, TimeUnit.MICROSECONDS)
                .until(() -> txnMap.size() == time);
    }

    @Test
    public void transactionTimeoutRecoverTest() throws Exception {
        int timeout = 2000;
        pulsar.getTransactionMetadataStoreService().handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await()
                .until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));
        checkTransactionMetadataStoreReady(transactionMetadataStore);

        newTransactionWithTimeoutOf(2000);

        pulsar.getTransactionMetadataStoreService()
                .removeTransactionMetadataStore(TransactionCoordinatorID.get(0));

        pulsar.getTransactionMetadataStoreService().handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await()
                .until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));

        checkTransactionMetadataStoreReady(transactionMetadataStore);

        Field field = MLTransactionMetadataStore.class.getDeclaredField("txnMetaMap");
        field.setAccessible(true);
        ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>> txnMap =
                (ConcurrentSkipListMap<Long, Pair<TxnMeta, List<Position>>>) field.get(transactionMetadataStore);
        Awaitility.await().until(() -> txnMap.size() == 0);

    }

    @DataProvider(name = "txnStatus")
    public Object[][] txnStatus() {
        return new Object[][] { { TxnStatus.OPEN }, { TxnStatus.ABORTING }, { TxnStatus.COMMITTING } };
    }

    @Test(dataProvider = "txnStatus")
    public void testEndTransactionOpRetry(TxnStatus txnStatus) throws Exception {
        int timeOut = 3000;
        pulsar.getTransactionMetadataStoreService().handleTcClientConnect(TransactionCoordinatorID.get(0));
        Awaitility.await()
                .until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));

        checkTransactionMetadataStoreReady(transactionMetadataStore);

        TxnID txnID = newTransactionWithTimeoutOf(timeOut - 2000);
        TxnMeta txnMeta = transactionMetadataStore.getTxnMeta(txnID).get();
        txnMeta.updateTxnStatus(txnStatus, TxnStatus.OPEN);

        Field field = TransactionMetadataStoreState.class.getDeclaredField("state");
        field.setAccessible(true);
        field.set(transactionMetadataStore, TransactionMetadataStoreState.State.None);
        CompletableFuture<Void> completableFuture = null;
        try {
            completableFuture = pulsar.getTransactionMetadataStoreService().endTransaction(txnID,
                    TxnAction.COMMIT.getValue(),
                    false);
            completableFuture.get(5, TimeUnit.SECONDS);
            fail();
        } catch (Exception e) {
            if (txnStatus == TxnStatus.OPEN || txnStatus == TxnStatus.COMMITTING) {
                assertTrue(e instanceof TimeoutException);
            } else if (txnStatus == TxnStatus.ABORTING) {
                assertTrue(e.getCause() instanceof CoordinatorException.InvalidTxnStatusException);
            } else {
                fail();
            }
        }

        assertEquals(txnMeta.status(), txnStatus);

        field = TransactionMetadataStoreState.class.getDeclaredField("state");
        field.setAccessible(true);
        field.set(transactionMetadataStore, TransactionMetadataStoreState.State.Ready);
        if (txnStatus == TxnStatus.ABORTING) {
            pulsar.getTransactionMetadataStoreService()
                    .endTransaction(txnID, TxnAction.ABORT.getValue(), false).get();
        }
        Awaitility.await().atMost(timeOut, TimeUnit.MILLISECONDS).until(() -> {
            try {
                transactionMetadataStore.getTxnMeta(txnID).get();
                return false;
            } catch (ExecutionException e) {
                return e.getCause() instanceof CoordinatorException.TransactionNotFoundException;
            }
        });
    }

    private void checkTransactionMetadataStoreReady(MLTransactionMetadataStore transactionMetadataStore)
            throws NoSuchMethodException {
        Method method = TransactionMetadataStoreState.class.getDeclaredMethod("checkIfReady");
        method.setAccessible(true);
        Awaitility.await()
                .until(() -> (Boolean) method.invoke(transactionMetadataStore));
    }
}
