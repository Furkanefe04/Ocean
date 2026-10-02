fun main() {
    val n = 100_000
    val start = System.nanoTime()
    var total = 0L
    for (i in 0 until n) {
        val s = buildString {
            append(i)
            append(42)
            append(i + 1)
            append(99)
            append(i * 2)
        }
        total += s.length
    }
    val end = System.nanoTime()
    println("total_len=$total | time_ms=${(end - start) / 1_000_000}")
}
