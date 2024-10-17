package prism.jellyfish.util;

import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.language.classes.*;
import java.util.ArrayList;
import java.util.List;

public class JavaUtil {
    public static List<JMethod> getOwnedMethods(JClass jclass, ClassHierarchy ch) {
        /*
        C c = new C();
        Determine the set of concrete method this c owns.
        If C is abstract, then returns empty.
        */
        if(jclass.isAbstract()) {
            return new ArrayList<>();
        }
        return Reflections.getMethods(jclass).map(JMethod::getRef).map(ref -> ch.dispatch(jclass, ref)).filter(m -> m != null).toList();

    }

    public static List<Subsignature> getCallableSignatures(JClass jclass) {
        /*
        C c = ...;
        Determine the set of method signatures this c can call.
        */
        List<Subsignature> res = new ArrayList<>();
        Reflections.getMethods(jclass).forEach(m->res.add(m.getSubsignature()));
        return res;
    }
}
