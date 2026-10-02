public class HardPrime {
    static int countPrimes(int max) {
        int count = 0;
        for (int i = 2; i <= max; i++) {
            int isPrime = 1;
            for (int j = 2; j * j <= i; j++) {
                if (i % j == 0) {
                    isPrime = 0;
                    break;
                }
            }
            if (isPrime == 1) count++;
        }
        return count;
    }

    public static void main(String[] args) {
        long start = System.nanoTime();
        int count = countPrimes(10000000);
        long dur = (System.nanoTime() - start) / 1000000;
        System.out.println("==============================================");
        System.out.println("  JAVA HARD PRIME (10M) BENCHMARK");
        System.out.println("  Sure: " + dur + " ms");
        System.out.println("  Hesap: " + count);
        System.out.println("==============================================");
    }
}
