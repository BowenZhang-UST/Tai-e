package prism.jellyfish.util;

import org.apache.logging.log4j.Logger;

public class AssertUtil {
    Logger logger;

    public AssertUtil(Logger logger) {
        this.logger = logger;
    }

    void exit() {
        Thread.dumpStack();
        System.exit(-1);
    }

    public void assertTrue(boolean condition, String errMsg) {
        if (!condition) {
            logger.error("Assertion fail: {}", errMsg);
            exit();
        }
    }

    public void assertFalse(boolean condition, String errMsg) {
        if (condition) {
            logger.error("Assertion fail: {}", errMsg);
            exit();
        }
    }

    public void unreachable(String errMsg) {
        logger.error("Unreachable: {}", errMsg);
        exit();
    }

    public void unimplemented() {
        logger.error("Unimplemented");
        exit();
    }
}
