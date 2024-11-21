package prism.jellyfish.util;

import pascal.taie.language.classes.*;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Stream;

public class JavaUtil {
    public static List<String> getCallableSignatures(ClassHierarchy ch, JClass jclass) {
        Stream<JMethod> methods = Reflections.getMethods(jclass);
        if (jclass.isInterface()) {
            methods = methods.filter(m -> m.getDeclaringClass().getName() != "java.lang.Object");
        }
        return methods.map(JMethod::getSubsignature).map(Subsignature::toString).toList();
    }

    public static List<JMethod> getCallableMethodTypes(ClassHierarchy ch, JClass jclass) {
        Stream<JMethod> methods = Reflections.getMethods(jclass);
        if (jclass.isInterface()) {
            methods = methods.filter(m -> m.getDeclaringClass().getName() != "java.lang.Object");
        }
        return methods.toList();
    }

    public static List<JMethod> getOwnedMethods(ClassHierarchy ch, JClass jclass) {
        List<JMethod> ownedMethods;
        if (jclass.isAbstract()) {
            ownedMethods = List.of();
        } else {
            ownedMethods = Reflections.getMethods(jclass).map(JMethod::getRef).map(ref -> ch.dispatch(jclass, ref)).filter(m -> m != null).toList();
        }
        return ownedMethods;
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
