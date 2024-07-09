// Test: Monitor

class Class12 {
    static Class12 f = new Class12();

    static {
        synchronized (f) {
        }
    }

    public static void main(String[] args) {

    }
}
