class BaseJava {
    public int increment(int n) {
        return n + 1;
    }
}

class SubJava extends BaseJava {
    @Override
    public int increment(int n) {
        return n + 2;
    }
}

public class OOPBenchmarkJava {
    public static void main(String[] args) {
        System.out.println("--- Java OOP (1M Instantiations & Calls) Benchmark Baslatiliyor ---");
        long start = System.currentTimeMillis();
        long total = 0;
        
        for (int i = 0; i < 1000000; i++) {
            SubJava s = new SubJava();
            total += s.increment(1);
        }
        
        long end = System.currentTimeMillis();
        System.out.println("Total: " + total);
        System.out.println("Java OOP Suresi: " + (end - start) + "ms");
        System.out.println("--- Java OOP Benchmark Tamamlandi ---");
    }
}
