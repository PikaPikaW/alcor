/*
Copyright 2019 The Alcor Authors.

Licensed under the Apache License, Version 2.0 (the "License");
        you may not use this file except in compliance with the License.
        You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

        Unless required by applicable law or agreed to in writing, software
        distributed under the License is distributed on an "AS IS" BASIS,
        WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
        See the License for the specific language governing permissions and
        limitations under the License.
*/
package com.futurewei.alcor.networkaclmanager.repo;

import com.futurewei.alcor.common.db.CacheException;
import com.futurewei.alcor.common.db.CacheFactory;
import com.futurewei.alcor.common.db.ICache;
import com.futurewei.alcor.common.db.Transaction;
import com.futurewei.alcor.web.entity.networkacl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@ComponentScan(value="com.futurewei.alcor.common.db")
public class NetworkAclRepository {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkAclRepository.class);

    private ICache<String, NetworkAclEntity> networkAclCache;
    private ICache<String, NetworkAclRuleEntity> networkAclRuleCache;

    public NetworkAclRepository() {

    }

    public NetworkAclRepository(ICache<String, NetworkAclEntity> networkAclCache, ICache<String, NetworkAclRuleEntity> networkAclRuleCache) {
        this.networkAclCache = networkAclCache;
        this.networkAclRuleCache = networkAclRuleCache;
    }

    @Autowired
    public NetworkAclRepository(CacheFactory cacheFactory) {
        networkAclCache = cacheFactory.getCache(NetworkAclEntity.class);
        networkAclRuleCache = cacheFactory.getCache(NetworkAclRuleEntity.class);
    }

    @PostConstruct
    private void init() throws Exception {
        LOG.info("NetworkAclRepository init done");
    }

    private NetworkAclRuleEntity buildDefaultNetworkAclRule(String ipPrefix, String direction) {
        NetworkAclRuleEntity networkAclRuleEntity = new NetworkAclRuleEntity();
        networkAclRuleEntity.setId(UUID.randomUUID().toString());
        networkAclRuleEntity.setNumber(NetworkAclRuleEntity.NUMBER_MAX_VALUE);
        networkAclRuleEntity.setIpPrefix(ipPrefix);
        networkAclRuleEntity.setProtocol(Protocol.ALL.getProtocol());
        networkAclRuleEntity.setDirection(direction);
        networkAclRuleEntity.setAction(Action.DENY.getAction());

        return networkAclRuleEntity;
    }

    private synchronized void createDefaultNetworkAclRules() throws Exception {
        try (Transaction tx = networkAclCache.getTransaction().start()) {
            List<NetworkAclRuleEntity> networkAclRules = getNetworkAclRulesByNumber(NetworkAclRuleEntity.NUMBER_MAX_VALUE);

            if (networkAclRules == null) {
                List<String> ipPrefixes = Arrays.asList(NetworkAclRuleEntity.DEFAULT_IPV4_PREFIX,
                        NetworkAclRuleEntity.DEFAULT_IPV6_PREFIX);
                Map<String, NetworkAclRuleEntity> networkAclRuleEntityMap = new HashMap<>();

                for (String ipPrefix: ipPrefixes) {
                    List<Direction> directions = Arrays.asList(Direction.INGRESS,
                            Direction.EGRESS);

                    for (Direction direction : directions) {
                        NetworkAclRuleEntity networkAclRuleEntity =
                                buildDefaultNetworkAclRule(ipPrefix, direction.getDirection());
                        networkAclRuleEntityMap.put(networkAclRuleEntity.getId(), networkAclRuleEntity);
                    }
                }

                networkAclRuleCache.putAll(networkAclRuleEntityMap);
            }

            tx.commit();
        }
    }

    public List<NetworkAclRuleEntity> getDefaultNetworkAclRules() throws Exception {
        List<NetworkAclRuleEntity> networkAclRules = getNetworkAclRulesByNumber(NetworkAclRuleEntity.NUMBER_MAX_VALUE);
        if (networkAclRules.isEmpty()) {
            try {
                createDefaultNetworkAclRules();
            } catch (Exception e) {
                LOG.warn("Create default Network ACL rules failed");
            }

            networkAclRules = getNetworkAclRulesByNumber(NetworkAclRuleEntity.NUMBER_MAX_VALUE);
        }

        return networkAclRules;
    }

    public void addNetworkAcl(NetworkAclEntity networkAclEntity) throws CacheException {
        LOG.debug("Add Network ACL:{}", networkAclEntity);

        networkAclCache.put(networkAclEntity.getId(), networkAclEntity);
    }

    public void addNetworkAclBulk(List<NetworkAclEntity> networkAclEntities) throws CacheException {
        LOG.debug("Add Network ACL Bulk:{}", networkAclEntities);

        Map<String, NetworkAclEntity> networkAclEntityMap = networkAclEntities.stream()
                .collect(Collectors.toMap(NetworkAclEntity::getId, Function.identity()));
        networkAclCache.putAll(networkAclEntityMap);
    }

    public void deleteNetworkAcl(String networkAclId) throws CacheException {
        LOG.debug("Delete Network ACL, Network ACL Id:{}", networkAclId);
        networkAclCache.remove(networkAclId);
    }

    public NetworkAclEntity getNetworkAcl(String networkAclId) throws Exception {
        NetworkAclEntity networkAclEntity = networkAclCache.get(networkAclId);
        if (networkAclEntity == null) {
            return null;
        }

        List<NetworkAclRuleEntity> networkAclRules =
                getNetworkAclRulesByNetworkAclId(networkAclEntity.getId());
        if (networkAclRules == null) {
            networkAclRules = getDefaultNetworkAclRules();
        }

        networkAclEntity.setNetworkAclRuleEntities(networkAclRules);

        return networkAclEntity;
    }

    public Map<String, NetworkAclEntity> getAllNetworkAcls() throws Exception {
        Map<String, NetworkAclEntity> networkAclEntityMap = networkAclCache.getAll();
        if (networkAclEntityMap == null) {
            return null;
        }

        for (Map.Entry<String, NetworkAclEntity> entry: networkAclEntityMap.entrySet()) {
            List<NetworkAclRuleEntity> networkAclRules = getNetworkAclRulesByNetworkAclId(entry.getKey());
            if (networkAclRules == null) {
                networkAclRules = getDefaultNetworkAclRules();
            }

            entry.getValue().setNetworkAclRuleEntities(networkAclRules);
        }

        return networkAclEntityMap;
    }

    public void addNetworkAclRule(NetworkAclRuleEntity networkAclRuleEntity) throws Exception {
        LOG.debug("Add Network ACL Rule:{}", networkAclRuleEntity);

        networkAclRuleCache.put(networkAclRuleEntity.getId(), networkAclRuleEntity);
    }

    public void addNetworkAclRuleBulk(List<NetworkAclRuleEntity> networkAclRuleEntities) throws Exception {
        LOG.debug("Add Network ACL Rule Bulk:{}", networkAclRuleEntities);

        Map<String, NetworkAclRuleEntity> networkAclRuleEntityMap = networkAclRuleEntities.stream()
                .collect(Collectors.toMap(NetworkAclRuleEntity::getId, Function.identity()));
        networkAclRuleCache.putAll(networkAclRuleEntityMap);
    }

    public void deleteNetworkAclRule(String networkAclRuleId) throws CacheException {
        LOG.debug("Delete Network ACL Rule, Network ACL Rule Id:{}", networkAclRuleId);
        networkAclRuleCache.remove(networkAclRuleId);
    }

    public NetworkAclRuleEntity getNetworkAclRule(String networkAclRuleId) throws CacheException {
        return networkAclRuleCache.get(networkAclRuleId);
    }

    public Map<String, NetworkAclRuleEntity> getAllNetworkAclRules() throws CacheException {
        return networkAclRuleCache.getAll();
    }

    public List<NetworkAclRuleEntity> getNetworkAclRulesByNumber(Integer number) throws CacheException {
        List<NetworkAclRuleEntity> networkAclRuleEntities = new ArrayList<>();
        //FIXME: not support yet
        return networkAclRuleEntities;
    }

    public List<NetworkAclRuleEntity> getNetworkAclRulesByNetworkAclId(String networkAclId) throws CacheException {
        List<NetworkAclRuleEntity> networkAclRuleEntities = new ArrayList<>();
        //FIXME: not support yet,
        return networkAclRuleEntities;
    }
}
