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
package org.apache.pulsar.jetty.tls;

import com.google.common.io.Resources;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.pulsar.common.util.DefaultPulsarSslFactory;
import org.apache.pulsar.common.util.PulsarSslConfiguration;
import org.apache.pulsar.common.util.PulsarSslFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.testng.annotations.Test;

@Slf4j
public class JettySslContextFactoryWithKeyStoreTest {
    static final String BROKEN_KEY_STORE_PATH =
            Resources.getResource("certificate-authority/jks/broker.keystore.jks").getPath();
    static final String BROKER_TRUST_STORE_PATH =
            Resources.getResource("certificate-authority/jks/broker.truststore.jks").getPath();
    static final String CLIENT_KEY_STORE_PATH =
            Resources.getResource("certificate-authority/jks/client.keystore.jks").getPath();
    static final String CLIENT_TRUST_STORE_PATH =
            Resources.getResource("certificate-authority/jks/client.truststore.jks").getPath();
    static final String KEY_STORE_TYPE = "JKS";
    static final String KEY_STORE_PASSWORD = "111111";

    @Test
    public void testJettyTlsServerTls() throws Exception {
        @Cleanup("stop")
        Server server = new Server();
        List<ServerConnector> connectors = new ArrayList<>();
        PulsarSslConfiguration sslConfiguration = PulsarSslConfiguration.builder()
                .tlsKeyStoreType(KEY_STORE_TYPE)
                .tlsKeyStorePath(BROKEN_KEY_STORE_PATH)
                .tlsKeyStorePassword(KEY_STORE_PASSWORD)
                .tlsTrustStoreType(KEY_STORE_TYPE)
                .tlsTrustStorePath(CLIENT_TRUST_STORE_PATH)
                .tlsTrustStorePassword(KEY_STORE_PASSWORD)
                .requireTrustedClientCertOnConnect(true)
                .tlsEnabledWithKeystore(true)
                .isHttps(true)
                .build();
        PulsarSslFactory sslFactory = new DefaultPulsarSslFactory();
        sslFactory.initialize(sslConfiguration);
        sslFactory.createInternalSslContext();
        SslContextFactory.Server factory = JettySslContextFactory.createSslContextFactory(null,
                sslFactory, true, null, null);
        factory.setHostnameVerifier((s, sslSession) -> true);
        ServerConnector connector = new ServerConnector(server, factory);
        connector.setPort(0);
        connectors.add(connector);
        server.setConnectors(connectors.toArray(new ServerConnector[0]));
        server.start();
        // client connect
        HttpClientBuilder httpClientBuilder = HttpClients.custom();
        RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.create();
        registryBuilder.register("https",
                new SSLConnectionSocketFactory(getClientSslContext(), new NoopHostnameVerifier()));
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(registryBuilder.build());
        httpClientBuilder.setConnectionManager(cm);
        @Cleanup
        CloseableHttpClient httpClient = httpClientBuilder.build();
        HttpGet httpGet = new HttpGet("https://localhost:" + connector.getLocalPort());
        httpClient.execute(httpGet);
    }

    @Test(expectedExceptions = SSLHandshakeException.class)
    public void testJettyTlsServerInvalidTlsProtocol() throws Exception {
        Configurator.setRootLevel(Level.INFO);
        @Cleanup("stop")
        Server server = new Server();
        List<ServerConnector> connectors = new ArrayList<>();
        PulsarSslConfiguration sslConfiguration = PulsarSslConfiguration.builder()
                .tlsKeyStoreType(KEY_STORE_TYPE)
                .tlsKeyStorePath(BROKEN_KEY_STORE_PATH)
                .tlsKeyStorePassword(KEY_STORE_PASSWORD)
                .tlsTrustStoreType(KEY_STORE_TYPE)
                .tlsTrustStorePath(CLIENT_TRUST_STORE_PATH)
                .tlsTrustStorePassword(KEY_STORE_PASSWORD)
                .tlsProtocols(new HashSet<String>() {
                    {
                        this.add("TLSv1.3");
                    }
                })
                .requireTrustedClientCertOnConnect(true)
                .tlsEnabledWithKeystore(true)
                .isHttps(true)
                .build();
        PulsarSslFactory sslFactory = new DefaultPulsarSslFactory();
        sslFactory.initialize(sslConfiguration);
        sslFactory.createInternalSslContext();
        SslContextFactory.Server factory = JettySslContextFactory.createSslContextFactory(null,
                sslFactory, true, null,
                new HashSet<String>() {
                    {
                        this.add("TLSv1.3");
                    }
                });
        factory.setHostnameVerifier((s, sslSession) -> true);
        ServerConnector connector = new ServerConnector(server, factory);
        connector.setPort(0);
        connectors.add(connector);
        server.setConnectors(connectors.toArray(new ServerConnector[0]));
        server.start();
        // client connect
        HttpClientBuilder httpClientBuilder = HttpClients.custom();
        RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.create();
        registryBuilder.register("https", new SSLConnectionSocketFactory(getClientSslContext(),
                new String[]{"TLSv1.2"}, null, new NoopHostnameVerifier()));
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(registryBuilder.build());
        httpClientBuilder.setConnectionManager(cm);
        @Cleanup
        CloseableHttpClient httpClient = httpClientBuilder.build();
        HttpGet httpGet = new HttpGet("https://localhost:" + connector.getLocalPort());
        httpClient.execute(httpGet);
    }

