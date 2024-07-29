// Test: Virtual call

class Class24 {
    public void foo() {
    }

    public static void main(String[] args) {
        Class24A a = new Class24A();
        a.foo();
    }
}

class Class24A extends Class24 {

}
