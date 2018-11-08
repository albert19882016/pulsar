/**
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
package org.apache.pulsar.broker.loadbalance;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.test.PortManager;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.impl.LoadManagerShared;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerImpl;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerWrapper;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceBundleFactory;
import org.apache.pulsar.common.naming.NamespaceBundles;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.ServiceUnitId;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.FailureDomain;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.zookeeper.LocalBookkeeperEnsemble;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.beust.jcommander.internal.Maps;
import com.google.common.collect.BoundType;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;

public class AntiAffinityNamespaceGroupTest {
    private LocalBookkeeperEnsemble bkEnsemble;

    private URL url1;
    private PulsarService pulsar1;
    private PulsarAdmin admin1;

    private URL url2;
    private PulsarService pulsar2;
    private PulsarAdmin admin2;

    private String primaryHost;
    private String secondaryHost;

    private NamespaceBundleFactory nsFactory;

    private ModularLoadManagerImpl primaryLoadManager;
    private ModularLoadManagerImpl secondaryLoadManager;

    private ExecutorService executor = new ThreadPoolExecutor(5, 20, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>());

    private final int ZOOKEEPER_PORT = PortManager.nextFreePort();
    private final int PRIMARY_BROKER_WEBSERVICE_PORT = PortManager.nextFreePort();
    private final int SECONDARY_BROKER_WEBSERVICE_PORT = PortManager.nextFreePort();
    private final int PRIMARY_BROKER_PORT = PortManager.nextFreePort();
    private final int SECONDARY_BROKER_PORT = PortManager.nextFreePort();
    private static final Logger log = LoggerFactory.getLogger(AntiAffinityNamespaceGroupTest.class);

    static {
        System.setProperty("test.basePort", "16100");
    }

    private static Object getField(final Object instance, final String fieldName) throws Exception {
        final Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(instance);
    }

    @BeforeMethod
    void setup() throws Exception {

        // Start local bookkeeper ensemble
        bkEnsemble = new LocalBookkeeperEnsemble(3, ZOOKEEPER_PORT, () -> PortManager.nextFreePort());
        bkEnsemble.start();

        // Start broker 1
        ServiceConfiguration config1 = new ServiceConfiguration();
        config1.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
        config1.setClusterName("use");
        config1.setWebServicePort(PRIMARY_BROKER_WEBSERVICE_PORT);
        config1.setZookeeperServers("127.0.0.1" + ":" + ZOOKEEPER_PORT);
        config1.setBrokerServicePort(PRIMARY_BROKER_PORT);
        config1.setFailureDomainsEnabled(true);
        config1.setLoadBalancerEnabled(true);
        config1.setAdvertisedAddress("localhost");
        createCluster(bkEnsemble.getZkClient(), config1);
        pulsar1 = new PulsarService(config1);

        pulsar1.start();

        primaryHost = String.format("%s:%d", "localhost", PRIMARY_BROKER_WEBSERVICE_PORT);
        url1 = new URL("http://127.0.0.1" + ":" + PRIMARY_BROKER_WEBSERVICE_PORT);
        admin1 = PulsarAdmin.builder().serviceHttpUrl(url1.toString()).build();

        // Start broker 2
        ServiceConfiguration config2 = new ServiceConfiguration();
        config2.setLoadManagerClassName(ModularLoadManagerImpl.class.getName());
        config2.setClusterName("use");
        config2.setWebServicePort(SECONDARY_BROKER_WEBSERVICE_PORT);
        config2.setZookeeperServers("127.0.0.1" + ":" + ZOOKEEPER_PORT);
        config2.setBrokerServicePort(SECONDARY_BROKER_PORT);
        config2.setFailureDomainsEnabled(true);
        pulsar2 = new PulsarService(config2);
        secondaryHost = String.format("%s:%d", "localhost",
                SECONDARY_BROKER_WEBSERVICE_PORT);

        pulsar2.start();

        url2 = new URL("http://127.0.0.1" + ":" + SECONDARY_BROKER_WEBSERVICE_PORT);
        admin2 = PulsarAdmin.builder().serviceHttpUrl(url2.toString()).build();

        primaryLoadManager = (ModularLoadManagerImpl) getField(pulsar1.getLoadManager().get(), "loadManager");
        secondaryLoadManager = (ModularLoadManagerImpl) getField(pulsar2.getLoadManager().get(), "loadManager");
        nsFactory = new NamespaceBundleFactory(pulsar1, Hashing.crc32());
        Thread.sleep(100);
    }

    @AfterMethod
    void shutdown() throws Exception {
        log.info("--- Shutting down ---");
        executor.shutdown();

        admin1.close();
        admin2.close();

        pulsar2.close();
        pulsar1.close();

        bkEnsemble.stop();
    }

    private void createCluster(ZooKeeper zk, ServiceConfiguration config) throws Exception {
        ZkUtils.createFullPathOptimistic(zk, "/admin/clusters/" + config.getClusterName(),
                ObjectMapperFactory.getThreadLocal().writeValueAsBytes(
                        new ClusterData("http://" + config.getAdvertisedAddress() + ":" + config.getWebServicePort())),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    @Test
    public void testClusterDomain() {

    }

    /**
     *
     * It verifies anti-affinity-namespace assignment with failure-domain
     *
     * <pre>
     * Domain     Brokers-count
     * ________  ____________
     * domain-0   broker-0,broker-1
     * domain-1   broker-2,broker-3
     *
     * Anti-affinity-namespace assignment
     *
     * (1) ns0 -> candidate-brokers: b0, b1, b2, b3 => selected b0
     * (2) ns1 -> candidate-brokers: b2, b3         => selected b2
     * (3) ns2 -> candidate-brokers: b1, b3         => selected b1
     * (4) ns3 -> candidate-brokers: b3             => selected b3
     * (5) ns4 -> candidate-brokers: b0, b1, b2, b3 => selected b0
     *
     * "candidate" broker to own anti-affinity-namespace = b2 or b4
     *
     * </pre>
     *
     */
    @Test
    public void testAntiAffinityNamespaceFilteringWithDomain() throws Exception {

        final String namespace = "my-tenant/use/my-ns";
        final int totalNamespaces = 5;
        final String namespaceAntiAffinityGroup = "my-antiaffinity";
        final String bundle = "/0x00000000_0xffffffff";
        final int totalBrokers = 4;

        pulsar1.getConfiguration().setFailureDomainsEnabled(true);
        admin1.tenants().createTenant("my-tenant",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("use")));

        for (int i = 0; i < totalNamespaces; i++) {
            final String ns = namespace + i;
            admin1.namespaces().createNamespace(ns);
            admin1.namespaces().setNamespaceAntiAffinityGroup(ns, namespaceAntiAffinityGroup);
        }

        Set<String> brokers = Sets.newHashSet();
        Map<String, String> brokerToDomainMap = Maps.newHashMap();
        brokers.add("brokerName-0");
        brokerToDomainMap.put("brokerName-0", "domain-0");
        brokers.add("brokerName-1");
        brokerToDomainMap.put("brokerName-1", "domain-0");
        brokers.add("brokerName-2");
        brokerToDomainMap.put("brokerName-2", "domain-1");
        brokers.add("brokerName-3");
        brokerToDomainMap.put("brokerName-3", "domain-1");

        Set<String> candidate = Sets.newHashSet();
        Map<String, Map<String, Set<String>>> brokerToNamespaceToBundleRange = Maps.newHashMap();

        assertEquals(brokers.size(), totalBrokers);

        String assignedNamespace = namespace + "0" + bundle;
        candidate.addAll(brokers);

        // for namespace-0 all brokers available
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, brokers,
                brokerToNamespaceToBundleRange, brokerToDomainMap);
        assertEquals(brokers.size(), totalBrokers);

        // add namespace-0 to broker-0 of domain-0 => state: n0->b0
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "brokerName-0", namespace + "0", assignedNamespace);
        candidate.addAll(brokers);
        // for namespace-1 only domain-1 brokers are available as broker-0 already owns namespace-0
        assignedNamespace = namespace + "1" + bundle;
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, candidate,
                brokerToNamespaceToBundleRange, brokerToDomainMap);
        assertEquals(candidate.size(), 2);
        candidate.forEach(broker -> assertEquals(brokerToDomainMap.get(broker), "domain-1"));

        // add namespace-1 to broker-2 of domain-1 => state: n0->b0, n1->b2
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "brokerName-2", namespace + "1", assignedNamespace);
        candidate.addAll(brokers);
        // for namespace-2 only brokers available are : broker-1 and broker-3
        assignedNamespace = namespace + "2" + bundle;
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, candidate,
                brokerToNamespaceToBundleRange, brokerToDomainMap);
        assertEquals(candidate.size(), 2);
        assertTrue(candidate.contains("brokerName-1"));
        assertTrue(candidate.contains("brokerName-3"));

        // add namespace-2 to broker-1 of domain-0 => state: n0->b0, n1->b2, n2->b1
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "brokerName-1", namespace + "2", assignedNamespace);
        candidate.addAll(brokers);
        // for namespace-3 only brokers available are : broker-3
        assignedNamespace = namespace + "3" + bundle;
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, candidate,
                brokerToNamespaceToBundleRange, brokerToDomainMap);
        assertEquals(candidate.size(), 1);
        assertTrue(candidate.contains("brokerName-3"));
        // add namespace-3 to broker-3 of domain-1 => state: n0->b0, n1->b2, n2->b1, n3->b3
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "brokerName-3", namespace + "3", assignedNamespace);
        candidate.addAll(brokers);
        // for namespace-4 only brokers available are : all 4 brokers
        assignedNamespace = namespace + "4" + bundle;
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, candidate,
                brokerToNamespaceToBundleRange, brokerToDomainMap);
        assertEquals(candidate.size(), 4);
    }

    /**
     * It verifies anti-affinity-namespace assignment without failure-domain enabled
     *
     * <pre>
     *  Anti-affinity-namespace assignment
     *
     * (1) ns0 -> candidate-brokers: b0, b1, b2     => selected b0
     * (2) ns1 -> candidate-brokers: b1, b2         => selected b1
     * (3) ns2 -> candidate-brokers: b2             => selected b2
     * (5) ns3 -> candidate-brokers: b0, b1, b2     => selected b0
     * </pre>
     *
     *
     * @throws Exception
     */
    @Test
    public void testAntiAffinityNamespaceFilteringWithoutDomain() throws Exception {

        final String namespace = "my-tenant/use/my-ns";
        final int totalNamespaces = 5;
        final String namespaceAntiAffinityGroup = "my-antiaffinity";
        final String bundle = "/0x00000000_0xffffffff";

        admin1.tenants().createTenant("my-tenant",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("use")));

        for (int i = 0; i < totalNamespaces; i++) {
            final String ns = namespace + i;
            admin1.namespaces().createNamespace(ns);
            admin1.namespaces().setNamespaceAntiAffinityGroup(ns, namespaceAntiAffinityGroup);
        }

        Set<String> brokers = Sets.newHashSet();
        Set<String> candidate = Sets.newHashSet();
        Map<String, Map<String, Set<String>>> brokerToNamespaceToBundleRange = Maps.newHashMap();
        brokers.add("broker-0");
        brokers.add("broker-1");
        brokers.add("broker-2");

        String assignedNamespace = namespace + "0" + bundle;

        // all brokers available so, candidate will be all 3 brokers
        candidate.addAll(brokers);
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, brokers,
                brokerToNamespaceToBundleRange, null);
        assertEquals(brokers.size(), 3);

        // add ns-0 to broker-0
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "broker-0", namespace + "0", assignedNamespace);
        candidate.addAll(brokers);
        assignedNamespace = namespace + "1" + bundle;
        // available brokers for ns-1 => broker-1, broker-2
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, candidate,
                brokerToNamespaceToBundleRange, null);
        assertEquals(candidate.size(), 2);
        assertTrue(candidate.contains("broker-1"));
        assertTrue(candidate.contains("broker-2"));

        // add ns-1 to broker-1
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "broker-1", namespace + "1", assignedNamespace);
        candidate.addAll(brokers);
        // available brokers for ns-2 => broker-2
        assignedNamespace = namespace + "2" + bundle;
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, candidate,
                brokerToNamespaceToBundleRange, null);
        assertEquals(candidate.size(), 1);
        assertTrue(candidate.contains("broker-2"));

        // add ns-2 to broker-2
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "broker-2", namespace + "2", assignedNamespace);
        candidate.addAll(brokers);
        // available brokers for ns-3 => broker-0, broker-1, broker-2
        assignedNamespace = namespace + "3" + bundle;
        LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar1, assignedNamespace, candidate,
                brokerToNamespaceToBundleRange, null);
        assertEquals(candidate.size(), 3);
    }

    private void selectBrokerForNamespace(Map<String, Map<String, Set<String>>> brokerToNamespaceToBundleRange,
            String broker, String namespace, String assignedBundleName) {
        Map<String, Set<String>> nsToBundleMap = Maps.newHashMap();
        nsToBundleMap.put(namespace, Sets.newHashSet(assignedBundleName));
        brokerToNamespaceToBundleRange.put(broker, nsToBundleMap);
    }

    /**
     * It verifies anti-affinity with failure domain enabled with 2 brokers.
     *
     * <pre>
     * 1. Register brokers to domain: domain-1: broker1 & domain-2: broker2
     * 2. Load-Manager receives a watch and updates brokerToDomain cache with new domain data
     * 3. Create two namespace with anti-affinity
     * 4. Load-manager selects broker for each namespace such that from different domains
     *
     * </pre>
     *
     * @throws Exception
     */
    @Test
    public void testBrokerSelectionForAntiAffinityGroup() throws Exception {

        final String broker1 = primaryHost;
        final String broker2 = secondaryHost;
        final String cluster = pulsar1.getConfiguration().getClusterName();
        final String property = "prop";
        final String namespace1 = property + "/" + cluster + "/ns1";
        final String namespace2 = property + "/" + cluster + "/ns2";
        final String namespaceAntiAffinityGroup = "group";
        FailureDomain domain = new FailureDomain();
        domain.brokers = Sets.newHashSet(broker1);
        admin1.clusters().createFailureDomain(cluster, "domain1", domain);
        domain.brokers = Sets.newHashSet(broker2);
        admin1.clusters().createFailureDomain(cluster, "domain1", domain);
        admin1.tenants().createTenant(property, new TenantInfo(null, Sets.newHashSet(cluster)));
        admin1.namespaces().createNamespace(namespace1);
        admin1.namespaces().createNamespace(namespace2);
        admin1.namespaces().setNamespaceAntiAffinityGroup(namespace1, namespaceAntiAffinityGroup);
        admin1.namespaces().setNamespaceAntiAffinityGroup(namespace2, namespaceAntiAffinityGroup);

        // validate strategically if brokerToDomainCache updated
        for (int i = 0; i < 5; i++) {
            if (!isLoadManagerUpdatedDomainCache(primaryLoadManager)
                    || !isLoadManagerUpdatedDomainCache(secondaryLoadManager) || i != 4) {
                Thread.sleep(200);
            }
        }
        assertTrue(isLoadManagerUpdatedDomainCache(primaryLoadManager));
        assertTrue(isLoadManagerUpdatedDomainCache(secondaryLoadManager));

        ServiceUnitId serviceUnit = makeBundle(property, cluster, "ns1");
        String selectedBroker1 = primaryLoadManager.selectBrokerForAssignment(serviceUnit).get();

        serviceUnit = makeBundle(property, cluster, "ns2");
        String selectedBroker2 = primaryLoadManager.selectBrokerForAssignment(serviceUnit).get();

        assertNotEquals(selectedBroker1, selectedBroker2);

    }

    /**
     * It verifies that load-shedding task should unload namespace only if there is a broker available which doesn't
     * cause uneven anti-affinitiy namespace distribution.
     *
     * <pre>
     * 1. broker1 owns ns-0 => broker1 can unload ns-0
     * 1. broker2 owns ns-1 => broker1 can unload ns-0
     * 1. broker3 owns ns-2 => broker1 can't unload ns-0 as all brokers have same no NS
     * </pre>
     *
     * @throws Exception
     */
    @Test
    public void testLoadSheddingUtilWithAntiAffinityNamespace() throws Exception {

        final String namespace = "my-tenant/use/my-ns";
        final int totalNamespaces = 5;
        final String namespaceAntiAffinityGroup = "my-antiaffinity";
        final String bundle = "/0x00000000_0xffffffff";

        admin1.tenants().createTenant("my-tenant",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("use")));

        for (int i = 0; i < totalNamespaces; i++) {
            final String ns = namespace + i;
            admin1.namespaces().createNamespace(ns);
            admin1.namespaces().setNamespaceAntiAffinityGroup(ns, namespaceAntiAffinityGroup);
        }

        Set<String> brokers = Sets.newHashSet();
        Set<String> candidate = Sets.newHashSet();
        Map<String, Map<String, Set<String>>> brokerToNamespaceToBundleRange = Maps.newHashMap();
        brokers.add("broker-0");
        brokers.add("broker-1");
        brokers.add("broker-2");

        String assignedNamespace = namespace + "0" + bundle;

        // all brokers available so, candidate will be all 3 brokers
        candidate.addAll(brokers);
        // add ns-0 to broker-0
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "broker-0", namespace + "0", assignedNamespace);
        String currentBroker = "broker-0";
        boolean shouldUnload = LoadManagerShared.shouldAntiAffinityNamespaceUnload(namespace + "0", bundle,
                currentBroker, pulsar1, brokerToNamespaceToBundleRange, candidate);
        assertTrue(shouldUnload);
        // add ns-1 to broker-1
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "broker-1", namespace + "1", assignedNamespace);
        shouldUnload = LoadManagerShared.shouldAntiAffinityNamespaceUnload(namespace + "0", bundle, currentBroker,
                pulsar1, brokerToNamespaceToBundleRange, candidate);
        assertTrue(shouldUnload);
        // add ns-2 to broker-2
        selectBrokerForNamespace(brokerToNamespaceToBundleRange, "broker-2", namespace + "2", assignedNamespace);
        shouldUnload = LoadManagerShared.shouldAntiAffinityNamespaceUnload(namespace + "0", bundle, currentBroker,
                pulsar1, brokerToNamespaceToBundleRange, candidate);
        assertFalse(shouldUnload);

    }

    /**
     * It verifies that load-manager::shouldAntiAffinityNamespaceUnload checks that unloading should only happen if all
     * brokers have same number of anti-affinity namespaces
     *
     * @throws Exception
     */
    @Test
    public void testLoadSheddingWithAntiAffinityNamespace() throws Exception {

        final String namespace = "my-tenant/use/my-ns";
        final int totalNamespaces = 5;
        final String namespaceAntiAffinityGroup = "my-antiaffinity";
        final String bundle = "0x00000000_0xffffffff";

        admin1.tenants().createTenant("my-tenant",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("use")));

        for (int i = 0; i < totalNamespaces; i++) {
            final String ns = namespace + i;
            admin1.namespaces().createNamespace(ns);
            admin1.namespaces().setNamespaceAntiAffinityGroup(ns, namespaceAntiAffinityGroup);
        }

        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsar1.getWebServiceAddress()).build();
        Producer<byte[]> producer = pulsarClient.newProducer().topic("persistent://" + namespace + "0/my-topic1")
                .create();
        ModularLoadManagerImpl loadManager = (ModularLoadManagerImpl) ((ModularLoadManagerWrapper) pulsar1
                .getLoadManager().get()).getLoadManager();

        pulsar1.getBrokerService().updateRates();
        loadManager.updateAll();

        assertTrue(loadManager.shouldAntiAffinityNamespaceUnload(namespace + "0", bundle, primaryHost));
        producer.close();
        pulsarClient.close();
    }

    private boolean isLoadManagerUpdatedDomainCache(ModularLoadManagerImpl loadManager) throws Exception {
        Field mapField = ModularLoadManagerImpl.class.getDeclaredField("brokerToFailureDomainMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) mapField.get(loadManager);
        return !map.isEmpty();
    }

    private NamespaceBundle makeBundle(final String property, final String cluster, final String namespace) {
        return nsFactory.getBundle(NamespaceName.get(property, cluster, namespace),
                Range.range(NamespaceBundles.FULL_LOWER_BOUND, BoundType.CLOSED, NamespaceBundles.FULL_UPPER_BOUND,
                        BoundType.CLOSED));
    }

}