// Test: Virtual call

class Class24 {
    public int f1;

    public void foo() {
    }

    public static int f2;

    public static void main(String[] args) {
        Class24A a = new Class24A();
        a.foo();
        f2 = a.f1;
    }
}

class Class24A extends Class24 {

}
