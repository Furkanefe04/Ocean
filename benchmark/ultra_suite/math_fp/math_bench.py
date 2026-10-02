import math
import time

def main():
    limit = 100000000
    start = time.time()
    
    res = 0.0
    for i in range(limit):
        # Using math functions directly
        res += math.sin(i) * math.cos(i) + math.sqrt(i)
        
    end = time.time()
    print("PYTHON Math Res: {}, Time: {:.1f} ms".format(res, (end - start) * 1000))

if __name__ == "__main__":
    main()
