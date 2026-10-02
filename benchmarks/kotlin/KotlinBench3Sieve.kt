fun main() {
    val limit = 2_000_000
    val sieve = BooleanArray(limit + 1)
    for (i in 2..limit) sieve[i] = true
    val start = System.nanoTime()
    for (i in 2..limit) {
        if (sieve[i]) {
            var j = i + i
            while (j <= limit) { sieve[j] = false; j += i }
        }
    }
    val count = (2..limit).count { sieve[it] }
    val end = System.nanoTime()
    println("primes(2M)=$count | time_ms=${(end - start) / 1_000_000}")
}
