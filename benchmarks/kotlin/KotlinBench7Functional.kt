fun main() {
    val n = 3_000_000
    val arr = IntArray(n) { it }
    val start = System.nanoTime()
    // Kotlin idiomatic: sumOf with inline lambda (single pass, no intermediate collection)
    val sum = arr.sumOf { if (it % 2 == 0) it.toLong() * 3 else 0L }
    val end = System.nanoTime()
    println("pipeline_sum=$sum | time_ms=${(end - start) / 1_000_000}")
}
