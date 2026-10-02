interface Computable9K { fun compute(n: Int): Long }
class Alpha9K : Computable9K { override fun compute(n: Int) = n * 2L }
class Beta9K  : Computable9K { override fun compute(n: Int) = n * 3L + 1 }
class Gamma9K : Computable9K { override fun compute(n: Int) = n.toLong() * n }
class Delta9K : Computable9K { override fun compute(n: Int) = (n + 1L) * 2 }

fun main() {
    val n = 10_000_000
    val cs = arrayOf<Computable9K>(Alpha9K(), Beta9K(), Gamma9K(), Delta9K())
    val start = System.nanoTime()
    var sum = 0L
    for (i in 0 until n) sum += cs[i % 4].compute(i)
    val end = System.nanoTime()
    println("sum=$sum | time_ms=${(end - start) / 1_000_000}")
}
