/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.phases;

import org.graalvm.compiler.core.common.type.AbstractObjectStamp;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.TypeReference;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.InvokeWithExceptionNode;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.spi.LimitedValueProxy;
import org.graalvm.compiler.phases.Phase;

import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.nodes.AssertStampNode;
import com.oracle.svm.hosted.nodes.AssertTypeStateNode;

import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.JavaTypeProfile.ProfiledType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.TriState;

/**
 * Strengthens the stamp of nodes based on the static analysis result. The canonicalizer then
 * propagates the improved stamp through the method and, for example, removes null-checks when the
 * new stamp states that a value is non-null.
 */
public class StrengthenStampsPhase extends Phase {

    private final boolean insertTypeChecks;

    public StrengthenStampsPhase(boolean insertTypeChecks) {
        this.insertTypeChecks = insertTypeChecks;
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (Node n : graph.getNodes()) {
            if (n instanceof ValueNode && !(n instanceof LimitedValueProxy) && !(n instanceof PhiNode)) {
                /*
                 * The stamp of proxy nodes and phi nodes is inferred automatically, so we do not
                 * need to improve them.
                 */
                ValueNode node = (ValueNode) n;
                Stamp newStamp = strengthen(node.stamp(NodeView.DEFAULT));
                if (newStamp != null) {
                    node.setStamp(newStamp);
                }
            }

            if (n instanceof LoadFieldNode) {
                LoadFieldNode node = (LoadFieldNode) n;
                updateStamp(node, toHosted(node.field()).getFieldTypeProfile());

            } else if (n instanceof InstanceOfNode) {
                InstanceOfNode node = (InstanceOfNode) n;
                ObjectStamp newStamp = (ObjectStamp) strengthen(node.getCheckedStamp());
                if (newStamp != null) {
                    node.strengthenCheckedStamp(newStamp);
                }

            } else if (n instanceof PiNode) {
                PiNode node = (PiNode) n;
                Stamp newStamp = strengthen(node.piStamp());
                if (newStamp != null) {
                    node.strengthenPiStamp(newStamp);
                }
            }
        }
    }

    private Stamp strengthen(Stamp s) {
        if (!(s instanceof AbstractObjectStamp)) {
            return null;
        }

        AbstractObjectStamp stamp = (AbstractObjectStamp) s;
        HostedType originalType = toHosted(stamp.type());
        if (originalType == null) {
            return null;
        }

        HostedType strengthenType = originalType.getStrengthenStampType();
        if (originalType.equals(strengthenType)) {
            /* Nothing to strengthen. */
            return null;
        }

        Stamp newStamp;
        if (strengthenType == null) {
            if (stamp.nonNull()) {
                /* We must be in dead code. */
                newStamp = StampFactory.empty(JavaKind.Object);
            } else {
                /* The type its subtypes are not instantiated, the only possible value is null. */
                newStamp = StampFactory.alwaysNull();
            }

        } else {
            if (stamp.isExactType()) {
                /* We must be in dead code. */
                newStamp = StampFactory.empty(JavaKind.Object);
            } else {
                TypeReference typeRef = TypeReference.createTrustedWithoutAssumptions(toTarget(strengthenType));
                newStamp = StampFactory.object(typeRef, stamp.nonNull());
            }
        }
        return newStamp;
    }

    private void updateStamp(ValueNode node, JavaTypeProfile typeProfile) {
        if (node.getStackKind() != JavaKind.Object) {
            return;
        }

        if (typeProfile != null) {
            Stamp newStamp = strengthenStamp(node, typeProfile);
            if (!newStamp.equals(node.stamp(NodeView.DEFAULT))) {
                node.getDebug().log("STAMP UPDATE  method %s  node %s  old %s  new %s\n", node.graph().method().format("%H.%n(%p)"), node, node.stamp(NodeView.DEFAULT), newStamp);
                node.setStamp(newStamp);
            }

        }
        if (insertTypeChecks) {
            /*
             * Checking all exact types of the type state is only feasible for type states with few
             * exact types, because every type requires an explicit comparison. For big type states
             * we fall back to instanceof-checking based on the stamp.
             */
            if (typeProfile != null && typeProfile.getTypes().length < 10) {
                AssertTypeStateNode.create(node, typeProfile);
            } else {
                AssertStampNode.create(node);
            }
        }
    }

