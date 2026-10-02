using System;
using System.Diagnostics;

class FibSim {
    static int Fib(int n) {
        if (n <= 1) return n;
        return Fib(n - 1) + Fib(n - 2);
    }

    static void Main() {
        Stopwatch sw = Stopwatch.StartNew();
        int result = Fib(38);
        sw.Stop();
        Console.WriteLine("==============================================");
        Console.WriteLine("  C# FIBONACCI(38) BENCHMARK");
        Console.WriteLine("  Sure: " + sw.ElapsedMilliseconds + " ms");
        Console.WriteLine("  Hesap: " + result);
        Console.WriteLine("==============================================");
    }
}
