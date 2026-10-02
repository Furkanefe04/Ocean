package benchmark.antigravity_suite.kotlin

import java.util.Arrays

object SuiteKotlin {
    interface Speakable {
        fun speak()
    }

    class Entity : Speakable {
        var valx: Int = 0
        override fun speak() {
            valx++
        }
    }

    fun fibPureIterative(n: Int): Long {
        if (n <= 1) return n.toLong()
        var a = 0L
        var b = 1L
        var c = 0L
        for (i in 2..n) {
            c = a + b
            a = b
            b = c
        }
        return b
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 1. FIBONACCI
        val t0 = System.nanoTime()
        var fsum = 0L
        for (i in 0 until 50000) {
            fsum += fibPureIterative(100)
        }
        val fibMs = (System.nanoTime() - t0) / 1e6

        // 2. CONTIGUOUS 1D MATRIX MULTIPLICATION
        val N = 500
        val A = DoubleArray(N * N) { 1.0 }
        val B = DoubleArray(N * N) { 2.0 }
        val C = DoubleArray(N * N) { 0.0 }

        val t1 = System.nanoTime()
        for (i in 0 until N) {
            for (k in 0 until N) {
                val a_ik = A[i * N + k]
                for (j in 0 until N) {
                    C[i * N + j] += a_ik * B[k * N + j]
                }
            }
        }
        val matrixMs = (System.nanoTime() - t1) / 1e6

        // 3. PRIME SIEVE (10M)
        val limit = 10000000
        val isPrime = BooleanArray(limit + 1) { true }
        val t2 = System.nanoTime()
        for (p in 2..limit) {
            if (p * p > limit) break
            if (isPrime[p]) {
                var i = p * p
                while (i <= limit) {
                    isPrime[i] = false
                    i += p
                }
            }
        }
        val sieveMs = (System.nanoTime() - t2) / 1e6

        // 4. OOP DISPATCH
        val entity: Speakable = Entity()
        val iterations = 100000000L
        val t3 = System.nanoTime()
        for (i in 0 until iterations) {
            entity.speak()
        }
        val oopMs = (System.nanoTime() - t3) / 1e6
        val dispatchNs = (oopMs * 1e6) / iterations.toDouble()

        println("DYNAMIC_BENCH_RESULTS")
        println("FIB_MS:$fibMs")
        println("MATRIX_MS:$matrixMs")
        println("SIEVE_MS:$sieveMs")
        println("OOP_DISPATCH_NS:$dispatchNs")
        println("OOP_OVERHEAD_MS:$oopMs")
    }
}
