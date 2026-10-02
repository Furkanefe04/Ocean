package benchmarks

import java.util.ArrayList

fun main() {
    val N = 10000000
    val ROUNDS = 5

    println("=== Kotlin ArrayList<Int> Benchmark (N=$N, Rounds=$ROUNDS) ===")

    val totalInitStart = System.nanoTime()
    val list = ArrayList<Int>(N)
    for (i in 0 until N) {
        list.add(i % 1000)
    }
    val initTimeMs = (System.nanoTime() - totalInitStart) / 1_000_000
    println("List Initialization Time: $initTimeMs ms")

    var totalSum = 0L
    val totalExecStart = System.nanoTime()

    for (r in 0 until ROUNDS) {
        val roundStart = System.nanoTime()

        // 1. Indexed updates
        for (i in 0 until N) {
            val updated = (list[i] * 3 + 7) % 10000
            list[i] = updated
        }

        // 2. Foreach iteration sum
        var sum = 0L
        for (valItem in list) {
            sum += valItem
        }
        totalSum += sum

        val roundTimeMs = (System.nanoTime() - roundStart) / 1_000_000
        println("  Round ${r + 1}: $roundTimeMs ms (Checksum: $sum)")
    }

    val totalExecTimeMs = (System.nanoTime() - totalExecStart) / 1_000_000
    println("----------------------------------------------")
    println("Total Execution Time: $totalExecTimeMs ms (Avg: ${totalExecTimeMs / ROUNDS} ms/round)")
    println("Final Checksum: $totalSum")
}