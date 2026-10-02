import os
import subprocess
import time
import statistics
import sys
import shutil

class UltraRunner:
    def __init__(self):
        self.suite_dir = "benchmark/ultra_suite"
        self.ocean_src_dir = "examples"
        self.results = {}
        # We need the full classpath for Ocean execution
        self.ocean_cp = "build/classes/java/main;build/resources/main;lib/*;." 
        
    def run_cmd(self, cmd, cwd=None):
        try:
            start = time.time()
            # Specify utf-8 encoding to avoid platform-specific decode errors
            result = subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=cwd, timeout=600, encoding='utf-8', errors='replace')
            end = time.time()
            return (end - start) * 1000, result.stdout, result.stderr
        except Exception as e:
            return -1, "", str(e)

    def compile_all(self):
        print("--- Compiling All Benchmarks ---", flush=True)
        # 1. C/C++
        for cat in os.listdir(self.suite_dir):
            cat_path = os.path.join(self.suite_dir, cat)
            if not os.path.isdir(cat_path): continue
            
            for file in os.listdir(cat_path):
                if file.endswith(".c"):
                    print(f" Compiling C: {file}", flush=True)
                    self.run_cmd(f"gcc -O3 {file} -o {file[:-2]}.exe -lm", cwd=cat_path)
                elif file.endswith(".cpp"):
                    print(f" Compiling CPP: {file}", flush=True)
                    self.run_cmd(f"g++ -O3 {file} -o {file[:-4]}_cpp.exe", cwd=cat_path)
                elif file.endswith(".java"):
                    print(f" Compiling Java: {file}", flush=True)
                    self.run_cmd(f"javac {file}", cwd=cat_path)
                elif file.endswith(".cs") and file != "Program.cs":
                    # For C#, we use dotnet
                    print(f" Preparing C# for {file}", flush=True)
                    self.run_cmd(f"dotnet new console --force", cwd=cat_path)
                    # We'll just replace Program.cs with this file
                    shutil.copy(os.path.join(cat_path, file), os.path.join(cat_path, "Program.cs"))
                    self.run_cmd(f"dotnet build -c Release", cwd=cat_path)

        # 2. Ocean (Requires gradle build first)
        print(" Compiling Ocean files...", flush=True)
        for file in os.listdir(self.ocean_src_dir):
            if file.endswith("Bench.ocean"):
                print(f"   Compiling: {file}", flush=True)
                # We use the ocean.bat (which should be in path or accessible)
                self.run_cmd(f"cmd /c ocean.bat {file[:-6]}", cwd=".")

    def run_benchmarks(self):
        categories = {
            "cpu_primes": "Primes",
            "memory_stress": "Array",
            "oop_gc": "Nodes",
            "call_overhead": "Calls",
            "math_fp": "Math",
            "ds_maps": "HashMap",
            "thread_parallel": "ParallelSum",
            "io_bound": "IO",
            "mixed_world": "Json"
        }

        for cat_dir, short_name in categories.items():
            print(f"\n>>> Category: {cat_dir}")
            self.results[cat_dir] = {}
            cat_path = os.path.join(self.suite_dir, cat_dir)
            
            # C
            res = self.benchmark(f"{cat_path}/primes.exe" if cat_dir == "cpu_primes" else f"{cat_path}/{cat_dir.split('_')[0]}_bench.exe" if "bench" in cat_dir else f"{cat_path}/{short_name.lower()}.exe")
            # Wait, the filenames vary. I'll make a more robust detection.
            
    def get_run_command(self, cat_dir, lang, short_name):
        cat_path = os.path.join(self.suite_dir, cat_dir)
        if lang == "C":
            # Just look for the exe that DOES NOT have cpp in the name
            exes = [f for f in os.listdir(cat_path) if f.endswith(".exe") and "cpp" not in f.lower()]
            if not exes: return None, None
            return f".\\{exes[0]}", cat_path
        elif lang == "CPP":
            # Just look for the exe that DOES have cpp in the name
            exes = [f for f in os.listdir(cat_path) if f.endswith(".exe") and "cpp" in f.lower()]
            # Backup for primes where I forgot to name it primes_cpp.exe? 
            # Wait, primes.cpp compiles to primes.exe. 
            # I should just rename the output in compile_all.
            if not exes: 
                # If no cpp-named exe, fallback to any exe if C was already handled? 
                # Better yet, I'll fix compile_all to use distinct names.
                return None, None
            return f".\\{exes[0]}", cat_path
        elif lang == "Java":
            return f"java {short_name}", cat_path
        elif lang == "CSHARP":
            return f"dotnet run -c Release --no-build", cat_path
        elif lang == "Python":
            pys = [f for f in os.listdir(cat_path) if f.endswith(".py")]
            if pys: return f"python {pys[0]}", cat_path
        elif lang == "Ocean":
            # Ocean classes are in org.Ocean.Compiler.generated
            return f"java -cp \"build/classes/java/main;build/resources/main;lib/*;.\" org.Ocean.Compiler.generated.{short_name}Bench", "."
        return None, None

    def benchmark(self, lang, cmd, cwd, iterations=5):
        print(f"  {lang:<10} ", end="", flush=True)
        # Warm-up (1-3 times)
        for _ in range(2):
            self.run_cmd(cmd, cwd=cwd)
        
        times = []
        for _ in range(iterations):
            t, out, err = self.run_cmd(cmd, cwd=cwd)
            if t < 0: 
                print("FAILED")
                return None
            # Parse time from output if possible, otherwise use wall clock
            # For now use wall clock
            times.append(t)
            print(".", end="", flush=True)
        
        avg = statistics.mean(times)
        print(f" {avg:.1f} ms")
        return avg

    def execute_all(self):
        langs = ["C", "CPP", "Java", "CSHARP", "Ocean"]
        categories = [
            ("cpu_primes", "Primes"),
            ("memory_stress", "ArrayBench"),
            ("oop_gc", "NodesBench"),
            ("call_overhead", "CallsBench"),
            ("math_fp", "MathBench"),
            ("ds_maps", "HashMapBench"),
            ("thread_parallel", "ParallelSum"),
            ("io_bound", "IOBench"),
            ("mixed_world", "JsonBench")
        ]

        for cat_dir, short_name in categories:
            print(f"\n[Category: {cat_dir}]")
            self.results[cat_dir] = {}
            for lang in langs:
                # Custom overrides for odd names
                sname = short_name
                if cat_dir == "cpu_primes" and lang in ["C", "CPP", "Python"]: sname = "primes"
                if cat_dir == "memory_stress" and lang in ["C", "CPP", "Python"]: sname = "array_bench"
                if cat_dir == "oop_gc" and lang in ["C", "CPP", "Python"]: sname = "nodes"
                if cat_dir == "call_overhead" and lang in ["C", "CPP", "Python"]: sname = "calls"
                if cat_dir == "math_fp" and lang in ["C", "CPP", "Python"]: sname = "math_bench"
                if cat_dir == "ds_maps" and lang in ["C", "CPP", "Python"]: sname = "hash_map"
                if cat_dir == "thread_parallel" and lang in ["C", "CPP", "Python"]: sname = "parallel_sum"
                if cat_dir == "io_bound" and lang in ["C", "CPP", "Python"]: sname = "io_bench"
                if cat_dir == "mixed_world" and lang in ["C", "CPP", "Python"]: sname = "json_bench"
                
                cmd, cwd = self.get_run_command(cat_dir, lang, sname)
                if cmd:
                    self.results[cat_dir][lang] = self.benchmark(lang, cmd, cwd)

    def print_report(self):
        # Generate Markdown Table
        header = "| Category | C | C++ | Java | C# | Ocean |"
        sep = "| :--- | :---: | :---: | :---: | :---: | :---: |"
        print("\n\nFINAL REPORT\n")
        print(header)
        print(sep)
        for cat, data in self.results.items():
            row = f"| {cat} |"
            for lang in ["C", "CPP", "Java", "CSHARP", "Ocean"]:
                val = data.get(lang)
                row += f" {val:.0f} ms |" if val else " N/A |"
            print(row)

if __name__ == "__main__":
    runner = UltraRunner()
    runner.compile_all()
    runner.execute_all()
    runner.print_report()
