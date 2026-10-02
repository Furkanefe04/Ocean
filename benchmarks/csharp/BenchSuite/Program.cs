using System;
using System.Diagnostics;
using System.Linq;
using System.Text;

// ── Type declarations ──────────────────────────────────────────────────────────
struct Point8CS {
    public int X, Y, Z;
    public Point8CS(int x, int y, int z) { X = x; Y = y; Z = z; }
}
interface IComputable9CS { long Compute(int n); }
class Alpha9CS : IComputable9CS { public long Compute(int n) => n * 2L; }
class Beta9CS  : IComputable9CS { public long Compute(int n) => n * 3L + 1; }
class Gamma9CS : IComputable9CS { public long Compute(int n) => (long)n * n; }
class Delta9CS : IComputable9CS { public long Compute(int n) => (n + 1L) * 2; }

// ── Entry point ────────────────────────────────────────────────────────────────
class Program {
    static int Fib(int n) => n <= 1 ? n : Fib(n - 1) + Fib(n - 2);

    static void Main(string[] args) {
        string bench = args.Length > 0 ? args[0] : "all";
        if (bench == "1"  || bench == "all") RunBench1();
        if (bench == "2"  || bench == "all") RunBench2();
        if (bench == "3"  || bench == "all") RunBench3();
        if (bench == "4"  || bench == "all") RunBench4();
        if (bench == "5"  || bench == "all") RunBench5();
        if (bench == "6"  || bench == "all") RunBench6();
        if (bench == "7"  || bench == "all") RunBench7();
        if (bench == "8"  || bench == "all") RunBench8();
        if (bench == "9"  || bench == "all") RunBench9();
        if (bench == "10" || bench == "all") RunBench10();
        if (bench == "11" || bench == "all") RunBench11();
        if (bench == "12" || bench == "all") RunBench12();
    }

    // ─── Bench 1: Recursive Fibonacci ─────────────────────────────────────────
    static void RunBench1() {
        var sw = Stopwatch.StartNew();
        int r = Fib(42);
        sw.Stop();
        Console.WriteLine($"fib(42)={r} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 2: Bubble Sort ──────────────────────────────────────────────────
    static void RunBench2() {
        int n = 6000;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = n - i;
        var sw = Stopwatch.StartNew();
        for (int i = 0; i < n - 1; i++)
            for (int j = 0; j < n - i - 1; j++)
                if (arr[j] > arr[j + 1]) (arr[j], arr[j + 1]) = (arr[j + 1], arr[j]);
        sw.Stop();
        Console.WriteLine($"sorted[0]={arr[0]} sorted[last]={arr[n-1]} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 3: Prime Sieve ─────────────────────────────────────────────────
    static void RunBench3() {
        int limit = 2_000_000;
        bool[] sieve = new bool[limit + 1];
        for (int i = 2; i <= limit; i++) sieve[i] = true;
        var sw = Stopwatch.StartNew();
        for (int i = 2; i <= limit; i++)
            if (sieve[i])
                for (int j = i + i; j <= limit; j += i) sieve[j] = false;
        int count = 0;
        for (int i = 2; i <= limit; i++) if (sieve[i]) count++;
        sw.Stop();
        Console.WriteLine($"primes(2M)={count} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 4: Sum Loop ────────────────────────────────────────────────────
    static void RunBench4() {
        for (int w = 0; w < 5; w++) { long s = 0; for (int i = 1; i <= 1_000_000; i++) s += i; }
        var sw = Stopwatch.StartNew();
        long sum = 0;
        for (int i = 1; i <= 1_000_000_000; i++) sum += i;
        sw.Stop();
        Console.WriteLine($"sum(1B)={sum} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 5: String Ops ──────────────────────────────────────────────────
    static void RunBench5() {
        int n = 200_000;
        var sw = Stopwatch.StartNew();
        long total = 0;
        for (int i = 0; i < n; i++) {
            string s = $"ocean_bench_{i}_string_operation_test";
            total += s.Length;
        }
        sw.Stop();
        Console.WriteLine($"total_chars={total} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 6: Exception Flood ─────────────────────────────────────────────
    static void RunBench6() {
        int n = 100_000;
        int caught = 0;
        var sw = Stopwatch.StartNew();
        for (int i = 0; i < n; i++) {
            try { throw new Exception("bench"); }
            catch (Exception) { caught++; }
        }
        sw.Stop();
        Console.WriteLine($"caught={caught} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 7: Functional Pipeline (LINQ) ──────────────────────────────────
    static void RunBench7() {
        int n = 3_000_000;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        var sw = Stopwatch.StartNew();
        long sum = arr.Where(i => i % 2 == 0).Select(i => (long)i * 3).Sum();
        sw.Stop();
        Console.WriteLine($"pipeline_sum={sum} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 8: Object Allocation (struct = stack, zero GC pressure) ────────
    static void RunBench8() {
        int n = 2_000_000;
        var sw = Stopwatch.StartNew();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            var p = new Point8CS(i, i + 1, i + 2);
            sum += p.X + p.Y + p.Z;
        }
        sw.Stop();
        Console.WriteLine($"sum={sum} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 9: Megamorphic Interface Dispatch ──────────────────────────────
    static void RunBench9() {
        int n = 10_000_000;
        IComputable9CS[] cs = { new Alpha9CS(), new Beta9CS(), new Gamma9CS(), new Delta9CS() };
        var sw = Stopwatch.StartNew();
        long sum = 0;
        for (int i = 0; i < n; i++) sum += cs[i % 4].Compute(i);
        sw.Stop();
        Console.WriteLine($"sum={sum} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 10: StrBuilder throughput (int appends, mirrors Ocean StrBuilder) ──
    static void RunBench10() {
        int n = 100_000;
        var sw = Stopwatch.StartNew();
        long total = 0;
        for (int i = 0; i < n; i++) {
            var sb = new StringBuilder();
            sb.Append(i);
            sb.Append(42);
            sb.Append(i + 1);
            sb.Append(99);
            sb.Append(i * 2);
            total += sb.Length;
        }
        sw.Stop();
        Console.WriteLine($"total_len={total} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 11: Reused StringBuilder (Furkan + 100 + ToString + Clear) ─────
    static void RunBench11() {
        int n = 5_000_000;
        var sw = Stopwatch.StartNew();
        long totalLength = 0;
        var builder = new StringBuilder();
        for (int i = 0; i < n; i++) {
            builder.Append("Furkan").Append(100);
            string s = builder.ToString();
            totalLength += s.Length;
            builder.Clear();
        }
        sw.Stop();
        Console.WriteLine($"total_len={totalLength} | time_ms={sw.ElapsedMilliseconds}");
    }

    // ─── Bench 12: 1B Reused/New StringBuilder Test (Furkan + 100 + Clear) ────
    static void RunBench12() {
        var sw = Stopwatch.StartNew();
        for (int i = 0; i < 100_000_000; i++) {
            StringBuilder builder = new StringBuilder();
            builder.Append(int.MaxValue).Append("Furkan").Append(true).Append(int.MinValue);
        }
        sw.Stop();
        Console.WriteLine($"süre : {sw.ElapsedMilliseconds} ms");
    }
}
