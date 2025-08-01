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
package org.apache.pulsar.broker.admin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.internal.JacksonConfigurator;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.naming.SystemTopicNames;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.ResourceGroup;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.tls.NoopHostnameVerifier;
import org.apache.pulsar.common.util.SecurityUtility;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
@Test(groups = "broker-admin")
public class AdminApiTlsAuthTest extends MockedPulsarServiceBaseTest {

    @BeforeMethod
    @Override
    public void setup() throws Exception {
        conf.setLoadBalancerEnabled(true);
        conf.setBrokerServicePortTls(Optional.of(0));
        conf.setWebServicePortTls(Optional.of(0));
        conf.setTlsCertificateFilePath(BROKER_CERT_FILE_PATH);
        conf.setTlsKeyFilePath(BROKER_KEY_FILE_PATH);
        conf.setTlsTrustCertsFilePath(CA_CERT_FILE_PATH);
        conf.setAuthenticationEnabled(true);
        conf.setAuthenticationProviders(
                Set.of("org.apache.pulsar.broker.authentication.AuthenticationProviderTls"));
        conf.setSuperUserRoles(Set.of("admin", "superproxy"));
        conf.setProxyRoles(Set.of("proxy", "superproxy"));
        conf.setAuthorizationEnabled(true);

        conf.setBrokerClientAuthenticationPlugin("org.apache.pulsar.client.impl.auth.AuthenticationTls");
        conf.setBrokerClientAuthenticationParameters(
                String.format("tlsCertFile:%s,tlsKeyFile:%s", getTlsFileForClient("admin.cert"),
                        getTlsFileForClient("admin.key-pk8")));
        conf.setBrokerClientTrustCertsFilePath(CA_CERT_FILE_PATH);
        conf.setBrokerClientTlsEnabled(true);
        conf.setNumExecutorThreadPoolSize(5);

        super.internalSetup();

        PulsarAdmin admin = buildAdminClient("admin");
        admin.clusters().createCluster("test", ClusterData.builder().serviceUrl(pulsar.getWebServiceAddress()).build());
        admin.close();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    public void cleanup() throws Exception {
        super.internalCleanup();
    }

    WebTarget buildWebClient(String user) throws Exception {
        ClientConfig httpConfig = new ClientConfig();
        httpConfig.property(ClientProperties.FOLLOW_REDIRECTS, true);
        httpConfig.property(ClientProperties.ASYNC_THREADPOOL_SIZE, 8);
        httpConfig.register(MultiPartFeature.class);

        ClientBuilder clientBuilder = ClientBuilder.newBuilder().withConfig(httpConfig)
            .register(JacksonConfigurator.class).register(JacksonFeature.class);

        X509Certificate trustCertificates[] = SecurityUtility.loadCertificatesFromPemFile(
                CA_CERT_FILE_PATH);
        SSLContext sslCtx = SecurityUtility.createSslContext(
                false, trustCertificates,
                SecurityUtility.loadCertificatesFromPemFile(getTlsFileForClient(user + ".cert")),
                SecurityUtility.loadPrivateKeyFromPemFile(getTlsFileForClient(user + ".key-pk8")));
        clientBuilder.sslContext(sslCtx).hostnameVerifier(NoopHostnameVerifier.INSTANCE);
        Client client = clientBuilder.build();

        return client.target(brokerUrlTls.toString());
    }

    PulsarAdmin buildAdminClient(String user) throws Exception {
        return PulsarAdmin.builder()
            .allowTlsInsecureConnection(false)
            .enableTlsHostnameVerification(false)
            .serviceHttpUrl(brokerUrlTls.toString())
            .authentication("org.apache.pulsar.client.impl.auth.AuthenticationTls",
                            String.format("tlsCertFile:%s,tlsKeyFile:%s",
                                          getTlsFileForClient(user + ".cert"), getTlsFileForClient(user + ".key-pk8")))
            .tlsTrustCertsFilePath(CA_CERT_FILE_PATH).build();
    }

    PulsarClient buildClient(String user) throws Exception {
        return PulsarClient.builder()
            .serviceUrl(pulsar.getBrokerServiceUrlTls())
            .enableTlsHostnameVerification(false)
            .authentication("org.apache.pulsar.client.impl.auth.AuthenticationTls",
                            String.format("tlsCertFile:%s,tlsKeyFile:%s",
                                          getTlsFileForClient(user + ".cert"), getTlsFileForClient(user + ".key-pk8")))
            .tlsTrustCertsFilePath(CA_CERT_FILE_PATH).build();
    }

    @Test
    public void testSuperUserCanListTenants() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("foobar"),
                                                        Set.of("test")));
            Assert.assertEquals(Set.of("tenant1"), admin.tenants().getTenants());
        }
    }

    @Test
    public void testProxyRoleCantListTenants() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("foobar"),
                                                        Set.of("test")));
        }
        try (PulsarAdmin admin = buildAdminClient("proxy")) {
            admin.tenants().getTenants();
            Assert.fail("Shouldn't be able to list tenants");
        } catch (PulsarAdminException.NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testSuperUserCanGetResourceGroups() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.resourcegroups().createResourceGroup("test-resource-group",
                    new ResourceGroup());
            admin.resourcegroups().getResourceGroup("test-resource-group");
            Assert.assertEquals(Set.of("test-resource-group"),
                            admin.resourcegroups().getResourceGroups());
            admin.resourcegroups().getResourceGroup("test-resource-group");
        }
    }

    @Test
    public void testSuperUserCanDeleteResourceGroups() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.resourcegroups().createResourceGroup("test-resource-group",
                    new ResourceGroup());
            admin.resourcegroups().deleteResourceGroup("test-resource-group");
        }
    }

    @Test
    public void testSuperUserCanUpdateScaleOfTransactionCoordinators() throws Exception {
        getPulsar().getConfiguration().setTransactionCoordinatorEnabled(true);
        pulsar.getPulsarResources()
                .getNamespaceResources()
                .getPartitionedTopicResources()
                .createPartitionedTopic(SystemTopicNames.TRANSACTION_COORDINATOR_ASSIGN,
                        new PartitionedTopicMetadata(3));
        @Cleanup
        PulsarAdmin admin = buildAdminClient("admin");
        admin.transactions().scaleTransactionCoordinators(4);
        int partitions = pulsar.getPulsarResources()
                .getNamespaceResources()
                .getPartitionedTopicResources()
                .getPartitionedTopicMetadataAsync(SystemTopicNames.TRANSACTION_COORDINATOR_ASSIGN)
                .get().get().partitions;
        Assert.assertEquals(partitions, 4);
    }

    @Test
    public void testProxyRoleCantDeleteResourceGroups() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.resourcegroups().createResourceGroup("test-resource-group",
                    new ResourceGroup());
        }
        try (PulsarAdmin admin = buildAdminClient("proxy")) {
            admin.resourcegroups().deleteResourceGroup("test-resource-group");
            Assert.fail("Shouldn't be able to delete ResourceGroup");
        } catch (PulsarAdminException.NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testProxyRoleCantCreateResourceGroups() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("proxy")) {
            admin.resourcegroups().createResourceGroup("test-resource-group",
                    new ResourceGroup());
            Assert.fail("Shouldn't be able to create ResourceGroup");
        } catch (PulsarAdminException.NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testProxyRoleCantGetResourceGroups() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.resourcegroups().createResourceGroup("test-resource-group",
                    new ResourceGroup());
        }
        try (PulsarAdmin admin = buildAdminClient("proxy")) {
            admin.resourcegroups().getResourceGroups();
            Assert.fail("Shouldn't be able to list ResourceGroups");
        } catch (PulsarAdminException.NotAuthorizedException e) {
            // expected
        }
        try (PulsarAdmin admin = buildAdminClient("proxy")) {
            admin.resourcegroups().getResourceGroup("test-resource-group");
            Assert.fail("Shouldn't be able to get ResourceGroup");
        } catch (PulsarAdminException.NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testProxyRoleCantListNamespacesEvenWithAccess() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("proxy"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        try (PulsarAdmin admin = buildAdminClient("proxy")) {
            admin.namespaces().getNamespaces("tenant1");
            Assert.fail("Shouldn't be able to list namespaces");
        } catch (PulsarAdminException.NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testAuthorizedUserAsOriginalPrincipal() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("proxy", "user1"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        WebTarget root = buildWebClient("proxy");
        Assert.assertEquals(Set.of("tenant1/ns1"),
                            root.path("/admin/v2/namespaces").path("tenant1")
                            .request(MediaType.APPLICATION_JSON)
                            .header("X-Original-Principal", "user1")
                            .get(new GenericType<List<String>>() {}));
    }

    @Test
    public void testUnauthorizedUserAsOriginalPrincipal() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("proxy", "user1"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        WebTarget root = buildWebClient("proxy");
        try {
            root.path("/admin/v2/namespaces").path("tenant1")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Original-Principal", "user2")
                .get(new GenericType<List<String>>() {});
            Assert.fail("user2 should not be authorized");
        } catch (NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testAuthorizedUserAsOriginalPrincipalButProxyNotAuthorized() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("user1"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        WebTarget root = buildWebClient("proxy");
        try {
            root.path("/admin/v2/namespaces").path("tenant1")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Original-Principal", "user1")
                .get(new GenericType<List<String>>() {});
            Assert.fail("Shouldn't be able to list namespaces");
        } catch (NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testAuthorizedUserAsOriginalPrincipalProxyIsSuperUser() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("user1"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        WebTarget root = buildWebClient("superproxy");
        Assert.assertEquals(Set.of("tenant1/ns1"),
                            root.path("/admin/v2/namespaces").path("tenant1")
                            .request(MediaType.APPLICATION_JSON)
                            .header("X-Original-Principal", "user1")
                            .get(new GenericType<List<String>>() {}));
    }

    @Test
    public void testUnauthorizedUserAsOriginalPrincipalProxyIsSuperUser() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("user1"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        WebTarget root = buildWebClient("superproxy");
        try {
            root.path("/admin/v2/namespaces").path("tenant1")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Original-Principal", "user2")
                .get(new GenericType<List<String>>() {});
            Assert.fail("user2 should not be authorized");
        } catch (NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testProxyUserViaProxy() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("proxy"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        WebTarget root = buildWebClient("superproxy");
        try {
            root.path("/admin/v2/namespaces").path("tenant1")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Original-Principal", "proxy")
                .get(new GenericType<List<String>>() {});
            Assert.fail("proxy should not be authorized");
        } catch (NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testSuperProxyUserAndAdminCanListTenants() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("user1"),
                                                        Set.of("test")));
        }
        WebTarget root = buildWebClient("superproxy");
        Assert.assertEquals(Set.of("tenant1"),
                            root.path("/admin/v2/tenants")
                            .request(MediaType.APPLICATION_JSON)
                            .header("X-Original-Principal", "admin")
                            .get(new GenericType<List<String>>() {}));
    }

    @Test
    public void testSuperProxyUserAndNonAdminCannotListTenants() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("proxy"),
                                                        Set.of("test")));
        }
        WebTarget root = buildWebClient("superproxy");
        try {
            root.path("/admin/v2/tenants")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Original-Principal", "user1")
                .get(new GenericType<List<String>>() {});
            Assert.fail("user1 should not be authorized");
        } catch (NotAuthorizedException e) {
            // expected
        }
    }

    @Test
    public void testProxyCannotSetOriginalPrincipalAsEmpty() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("user1"),
                                                        Set.of("test")));
            admin.namespaces().createNamespace("tenant1/ns1");
        }
        WebTarget root = buildWebClient("proxy");
        try {
            root.path("/admin/v2/namespaces").path("tenant1")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Original-Principal", "")
                .get(new GenericType<List<String>>() {});
            Assert.fail("Proxy shouldn't be able to set original principal.");
        } catch (NotAuthorizedException e) {
            // expected
        }
    }

    // For https://github.com/apache/pulsar/issues/2880
    @Test
    public void testDeleteNamespace() throws Exception {
        try (PulsarAdmin admin = buildAdminClient("admin")) {
            log.info("Creating tenant");
            admin.tenants().createTenant("tenant1",
                                         new TenantInfoImpl(Set.of("admin"), Set.of("test")));
            log.info("Creating namespace, and granting perms to user1");
            admin.namespaces().createNamespace("tenant1/ns1", Set.of("test"));
            admin.namespaces().grantPermissionOnNamespace("tenant1/ns1", "user1", Set.of(AuthAction.produce));

            log.info("user1 produces some messages");
            try (PulsarClient client = buildClient("user1");
                 Producer<String> producer = client.newProducer(Schema.STRING).topic("tenant1/ns1/foobar").create()) {
                producer.send("foobar");
            }

            log.info("Deleting the topic");
            admin.topics().delete("tenant1/ns1/foobar", true);

            log.info("Deleting namespace");
            deleteNamespaceWithRetry("tenant1/ns1", false, admin);
        }
    }

    /**
     * Validates Pulsar-admin performs auto cert refresh.
     * @throws Exception
     */
    @Test
    public void testCertRefreshForPulsarAdmin() throws Exception {
        String adminUser = "admin";
        String user2 = "user1";
        File keyFile = File.createTempFile("temp", ".key-pk8");
        Path keyFilePath = Paths.get(keyFile.getAbsolutePath());
        int autoCertRefreshTimeSec = 1;
        try {
            Files.copy(Paths.get(getTlsFileForClient(user2 + ".key-pk8")), keyFilePath,
                    StandardCopyOption.REPLACE_EXISTING);
            @Cleanup
            PulsarAdmin admin = PulsarAdmin.builder()
                    .allowTlsInsecureConnection(false)
                    .enableTlsHostnameVerification(false)
                    .serviceHttpUrl(brokerUrlTls.toString())
                    .autoCertRefreshTime(autoCertRefreshTimeSec, TimeUnit.SECONDS)
                    .authentication("org.apache.pulsar.client.impl.auth.AuthenticationTls",
                                    String.format("tlsCertFile:%s,tlsKeyFile:%s",
                                                  getTlsFileForClient(adminUser + ".cert"), keyFile))
                    .tlsTrustCertsFilePath(CA_CERT_FILE_PATH).build();
            // try to call admin-api which should fail due to incorrect key-cert
            try {
                admin.tenants().createTenant("tenantX",
                        new TenantInfoImpl(Set.of("foobar"), Set.of("test")));
                Assert.fail("should have failed due to invalid key file");
            } catch (Exception e) {
                //OK
            }
            // replace correct key file
            Files.delete(keyFile.toPath());
            Thread.sleep(2 * autoCertRefreshTimeSec * 1000);
            Files.copy(Paths.get(getTlsFileForClient(adminUser + ".key-pk8")), keyFilePath);
            MutableBoolean success = new MutableBoolean(false);
            retryStrategically((test) -> {
                try {
                    admin.tenants().createTenant("tenantX",
                            new TenantInfoImpl(Set.of("foobar"), Set.of("test")));
                    success.setValue(true);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }, 5, 1000);
            Assert.assertTrue(success.booleanValue());
            Assert.assertEquals(Set.of("tenantX"), admin.tenants().getTenants());
        } finally {
            Files.delete(keyFile.toPath());
        }
    }
}