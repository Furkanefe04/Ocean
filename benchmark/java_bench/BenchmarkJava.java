class Base {
    public int increment(int n) { return n + 1; }
}
class Sub extends Base {
    public int increment(int n) { return n + 2; }
}
public class BenchmarkJava {
    public static void main(String[] args) {
        // Test 1: Loop 100M
        long startLoop = System.currentTimeMillis();
        long sum = 0;
        for (int i = 1; i <= 100000000; i++) {
            sum++;
        }
        long endLoop = System.currentTimeMillis();
        System.out.println("Loop 100M Result: " + sum);
        System.out.println("Java Loop Suresi: " + (endLoop - startLoop) + "ms");

        // Test 2: OOP 1M
        long startOOP = System.currentTimeMillis();
        int total = 0;
        for (int i = 0; i < 1000000; i++) {
            Sub s = new Sub();
            total += s.increment(1);
        }
        long endOOP = System.currentTimeMillis();
        System.out.println("OOP 1M Result: " + total);
        System.out.println("Java OOP Suresi: " + (endOOP - startOOP) + "ms");
    }
}
