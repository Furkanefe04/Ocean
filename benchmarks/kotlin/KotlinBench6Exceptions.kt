fun main() {
    val n = 100_000
    var caught = 0
    val start = System.nanoTime()
    repeat(n) {
        try {
            throw RuntimeException("bench")
        } catch (e: Exception) {
            caught++
        }
    }
    val end = System.nanoTime()
    println("caught=$caught | time_ms=${(end - start) / 1_000_000}")
}
