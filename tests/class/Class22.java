// Test: Virtual methods memory layout


class Class22 {
    public int foo() {
        return 1;
    }

    static {
        Class22 c1 = new Class22A();
        Class22 c2 = new Class22B();
    }

    public static void main(String[] args) {
    }
}


class Class22A extends Class22 {
    public int foo() {
        return 2;
    }

    public int bar() {
        return 2;
    }
}

class Class22B extends Class22A {
    public int foo() {
        return 3;
    }

    public int bar() {
        return 3;
    }
}
