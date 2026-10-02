fun main() {
    val n = 6000
    val arr = IntArray(n) { n - it }
    val start = System.nanoTime()
    for (i in 0 until n - 1)
        for (j in 0 until n - i - 1)
            if (arr[j] > arr[j + 1]) { val t = arr[j]; arr[j] = arr[j+1]; arr[j+1] = t }
    val end = System.nanoTime()
    println("sorted[0]=${arr[0]} sorted[last]=${arr[n-1]} | time_ms=${(end - start) / 1_000_000}")
}
