using System;
using System.Diagnostics;

class Node { public int a; public int b; }

class AllocBench {
    static void Main() {
        Stopwatch sw = Stopwatch.StartNew();
        long sum = 0;
        for (int i = 1; i <= 50000000; i++) {
            Node n = new Node();
            n.a = i;
            n.b = i + 1;
            if (i == 49999999) { sum = n.a + n.b; }
        }
        sw.Stop();
        Console.WriteLine("==============================================");
        Console.WriteLine("  C# OBJECT ALLOC (50M) BENCHMARK");
        Console.WriteLine("  Sure: " + sw.ElapsedMilliseconds + " ms");
        Console.WriteLine("  Hesap: " + sum);
        Console.WriteLine("==============================================");
    }
}
