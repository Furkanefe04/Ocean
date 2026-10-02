public class StressTest {

    // TEST 1: 3-level nested for
    static int tripleNestedFor() {
        int total = 0;
        for (int i = 1; i <= 200; i++)
            for (int j = 1; j <= 200; j++)
                for (int k = 1; k <= 200; k++)
                    total += 1;
        return total;
    }

    // TEST 2: Nested while GCD
    static int nestedWhileGcd() {
        int total = 0;
        int i = 1;
        while (i <= 5000) {
            int j = 1;
            while (j <= 100) {
                int a = i, b = j;
                while (b != 0) { int t = b; b = a % b; a = t; }
                total += a;
                j++;
            }
            i++;
        }
        return total;
    }

    // TEST 3: For+While mix (insertion sort)
    static int forWhileMix() {
        int size = 5000;
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) arr[i] = size - i;

        for (int i = 1; i < size; i++) {
            int key = arr[i];
            int j = i - 1;
            while (j >= 0) {
                if (arr[j] > key) { arr[j + 1] = arr[j]; j--; }
                else { j = -1; }
            }
            arr[j + 1] = key;
        }

        int checksum = 0;
        for (int i = 0; i < 10; i++) checksum += arr[i];
        return checksum;
    }

    // TEST 4: While+For mix (prime factorization sum)
    static int whileForMix() {
        int total = 0;
        int n = 2;
        while (n <= 50000) {
            int num = n;
            for (int p = 2; p <= num; p++) {
                while (num % p == 0) { total += p; num /= p; }
            }
            n++;
        }
        return total;
    }

    // TEST 5: Skip (continue) stress
    static int skipStressTest() {
        int total = 0;
        for (int i = 1; i <= 1000; i++) {
            for (int j = 1; j <= 1000; j++) {
                if (j % 3 == 0) continue;
                if (j % 7 == 0) continue;
                total++;
            }
        }
        return total;
    }

    // TEST 6: Stop (break) stress
    static int stopStressTest() {
        int total = 0;
        int i = 1;
        while (i <= 10000) {
            int j = 1;
            while (j <= 10000) {
                if (j > i) break;
                total++;
                j++;
            }
            i++;
        }
        return total;
    }

    // TEST 7: 4-level mixed nesting
    static int quadNestedMix() {
        int total = 0;
        for (int a = 1; a <= 30; a++) {
            int b = 1;
            while (b <= 30) {
                for (int c = 1; c <= 30; c++) {
                    int d = 1;
                    while (d <= 30) { total++; d++; }
                }
                b++;
            }
        }
        return total;
    }

    // TEST 8: Massive simple loop
    static int massiveSimpleLoop() {
        int total = 0;
        for (int i = 1; i <= 500000000; i++) total++;
        return total;
    }

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("  JAVA STRESS TEST v1.0");
        System.out.println("==============================================");

        long t;

        t = System.currentTimeMillis();
        int r1 = tripleNestedFor();
        System.out.println("[TEST 1] 3x Nested For: " + r1 + " (" + (System.currentTimeMillis()-t) + "ms)");

        t = System.currentTimeMillis();
        int r2 = nestedWhileGcd();
        System.out.println("[TEST 2] Nested While GCD: " + r2 + " (" + (System.currentTimeMillis()-t) + "ms)");

        t = System.currentTimeMillis();
        int r3 = forWhileMix();
        System.out.println("[TEST 3] For+While Mix: " + r3 + " (" + (System.currentTimeMillis()-t) + "ms)");

        t = System.currentTimeMillis();
        int r4 = whileForMix();
        System.out.println("[TEST 4] While+For Mix: " + r4 + " (" + (System.currentTimeMillis()-t) + "ms)");

        t = System.currentTimeMillis();
        int r5 = skipStressTest();
        System.out.println("[TEST 5] Skip Stress: " + r5 + " (" + (System.currentTimeMillis()-t) + "ms)");

        t = System.currentTimeMillis();
        int r6 = stopStressTest();
        System.out.println("[TEST 6] Stop Stress: " + r6 + " (" + (System.currentTimeMillis()-t) + "ms)");

        t = System.currentTimeMillis();
        int r7 = quadNestedMix();
        System.out.println("[TEST 7] 4x Nested Mix: " + r7 + " (" + (System.currentTimeMillis()-t) + "ms)");

        t = System.currentTimeMillis();
        int r8 = massiveSimpleLoop();
        System.out.println("[TEST 8] Massive Loop: " + r8 + " (" + (System.currentTimeMillis()-t) + "ms)");

        System.out.println("==============================================");
    }
}
