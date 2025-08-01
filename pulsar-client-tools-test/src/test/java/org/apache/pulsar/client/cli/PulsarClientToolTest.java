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
package org.apache.pulsar.client.cli;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.Cleanup;
import lombok.NoArgsConstructor;
import org.apache.pulsar.broker.service.BrokerTestBase;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.ProxyProtocol;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.BatchMessageIdImpl;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.awaitility.Awaitility;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class PulsarClientToolTest extends BrokerTestBase {

    @BeforeMethod
    @Override
    public void setup() throws Exception {
        super.internalSetup();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    public void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testInitialization() throws InterruptedException, ExecutionException, PulsarAdminException {

        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");
        properties.setProperty("memoryLimit", "10M");

        String tenantName = UUID.randomUUID().toString();

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant(tenantName, tenantInfo);

        String topicName = String.format("persistent://%s/ns/topic-scale-ns-0/topic", tenantName);

        int numberOfMessages = 10;

        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();

        CompletableFuture<Void> future = new CompletableFuture<Void>();
        executor.execute(() -> {
            PulsarClientTool pulsarClientToolConsumer;
            try {
                pulsarClientToolConsumer = new PulsarClientTool(properties);
                String[] args = { "consume", "-t", "Exclusive", "-s", "sub-name", "-n",
                        Integer.toString(numberOfMessages), "--hex", "-r", "30", topicName };
                Assert.assertEquals(pulsarClientToolConsumer.run(args), 0);
                Assert.assertEquals(pulsarClientToolConsumer.rootParams.memoryLimit, 10 * 1024 * 1024);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        Awaitility.await()
                .ignoreExceptions()
                .until(()->admin.topics().getSubscriptions(topicName).size() == 1);

        PulsarClientTool pulsarClientToolProducer = new PulsarClientTool(properties);

        String[] args = { "produce", "--messages", "Have a nice day", "-n", Integer.toString(numberOfMessages), "-r",
                "20", "-p", "key1=value1", "-p", "key2=value2", "-k", "partition_key", topicName };
        Assert.assertEquals(pulsarClientToolProducer.run(args), 0);
        Assert.assertEquals(pulsarClientToolProducer.rootParams.memoryLimit, 10 * 1024 * 1024);

        future.get();
    }

    @Test(timeOut = 20000)
    public void testNonDurableSubscribe() throws Exception {

        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");

        final String topicName = getTopicWithRandomSuffix("non-durable");
        admin.topics().createNonPartitionedTopic(topicName);

        int numberOfMessages = 10;
        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                PulsarClientTool pulsarClientToolConsumer = new PulsarClientTool(properties);
                String[] args = {"consume", "-t", "Exclusive", "-s", "sub-name", "-n",
                        Integer.toString(numberOfMessages), "--hex", "-m", "NonDurable", "-r", "30", topicName};
                Assert.assertEquals(pulsarClientToolConsumer.run(args), 0);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        // Make sure subscription has been created
        retryStrategically((test) -> {
            try {
                return admin.topics().getSubscriptions(topicName).size() == 1;
            } catch (Exception e) {
                return false;
            }
        }, 10, 500);

        assertEquals(admin.topics().getSubscriptions(topicName).size(), 1);
        PulsarClientTool pulsarClientToolProducer = new PulsarClientTool(properties);

        String[] args = {"produce", "--messages", "Have a nice day", "-n", Integer.toString(numberOfMessages), "-r",
                "20", "-p", "key1=value1", "-p", "key2=value2", "-k", "partition_key", topicName};
        Assert.assertEquals(pulsarClientToolProducer.run(args), 0);
        Assert.assertFalse(future.isCompletedExceptionally());
        future.get();

        Awaitility.await()
                .ignoreExceptions()
                .atMost(Duration.ofMillis(20000))
                .until(()->admin.topics().getSubscriptions(topicName).size() == 0);
    }

    @Test(timeOut = 60000)
    public void testDurableSubscribe() throws Exception {

        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");

        final String topicName = getTopicWithRandomSuffix("durable");

        int numberOfMessages = 10;
        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                PulsarClientTool pulsarClientToolConsumer = new PulsarClientTool(properties);
                String[] args = {"consume", "-t", "Exclusive", "-s", "sub-name", "-n",
                        Integer.toString(numberOfMessages), "--hex", "-m", "Durable", "-r", "30", topicName};
                Assert.assertEquals(pulsarClientToolConsumer.run(args), 0);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        // Make sure subscription has been created
        Awaitility.await()
                .atMost(Duration.ofMillis(60000))
                .ignoreExceptions()
                .until(() -> admin.topics().getSubscriptions(topicName).size() == 1);

        PulsarClientTool pulsarClientToolProducer = new PulsarClientTool(properties);

        String[] args = {"produce", "--messages", "Have a nice day", "-n", Integer.toString(numberOfMessages), "-r",
                "20", "-p", "key1=value1", "-p", "key2=value2", "-k", "partition_key", topicName};
        Assert.assertEquals(pulsarClientToolProducer.run(args), 0);

        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Assert.fail("consumer was unable to receive messages", e);
        }
    }

    @Test(timeOut = 20000)
    public void testRead() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");

        final String topicName = getTopicWithRandomSuffix("reader");
        admin.topics().createNonPartitionedTopic(topicName);

        int numberOfMessages = 10;
        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                PulsarClientTool pulsarClientToolReader = new PulsarClientTool(properties);
                String[] args = {"read", "-m", "latest", "-n", Integer.toString(numberOfMessages), "--hex", "-r", "30",
                        topicName};
                assertEquals(pulsarClientToolReader.run(args), 0);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        // Make sure subscription has been created
        retryStrategically((test) -> {
            try {
                return admin.topics().getSubscriptions(topicName).size() == 1;
            } catch (Exception e) {
                return false;
            }
        }, 10, 500);

        assertEquals(admin.topics().getSubscriptions(topicName).size(), 1);
        assertTrue(admin.topics().getSubscriptions(topicName).get(0).startsWith("reader-"));
        PulsarClientTool pulsarClientToolProducer = new PulsarClientTool(properties);

        String[] args = {"produce", "--messages", "Have a nice day", "-n", Integer.toString(numberOfMessages), "-r",
                "20", "-p", "key1=value1", "-p", "key2=value2", "-k", "partition_key", topicName};
        assertEquals(pulsarClientToolProducer.run(args), 0);
        assertFalse(future.isCompletedExceptionally());
        future.get();

        Awaitility.await()
                .ignoreExceptions()
                .atMost(Duration.ofMillis(20000))
                .until(()->admin.topics().getSubscriptions(topicName).size() == 0);
    }

    @Test(timeOut = 20000)
    public void testEncryption() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");

        final String topicName = getTopicWithRandomSuffix("encryption");
        admin.topics().createNonPartitionedTopic(topicName);
        final String keyUriBase = "file:../pulsar-broker/src/test/resources/certificate/";
        final int numberOfMessages = 10;

        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                PulsarClientTool pulsarClientToolConsumer = new PulsarClientTool(properties);
                String[] args = {"consume", "-s", "sub-name", "-n", Integer.toString(numberOfMessages), "-ekv",
                        keyUriBase + "private-key.client-rsa.pem", topicName};
                Assert.assertEquals(pulsarClientToolConsumer.run(args), 0);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        // Make sure subscription has been created
        Awaitility.await()
                .atMost(Duration.ofMillis(20000))
                .ignoreExceptions()
                .until(() -> admin.topics().getSubscriptions(topicName).size() == 1);

        PulsarClientTool pulsarClientToolProducer = new PulsarClientTool(properties);
        String[] args = {"produce", "-m", "Have a nice day", "-n", Integer.toString(numberOfMessages), "-ekn",
                "my-app-key", "-ekv", keyUriBase + "public-key.client-rsa.pem", topicName};
        Assert.assertEquals(pulsarClientToolProducer.run(args), 0);

        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Assert.fail("consumer was unable to decrypt messages", e);
        }
    }

    @Test(timeOut = 20000)
    public void testDisableBatching() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");

        final String topicName = getTopicWithRandomSuffix("disable-batching");
        // `numberOfMessages` should be an even number, because we set `batchNum` as 2, make sure batch and non batch
        // messages in the same batch
        final int numberOfMessages = 6;
        final int batchNum = 2;

        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName("sub").subscribe();

        PulsarClientTool pulsarClientTool1 = new PulsarClientToolForceBatchNum(properties, topicName, batchNum);
        String[] args1 = {"produce", "-m", "batched", "-n", Integer.toString(numberOfMessages), topicName};
        Assert.assertEquals(pulsarClientTool1.run(args1), 0);

        PulsarClientTool pulsarClientTool2 = new PulsarClientToolForceBatchNum(properties, topicName, batchNum);
        String[] args2 = {"produce", "-m", "non-batched", "-n", Integer.toString(numberOfMessages), "-db", topicName};
        Assert.assertEquals(pulsarClientTool2.run(args2), 0);

        for (int i = 0; i < numberOfMessages * 2; i++) {
            Message<byte[]> msg = consumer.receive(10, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            if (i < numberOfMessages) {
                Assert.assertEquals(new String(msg.getData()), "batched");
                Assert.assertTrue(msg.getMessageId() instanceof BatchMessageIdImpl);
            } else {
                Assert.assertEquals(new String(msg.getData()), "non-batched");
                Assert.assertFalse(msg.getMessageId() instanceof BatchMessageIdImpl);
            }
        }
    }

    @Test(timeOut = 20000)
    public void testArgs() throws Exception {
        PulsarClientTool pulsarClientTool = new PulsarClientTool(new Properties());
        final String url = "pulsar+ssl://localhost:6651";
        final String authPlugin = "org.apache.pulsar.client.impl.auth.AuthenticationTls";
        final String authParams = String.format("tlsCertFile:%s,tlsKeyFile:%s", getTlsFileForClient("admin.cert"),
                getTlsFileForClient("admin.key-pk8"));
        final String message = "test msg";
        final int numberOfMessages = 1;
        final String topicName = getTopicWithRandomSuffix("test-topic");
        final String memoryLimitArg = "10M";

        String[] args = {"--url", url,
                "--auth-plugin", authPlugin,
                "--auth-params", authParams,
                "--tlsTrustCertsFilePath", CA_CERT_FILE_PATH,
                "--memory-limit", memoryLimitArg,
                "produce", "-m", message,
                "-n", Integer.toString(numberOfMessages), topicName};
        pulsarClientTool.getCommander().parseArgs(args);
        assertEquals(pulsarClientTool.rootParams.getTlsTrustCertsFilePath(), CA_CERT_FILE_PATH);
        assertEquals(pulsarClientTool.rootParams.getAuthParams(), authParams);
        assertEquals(pulsarClientTool.rootParams.getAuthPluginClassName(), authPlugin);
        assertEquals(pulsarClientTool.rootParams.getMemoryLimit(), 10 * 1024 * 1024);
        assertEquals(pulsarClientTool.rootParams.getServiceURL(), url);
        assertNull(pulsarClientTool.rootParams.getProxyServiceURL());
        assertNull(pulsarClientTool.rootParams.getProxyProtocol());
    }

    @Test(timeOut = 20000)
    public void testMemoryLimitArgShortName() throws Exception {
        PulsarClientTool pulsarClientTool = new PulsarClientTool(new Properties());
        final String url = "pulsar+ssl://localhost:6651";
        final String authPlugin = "org.apache.pulsar.client.impl.auth.AuthenticationTls";
        final String authParams = String.format("tlsCertFile:%s,tlsKeyFile:%s", getTlsFileForClient("admin.cert"),
                getTlsFileForClient("admin.key-pk8"));
        final String message = "test msg";
        final int numberOfMessages = 1;
        final String topicName = getTopicWithRandomSuffix("test-topic");
        final String memoryLimitArg = "10M";

        String[] args = {"--url", url,
                "--auth-plugin", authPlugin,
                "--auth-params", authParams,
                "--tlsTrustCertsFilePath", CA_CERT_FILE_PATH,
                "-ml", memoryLimitArg,
                "produce", "-m", message,
                "-n", Integer.toString(numberOfMessages), topicName};
        pulsarClientTool.getCommander().parseArgs(args);
        assertEquals(pulsarClientTool.rootParams.getMemoryLimit(), 10 * 1024 * 1024);
    }

    @Test
    public void testParsingProxyServiceUrlAndProxyProtocolFromProperties() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("proxyServiceUrl", "pulsar+ssl://my-proxy-pulsar:4443");
        properties.setProperty("proxyProtocol", "SNI");
        PulsarClientTool pulsarClientTool = new PulsarClientTool(properties);
        final String url = "pulsar+ssl://localhost:6651";
        final String message = "test msg";
        final int numberOfMessages = 1;
        final String topicName = getTopicWithRandomSuffix("test-topic");

        String[] args = {"--url", url,
                "produce", "-m", message,
                "-n", Integer.toString(numberOfMessages), topicName};
        pulsarClientTool.getCommander().parseArgs(args);
        assertEquals(pulsarClientTool.rootParams.getServiceURL(), url);
        assertEquals(pulsarClientTool.rootParams.getProxyServiceURL(), "pulsar+ssl://my-proxy-pulsar:4443");
        assertEquals(pulsarClientTool.rootParams.getProxyProtocol(), ProxyProtocol.SNI);
    }

    @Test
    public void testSendMultipleMessage() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");

        final String topicName = getTopicWithRandomSuffix("test-multiple-msg");

        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName("sub").subscribe();

        PulsarClientTool pulsarClientTool = new PulsarClientTool(properties);
        String[] args1 = {"produce", "-m", "msg0", "-m", "msg1,msg2", topicName};
        Assert.assertEquals(pulsarClientTool.run(args1), 0);

        for (int i = 0; i < 3; i++) {
            Message<byte[]> msg = consumer.receive(10, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "msg" + i);
        }
    }

    private static String getTopicWithRandomSuffix(String localNameBase) {
        return String.format("persistent://prop/ns-abc/test/%s-%s", localNameBase, UUID.randomUUID().toString());
    }


    @Test(timeOut = 20000)
    public void testProducePartitioningKey() throws Exception {

        Properties properties = initializeToolProperties();

        final String topicName = getTopicWithRandomSuffix("key-topic");

        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName("sub").subscribe();

        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                PulsarClientTool pulsarClientToolConsumer = new PulsarClientTool(properties);
                String[] args = {"produce", "-m", "test", "-k", "partition-key1", topicName};
                Assert.assertEquals(pulsarClientToolConsumer.run(args), 0);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        final Message<byte[]> message = consumer.receive(10, TimeUnit.SECONDS);
        assertNotNull(message);
        assertTrue(message.hasKey());
        Assert.assertEquals(message.getKey(), "partition-key1");
    }

    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestKey {
        public String keyA;
        public int keyB;

    }

    @Test
    public void testProduceKeyValueSchemaInlineValue() throws Exception {

        Properties properties = initializeToolProperties();

        final String topicName = getTopicWithRandomSuffix("key-topic");


        @Cleanup
        Consumer<KeyValue<TestKey, String>> consumer = pulsarClient.newConsumer(Schema.KeyValue(Schema.JSON(
                TestKey.class), Schema.STRING)).topic(topicName).subscriptionName("sub").subscribe();

        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        final Schema<TestKey> keySchema = Schema.JSON(TestKey.class);

        executor.execute(() -> {
            try {
                PulsarClientTool pulsarClientToolConsumer = new PulsarClientTool(properties);
                String[] args = {"produce",
                        "-kvet", "inline",
                        "-ks", String.format("json:%s", keySchema.getSchemaInfo().getSchemaDefinition()),
                        "-kvk", ObjectMapperFactory.getMapper().writer().writeValueAsString(
                                new TestKey("my-key", Integer.MAX_VALUE)),
                        "-vs", "string",
                        "-m", "test",
                        topicName};
                Assert.assertEquals(pulsarClientToolConsumer.run(args), 0);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        final Message<KeyValue<TestKey, String>> message = consumer.receive(10, TimeUnit.SECONDS);
        assertNotNull(message);
        assertFalse(message.hasKey());
        Assert.assertEquals(message.getValue().getKey().keyA, "my-key");
        Assert.assertEquals(message.getValue().getKey().keyB, Integer.MAX_VALUE);
        Assert.assertEquals(message.getValue().getValue(), "test");
    }

    @DataProvider(name = "keyValueKeySchema")
    public static Object[][] keyValueKeySchema() {
        return new Object[][]{
                {"json"},
                {"avro"}
        };
    }

    @Test(dataProvider = "keyValueKeySchema")
    public void testProduceKeyValueSchemaFileValue(String schema) throws Exception {

        Properties properties = initializeToolProperties();

        final String topicName = getTopicWithRandomSuffix("key-topic");



        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        File file = Files.createTempFile("", "").toFile();
        final Schema<TestKey> keySchema;
        if (schema.equals("json")) {
           keySchema = Schema.JSON(TestKey.class);
        } else if (schema.equals("avro")) {
            keySchema = Schema.AVRO(TestKey.class);
        } else {
            throw new IllegalStateException();
        }


        Files.write(file.toPath(), keySchema.encode(new TestKey("my-key", Integer.MAX_VALUE)));

        @Cleanup
        Consumer<KeyValue<TestKey, String>> consumer =
                pulsarClient.newConsumer(Schema.KeyValue(keySchema, Schema.STRING))
                .topic(topicName).subscriptionName("sub").subscribe();

        executor.execute(() -> {
            try {
                PulsarClientTool pulsarClientToolConsumer = new PulsarClientTool(properties);
                String[] args = {"produce",
                        "-k", "partitioning-key",
                        "-kvet", "inline",
                        "-ks", String.format("%s:%s", schema, keySchema.getSchemaInfo().getSchemaDefinition()),
                        "-kvkf", file.getAbsolutePath(),
                        "-vs", "string",
                        "-m", "test",
                        topicName};
                Assert.assertEquals(pulsarClientToolConsumer.run(args), 0);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        final Message<KeyValue<TestKey, String>> message = consumer.receive(10, TimeUnit.SECONDS);
        assertNotNull(message);
        // -k should not be considered
        assertFalse(message.hasKey());
        Assert.assertEquals(message.getValue().getKey().keyA, "my-key");
        Assert.assertEquals(message.getValue().getKey().keyB, Integer.MAX_VALUE);
    }

    private Properties initializeToolProperties() {
        Properties properties = new Properties();
        properties.setProperty("serviceUrl", brokerUrl.toString());
        properties.setProperty("useTls", "false");
        return properties;
    }

}
