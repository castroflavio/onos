/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.driver.pipeline;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import org.onlab.osgi.ServiceDirectory;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onlab.util.KryoNamespace;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.behaviour.NextGroup;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.behaviour.PipelinerContext;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveStore;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.group.DefaultGroupBucket;
import org.onosproject.net.group.DefaultGroupDescription;
import org.onosproject.net.group.DefaultGroupKey;
import org.onosproject.net.group.Group;
import org.onosproject.net.group.GroupBucket;
import org.onosproject.net.group.GroupBuckets;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupEvent;
import org.onosproject.net.group.GroupKey;
import org.onosproject.net.group.GroupListener;
import org.onosproject.net.group.GroupService;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.onlab.util.Tools.groupedThreads;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * OpenvSwitch emulation of the Corsa pipeline handler.
 */
public class OVSCorsaPipeline extends AbstractHandlerBehaviour implements Pipeliner {

    private static final int CONTROLLER_PRIORITY = 255;
    private static final int DROP_PRIORITY = 0;
    private static final int HIGHEST_PRIORITY = 0xffff;

    private final Logger log = getLogger(getClass());

    private ServiceDirectory serviceDirectory;
    private FlowRuleService flowRuleService;
    private CoreService coreService;
    private GroupService groupService;
    private FlowObjectiveStore flowObjectiveStore;
    private DeviceId deviceId;
    private ApplicationId appId;

    private KryoNamespace appKryo = new KryoNamespace.Builder()
            .register(GroupKey.class)
            .register(DefaultGroupKey.class)
            .register(CorsaGroup.class)
            .register(byte[].class)
            .build();

    private Cache<GroupKey, NextObjective> pendingGroups;

    private ScheduledExecutorService groupChecker =
            Executors.newScheduledThreadPool(2, groupedThreads("onos/pipeliner",
                                                               "ovs-corsa-%d"));

    @Override
    public void init(DeviceId deviceId, PipelinerContext context) {
        this.serviceDirectory = context.directory();
        this.deviceId = deviceId;

        pendingGroups = CacheBuilder.newBuilder()
                .expireAfterWrite(20, TimeUnit.SECONDS)
                .removalListener((RemovalNotification<GroupKey, NextObjective> notification) -> {
                    if (notification.getCause() == RemovalCause.EXPIRED) {
                        fail(notification.getValue(), ObjectiveError.GROUPINSTALLATIONFAILED);
                    }
                }).build();

        groupChecker.scheduleAtFixedRate(new GroupChecker(), 0, 500, TimeUnit.MILLISECONDS);

        coreService = serviceDirectory.get(CoreService.class);
        flowRuleService = serviceDirectory.get(FlowRuleService.class);
        groupService = serviceDirectory.get(GroupService.class);
        flowObjectiveStore = context.store();

        groupService.addListener(new InnerGroupListener());

        appId = coreService.registerApplication(
                "org.onosproject.driver.OVSCorsaPipeline");

        pushDefaultRules();
    }

    @Override
    public void filter(FilteringObjective filteringObjective) {
        if (filteringObjective.type() == FilteringObjective.Type.PERMIT) {
            processFilter(filteringObjective,
                          filteringObjective.op() == Objective.Operation.ADD,
                          filteringObjective.appId());
        } else {
            fail(filteringObjective, ObjectiveError.UNSUPPORTED);
        }
    }

