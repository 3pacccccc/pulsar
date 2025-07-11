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
package org.apache.pulsar.io.elasticsearch;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import java.io.IOException;
import java.time.Duration;
import org.apache.pulsar.io.core.SinkContext;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.MountableFile;
import org.testng.annotations.Test;

// see https://www.elastic.co/guide/en/elasticsearch/reference/current/security-settings.html#ssl-tls-settings
public abstract class ElasticSearchClientSslTest extends ElasticSearchTestBase {

    static final String INDEX = "myindex";

    static final String SSL_RESOURCE_DIR = MountableFile.forClasspathResource("ssl").getFilesystemPath();
    static final  String CONFIG_DIR = "/usr/share/elasticsearch/config";

    public ElasticSearchClientSslTest(String elasticImageName) {
        super(elasticImageName);
    }

    @Test
    public void testSslBasic() throws IOException {
        try (ElasticsearchContainer container = createElasticsearchContainer()
                .withFileSystemBind(SSL_RESOURCE_DIR, CONFIG_DIR + "/ssl")
                .withPassword("elastic")
                .withEnv("xpack.license.self_generated.type", "trial")
                .withEnv("xpack.security.enabled", "true")
                .withEnv("xpack.security.http.ssl.enabled", "true")
                .withEnv("xpack.security.http.ssl.client_authentication", "optional")
                .withEnv("xpack.security.http.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.http.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.http.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .withEnv("xpack.security.transport.ssl.enabled", "true")
                .withEnv("xpack.security.transport.ssl.verification_mode", "certificate")
                .withEnv("xpack.security.transport.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.transport.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.transport.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .waitingFor(Wait.forLogMessage(".*(Security is enabled|Active license).*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            container.start();

            ElasticSearchConfig config = new ElasticSearchConfig()
                    .setElasticSearchUrl("https://" + container.getHttpHostAddress())
                    .setIndexName(INDEX)
                    .setUsername("elastic")
                    .setPassword("elastic")
                    .setSsl(new ElasticSearchSslConfig()
                            .setEnabled(true)
                            .setTruststorePath(SSL_RESOURCE_DIR + "/truststore.jks")
                            .setTruststorePassword("changeit"));
            testClientWithConfig(config);
        }
    }

    @Test
    public void testSslWithHostnameVerification() throws IOException {
        try (ElasticsearchContainer container = createElasticsearchContainer()
                .withFileSystemBind(SSL_RESOURCE_DIR, CONFIG_DIR + "/ssl")
                .withPassword("elastic")
                .withEnv("xpack.license.self_generated.type", "trial")
                .withEnv("xpack.security.enabled", "true")
                .withEnv("xpack.security.http.ssl.enabled", "true")
                .withEnv("xpack.security.http.ssl.supported_protocols", "TLSv1.2,TLSv1.1")
                .withEnv("xpack.security.http.ssl.client_authentication", "optional")
                .withEnv("xpack.security.http.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.http.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.http.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .withEnv("xpack.security.transport.ssl.enabled", "true")
                .withEnv("xpack.security.transport.ssl.verification_mode", "full")
                .withEnv("xpack.security.transport.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.transport.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.transport.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .waitingFor(Wait.forLogMessage(".*(Security is enabled|Active license).*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            container.start();

            ElasticSearchConfig config = new ElasticSearchConfig()
                    .setElasticSearchUrl("https://" + container.getHttpHostAddress())
                    .setIndexName(INDEX)
                    .setUsername("elastic")
                    .setPassword("elastic")
                    .setSsl(new ElasticSearchSslConfig()
                            .setEnabled(true)
                            .setProtocols("TLSv1.2")
                            .setHostnameVerification(true)
                            .setTruststorePath(SSL_RESOURCE_DIR + "/truststore.jks")
                            .setTruststorePassword("changeit"));
            testClientWithConfig(config);
        }
    }

    @Test
    public void testSslWithClientAuth() throws IOException {
        try (ElasticsearchContainer container = createElasticsearchContainer()
                .withFileSystemBind(SSL_RESOURCE_DIR, CONFIG_DIR + "/ssl")
                .withPassword("elastic")
                .withEnv("xpack.license.self_generated.type", "trial")
                .withEnv("xpack.security.enabled", "true")
                .withEnv("xpack.security.http.ssl.enabled", "true")
                .withEnv("xpack.security.http.ssl.client_authentication", "required")
                .withEnv("xpack.security.http.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.http.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.http.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .withEnv("xpack.security.transport.ssl.enabled", "true")
                .withEnv("xpack.security.transport.ssl.verification_mode", "full")
                .withEnv("xpack.security.transport.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.transport.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.transport.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .waitingFor(Wait.forLogMessage(".*(Security is enabled|Active license).*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)))) {
            container.start();

            ElasticSearchConfig config = new ElasticSearchConfig()
                    .setElasticSearchUrl("https://" + container.getHttpHostAddress())
                    .setIndexName(INDEX)
                    .setUsername("elastic")
                    .setPassword("elastic")
                    .setSsl(new ElasticSearchSslConfig()
                            .setEnabled(true)
                            .setHostnameVerification(true)
                            .setTruststorePath(SSL_RESOURCE_DIR + "/truststore.jks")
                            .setTruststorePassword("changeit")
                            .setKeystorePath(SSL_RESOURCE_DIR + "/keystore.jks")
                            .setKeystorePassword("changeit"));
            testClientWithConfig(config);
        }
    }

    @Test
    public void testSslDisableCertificateValidation() throws IOException {
        try (ElasticsearchContainer container = createElasticsearchContainer()
                .withFileSystemBind(SSL_RESOURCE_DIR, CONFIG_DIR + "/ssl")
                .withPassword("elastic")
                .withEnv("xpack.license.self_generated.type", "trial")
                .withEnv("xpack.security.enabled", "true")
                .withEnv("xpack.security.http.ssl.enabled", "true")
                .withEnv("xpack.security.http.ssl.client_authentication", "optional")
                .withEnv("xpack.security.http.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.http.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.http.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .withEnv("xpack.security.transport.ssl.enabled", "true")
                .withEnv("xpack.security.transport.ssl.verification_mode", "certificate")
                .withEnv("xpack.security.transport.ssl.key", CONFIG_DIR + "/ssl/elasticsearch.key")
                .withEnv("xpack.security.transport.ssl.certificate", CONFIG_DIR + "/ssl/elasticsearch.crt")
                .withEnv("xpack.security.transport.ssl.certificate_authorities", CONFIG_DIR + "/ssl/cacert.crt")
                .waitingFor(Wait.forLogMessage(".*(Security is enabled|Active license).*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            container.start();

            ElasticSearchConfig config = new ElasticSearchConfig()
                    .setElasticSearchUrl("https://" + container.getHttpHostAddress())
                    .setIndexName(INDEX)
                    .setUsername("elastic")
                    .setPassword("elastic")
                    .setSsl(new ElasticSearchSslConfig()
                            .setEnabled(true)
                            .setDisableCertificateValidation(true));
            testClientWithConfig(config);
        }
    }

    private void testClientWithConfig(ElasticSearchConfig config) throws IOException {
        try (ElasticSearchClient client = new ElasticSearchClient(config, mock(SinkContext.class));) {
            testIndexExists(client);
        }
    }

    private void testIndexExists(ElasticSearchClient client) throws IOException {
        assertFalse(client.indexExists("mynewindex"));
        assertTrue(client.createIndexIfNeeded("mynewindex"));
        assertTrue(client.indexExists("mynewindex"));
        assertFalse(client.createIndexIfNeeded("mynewindex"));
    }

}
