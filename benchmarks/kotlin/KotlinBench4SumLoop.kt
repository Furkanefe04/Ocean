fun main() {
    // Warmup
    repeat(5) { var s = 0L; for (i in 1..1_000_000) s += i }
    val start = System.nanoTime()
    var sum = 0L
    for (i in 1..1_000_000_000) sum += i
    val end = System.nanoTime()
    println("sum(1B)=$sum | time_ms=${(end - start) / 1_000_000}")
}
