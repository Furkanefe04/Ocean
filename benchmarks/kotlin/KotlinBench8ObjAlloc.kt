// data class = heap allocated (same as Java), shows same GC pressure
data class Point8K(val x: Int, val y: Int, val z: Int)

fun main() {
    val n = 2_000_000
    val start = System.nanoTime()
    var sum = 0L
    for (i in 0 until n) {
        val p = Point8K(i, i + 1, i + 2)
        sum += p.x + p.y + p.z
    }
    val end = System.nanoTime()
    println("sum=$sum | time_ms=${(end - start) / 1_000_000}")
}
