fun main() {
    val n = 5_000_000
    val start = System.nanoTime()
    var totalLength = 0L
    val builder = java.lang.StringBuilder()
    for (i in 0 until n) {
        builder.append("Furkan").append(100)
        val s = builder.toString()
        totalLength += s.length
        builder.setLength(0)
    }
    val end = System.nanoTime()
    println("total_len=$totalLength | time_ms=${(end - start) / 1_000_000}")
}