    @Override
    public void forward(ForwardingObjective fwd) {
        Collection<FlowRule> rules;
        FlowRuleOperations.Builder flowBuilder = FlowRuleOperations.builder();

        rules = processForward(fwd);
        switch (fwd.op()) {
            case ADD:
                rules.stream()
                        .filter(rule -> rule != null)
                        .forEach(flowBuilder::add);
                break;
            case REMOVE:
                rules.stream()
                        .filter(rule -> rule != null)
                        .forEach(flowBuilder::remove);
                break;
            default:
                fail(fwd, ObjectiveError.UNKNOWN);
                log.warn("Unknown forwarding type {}", fwd.op());
        }


        flowRuleService.apply(flowBuilder.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                pass(fwd);
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                fail(fwd, ObjectiveError.FLOWINSTALLATIONFAILED);
            }
        }));

    }

    @Override
    public void next(NextObjective nextObjective) {
        switch (nextObjective.type()) {
            case SIMPLE:
                Collection<TrafficTreatment> treatments = nextObjective.next();
                if (treatments.size() == 1) {
                    TrafficTreatment treatment = treatments.iterator().next();
                    GroupBucket bucket =
                            DefaultGroupBucket.createIndirectGroupBucket(treatment);
                    final GroupKey key = new DefaultGroupKey(appKryo.serialize(nextObjective.id()));
                    GroupDescription groupDescription
                            = new DefaultGroupDescription(deviceId,
                                    GroupDescription.Type.INDIRECT,
                                    new GroupBuckets(Collections
                                                             .singletonList(bucket)),
                                    key,
                                    nextObjective.appId());
                    groupService.addGroup(groupDescription);
                    pendingGroups.put(key, nextObjective);
                }
                break;
            case HASHED:
            case BROADCAST:
            case FAILOVER:
                fail(nextObjective, ObjectiveError.UNSUPPORTED);
                log.warn("Unsupported next objective type {}", nextObjective.type());
                break;
            default:
                fail(nextObjective, ObjectiveError.UNKNOWN);
                log.warn("Unknown next objective type {}", nextObjective.type());
        }

    }

    private Collection<FlowRule> processForward(ForwardingObjective fwd) {
        switch (fwd.flag()) {
            case SPECIFIC:
                return processSpecific(fwd);
            case VERSATILE:
                return processVersatile(fwd);
            default:
                fail(fwd, ObjectiveError.UNKNOWN);
                log.warn("Unknown forwarding flag {}", fwd.flag());
        }
        return Collections.emptySet();
    }

    private Collection<FlowRule> processVersatile(ForwardingObjective fwd) {
        fail(fwd, ObjectiveError.UNSUPPORTED);
        return Collections.emptySet();
    }

    private Collection<FlowRule> processSpecific(ForwardingObjective fwd) {
        log.warn("Processing specific");
        TrafficSelector selector = fwd.selector();
        Criteria.EthTypeCriterion ethType =
                (Criteria.EthTypeCriterion) selector.getCriterion(Criterion.Type.ETH_TYPE);
        if (ethType == null || ethType.ethType() != Ethernet.TYPE_IPV4) {
            fail(fwd, ObjectiveError.UNSUPPORTED);
            return Collections.emptySet();
        }

        TrafficSelector filteredSelector =
                DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPDst(
                                ((Criteria.IPCriterion)
                                        selector.getCriterion(Criterion.Type.IPV4_DST)).ip())
                        .build();

        NextGroup next = flowObjectiveStore.getNextGroup(fwd.nextId());

        GroupKey key = appKryo.deserialize(next.data());

        Group group = groupService.getGroup(deviceId, key);

        if (group == null) {
            log.warn("The group left!");
            fail(fwd, ObjectiveError.GROUPMISSING);
            return Collections.emptySet();
        }

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .group(group.id())
                .build();

        return Collections.singletonList(
                new DefaultFlowRule(deviceId, filteredSelector, treatment,
                                   fwd.priority(), fwd.appId(), 0, fwd.permanent(),
                                   FlowRule.Type.IP));

    }

    private void processFilter(FilteringObjective filt, boolean install,
                                             ApplicationId applicationId) {
        // This driver only processes filtering criteria defined with switch
        // ports as the key
        Criteria.PortCriterion p;
        if (!filt.key().equals(Criteria.dummy()) &&
                filt.key().type() == Criterion.Type.IN_PORT) {
            p = (Criteria.PortCriterion) filt.key();
        } else {
            log.warn("No key defined in filtering objective from app: {}. Not"
                    + "processing filtering objective", applicationId);
            fail(filt, ObjectiveError.UNKNOWN);
            return;
        }
        // convert filtering conditions for switch-intfs into flowrules
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        for (Criterion c : filt.conditions()) {
            if (c.type() == Criterion.Type.ETH_DST) {
                Criteria.EthCriterion e = (Criteria.EthCriterion) c;
                log.debug("adding rule for MAC: {}", e.mac());
                TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
                TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
                selector.matchEthDst(e.mac());
                treatment.transition(FlowRule.Type.VLAN_MPLS);
                FlowRule rule = new DefaultFlowRule(deviceId, selector.build(),
                                                    treatment.build(),
                                                    CONTROLLER_PRIORITY, applicationId,
                                                    0, true, FlowRule.Type.FIRST);
                ops =  install ? ops.add(rule) : ops.remove(rule);
            } else if (c.type() == Criterion.Type.VLAN_VID) {
                Criteria.VlanIdCriterion v = (Criteria.VlanIdCriterion) c;
                log.debug("adding rule for VLAN: {}", v.vlanId());
                TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
                TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
                selector.matchVlanId(v.vlanId());
                selector.matchInPort(p.port());
                treatment.transition(FlowRule.Type.ETHER);
                treatment.deferred().popVlan();
                FlowRule rule = new DefaultFlowRule(deviceId, selector.build(),
                                           treatment.build(),
                                           CONTROLLER_PRIORITY, applicationId,
                                           0, true, FlowRule.Type.VLAN);
                ops = install ? ops.add(rule) : ops.remove(rule);
            } else if (c.type() == Criterion.Type.IPV4_DST) {
                Criteria.IPCriterion ip = (Criteria.IPCriterion) c;
                log.debug("adding rule for IP: {}", ip.ip());
                TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
                TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
                selector.matchEthType(Ethernet.TYPE_IPV4);
                selector.matchIPDst(ip.ip());
                treatment.transition(FlowRule.Type.ACL);
                FlowRule rule = new DefaultFlowRule(deviceId, selector.build(),
                                           treatment.build(), HIGHEST_PRIORITY, appId,
                                           0, true, FlowRule.Type.IP);
                ops = install ? ops.add(rule) : ops.remove(rule);
            } else {
                log.warn("Driver does not currently process filtering condition"
                        + " of type: {}", c.type());
                fail(filt, ObjectiveError.UNSUPPORTED);
            }
        }
        // apply filtering flow rules
        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                pass(filt);
                log.info("Provisioned default table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                fail(filt, ObjectiveError.FLOWINSTALLATIONFAILED);
                log.info("Failed to provision default table for bgp router");
            }
        }));
    }

    private void pass(Objective obj) {
        if (obj.context().isPresent()) {
            obj.context().get().onSuccess(obj);
        }
    }

    private void fail(Objective obj, ObjectiveError error) {
        if (obj.context().isPresent()) {
            obj.context().get().onError(obj, error);
        }
    }

    private void pushDefaultRules() {
        processTableZero(true);
        processTableOne(true);
        processTableTwo(true);
        processTableFour(true);
        processTableFive(true);
        processTableSix(true);
        processTableNine(true);
    }

    private void processTableZero(boolean install) {
        TrafficSelector.Builder selector;
        TrafficTreatment.Builder treatment;

        // Bcast rule
        selector = DefaultTrafficSelector.builder();
        treatment = DefaultTrafficTreatment.builder();

        selector.matchEthDst(MacAddress.BROADCAST);
        treatment.transition(FlowRule.Type.VLAN_MPLS);

        FlowRule rule = new DefaultFlowRule(deviceId, selector.build(),
                                            treatment.build(),
                                            CONTROLLER_PRIORITY, appId, 0,
                                            true, FlowRule.Type.FIRST);

        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();

        ops = install ? ops.add(rule) : ops.remove(rule);


        //Drop rule
        selector = DefaultTrafficSelector.builder();
        treatment = DefaultTrafficTreatment.builder();

        treatment.drop();

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), DROP_PRIORITY, appId,
                                   0, true, FlowRule.Type.FIRST);

        ops = install ? ops.add(rule) : ops.remove(rule);

        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Provisioned default table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info("Failed to provision default table for bgp router");
            }
        }));

    }

    private void processTableOne(boolean install) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment
                .builder();
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        FlowRule rule;

        selector.matchVlanId(VlanId.ANY);
        treatment.transition(FlowRule.Type.VLAN);

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), CONTROLLER_PRIORITY,
                                   appId, 0, true, FlowRule.Type.VLAN_MPLS);

        ops = install ? ops.add(rule) : ops.remove(rule);

        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Provisioned vlan/mpls table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info(
                        "Failed to provision vlan/mpls table for bgp router");
            }
        }));

    }

    private void processTableTwo(boolean install) {
        TrafficSelector.Builder selector;
        TrafficTreatment.Builder treatment;
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        FlowRule rule;


        //Drop rule
        selector = DefaultTrafficSelector.builder();
        treatment = DefaultTrafficTreatment.builder();

        treatment.drop();

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), DROP_PRIORITY, appId,
                                   0, true, FlowRule.Type.VLAN);

        ops = install ? ops.add(rule) : ops.remove(rule);

        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Provisioned vlan table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info("Failed to provision vlan table for bgp router");
            }
        }));
    }

    private void processTableFour(boolean install) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment
                .builder();
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        FlowRule rule;

        selector.matchEthType(Ethernet.TYPE_ARP);
        treatment.punt();

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), CONTROLLER_PRIORITY,
                                   appId, 0, true, FlowRule.Type.ETHER);

        ops = install ? ops.add(rule) : ops.remove(rule);

        selector = DefaultTrafficSelector.builder();
        treatment = DefaultTrafficTreatment.builder();

        selector.matchEthType(Ethernet.TYPE_IPV4);
        treatment.transition(FlowRule.Type.COS);

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), CONTROLLER_PRIORITY,
                                   appId, 0, true, FlowRule.Type.ETHER);

        ops = install ? ops.add(rule) : ops.remove(rule);

        //Drop rule
        selector = DefaultTrafficSelector.builder();
        treatment = DefaultTrafficTreatment.builder();

        treatment.drop();

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), DROP_PRIORITY, appId,
                                   0, true, FlowRule.Type.ETHER);

        ops = install ? ops.add(rule) : ops.remove(rule);

        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Provisioned ether table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info("Failed to provision ether table for bgp router");
            }
        }));

    }

    private void processTableFive(boolean install) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment
                .builder();
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        FlowRule rule;

        treatment.transition(FlowRule.Type.IP);

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), DROP_PRIORITY, appId,
                                   0, true, FlowRule.Type.COS);

        ops = install ? ops.add(rule) : ops.remove(rule);

        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Provisioned cos table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info("Failed to provision cos table for bgp router");
            }
        }));

    }

    private void processTableSix(boolean install) {
        TrafficSelector.Builder selector;
        TrafficTreatment.Builder treatment;
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        FlowRule rule;

        //Drop rule
        selector = DefaultTrafficSelector.builder();
        treatment = DefaultTrafficTreatment.builder();

        treatment.drop();

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), DROP_PRIORITY, appId,
                                   0, true, FlowRule.Type.IP);

        ops = install ? ops.add(rule) : ops.remove(rule);

        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Provisioned FIB table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info("Failed to provision FIB table for bgp router");
            }
        }));
    }

    private void processTableNine(boolean install) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment
                .builder();
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        FlowRule rule;

        treatment.punt();

        rule = new DefaultFlowRule(deviceId, selector.build(),
                                   treatment.build(), CONTROLLER_PRIORITY,
                                   appId, 0, true, FlowRule.Type.DEFAULT);

        ops = install ? ops.add(rule) : ops.remove(rule);

        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Provisioned Local table for bgp router");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info("Failed to provision Local table for bgp router");
            }
        }));
    }

    private class InnerGroupListener implements GroupListener {
        @Override
        public void event(GroupEvent event) {
            if (event.type() == GroupEvent.Type.GROUP_ADDED) {
                GroupKey key = event.subject().appCookie();

                NextObjective obj = pendingGroups.getIfPresent(key);
                if (obj != null) {
                    flowObjectiveStore.putNextGroup(obj.id(), new CorsaGroup(key));
                    pass(obj);
                    pendingGroups.invalidate(key);
                }
            }
        }
    }


    private class GroupChecker implements Runnable {

        @Override
        public void run() {
            Set<GroupKey> keys = pendingGroups.asMap().keySet().stream()
                    .filter(key -> groupService.getGroup(deviceId, key) != null)
                    .collect(Collectors.toSet());

            keys.stream().forEach(key -> {
                NextObjective obj = pendingGroups.getIfPresent(key);
                if (obj == null) {
                    return;
                }
                pass(obj);
                pendingGroups.invalidate(key);
                flowObjectiveStore.putNextGroup(obj.id(), new CorsaGroup(key));
            });
        }
    }

    private class CorsaGroup implements NextGroup {

        private final GroupKey key;

        public CorsaGroup(GroupKey key) {
            this.key = key;
        }

        public GroupKey key() {
            return key;
        }

        @Override
        public byte[] data() {
            return appKryo.serialize(key);
        }

    }
}