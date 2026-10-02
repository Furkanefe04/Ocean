import java.math.BigInteger;

public class FibBench {
    public static void main(String[] args) {
        System.out.println("Java Fib(200,000) baslatiliyor...");
        long start = System.currentTimeMillis();
        
        BigInteger a = BigInteger.ZERO;
        BigInteger b = BigInteger.ONE;
        
        for (int i = 2; i <= 200000; i++) {
            BigInteger temp = a.add(b);
            a = b;
            b = temp;
        }
        
        long dur = System.currentTimeMillis() - start;
        System.out.println("Java Fib(200,000) Tamamlandi.");
        System.out.println("Sure: " + dur + " ms");
        String out = b.toString();
        System.out.println("Basamak Sayisi: " + out.length());
        System.out.println("DOGRULAMA: " + out.substring(0, 10) + "..." + out.substring(out.length() - 10));
    }
}