    @Test(expectedExceptions = SSLHandshakeException.class)
    public void testJettyTlsServerInvalidCipher() throws Exception {
        @Cleanup("stop")
        Server server = new Server();
        List<ServerConnector> connectors = new ArrayList<>();
        PulsarSslConfiguration sslConfiguration = PulsarSslConfiguration.builder()
                .tlsKeyStoreType(KEY_STORE_TYPE)
                .tlsKeyStorePath(BROKEN_KEY_STORE_PATH)
                .tlsKeyStorePassword(KEY_STORE_PASSWORD)
                .tlsTrustStoreType(KEY_STORE_TYPE)
                .tlsTrustStorePath(CLIENT_TRUST_STORE_PATH)
                .tlsTrustStorePassword(KEY_STORE_PASSWORD)
                .tlsCiphers(new HashSet<String>() {
                    {
                        this.add("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
                    }
                })
                .tlsProtocols(new HashSet<String>() {
                    {
                        this.add("TLSv1.3");
                    }
                })
                .requireTrustedClientCertOnConnect(true)
                .tlsEnabledWithKeystore(true)
                .isHttps(true)
                .build();
        PulsarSslFactory sslFactory = new DefaultPulsarSslFactory();
        sslFactory.initialize(sslConfiguration);
        sslFactory.createInternalSslContext();
        SslContextFactory.Server factory = JettySslContextFactory.createSslContextFactory(null,
                sslFactory, true,
                new HashSet<String>() {
                    {
                        this.add("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
                    }
                },
                new HashSet<String>() {
                    {
                        this.add("TLSv1.2");
                        this.add("TLSv1.3");
                    }
                });
        factory.setHostnameVerifier((s, sslSession) -> true);
        ServerConnector connector = new ServerConnector(server, factory);
        connector.setPort(0);
        connectors.add(connector);
        server.setConnectors(connectors.toArray(new ServerConnector[0]));
        server.start();
        // client connect
        HttpClientBuilder httpClientBuilder = HttpClients.custom();
        RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.create();
        registryBuilder.register("https", new SSLConnectionSocketFactory(getClientSslContext(),
                new String[]{"TLSv1.2"}, new String[]{"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"},
                new NoopHostnameVerifier()));
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(registryBuilder.build());
        httpClientBuilder.setConnectionManager(cm);
        @Cleanup
        CloseableHttpClient httpClient = httpClientBuilder.build();
        HttpGet httpGet = new HttpGet("https://localhost:" + connector.getLocalPort());
        httpClient.execute(httpGet);
    }

    private static SSLContext getClientSslContext() {
        return getSslContext(CLIENT_KEY_STORE_PATH, KEY_STORE_PASSWORD, BROKER_TRUST_STORE_PATH, KEY_STORE_PASSWORD);
    }

    private static SSLContext getSslContext(String keyStorePath, String keyStorePassword,
                                            String trustStorePath, String trustStorePassword) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            // key store
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream inputStream = new FileInputStream(keyStorePath)) {
                keyStore.load(inputStream, keyStorePassword.toCharArray());
            }
            keyManagerFactory.init(keyStore, keyStorePassword.toCharArray());
            KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
            // trust store
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream inputStream = new FileInputStream(trustStorePath)) {
                trustStore.load(inputStream, trustStorePassword.toCharArray());
            }
            trustManagerFactory.init(trustStore);
            sslContext.init(keyManagers, trustManagerFactory.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            log.error("load ssl context error ", e);
            return null;
        }
    }
}
