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
package org.apache.pulsar.proxy.server;

import static org.apache.pulsar.proxy.server.ProxyServiceStarterTest.getArgs;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import lombok.Cleanup;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.websocket.data.ProducerMessage;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ProxyServiceTlsStarterTest extends MockedPulsarServiceBaseTest {
    private ProxyServiceStarter serviceStarter;
    private String serviceUrl;
    private int webPort;

    @Override
    @BeforeClass
    protected void setup() throws Exception {
        internalSetup();
        serviceStarter = new ProxyServiceStarter(getArgs(), null, true);
        serviceStarter.getConfig().setBrokerServiceURL(pulsar.getBrokerServiceUrl());
        serviceStarter.getConfig().setBrokerServiceURLTLS(pulsar.getBrokerServiceUrlTls());
        serviceStarter.getConfig().setBrokerWebServiceURL(pulsar.getWebServiceAddress());
        serviceStarter.getConfig().setBrokerWebServiceURLTLS(pulsar.getWebServiceAddressTls());
        serviceStarter.getConfig().setBrokerClientTrustCertsFilePath(CA_CERT_FILE_PATH);
        serviceStarter.getConfig().setBrokerClientCertificateFilePath(BROKER_CERT_FILE_PATH);
        serviceStarter.getConfig().setBrokerClientKeyFilePath(BROKER_KEY_FILE_PATH);
        serviceStarter.getConfig().setServicePort(Optional.empty());
        serviceStarter.getConfig().setServicePortTls(Optional.of(0));
        serviceStarter.getConfig().setWebServicePort(Optional.of(0));
        serviceStarter.getConfig().setTlsEnabledWithBroker(true);
        serviceStarter.getConfig().setWebSocketServiceEnabled(true);
        serviceStarter.getConfig().setTlsCertificateFilePath(PROXY_CERT_FILE_PATH);
        serviceStarter.getConfig().setTlsKeyFilePath(PROXY_KEY_FILE_PATH);
        serviceStarter.getConfig().setBrokerProxyAllowedTargetPorts("*");
        serviceStarter.getConfig().setClusterName(configClusterName);
        serviceStarter.start();
        serviceUrl = serviceStarter.getProxyService().getServiceUrlTls();
        webPort = serviceStarter.getServer().getListenPortHTTP().get();
    }

    protected void doInitConf() throws Exception {
        super.doInitConf();
        this.conf.setBrokerServicePortTls(Optional.of(0));
        this.conf.setWebServicePortTls(Optional.of(0));
        this.conf.setTlsCertificateFilePath(PROXY_CERT_FILE_PATH);
        this.conf.setTlsKeyFilePath(PROXY_KEY_FILE_PATH);
    }

    @Override
    @AfterClass(alwaysRun = true)
    protected void cleanup() throws Exception {
        internalCleanup();
        serviceStarter.close();
    }

    @Test
    public void testProducer() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(serviceUrl)
                .allowTlsInsecureConnection(false).tlsTrustCertsFilePath(CA_CERT_FILE_PATH)
                .build();

        @Cleanup
        Producer<byte[]> producer = client.newProducer()
                .topic("persistent://sample/test/local/websocket-topic")
                .create();

        for (int i = 0; i < 10; i++) {
            producer.send("test".getBytes());
        }
    }

    @Test
    public void testProduceAndConsumeMessageWithWebsocket() throws Exception {
        @Cleanup("stop")
        HttpClient producerClient = new HttpClient();
        @Cleanup("stop")
        WebSocketClient producerWebSocketClient = new WebSocketClient(producerClient);
        producerWebSocketClient.start();
        MyWebSocket producerSocket = new MyWebSocket();
        String produceUri = "ws://localhost:" + webPort + "/ws/producer/persistent/sample/test/local/websocket-topic";
        Future<Session> producerSession = producerWebSocketClient.connect(producerSocket, URI.create(produceUri));

        ProducerMessage produceRequest = new ProducerMessage();
        produceRequest.setContext("context");
        produceRequest.setPayload(Base64.getEncoder().encodeToString("my payload".getBytes()));

        @Cleanup("stop")
        HttpClient consumerClient = new HttpClient();
        @Cleanup("stop")
        WebSocketClient consumerWebSocketClient = new WebSocketClient(consumerClient);
        consumerWebSocketClient.start();
        MyWebSocket consumerSocket = new MyWebSocket();
        String consumeUri = "ws://localhost:" + webPort
                + "/ws/consumer/persistent/sample/test/local/websocket-topic/my-sub";
        Future<Session> consumerSession = consumerWebSocketClient.connect(consumerSocket, URI.create(consumeUri));
        consumerSession.get().getRemote().sendPing(ByteBuffer.wrap("ping".getBytes()));
        producerSession.get().getRemote().sendString(ObjectMapperFactory.getMapper().writer()
                .writeValueAsString(produceRequest));
        assertTrue(consumerSocket.getResponse().contains("ping"));
        ProducerMessage message = ObjectMapperFactory.getMapper().reader().readValue(consumerSocket.getResponse(),
                ProducerMessage.class);
        assertEquals(new String(Base64.getDecoder().decode(message.getPayload())), "my payload");
    }

    @WebSocket
    public static class MyWebSocket extends WebSocketAdapter implements WebSocketPingPongListener {

        ArrayBlockingQueue<String> incomingMessages = new ArrayBlockingQueue<>(10);

        @Override
        public void onWebSocketText(String message) {
            incomingMessages.add(message);
        }

        @Override
        public void onWebSocketClose(int i, String s) {
        }

        @Override
        public void onWebSocketConnect(Session session) {
        }

        @Override
        public void onWebSocketError(Throwable throwable) {
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload) {
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload) {
            incomingMessages.add(BufferUtil.toDetailString(payload));
        }

        public String getResponse() throws InterruptedException {
            return incomingMessages.take();
        }
    }

}
