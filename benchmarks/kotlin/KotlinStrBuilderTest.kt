package packagetest

fun main() {
    val start = System.nanoTime()
    for (i in 0 until 100_000_000) {
        val builder = java.lang.StringBuilder()
        builder.append(Int.MAX_VALUE).append("Furkan").append(true).append(Int.MIN_VALUE)
    }
    val end = System.nanoTime()
    println("süre : ${(end - start) / 1_000_000} ms")
}
