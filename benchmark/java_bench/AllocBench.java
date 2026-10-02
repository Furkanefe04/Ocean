class Node { int a; int b; }

public class AllocBench {
    public static void main(String[] args) {
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 1; i <= 50000000; i++) {
            Node n = new Node();
            n.a = i;
            n.b = i + 1;
            if (i == 49999999) { sum = n.a + n.b; }
        }
        long dur = (System.nanoTime() - start) / 1000000;
        System.out.println("==============================================");
        System.out.println("  JAVA OBJECT ALLOC (50M) BENCHMARK");
        System.out.println("  Sure: " + dur + " ms");
        System.out.println("  Hesap: " + sum);
        System.out.println("==============================================");
    }
}
