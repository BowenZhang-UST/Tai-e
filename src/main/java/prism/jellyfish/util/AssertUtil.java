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

    public void assertTrue(boolean condition, String errMsg, Object... strParams) {
        if (!condition) {
            logger.error("Assertion fail:");
            logger.error(errMsg, strParams);
            exit();
        }
    }

    public void assertFalse(boolean condition, String errMsg, Object... strParams) {
        if (condition) {
            logger.error("Assertion fail:");
            logger.error(errMsg, strParams);
            exit();
        }
    }

    public void unreachable(String errMsg, Object... strParams) {
        logger.error("Unreachable:");
        logger.error(errMsg, strParams);
        exit();
    }

    public void unimplemented() {
        logger.error("Unimplemented");
        exit();
    }
}
