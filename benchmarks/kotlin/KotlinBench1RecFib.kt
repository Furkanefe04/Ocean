fun fib(n: Int): Int = if (n <= 1) n else fib(n - 1) + fib(n - 2)

fun main() {
    val start = System.nanoTime()
    val result = fib(42)
    val end = System.nanoTime()
    println("fib(42)=$result | time_ms=${(end - start) / 1_000_000}")
}
