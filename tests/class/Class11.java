// Test: Array operations

class Class11 {
    static int f0 = 5;
    static int[] f1;
    static int[] f2;
    static int[][] f3;
    static int[] f4;
    static int[] f5 = {111, 112, 113, 114};
    static int[][] f6 = {{121, 122}, {123, 124}};

    static {
        f1 = new int[5];
        f2 = new int[f0];
        f3 = new int[3][4];
        f0 = f3.length;
        f0 = f1.length;
        f0 = f1[2];
        f1[1] = f0;
        f0 = f3[1][2];
        f3[2][1] = f0;
        f4 = f3[4];
    }

    public static void main(String[] args) {
    }
}
