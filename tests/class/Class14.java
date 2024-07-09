// Test: Explicit and implicit cast

class Class14A {
}

class Class14 extends Class14A {
    static int f1;
    static long f2;
    static long f3;
    static short f4;
    static Object o1;
    static Class14 o2;
    static Class14A o3;

    static {
        f1 = 1;
        f2 = (long) f1;
        f3 = f1;
        f4 = (short) f1;
        o1 = (Object) new Class14();
        o2 = (Class14) o1;
        o3 = o2;
    }

    public static void main(String[] args) {

    }
}
