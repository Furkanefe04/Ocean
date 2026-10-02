fun main() {
    val n = 200_000
    val start = System.nanoTime()
    var total = 0L
    for (i in 0 until n) {
        val s = "ocean_bench_${i}_string_operation_test"
        total += s.length
    }
    val end = System.nanoTime()
    println("total_chars=$total | time_ms=${(end - start) / 1_000_000}")
}
