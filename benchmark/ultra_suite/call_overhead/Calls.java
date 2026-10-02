public class Calls {
    static int add(int a, int b) {
        return a + b;
    }

    public static void main(String[] args) {
        long limit = 1000000000L;
        long start = System.currentTimeMillis();
        
        long sum = 0;
        for (long i = 0; i < limit; i++) {
            sum += add((int)i, 1);
        }
        
        long end = System.currentTimeMillis();
        System.out.println("Java Calls Sum: " + sum + ", Time: " + (end - start) + " ms");
    }
}
