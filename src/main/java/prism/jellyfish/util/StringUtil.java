package prism.jellyfish.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.language.classes.JClass;


public class StringUtil {
    private static final Logger logger = LogManager.getLogger(StringUtil.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public static String getMethodName(MethodRef ref, boolean isPhantom) {
        String phantom = isPhantom ? "phantom." : "";
        return String.format("%s%s.%s", phantom, ref.getDeclaringClass().getName(), ref.getName());
    }

    public static String getClassName(JClass jclass) {
        return jclass.getName();
    }

    public static String getStaticFieldName(FieldRef fieldRef, boolean isPhantom) {
        as.assertTrue(fieldRef.isStatic(), "The field should be static");
        String prefix = isPhantom ? "phantom.static" : "static";
        String className = StringUtil.getClassName(fieldRef.getDeclaringClass());

        return String.format("%s.%s.%s", prefix, className, fieldRef.getName());
    }

    private static String getRealVarName(Var var) {
        String varName = var.getName();
        String realVarName = null;
        if (varName.substring(0, 1).equals("%")) {
            realVarName = varName.substring(1);
        } else if (varName.length() > 0) {
            realVarName = varName;
        } else {
            as.unreachable("Error: the var name is empty. Var: {}", var);
        }
        return realVarName;

    }

    public static String getVarNameAsPtr(Var var) {
        return String.format("var.%s", getRealVarName(var));
    }

    public static String getVarNameAsLoad(Var var) {
        return String.format("load.%s", getRealVarName(var));
    }

}
