// Test: Array operations

class Class11 {
    static int f0 = 5;
    static int[] f1;
    static int[] f2;
    static int[][] f3;
//    static int[] f2 = {1,2,3,4};
//    static int f3;
//    static int[][] f4 = {{1,2}, {3,4}};
//    static int f5;
//    static int f6;

    static {
        f1 = new int[5];
        f2 = new int[f0];
        f3 = new int[3][4];
        f0 = f3.length;
        f0 = f1.length;
//        f3 = f2.length;
//        f5 = f1[0];
//        f1[1] = f5;
//        f6 = f4[0][1];
//        f4[1][0] = f6;

    }

    public static void main(String[] args) {
    }
}
