package prism.jellyfish.util;

import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

public class StringUtil {
    public static String getMethodName(JClass jclass, JMethod jmethod) {
        return String.format("%s.%s", jclass.getName(), jmethod.getName());
    }

    public static String getClassName(JClass jclass) {
        return jclass.getName();
    }
}
