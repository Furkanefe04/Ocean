<p align="center">
  <img src="assets/ocean.svg" alt="Ocean Programming Language Logo" width="128" height="128" />
</p>

<h1 align="center">Ocean Programming Language</h1>

<p align="center">
  <strong>A modern, statically-typed, high-performance programming language designed for the Java Virtual Machine (JVM).</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-0077b6.svg" alt="License: Apache 2.0"></a>
  <a href="BENCHMARK.md"><img src="https://img.shields.io/badge/Benchmarks-10M_Ops_%7C_6.4x_faster-f72585.svg" alt="Benchmarks: 10M Ops | 6.4x faster"></a>
  <img src="https://img.shields.io/badge/Bytecode_Target-JVM_17_%7C_21+-0096c7.svg" alt="Bytecode Target: JVM 17 | 21+">
  <img src="https://img.shields.io/badge/Compiler_JDK-Java_21+-48cae4.svg" alt="Compiler JDK: Java 21+">
  <img src="https://img.shields.io/badge/Verification-1600%2B_Passing_%28100%25%29-52b788.svg" alt="1600+ Tests Passing">
  <img src="https://img.shields.io/badge/Frontend-ANTLR4-023e8a.svg" alt="Frontend: ANTLR4">
  <img src="https://img.shields.io/badge/Bytecode-OW2_ASM-03045e.svg" alt="Bytecode: OW2 ASM">
</p>

---

## 🌊 Overview

**Ocean** is a statically-typed programming language that combines developer ergonomics (type inference, compile-time null safety, data classes, pattern matching, extension methods, and virtual threads) with high-performance JVM execution.

The compiler frontend parses source files using **ANTLR4**, resolves circular dependencies and type boundaries via an early **PreScanner**, lowers code into a strongly-typed **Intermediate Representation (IR)**, applies multi-pass IR optimizations (constant folding, method inlining, dead-code elimination), and emits clean JVM bytecode via **OW2 ASM**.

> 📘 **Looking for the exhaustive language specification?**  
> See the [**Complete Syntax Guide (`Ocean_Architecture_Docs/OCEAN_SYNTAX_GUIDE.txt`)**](Ocean_Architecture_Docs/OCEAN_SYNTAX_GUIDE.txt) for every keyword, grammar rule, and operator.  
>  
> ⚡ **Looking for empirical benchmark data?**  
> See the [**Official Benchmark Report (`BENCHMARK.md`)**](BENCHMARK.md) for full multi-language benchmarks against Java, Kotlin, C# (.NET 9), and C++ (GCC -O3).

---

## 🎯 Why Ocean?

Ocean is designed to address common pain points in JVM development without sacrificing the immense power of the Java ecosystem:

1. **Zero-Overhead Java Ecosystem Interop:** Import, instantiate, inherit, and call any Java standard library or third-party class directly with zero binding overhead.
2. **Compile-Time Null Safety:** Eliminates `NullPointerException` at compile time through distinct non-nullable (`T`) and nullable (`T?`) types, safe navigation (`?.`), and Elvis unwrapping (`??`).
3. **High-Performance Unboxed Primitive Collections:** Native support for specialized unboxed collections (`OceanIntList`, `OceanDoubleList`, `OceanLongList`), completely bypassing `java.lang.Integer` heap allocation and autoboxing bottlenecks.
4. **Modern Ergonomics Without Runtime Bloat:** Concise data classes, string interpolation (`$"..."`), stepped loops, declaration-site variance (`<+T>`, `<-T>`), and extension methods compile down to direct, efficient bytecode.
5. **Project Loom Concurrency Built-In:** First-class `async` and `await` keywords mapped to JVM `CompletableFuture`, paired with non-blocking virtual thread utilities (`Task.sleep`, `Task.all`, `Task.race`).
6. **Automatic Incremental Compilation:** Tracks file dependency fingerprints and timestamps to recompile only modified classes, caching session metadata in `compiler_cache.dat` with no manual configuration flags required.