    private Stamp strengthenStamp(ValueNode node, JavaTypeProfile typeProfile) {
        ObjectStamp oldStamp = (ObjectStamp) node.stamp(NodeView.DEFAULT);
        HostedType oldType = toHosted(oldStamp.type());

        if (oldStamp.alwaysNull()) {
            /* We cannot make that more precise. */
            return oldStamp;
        }

        boolean nonNull = oldStamp.nonNull() || typeProfile.getNullSeen() == TriState.FALSE;
        ProfiledType[] exactTypes = typeProfile.getTypes();

        if (exactTypes.length == 1) {
            ResolvedJavaType exactType = exactTypes[0].getType();

            assert oldType == null || oldType.isAssignableFrom(exactType);
            if (!oldStamp.isExactType() || !exactType.equals(oldType) || nonNull != oldStamp.nonNull()) {
                TypeReference typeRef = TypeReference.createExactTrusted(toTarget(exactType));
                return nonNull ? StampFactory.objectNonNull(typeRef) : StampFactory.object(typeRef);
            } else {
                return oldStamp;
            }

        }

        if (exactTypes.length == 0) {
            if (!nonNull) {
                return StampFactory.alwaysNull();
            } else {
                /*
                 * The code after the node is unreachable. We just insert a always-failing guard
                 * after the node and let dead code elimination remove everything after the node.
                 */
                StructuredGraph graph = node.graph();
                FixedWithNextNode insertionPoint;
                if (node instanceof ParameterNode) {
                    /* The whole method is unreachable. */
                    insertionPoint = graph.start();
                } else if (node instanceof InvokeWithExceptionNode) {
                    /* The invoked method never returns normally (but can throw an exception). */
                    insertionPoint = ((InvokeWithExceptionNode) node).next();
                } else {
                    insertionPoint = (FixedWithNextNode) node;
                }
                graph.addAfterFixed(insertionPoint, graph.add(new FixedGuardNode(LogicConstantNode.forBoolean(true, graph), DeoptimizationReason.UnreachedCode, DeoptimizationAction.None, true)));
                return oldStamp;
            }
        }

        ResolvedJavaType baseType;
        if (oldStamp.isExactType()) {
            /* Base type cannot be more precise. */
            baseType = oldType;
        } else {
            assert exactTypes.length > 1;
            assert oldType == null || oldType.isAssignableFrom(exactTypes[0].getType());
            baseType = exactTypes[0].getType();
            for (int i = 1; i < exactTypes.length; i++) {
                assert oldType == null || oldType.isAssignableFrom(exactTypes[i].getType());
                baseType = baseType.findLeastCommonAncestor(exactTypes[i].getType());
            }

            if (oldType != null && !oldType.isAssignableFrom(baseType)) {
                /*
                 * When the original stamp is an interface type, we do not want to weaken that type
                 * with the common base class of all implementation types (which could even be
                 * java.lang.Object).
                 */
                baseType = oldType;
            }
        }

        if (!baseType.equals(oldType) || nonNull != oldStamp.nonNull()) {
            TypeReference typeRef = TypeReference.createTrustedWithoutAssumptions(toTarget(baseType));
            return nonNull ? StampFactory.objectNonNull(typeRef) : StampFactory.object(typeRef);
        }
        return oldStamp;
    }

    protected HostedType toHosted(ResolvedJavaType type) {
        return (HostedType) type;
    }

    protected HostedMethod toHosted(ResolvedJavaMethod method) {
        return (HostedMethod) method;
    }

    protected HostedField toHosted(ResolvedJavaField field) {
        return (HostedField) field;
    }

    protected ResolvedJavaType toTarget(ResolvedJavaType type) {
        return type;
    }
}
