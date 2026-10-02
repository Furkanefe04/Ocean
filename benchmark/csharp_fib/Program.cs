using System;
using System.Numerics;
using System.Diagnostics;

class FibBench {
    static void Main() {
        Console.WriteLine("C# Fib(200,000) baslatiliyor...");
        Stopwatch sw = Stopwatch.StartNew();
        
        BigInteger a = BigInteger.Zero;
        BigInteger b = BigInteger.One;
        
        for (int i = 2; i <= 200000; i++) {
            BigInteger temp = a + b;
            a = b;
            b = temp;
        }
        
        sw.Stop();
        Console.WriteLine("C# Fib(200,000) Tamamlandi.");
        Console.WriteLine("Sure: " + sw.ElapsedMilliseconds + " ms");
        string s = b.ToString();
        Console.WriteLine("Basamak Sayisi: " + s.Length);
        Console.WriteLine("DOGRULAMA: " + s.Substring(0, 10) + "..." + s.Substring(s.Length - 10));
    }
}
