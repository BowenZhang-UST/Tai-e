package prism.jellyfish.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import prism.jellyfish.Mappings;

public class StringUtil {
    private static final Logger logger = LogManager.getLogger(StringUtil.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public static String getMethodName(JClass jclass, JMethod jmethod) {
        return String.format("%s.%s", jclass.getName(), jmethod.getName());
    }

    public static String getClassName(JClass jclass) {
        return jclass.getName();
    }

    public static String getStaticFieldName(JClass jclass, JField jfield) {
        as.assertTrue(jfield.isStatic(), "The field should be static");
        String className = StringUtil.getClassName(jclass);

        return String.format("%s.%s", className, jfield.getName());
    }
}
