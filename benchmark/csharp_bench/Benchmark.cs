using System;
using System.Diagnostics;

abstract class Base {
    public abstract int Increment(int n);
}

class Sub : Base {
    public override int Increment(int n) => n + 1;
}

class Benchmark {
    static int Fib(int n) {
        if (n <= 1) return n;
        return Fib(n - 1) + Fib(n - 2);
    }

    static void Main() {
        Console.WriteLine("--- C# Final Benchmark Suite ---");
        Console.WriteLine();

        // Test 1: Turbo Loop 100M
        var sw = Stopwatch.StartNew();
        long total = 0;
        for (int i = 0; i <= 100000000; i++) {
            total = i;
        }
        sw.Stop();
        Console.WriteLine($"[Test 1: Turbo Loop 100M]\nResult: {total}\nDuration: {sw.ElapsedMilliseconds}ms\n");

        // Test 2: OOP Dynamic Dispatch 3M
        Base sub = new Sub();
        sw = Stopwatch.StartNew();
        long oopTotal = 0;
        for (int i = 0; i < 3000000; i++) {
            oopTotal += sub.Increment(1);
        }
        sw.Stop();
        Console.WriteLine($"[Test 2: OOP Dynamic Dispatch 3M]\nResult: {oopTotal}\nDuration: {sw.ElapsedMilliseconds}ms\n");

        // Test 3: Recursive Fibonacci 38
        sw = Stopwatch.StartNew();
        int fibRes = Fib(38);
        sw.Stop();
        Console.WriteLine($"[Test 3: Recursive Fibonacci 38]\nResult: {fibRes}\nDuration: {sw.ElapsedMilliseconds}ms\n");
    }
}
