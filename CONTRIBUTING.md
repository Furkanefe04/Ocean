# Contributing to Ocean

Thank you for your interest in contributing to the **Ocean Programming Language**!  
We welcome contributions ranging from bug fixes, compiler optimizations, standard library improvements, to documentation and examples.

---

## 🛠️ Development Setup & Prerequisites

- **Java Development Kit (JDK):** Version 21 or higher (OpenJDK, Temurin, or Oracle JDK).
- **Gradle:** 8.12+ (or use the included `./gradlew` wrapper).
- **Python:** 3.10+ (optional, for benchmark analysis and audit scripts).
- **PowerShell:** 7+ / Windows PowerShell (for running the comprehensive empirical test suites on Windows).

---

## 🏗️ Building from Source

To compile the ANTLR grammar, Java compiler sources, and package the standalone executable JAR:

```bash
# Clone the repository (once hosted on GitHub)
git clone https://github.com/<your-username>/Ocean.git
cd Ocean

# Build standalone executable shadow JAR (build/libs/Ocean-all.jar)
./gradlew shadowJar
```

---

## 🧪 Running Tests

Ocean maintains rigorous quality gates with over 1,600+ automated test cases. Before submitting any changes, ensure all tests pass:

### 1. Gradle JUnit Suite (1,220+ tests)
```bash
./gradlew test
```

### 2. Empirical & Logical Correctness Suites (PowerShell)
```powershell
# Logical execution of compiled Ocean programs (306 tests)
powershell -ExecutionPolicy Bypass -File test_logical_correctness.ps1

# Negative compilation diagnostics & compiler error detection (68 tests)
powershell -ExecutionPolicy Bypass -File test_negative_correctness.ps1

# Interactive feature verification (6 tests)
powershell -ExecutionPolicy Bypass -File test_interactive_correctness.ps1

# Error rejection & exit code validation (15 tests)
powershell -ExecutionPolicy Bypass -File test_error_correctness.ps1
```

---

## 🏛️ Compiler Architecture Guidelines

Ocean employs a modern multi-pass compiler pipeline:

1. **Frontend (`src/main/antlr/Ocean.g4`):** ANTLR4 language grammar. If you modify `.g4`, run `./gradlew generateGrammarSource` to regenerate AST visitors.
2. **PreScanner (`src/main/java/ocean/compiler/PreScanner.java`):** Resolves symbols, types, and forward declarations before AST lowering.
3. **Intermediate Representation (`ocean.compiler.ir.*`):** Typed high-level IR nodes decoupling semantic analysis from emission.
4. **IR Optimizers:** Constant folding (`IRConstantFolder`), dead-code elimination (`IRDeadCodeEliminator`), method inlining (`IRMethodInliner`), tail-recursion (`IRTailRecOptimizer`).
5. **Backend (`ocean.compiler.IRToBytecodeEmitter.java`):** Emits clean JVM 17/21 bytecode using OW2 ASM, attaching `@ocean.compiler.Metadata` to all generated classes.
6. **Standard Library (`ocean.stdlib.*`):** High-performance boxing-free primitive collections (`OceanIntList`, etc.), concurrency (`Task`), and runtime utilities.

---

## 📬 Submitting Changes

1. Fork the repository and create a descriptive branch:
   ```bash
   git checkout -b feature/my-cool-feature
   # or
   git checkout -b fix/issue-123
   ```
2. Commit your changes with clear, concise messages.
3. Verify that `./gradlew test` passes with zero regressions.
4. If introducing a new language feature or syntax, add corresponding `.ocean` test cases in `examples/` and a unit test in `src/test/java/ocean/compiler/`.
5. Open a Pull Request with a clear description of your changes and motivation.
