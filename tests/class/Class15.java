// Test: Determined method invokation

class Class15A {
    public void f1() {
    }

    static void f2() {
    }
}

class Class15 extends Class15A {
    public int f3() {
        return 0;
    }

    static void f4() {
    }

    static Class15 f5;
    static int f6;

    static {
        f5 = new Class15();
        f5.f1();
        Class15.f2();
        f6 = f5.f3();
        Class15.f4();
    }

    public static void main(String[] args) {

    }
}
