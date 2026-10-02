public class SieveSim {
    static int countPrimes(int max) {
        int[] isPrime = new int[max];
        isPrime[0] = 1; isPrime[1] = 1;
        int p = 2;
        while (p * p <= max) {
            if (isPrime[p] == 0) {
                int i = p * p;
                while (i < max) { isPrime[i] = 1; i += p; }
            }
            p++;
        }
        int count = 0;
        for (int i = 2; i < max; i++) { if (isPrime[i] == 0) count++; }
        return count;
    }

    public static void main(String[] args) {
        long start = System.nanoTime();
        int count = countPrimes(20000000);
        long dur = (System.nanoTime() - start) / 1000000;
        System.out.println("==============================================");
        System.out.println("  JAVA SIEVE(20M) BENCHMARK");
        System.out.println("  Sure: " + dur + " ms");
        System.out.println("  Hesap: " + count);
        System.out.println("==============================================");
    }
}
