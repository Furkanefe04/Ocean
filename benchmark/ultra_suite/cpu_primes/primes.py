import time

def count_primes(limit):
    count = 0
    for i in range(2, limit + 1):
        is_prime = True
        j = 2
        while j * j <= i:
            if i % j == 0:
                is_prime = False
                break
            j += 1
        if is_prime:
            count += 1
    return count

if __name__ == "__main__":
    limit = 2000000
    start = time.time()
    count = count_primes(limit)
    end = time.time()
    print("PYTHON Count: {}, Time: {:.1f} ms".format(count, (end - start) * 1000))