---

## ⚡ Quick Start (Under 30 Seconds)

### Option A: Install Standalone SDK (Recommended)
1. Download **`ocean-1.0.0.zip`** from [GitHub Releases](https://github.com/Furkanefe04/Ocean/releases).
2. Extract the archive to your preferred location (e.g. `C:\ocean` or `/opt/ocean`).
3. Add the `bin/` directory to your system `PATH`.
4. Run:
   ```bash
   ocean --version
   ocean examples/Hello.ocean
   ```

### Option B: Build from Source
```bash
# 1. Clone the repository
git clone https://github.com/Furkanefe04/Ocean.git
cd Ocean

# 2. Build the compiler
# On Windows
.\gradlew.bat shadowJar

# On Linux / macOS
./gradlew shadowJar

# 3. Run the Included Hello World Example
# On Windows
.\ocean.bat examples\Hello.ocean

# On Linux / macOS
./ocean examples/Hello.ocean
```

**Expected Output:**
```text
Hello, Ocean!
```

### CLI Command Options
```text
Usage:
  ocean <source.ocean> [args...]       Compile and execute an Ocean program
  ocean -c <source.ocean>              Compile to bytecode (.class) only
  ocean --version | -v                 Display compiler version
  ocean --help | -h                    Display this help message

Options:
  -cp, -classpath <path>               Specify additional classpath directories or JARs
  -c, --compile-only                   Compile source files without running main
  --json                               Emit compiler diagnostics in structured JSON format
  -debug                               Enable compiler debugging logs
```

---

## 💡 Hello World Code

```ocean
package examples;

class Hello {
    main() {
        OceanOutput("Hello, Ocean!");
    }
}
```

---

## 🚀 Key Highlights

- **🛡️ Full Null Safety:** Strict compile-time tracking ensures nullable references cannot be assigned to non-null variables or dereferenced without safe calls.
- **⚡ Unboxed Primitive Collections:** Specialized `OceanIntList`, `OceanDoubleList`, and `OceanLongList` provide zero-allocation primitive array backing.
- **🧵 Virtual Threads:** Seamless async workflows powered by Java Project Loom (`async`, `await`, `Task.sleep`, `Task.all`).
- **📦 Concise Data Classes:** Synthesizes constructors, getters, setters, `equals`, `hashCode`, `toString`, and immutable `copy()` in one line.
- **🧩 Extension Methods:** Add functions to existing classes and primitives (`String.function reverse()`, `int.function square()`) via static dispatch.
- **🧬 Advanced Generics & Variance:** Declaration-site covariance (`<+T>`), contravariance (`<-T>`), and lower bounds (`<T >: Integer>`) emit standard JVM generic signatures.
- **🔄 Automatic Incremental Engine:** Enabled out-of-the-box; recompiles only dirty sources based on dependency graphs.

---

## 📖 Language Tour & Syntax

Ocean uses an expressive, unambiguous grammar designed for clarity and safety.

### 1. Variables & Immutability

Ocean distinguishes between mutable and immutable variables, with optional type inference:

| Syntax | Type Resolution | Reassignable? | Description |
|---|---|---|---|
| `int count = 10;` | Explicit | Yes (Mutable) | Standard mutable variable |
| `variable x = 10;` | Inferred (`int`) | Yes (Mutable) | Local mutable variable |
| `value y = 3.14;` | Inferred (`double`) | **No (Immutable)** | Local immutable constant |
| `final int z = 100;` | Explicit | **No (Immutable)** | Explicit immutable constant |

```ocean
package com.demo;

int count = 10;
variable status = "Active"; // Inferred as String, mutable
value pi = 3.14159;         // Inferred as double, immutable (cannot be reassigned)
final int MAX_LIMIT = 500;  // Explicitly typed immutable

count += 5;
count++;
```

---

### 2. Compile-Time Null Safety

Types are non-null by default. Nullable types append `?`:

```ocean
String name = "Ocean";
// name = null;               // COMPILE ERROR: Non-null type cannot be assigned null

String? optionalName = null;  // OK: Nullable type

// Assignment restriction
String safe = "Default";
// safe = optionalName;       // COMPILE ERROR: Cannot assign nullable 'String?' to non-null 'String'

// Safe unwrap with Elvis operator (??)
safe = optionalName ?? "Fallback"; // OK: Evaluates to fallback if null

// Safe navigation operator (?.)
int? length = optionalName?.length(); // Returns null safely without throwing NPE
// int err = optionalName.length();   // COMPILE ERROR: Cannot dereference nullable type directly

// Smart Casting (instanceof pattern matching)
Object obj = "Ocean Language";
if (obj instanceof String s) {
    OceanOutput($"Length: {s.length()}");
}
```

---

### 3. Strings & Collection Literals

```ocean
// 1. String Interpolation ($"...") and Text Blocks
variable user = "Furkan";
variable year = 2026;
String greeting = $"User: {user}, Year: {year}";

String block = """
    Multi-line text block
    with preserved formatting.
    """;

// 2. High-Performance Zero-Allocation Unboxed Primitive Lists
OceanIntList numbers = [10, 20, 30, 40, 50];            // Unboxed primitive int[] backing
OceanDoubleList rates = [1.5, 2.7, 3.14];               // Unboxed primitive double[] backing

// 3. Specialized Ocean Collection Literals
OceanList<String> frameworks = ["Spring", "Ktor", "Ocean"];      // OceanList literal [...]
OceanSet<String> uniqueTags = #{ "jvm", "compiler", "ocean" };   // OceanSet literal #{...}
OceanMap<String, int> scores = { "Alice": 95, "Bob": 88 };       // OceanMap literal {...}

// 4. Raw JVM Array Literals
int[] rawArray = { 1, 2, 3, 4 };

// 5. Range Slicing ([start..end])
// RULE: Slicing bounds are INCLUSIVE on both ends.
OceanIntList slice = numbers[1..3];                     // [20, 30, 40] (indices 1, 2, 3)
int[] arraySlice = rawArray[0..2];                      // { 1, 2, 3 }
```

---

### 4. Control Flow & Loop Jumps

Ocean features stepped ranges, explicit `stop` (break) and `skip` (continue) statements, and labeled jumps:

```ocean
// 1. Stepped For Loop (from ... to ... with increasing/decreasing)
// RULE: The 'to' boundary is EXCLUSIVE (loops while i < 10 for increasing).
for (int i from 1 to 10 with increasing 1) {
    OceanOutput($"Step: {i}"); // Prints 1 to 9
}

for (variable j from 100 to 0 with decreasing 10) {
    OceanOutput($"Countdown: {j}"); // Prints 100 down to 10 (0 excluded)
}

// 2. Foreach (For-In)
for (int num in numbers) {
    if (num == 20) skip;  // skip iteration (continue)
    if (num > 40) stop;   // break loop (break)
    OceanOutput(num);
}

// 3. Labeled Loops
outer: for (int a from 1 to 5 with increasing 1) {
    inner: for (int b from 1 to 5 with increasing 1) {
        if (a * b == 6) stop outer; // Breaks outer loop
        if (b == 2) skip inner;     // Skips to next inner step
    }
}
```

---

### 5. Switch Statements & Expressions

Switch constructs support both statement blocks and expression returns using the `result` keyword:

```ocean
// 1. Switch Statement
switch (score) {
    case 100:
        OceanOutput("Perfect Score!");
        stop;
    case 90 -> OceanOutput("Grade: A");
    default -> OceanOutput("Passing");
}

// 2. Switch Expression (Yields a value)
// Single-line arrow branches omit semicolons; the outer variable assignment ends with ';':
variable label = switch (score) {
    case 100 -> "Perfect"
    case 90 -> "Grade A"
    case 50 -> {
        // Multi-statement block branches yield value using 'result':
        variable note = "Needs Improvement";
        result note;
    }
    default -> "Standard"
};
```

---

### 6. Classes, Constructors & Data Classes

Ocean uses standard, explicit object-oriented constructor declarations for regular classes:

```ocean
// 1. Standard Class & Inheritance
public class Person {
    protected String name;
    protected int age;

    public function Person(String name, int age) {
        this.name = name;
        this.age = age;
    }

    public String function getName() { return this.name; }
    public int function getAge() { return this.age; }
}

public class Employee extends Person {
    private double salary;

    public function Employee(String name, int age, double salary) {
        super(name, age);
        this.salary = salary;
    }

    public double function getSalary() {
        return this.salary;
    }
}

// 2. Data Classes (Value Containers)
// IMPORTANT: Primary constructor syntax is EXCLUSIVE to data classes.
// Data classes cannot extend other classes; the compiler synthesizes
// constructor, getters, setters, equals, hashCode, toString, and copy():
public data class Point(int x, int y);

// Instantiation:
Point p1 = new Point(10, 20);
Point p2 = p1.copy(x = 15); // Immutable copy with named field override
OceanOutput(p1.toString()); // Point(x=10, y=20)
```

---

### 7. Sealed Hierarchies

Restricts permitted subclasses explicitly via the `restricts` clause:

```ocean
public sealed class Shape restricts Circle, Rectangle {
    public abstract double function calculateArea();
}

public non-sealed class Circle extends Shape {
    private double radius;
    public function Circle(double radius) {
        this.radius = radius;
    }
    @Override
    public double function calculateArea() {
        return 3.14159 * this.radius * this.radius;
    }
}

public final class Rectangle extends Shape {
    private double width;
    private double height;
    public function Rectangle(double width, double height) {
        this.width = width;
        this.height = height;
    }
    @Override
    public double function calculateArea() {
        return this.width * this.height;
    }
}
```

---

### 8. Generics, Variance & Bounds

Ocean supports declaration-site variance and full JVM generic signatures:

```ocean
// 1. Covariance (+T / Producer / Out)
// 'T' is permitted in return positions.
// Note: Constructor parameters are exempt because constructors initialize instances.
public class Producer<+T> {
    private T item;
    public function Producer(T item) { this.item = item; }
    public T function produce() { return this.item; }
}

// 2. Contravariance (-T / Consumer / In)
// 'T' is permitted only in method parameter positions.
public class Consumer<-T> {
    public void function consume(T item) {
        OceanOutput($"Consumed: {item}");
    }
}

// 3. Upper Bound (<: / extends) & Lower Bound (>:)
public class NumberBox<T <: Number> {
    public double function toDouble(T val) {
        return val.doubleValue();
    }
}

public class LowerBoundBox<T >: Integer> {
    private T value;
    public function LowerBoundBox(T value) { this.value = value; }
    public T function getValue() { return this.value; }
}
```

---

### 9. Extension Methods

Augment existing types with new functionality via static dispatch without wrapper objects:

```ocean
public class Extensions {
    // Extend String
    public String String.function reverse() {
        return new StringBuilder(this).reverse().toString();
    }

    // Extend primitive int
    public int int.function square() {
        return this * this;
    }
}

// Usage:
// "Ocean".reverse();  -> "naecO"
// 5.square();         -> 25
```

---

### 10. Exception Handling (`trying`)

Ocean uses the `trying` keyword for exception handling and try-with-resources:

```ocean
public class ExceptionDemo {
    public static void function run() {
        // Multi-catch & standard handling
        trying {
            if (true) throw new IllegalArgumentException("Invalid state");
        } catch (IllegalArgumentException | NullPointerException e) {
            OceanOutput($"Handled error: {e.getMessage()}");
        } finally {
            OceanOutput("Cleanup executed.");
        }

        // Try-With-Resources (Automatic AutoCloseable management)
        trying (FileInputStream fis = new FileInputStream("app.config")) {
            fis.read();
        } catch (IOException e) {
            OceanOutput("I/O error occurred.");
        }
    }
}
```

---

### 11. Concurrency, Virtual Threads & Async/Await

Ocean provides first-class support for Java Project Loom virtual threads and async workflows:

```ocean
import java.util.concurrent.CompletableFuture;
import ocean.stdlib.Task;

public class ConcurrencyDemo {
    private int counter = 0;
    private Object lockObject = new Object();

    // Synchronized method
    public sync void function incrementSync() {
        this.counter++;
    }

    // Monitor lock statement
    public void function incrementLock() {
        lock (this.lockObject) {
            this.counter++;
        }
    }

    // Async method: returns CompletableFuture<String> on the JVM
    public static async String function fetchUserData(String userId) {
        Task.sleep(50); // Non-blocking virtual thread sleep
        return $"UserData({userId})";
    }

    // Await execution
    public static async void function run() {
        CompletableFuture<String> f1 = fetchUserData("user_101");
        CompletableFuture<String> f2 = fetchUserData("user_102");

        // Wait for all virtual thread futures:
        await Task.all(f1, f2);

        String u1 = await f1;
        String u2 = await f2;
        OceanOutput($"Fetched: {u1}, {u2}");
    }
}
```

---

### 12. Program Entry Point (`main`)

Ocean provides several flexible and ergonomic ways to define application entry points. The compiler automatically synthesizes and emits the standard JVM `public static void main(String[] args)` bytecode:

```ocean
// 1. Most Idiomatic & Concise Entry Point (No boilerplate static/void/args required)
class Application {
    main() {
        OceanOutput("Hello from Ocean Programming Language!");
    }
}

// 2. With Command-Line Arguments
class AppWithArgs {
    main(String[] args) {
        for (String arg in args) {
            OceanOutput($"Argument: {arg}");
        }
    }
}

// 3. Waiting on Asynchronous Tasks (The .join() Rule)
// Because main() is synchronous, call .join() on async futures to prevent
// the JVM process from exiting before background tasks complete:
class AsyncApp {
    main() {
        ConcurrencyDemo.run().join();
    }
}

// 4. Explicit Static (Optional)
class StaticApp {
    static main() {
        OceanOutput("Static main entry point");
    }
}

// 5. Traditional Verbose JVM Style (Optional)
class TraditionalApp {
    public static main(String[] args) {
        OceanOutput("Standard verbose JVM entry point");
    }
}
```

---

## ☕ Java Interoperability

Ocean is built for zero-friction interoperability with the entire Java ecosystem. You can import and use any Java class, generic collection, or third-party library directly:

```ocean
import java.util.ArrayList;
import java.util.HashMap;
import java.io.File;

class JavaInteropExample {
    main() {
        // Direct instantiation of Java Generic Collections
        ArrayList<String> javaList = new ArrayList<String>();
        javaList.add("Ocean");
        javaList.add("Java 21");

        for (String item in javaList) {
            OceanOutput($"From Java List: {item}");
        }

        // Interacting with Java Filesystem APIs
        File projectFile = new File("build.gradle");
        if (projectFile.exists()) {
            OceanOutput($"Found file of size: {projectFile.length()} bytes");
        }
    }
}
```

---

## 📚 Standard Library (`ocean.stdlib`)

Ocean ships with a built-in standard library focused on performance, concurrency, and developer ergonomics:

- **Unboxed Primitive Collections:** `OceanIntList`, `OceanLongList`, `OceanDoubleList`, `OceanFloatList`, `OceanByteList`, `OceanShortList`, `OceanCharList` (high-speed unboxed arrays avoiding heap allocation).
- **Generic Collections:** `OceanList<T>`, `OceanMap<K, V>`, `OceanSet<T>`, `OceanStack<T>`, `OceanQueue<T>`, `OceanCounter`.
- **Functional Types:** `OceanOptional<T>`, `OceanResult<T, E>`, `OceanPair<A, B>`, `OceanTriple<A, B, C>`.
- **Concurrency & Virtual Threads:** `Task` (`Task.sleep`, `Task.all`, `Task.race`, `Task.isVirtual`), `ScopedValue`.
- **I/O & Networking:** `OceanFile`, `OceanHttp`, `OceanHttpResponse`, `OceanJson`.
- **Text & Time Utilities:** `OceanString`, `OceanStringBuilder`, `OceanRegex`, `OceanDate`, `OceanTime`.

> For complete class-level details, refer to the [Complete Syntax Guide](Ocean_Architecture_Docs/OCEAN_SYNTAX_GUIDE.txt).

---

## 🏛️ Compiler Pipeline & Architecture

The Ocean compiler follows an 8-stage decoupled architecture:

```
+-----------------------------------------------------------------------------------+
|                                Ocean CLI / Runner                                 |
|                  (OceanRunner -> OceanRunnerV3 / CompilationSession)              |
+-----------------------------------------------------------------------------------+
                                         |
     +-----------------------------------+-----------------------------------+
     |                                                                       |
     v                                                                       v
+-----------------------------+                         +-----------------------------+
|    Incremental Engine       |                         |      Compiler Registry      |
|  - Dependency Tracking      |                         |  - Global Type / Signatures |
|  - Timestamp & Fingerprint  |                         |  - ThreadLocal Session Data |
|  - Binary & Cache Manager   |                         |  - Dynamic Type Resolution  |
+-----------------------------+                         +-----------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                 Compilation Engine                                |
|                                                                                   |
|  1. Lexer & Parser (ANTLR4) ---> CST / ParseTree                                  |
|  2. PreScanner (Pass 1)     ---> Discovers Symbols & Circular Type Bounds         |
|  3. IRGenerator (Pass 2)    ---> Constructs Object-Oriented IR AST                |
|  4. IRSemanticAnalyzer      ---> Type Checking, Access Modifiers, Definite Assign |
|  5. IROptimizerPipeline     ---> Method Inlining, Constant Folding, Dead Code     |
|  6. IRToBytecodeEmitter     ---> Generates JVM Bytecode via OW2 ASM               |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                        JVM Runtime & Standard Library                             |
|  - Specialized Unboxed Primitive Collections (OceanIntList, OceanDoubleList, ...) |
|  - Runtime Utilities (OceanString, OceanJson, OceanHttp, Task)                    |
|  - Seamless Java Interoperability & Reflection Fallback                           |
+-----------------------------------------------------------------------------------+
```

### Pipeline Overview:

| Stage | Main Classes | Output | Description |
|---|---|---|---|
| **1. Incremental Check** | `OceanRunnerV3`, `CompilationSession` | Dirty File Set | Checks timestamps and dependency fingerprints to compile only changed sources. |
| **2. Parallel Parsing** | `OceanLexer`, `OceanParser` | ANTLR ParseTree | Tokenizes and parses `.ocean` sources in parallel with shift disambiguation. |
| **3. Pre-Scanning** | `PreScanner`, `CompilerRegistry` | Populated Registries | Discovers packages, classes, supertypes, interfaces, method headers, and type parameters. |
| **4. IR Construction** | `IRGenerator`, `OceanTypeSystem` | Typed `IRNode` Tree | Lowers parse tree into an object-oriented IR representation (`IRClass`, `IRMethod`, `IRBlock`). |
| **5. Semantic Analysis** | `IRSemanticAnalyzer`, `TypeChecker` | Verified IR Tree | Validates typing, nullability, definite assignment, sealed rules, and override contracts. |
| **6. IR Optimization** | `IROptimizerPipeline` | Optimized IR Tree | Inlines methods, folds compile-time constants, and eliminates dead/unreachable branches. |
| **7. Bytecode Emission** | `IRToBytecodeEmitter` | `.class` Bytecode | Generates JVM 17/21 bytecode with precise debug line-number tables via OW2 ASM. |
| **8. Execution** | `OceanRunnerV3` | Program Output | Loads compiled classes via an isolated `MemoryClassLoader` and executes `main()`. |

---

## 🧪 Comprehensive Verification Suite

Ocean includes an exhaustive verification suite with **1,600+ automated tests** verifying compiler accuracy, language semantics, bytecode generation, runtime behavior, and diagnostics:

| Test Suite | Total Tests | Pass Rate | Scope |
|---|---|---|---|
| **Gradle Unit Tests** | 1,220+ | **100%** | AST, IR generator, bytecode emitter, reflection, type checking, and optimizer |
| **Logical Correctness** | 308 | **100%** | Full-program compiler execution, virtual threads, and runtime validation |
| **Negative Correctness** | 68 | **100%** | Compiler diagnostic checks, type boundaries, and syntax error rejection |
| **Interactive Tests** | 6 | **100%** | Interactive console inputs, simulations, and algorithmic benchmarks |
| **TOTAL VERIFIED** | **1,602+** | **100%** | **Comprehensive automated verification across the entire compiler stack** |

### Running the Test Suites:

```bash
# 1. Run Gradle unit tests (1,220+ tests)
./gradlew test

# 2. Run logical correctness suite (308 tests, PowerShell)
.\test_logical_correctness.ps1

# 3. Run negative error diagnostics suite (68 tests, PowerShell)
.\test_negative_correctness.ps1

# 4. Run interactive suite (6 tests, PowerShell)
.\test_interactive_correctness.ps1
```

---

## ⚡ Performance & Benchmarks

Ocean is engineered for high-throughput JVM execution. By utilizing specialized unboxed primitive collections (`OceanIntList`, `OceanDoubleList`, `OceanLongList`), generating clean JVM 17 bytecode, and applying multi-pass IR optimizations, **Ocean achieves native-like execution speeds while completely eliminating autoboxing bottlenecks**.

### Multi-Language Massive Benchmark (10,000,000 Elements × 5 Rounds)
*Workload: 10,000,000 initialized elements, 5 consecutive rounds of indexed updates and foreach summation.*

| Rank | Language & Data Structure | Runtime Engine | Compile Time | 5-Round Execution | Total (Compile + Exec) | Avg / Round |
| :---: | :--- | :--- | :---: | :---: | :---: | :---: |
| 🥇 | **Ocean (`OceanIntList`)** | **JVM Bytecode (ASM)** | **588 ms** | **116 ms** | **704 ms** | **23 ms** |
| 🥈 | **Java (`ArrayList<Integer>`)** | JVM Bytecode (javac) | 974 ms | 745 ms | 1,719 ms | 149 ms |
| 🥉 | **C# (`List<int>`)** | .NET 9 RyuJIT | 2,676 ms | 391 ms | 3,067 ms | 78 ms |
| 4️⃣ | **C++ (`std::vector<int>`)** | Native GCC (`-O3`) | 3,245 ms | 74 ms | 3,319 ms | 14 ms |
| 5️⃣ | **Kotlin (`ArrayList<Int>`)** | JVM Bytecode (kotlinc) | 9,964 ms | 860 ms | 10,824 ms | 172 ms |

- 🚀 **6.42x Faster than Java in Data Workloads:** Direct contiguous primitive array storage bypasses pointer chasing and GC overhead.
- 🚀 **#1 in Developer Turnaround Velocity:** Across every algorithmic test, Ocean's fast compilation pipeline (<450 ms) paired with JIT execution delivers the fastest **Total Turnaround Time (Compile + Execution)**:

| Workload / Benchmark | Ocean (Compile + Exec) | Java (Compile + Exec) | C# (Compile + Exec) | Kotlin (Compile + Exec) | Turnaround Leader |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Sum Loop (1..1B)** | **830 ms** | 972 ms | 3,247 ms | 4,645 ms | 🥇 **Ocean** |
| **Bubble Sort (n=6,000)** | **434 ms** | 547 ms | 2,836 ms | 4,173 ms | 🥇 **Ocean** |
| **Prime Sieve (n=2,000,000)** | **434 ms** | 542 ms | 2,822 ms | 4,167 ms | 🥇 **Ocean** |
| **Reused Builder (5M cycles)** | **572 ms** | 697 ms | 2,931 ms | 4,325 ms | 🥇 **Ocean** |
| **10M Collections (5 Rounds)** | **704 ms** | 1,719 ms | 3,067 ms | 10,824 ms | 🥇 **Ocean** |

> 📊 **Full Benchmark Report, Turnaround Philosophy & Reproduction:**  
> For complete multi-language benchmark tables, methodology, and single-click reproduction scripts, see [**BENCHMARK.md**](BENCHMARK.md).
> 
> *Note: Absolute timings depend on CPU architecture, OS, thermal throttling, and background load. The primary metric of significance is the **relative speedup ratio** observed between languages under identical machine conditions.*

---

## 🛠️ Prerequisites & Building

### Prerequisites

- **Compiler Build & Execution JDK:** Version 21 or higher (Java 21 LTS recommended).
- **Emitted Bytecode Target:** Standard JVM 17 bytecode (`Opcodes.V17`), running seamlessly on Java 17, 21, and newer.
- **Gradle:** Version 8.12+ (or use the included `./gradlew` wrapper).

### Build the Compiler

To generate ANTLR parser classes and assemble the standalone fat-JAR:

```bash
# On Linux / macOS
./gradlew shadowJar

# On Windows
.\gradlew.bat shadowJar
```

The resulting executable JAR will be located at:
`build/libs/Ocean-all.jar`

### Compile & Run Source Files

```bash
# Using the Windows batch script:
.\ocean.bat path\to\YourProgram.ocean

# Or directly via Java:
java -jar build/libs/Ocean-all.jar path\to\YourProgram.ocean
```

---

## 📁 Repository Directory Structure

```text
Ocean/
├── .github/
│   └── workflows/
│       └── ci.yml                 # GitHub Actions CI workflow (Ubuntu & Windows)
├── assets/
│   ├── logo.svg                   # Official Ocean SVG logo
│   └── ocean.svg                  # Alternative vector icon
├── examples/                      # Ocean sample programs & test suite (.ocean files)
├── gradle/
│   └── wrapper/                   # Gradle wrapper binaries
├── Ocean_Architecture_Docs/       # In-depth architectural & syntax specifications
│   ├── OCEAN_SYNTAX_GUIDE.txt     # Complete Ocean syntax guide
│   ├── PROJECT.md                 # Detailed compiler architecture documentation
│   └── PROJECT_TURKISH.MD         # Turkish architecture reference
├── src/
│   ├── main/
│   │   ├── antlr/
│   │   │   └── Ocean.g4           # Official ANTLR4 language grammar
│   │   └── java/
│   │       └── ocean/
│   │           ├── Compiler/      # Compiler pipeline (AST, IR, Optimizer, Emitter)
│   │           ├── stdlib/        # Ocean standard library (unboxed collections, I/O)
│   │           └── utils/         # Internal compiler utilities
│   └── test/                      # Gradle JUnit unit & reflection tests (1,220+ tests)
├── build.gradle                   # Gradle build script & dependencies
├── gradlew / gradlew.bat          # Gradle wrapper scripts
├── ocean.bat                      # Windows CLI execution script
├── LICENSE                        # Apache License, Version 2.0
├── README.md                      # Project documentation
├── BENCHMARK.md                   # Multi-language empirical benchmark report
├── test_logical_correctness.ps1   # 308-test logical runner
├── test_negative_correctness.ps1  # 68-test negative diagnostics runner
└── test_interactive_correctness.ps1# 6-test interactive runner
```

---

## 📜 License

Ocean is licensed under the [Apache License, Version 2.0](LICENSE).  
Copyright © 2026 Furkan and Ocean Contributors.
