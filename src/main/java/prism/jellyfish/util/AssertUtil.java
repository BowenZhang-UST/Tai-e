package prism.jellyfish.util;

import org.apache.logging.log4j.Logger;

import java.text.MessageFormat;
import java.util.regex.Pattern;

public class AssertUtil {
    Logger logger;

    public AssertUtil(Logger logger) {
        this.logger = logger;
    }

    void exit() {
        Thread.dumpStack();
        System.exit(-1);
    }

    private String myFormat(String s, Object... params) {
        // Input: "Format: {} {}"
        // Output: "Format: {0} {1}"
        int i = 0;
        while (s.contains("{}")) {
            s = s.replaceFirst(Pattern.quote("{}"), "{" + i++ + "}");
        }
        return MessageFormat.format(s, params);
    }

    public void assertTrue(boolean condition, String errMsg, Object... strParams) {
        if (!condition) {
            String userStr = myFormat(errMsg, strParams);
            logger.error("Assertion fail: {}", userStr);
            exit();
        }
    }

    public void assertFalse(boolean condition, String errMsg, Object... strParams) {
        if (condition) {
            String userStr = myFormat(errMsg, strParams);
            logger.error("Assertion fail: {}", userStr);
            exit();
        }
    }

    public void unreachable(String errMsg, Object... strParams) {
        String userStr = myFormat(errMsg, strParams);
        logger.error("Unreachable: {}", userStr);
        exit();
    }

    public void unimplemented() {
        logger.error("Unimplemented");
        exit();
    }
}
