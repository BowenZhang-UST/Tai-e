// Test: Virtual methods memory layout & invokation

class Class22 {
    public int foo() {
        System.out.println(1);
        return 1;
    }

    static Class22 c1;
    static Class22A c2;
    static Class22B c3;

    static {

        // Virtual:
        c1.foo();
        c2.foo();
        c2.bar();

        // Non-virtual
        c3.foo();
        c3.bar();

    }

    public static void main(String[] args) {
    }
}


class Class22A extends Class22 {
    public int foo() {
        System.out.println(2);
        return 2;
    }

    public int bar() {
        System.out.println(2);
        return 2;
    }
}

class Class22B extends Class22A {
    public int foo() {
        System.out.println(3);
        return 3;
    }

    public int bar() {
        System.out.println(3);
        return 3;
    }
}
