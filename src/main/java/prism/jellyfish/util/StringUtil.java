package prism.jellyfish.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;

import java.util.UUID;


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

    public static String getAscii(String str) {
        String res = "";
        for (char c : str.toCharArray()) {
            int ic = (int) c;
            res += String.valueOf(ic);
        }
        return res;
    }

    public static String getUUID() {
        return UUID.randomUUID().toString();
    }

}
