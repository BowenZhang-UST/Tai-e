/*
 * Bamboo - A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Bamboo is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package bamboo.callgraph.cha;

import bamboo.callgraph.CallGraph;
import bamboo.callgraph.CallKind;
import bamboo.callgraph.JimpleCallGraph;
import bamboo.callgraph.JimpleCallUtils;
import bamboo.util.AnalysisException;
import soot.FastHierarchy;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.Stmt;

import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class CHACallGraphBuilder extends SceneTransformer {

    private static CallGraph<Unit, SootMethod> recentCallGraph;

    public static CallGraph<Unit, SootMethod> getRecentCallGraph() {
        return recentCallGraph;
    }

    private FastHierarchy hierarchy;

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        hierarchy = Scene.v().getOrMakeFastHierarchy();
        CallGraph<Unit, SootMethod> callGraph = build();
        recentCallGraph = callGraph;
        callGraph.forEach(System.out::println);
    }

    public CallGraph<Unit, SootMethod> build() {
        JimpleCallGraph callGraph = new JimpleCallGraph();
        callGraph.addEntryMethod(Scene.v().getMainMethod());
        buildEdges(callGraph);
        return callGraph;
    }

    private void buildEdges(JimpleCallGraph callGraph) {
        Queue<SootMethod> queue = new LinkedList<>(callGraph.getEntryMethods());
        while (!queue.isEmpty()) {
            SootMethod method = queue.remove();
            for (Unit callSite : callGraph.getCallSitesIn(method)) {
                Set<SootMethod> callees = resolveCalleesOf(callSite, callGraph);
                callees.forEach(callee -> {
                    if (!callGraph.contains(callee)) {
                        queue.add(callee);
                    }
                    callGraph.addEdge(callSite, callee, getCallKind(callSite));
                });
            }
        }
    }

    private Set<SootMethod> resolveCalleesOf(
            Unit callSite, CallGraph<Unit, SootMethod> callGraph) {
        InvokeExpr invoke = ((Stmt) callSite).getInvokeExpr();
        SootMethod method = invoke.getMethod();
        CallKind kind = getCallKind(callSite);
        switch (kind) {
            case VIRTUAL:
                return hierarchy.resolveAbstractDispatch(method.getDeclaringClass(), method);
            case SPECIAL:
                return Collections.singleton(hierarchy.resolveSpecialDispatch(
                        (SpecialInvokeExpr) invoke,
                        callGraph.getContainerMethodOf(callSite)));
            case STATIC:
                return Collections.singleton(method);
            default:
                throw new AnalysisException("Unknown invocation expression: " + invoke);
        }
    }

    private CallKind getCallKind(Unit callSite) {
        InvokeExpr invoke = ((Stmt) callSite).getInvokeExpr();
        return JimpleCallUtils.getCallKind(invoke);
    }

    private SootMethod dispatch(SootClass cls, SootMethod method) {
        SootMethod target = null;
        String subSig = method.getSubSignature();
        do {
            SootMethod m = cls.getMethodUnsafe(subSig);
            if (m != null && m.isConcrete()) {
                target = m;
                break;
            }
            cls = cls.getSuperclass();
        } while (cls != null);
        return target;
    }

    private Set<SootMethod> resolveCalleesOf(Unit callSite) {
        InvokeExpr invoke = ((Stmt) callSite).getInvokeExpr();
        SootMethod method = invoke.getMethod();
        CallKind kind = getCallKind(callSite);
        switch (kind) {
            case VIRTUAL: {
                SootClass cls = method.getDeclaringClass();
                hierarchy.getSubclassesOf(cls);
                hierarchy.getAllImplementersOfInterface(cls);
            }
            case SPECIAL:
            case STATIC:
                return Collections.singleton(method);
            default:
                throw new AnalysisException("Unknown invocation expression: " + invoke);
        }
    }
}