using System;
using System.Diagnostics;

namespace AntigravityBenchmark
{
    class SuiteCSharp
    {
        static long FibPureIterative(int n)
        {
            if (n <= 1) return n;
            long a = 0, b = 1, c = 0;
            for (int i = 2; i <= n; i++)
            {
                c = a + b;
                a = b;
                b = c;
            }
            return b;
        }

        interface ISpeakable
        {
            void Speak();
        }

        class Entity : ISpeakable
        {
            public int Val = 0;
            public void Speak() { Val++; }
        }

        static void Main(string[] args)
        {
            // 1. FIBONACCI
            long t0 = Stopwatch.GetTimestamp();
            long fsum = 0;
            for (int i = 0; i < 50000; i++)
            {
                fsum += FibPureIterative(100);
            }
            double fibMs = (Stopwatch.GetTimestamp() - t0) * 1000.0 / Stopwatch.Frequency;

            // 2. CONTIGUOUS 1D MATRIX MULTIPLICATION (500x500 flat double[])
            int N = 500;
            double[] A = new double[N * N];
            double[] B = new double[N * N];
            double[] C = new double[N * N];
            Array.Fill(A, 1.0);
            Array.Fill(B, 2.0);

            t0 = Stopwatch.GetTimestamp();
            for (int i = 0; i < N; i++)
            {
                for (int k = 0; k < N; k++)
                {
                    double a_ik = A[i * N + k];
                    for (int j = 0; j < N; j++)
                    {
                        C[i * N + j] += a_ik * B[k * N + j];
                    }
                }
            }
            double matrixMs = (Stopwatch.GetTimestamp() - t0) * 1000.0 / Stopwatch.Frequency;

            // 3. PRIME SIEVE (10M elements)
            int limit = 10000000;
            bool[] isPrime = new bool[limit + 1];
            Array.Fill(isPrime, true);
            t0 = Stopwatch.GetTimestamp();
            for (int p = 2; p * p <= limit; p++)
            {
                if (isPrime[p])
                {
                    for (int i = p * p; i <= limit; i += p)
                        isPrime[i] = false;
                }
            }
            double sieveMs = (Stopwatch.GetTimestamp() - t0) * 1000.0 / Stopwatch.Frequency;

            // 4. PURE VIRTUAL DISPATCH LATENCY (Pre-allocated persistent object)
            ISpeakable entity = new Entity();
            long iterations = 100000000; // 100M calls
            t0 = Stopwatch.GetTimestamp();
            for (long i = 0; i < iterations; i++)
            {
                entity.Speak();
            }
            double oopMs = (Stopwatch.GetTimestamp() - t0) * 1000.0 / Stopwatch.Frequency;
            double dispatchNs = (oopMs * 1e6) / (double)iterations;

            Console.WriteLine("DYNAMIC_BENCH_RESULTS");
            Console.WriteLine($"FIB_MS:{fibMs:F2}");
            Console.WriteLine($"MATRIX_MS:{matrixMs:F2}");
            Console.WriteLine($"SIEVE_MS:{sieveMs:F2}");
            Console.WriteLine($"OOP_DISPATCH_NS:{dispatchNs:F3}");
            Console.WriteLine($"OOP_OVERHEAD_MS:{oopMs:F2}");
        }
    }
}
