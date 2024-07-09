// Test: Monitor

class Class13 {
    static Class13 f1 = new Class13();
    static Class13 f2 = null;
    static boolean f3;

    static {
        f3 = f1 instanceof Object;
        f3 = f1 instanceof Class13;
        f3 = f2 instanceof Class13;
    }

    public static void main(String[] args) {

    }
}
