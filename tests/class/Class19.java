// Test: conversions between Integer/int, Float/float


class Class19 {
    static int f1;
    static Integer f2;
    static float f3;
    static Float f4;

    static {
        f2 = f1;
        f1 = f2;
        f3 = f4;
        f4 = f3;
    }

    public static void main(String[] args) {
    }
}
