package prism.jellyfish.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Sets;

import java.util.*;
import java.util.stream.Stream;

public class JavaUtil {
    private static final Logger logger = LogManager.getLogger(JavaUtil.class);
    private static final AssertUtil as = new AssertUtil(logger);

    private static Stream<JMethod> getCallablesImpl(JClass jclass) {
        List<JMethod> methods = new ArrayList<>();
        Set<Subsignature> subSignatures = Sets.newHybridSet();
        Queue<JClass> worklist = new LinkedList<>();
        worklist.add(jclass);
        while (!worklist.isEmpty()) {
            JClass cur = worklist.poll();
            cur.getDeclaredMethods()
                    .stream()
                    .filter(JMethod::isPublic)
                    .filter(m -> !m.isConstructor() && !m.isStaticInitializer())
                    .filter(m -> !subSignatures.contains(m.getSubsignature()))
                    .forEach(m -> {
                        methods.add(m);
                        subSignatures.add(m.getSubsignature());
                    });
            if (!cur.isInterface() && cur.getSuperClass() != null) {
                worklist.add(cur.getSuperClass());
            }
            cur.getInterfaces()
                    .stream()
                    .forEach(i -> worklist.add(i));
        }
        return methods.stream();
    }

    public static List<JMethod> getCallableMethodTypes(JClass jclass) {
        return getCallablesImpl(jclass).toList();
    }

    public static List<String> getCallableSignatures(JClass jclass) {
        return getCallablesImpl(jclass).map(JMethod::getSubsignature).map(Subsignature::toString).toList();
    }

    public static List<JMethod> getOwnedMethods(ClassHierarchy ch, JClass jclass) {
        List<JMethod> ownedMethods;
        if (jclass.isAbstract()) {
            ownedMethods = List.of();
        } else {
            ownedMethods = getCallablesImpl(jclass).map(JMethod::getRef).map(ref -> ch.dispatch(jclass, ref)).filter(m -> m != null).toList();
        }
        return ownedMethods;
    }

    public static JClass getJClassOfBaseVar(Var var, ClassHierarchy ch) {
        /*
         * Resolve the JClass of the base var 「xxx」 used in 「xxx.yyy」
         */
        Type type = var.getType();
        if (type instanceof ArrayType) {
            JClass objClass = ch.getClass("java.lang.Object");
            type = objClass.getType();
        }
        as.assertTrue(type instanceof ClassType, "The base var should be ClassType. Got {}. ", type);
        return ((ClassType) type).getJClass();
    }

    public static List<JClass> getTraceBetween(ClassHierarchy ch, JClass source, JClass target) {
        Queue<List<JClass>> worklist = new LinkedList<>();
        List<JClass> first = new ArrayList<>();
        first.add(source);
        worklist.add(first);
        List<JClass> res = null;
        while (!worklist.isEmpty()) {
            List<JClass> trace = worklist.poll();
            JClass last = trace.get(trace.size() - 1);
            if (last == target) {
                res = trace;
                break;
            }
            if (last.getSuperClass() != null) {
                List<JClass> trace2 = new ArrayList<>(trace);
                trace2.add(last.getSuperClass());
                worklist.add(trace2);
            }
            for (JClass i : last.getInterfaces()) {
                List<JClass> trace2 = new ArrayList<>(trace);
                trace2.add(i);
                worklist.add(trace2);
            }
        }
        worklist.clear();
        return res;

    }


}
