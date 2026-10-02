# Project Overview

Ocean is a statically-typed, high-performance programming language designed to run on the Java Virtual Machine (JVM). It combines modern language ergonomics (type inference, null safety, lambdas, pattern matching, async/await, sealed classes, inline value classes, data classes, and extension methods) with native-level performance achieved through specialized primitive collections, Ahead-of-Time (AOT) constant folding, method inlining, and an intermediate representation (IR) optimization pipeline.

The Ocean compiler frontend is built on ANTLR4 for lexical and syntactic parsing, parses source files in parallel, validates semantic constraints, translates the Abstract Syntax Tree (AST) into a typed Intermediate Representation (IR), applies multiple IR optimization passes, and emits standard JVM bytecode (target bytecode version Java 17/Opcodes.V17) using the OW2 ASM library.

---

# Architecture

The Ocean compiler architecture follows a multi-pass, decoupled, IR-driven compilation model:

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
|  2. PreScanner (Pass 1)     ---> Populates Session & Global Registries            |
|  3. IRGenerator (Pass 2)    ---> Constructs Typed High-Level IR Tree              |
|  4. IRSemanticAnalyzer      ---> Type Checking, Access Control, Definite Assign.  |
|  5. IROptimizerPipeline     ---> Inlining, Constant Folding, Dead Code Elimin.    |
|  6. IRToBytecodeEmitter     ---> Generates JVM Bytecode via OW2 ASM               |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                        JVM Runtime & Standard Library                             |
|  - High-performance unboxed primitive collections (OceanIntList, OceanDoubleList) |
|  - Runtime utilities (RuntimeUtils, OceanString, OceanJson, OceanHttp)            |
|  - Java Standard Library interoperability & Reflection fallback                   |
+-----------------------------------------------------------------------------------+
```

### Core Architecture Components

1. **Session & Registry Layer (`CompilationSession`, `CompilerRegistry`)**:
   Manages global type signatures, class hierarchies, method overloads, field layouts, sealed class constraints, and dependency graphs. Uses `ThreadLocal` delegations to enable safe parallel compilation.
2. **Parsing & AST Stage (`OceanLexer`, `OceanParser`, `OceanTokenStreamFactory`)**:
   Converts Ocean source files into ANTLR parse trees with specialized handling for nested generic tokens (`>>`, `>>>`).
3. **Pre-Scanning Stage (`PreScanner`)**:
   Extracts class names, method signatures, field types, and type bounds ahead of code generation, breaking circular dependencies between interrelated classes.
4. **Intermediate Representation Layer (`ocean.compiler.ir.*`)**:
   An explicit, object-oriented intermediate representation tree that decouples semantic checking and code generation from ANTLR parse trees.
5. **Semantic Analysis (`IRSemanticAnalyzer`, `SymbolTable`, `TypeChecker`, `OverloadResolver`)**:
   Performs type checking, nullability analysis, definite assignment verification, exception contract enforcement, access modifier checking, and smart casts directly on the IR tree.
6. **IR Optimization Pipeline (`IROptimizerPipeline`, `IRMethodInliner`, `IRConstantFolder`, `IRDeadCodeEliminator`)**:
   Applies AST-independent transformations: inlines small/pure methods, evaluates compile-time constants (arithmetic, bitwise, string interpolation), and prunes dead branches.
7. **Bytecode Emission (`IRToBytecodeEmitter`, `StackTrackingMethodVisitor`)**:
   Traverses optimized IR nodes and produces JVM bytecode class files using OW2 ASM.
8. **Runtime & Standard Library (`ocean.stdlib.*`)**:
   Specialized standard library classes providing non-boxing primitive lists, collections, functional tuples, result monads, I/O, networking, and JSON parsing.

---

# Pipeline

The Ocean compiler executes the compilation and execution flow in discrete sequential and parallel stages:

`Source (.ocean) -> Lexer/Parser -> PreScan -> IR Generation -> Semantic Analysis -> IR Optimization -> Bytecode Emission -> Cache/Disk Output -> Execution`

### Detailed Pipeline Stages

| Stage | Main Classes | Input | Output | Description |
|---|---|---|---|---|
| **1. Session & Incremental Check** | `OceanRunnerV3`, `CompilationSession` | File Paths, Cache File | Set of Dirty Sources | Checks file timestamps, declared types, and dependency fingerprints to only recompile dirty files. Loads cached metadata if valid. |
| **2. Parallel Parsing** | `OceanLexer`, `OceanParser`, `OceanTokenStreamFactory`, `OceanErrorListener` | Source Text | `ParseTree` (ProgramContext) | Tokenizes and parses Ocean source files into ANTLR parse trees in parallel. Disambiguates right-shift generic tokens. |
| **3. Pre-Scanning (Pass 1)** | `PreScanner`, `CompilerRegistry`, `SymbolTable` | `ParseTree` | Populated Registries | Discovers all package declarations, class definitions, supertypes, interfaces, method headers, fields, extension functions, and type parameters. |
| **4. IR Construction (Pass 2)** | `IRGenerator`, `SymbolTable`, `OceanTypeSystem` | `ParseTree` | High-Level `IRNode` Tree (`IRCompilationUnit`) | Converts ANTLR CST nodes into structured IR nodes (`IRClass`, `IRMethod`, `IRBlock`, `IRExpression`, etc.). Resolves variable declarations and scopes. |
| **5. Semantic Analysis** | `IRSemanticAnalyzer`, `TypeChecker`, `OverloadResolver`, `SymbolTable` | `IRNode` Tree | Verified `IRNode` Tree / Diagnostic Errors | Validates types, assignment compatibility, null-safety, definite assignment, override validity, access modifiers, sealed class constraints, and checked exceptions. |
| **6. IR Optimization** | `IROptimizerPipeline`, `IRMethodInliner`, `IRConstantFolder`, `IRDeadCodeEliminator` | `IRNode` Tree | Optimized `IRNode` Tree | 3-pass optimization: inlines method candidates, evaluates compile-time constants and operations, and eliminates dead code / unreachable branches. |
| **7. Bytecode Emission** | `IRToBytecodeEmitter`, `StackTrackingMethodVisitor` | Optimized `IRNode` Tree | `Map<String, byte[]>` (Class Bytecode) | Visits IR nodes using OW2 ASM and emits JVM `.class` bytecode with precise line number debug tables. |
| **8. Persistence & Caching** | `OceanRunnerV3`, `CompilationSession` | Bytecode Maps & Metadata | `.class` Files & `compiler_cache.dat` | Writes compiled bytecode to disk (`build/classes/java/main`) and serializes compilation session metadata and dependency graphs to cache. |
| **9. Execution (Optional)** | `OceanRunnerV3` | Bytecode / Class Name | Program Output / Exit Code | Loads generated classes using an isolated `URLClassLoader` and invokes the static `main(String[])` method. |

---

# Directory / Package Structure

```
# Ocean Compiler Derinlemesine Hata ve JVM Uyumluluk Denetimi

Ocean projesindeki mevcut compiler implementasyonunu **derinlemesine incele**. Amacın yalnızca açıkça görünen bug'ları bulmak değil; compiler'ın yanlış, eksik, hardcoded, tutarsız veya JVM seviyesinde geçersiz bytecode üretmesine yol açabilecek tüm noktaları sistematik olarak tespit etmektir.

Bu çalışma öncelikle **analiz + kök neden tespiti + çözüm önerisi** şeklinde yapılmalıdır. İnceleme sırasında mevcut davranışı gereksiz yere değiştirme veya büyük refactor önerme. Önce mevcut mimariyi ve neden böyle tasarlandığını anlamaya çalış.

---

# 1. PROJEYİ ÖNCE TANIMLA

Proje yapısı:

```text
Ocean/
├── src/
│   ├── main/
│   │   ├── antlr/
│   │   │   └── Ocean.g4
│   │   ├── java/
│   │   │   └── org/
│   │   │       └── Ocean/
│   │   │           ├── Compiler/
│   │   │           │   ├── ir/
│   │   │           │   └── legacy/
│   │   │           ├── stdlib/
│   │   │           └── utils/
│   └── generated/
│       └── java/
├── build.gradle
├── ocean.bat
├── test_logical_correctness.ps1
└── test_error_correctness.ps1
```

Önce aşağıdaki dosyaları ve yapıları incele:

1. `PROJECT.md` / `PROJECT.md` benzeri proje dokümantasyonu varsa tamamını oku.
2. `src/main/antlr/Ocean.g4`
3. `src/main/java/ocean/compiler/`
4. `src/main/java/ocean/compiler/ir/`
5. `src/main/java/ocean/stdlib/` gerektiği kadar
6. test kaynakları ve özellikle compiler testleri
7. `build.gradle`
8. `test_logical_correctness.ps1`
9. `test_error_correctness.ps1`

### ÖNEMLİ KAPSAM SINIRI

Ana inceleme kapsamı:

```text
src/main/java/ocean/compiler/
```

ancak:

```text
src/main/java/ocean/compiler/legacy/
```

**kesinlikle ana inceleme kapsamına dahil değildir.**

Legacy kodunu yalnızca mevcut compiler davranışını anlamak için gerçekten gerekli olduğu durumlarda referans olarak inceleyebilirsin; ancak legacy kodu için bug listesi, refactor önerisi veya hata raporu üretme.

---

# 2. SYNTAX'I KENDİ KAFANDAN VARSAYMA

Ocean'ın syntax'ını Java/Kotlin/C# varsayımlarıyla yorumlama.

Projede bulunan syntax dokümantasyonunu ve `Ocean.g4` grammar'ını temel al.

Özellikle aşağıdaki Ocean özelliklerinin compiler tarafındaki implementasyonlarını incele:

* `variable`
* `value`
* explicit typed variables
* `final`
* nullable types (`?`)
* `?.`
* `??`
* arrays
* List/Set/Map literals
* slicing
* `for (int i from ... to ... with increasing/decreasing ...)`
* foreach
* `skip`
* `stop`
* labeled break/continue
* switch statement
* switch expression
* `result`
* class
* `data class`
* primary constructor
* inheritance
* constructor chaining
* sealed / non-sealed
* `restricts`
* interface
* default interface methods
* enum
* annotation
* generics
* variance
* generic bounds
* extension methods
* default parameters
* varargs
* throws
* lambdas
* method references
* constructor references
* `trying`
* try/catch/finally
* try-with-resources
* `sync`
* `lock`
* `async`
* `await`
* `main`
* `join()`
* `@Inline`
* Java interop

Özellikle Java/Kotlin'deki benzer syntax'ın Ocean'da aynı semantiğe sahip olduğunu **varsayma**.

---

# 3. ANA HEDEF: COMPILER'DAKİ HATALI / EKSİK / HARDCODED YAPILARI BUL

Compiler kodunu aşağıdaki kategorilere göre tara.

## A. Açık bug'lar

Şunları tespit et:

* yanlış condition
* yanlış opcode
* yanlış descriptor
* yanlış owner
* yanlış method signature
* yanlış internal name
* yanlış generic type
* yanlış stack type
* yanlış local variable index
* yanlış scope
* yanlış symbol resolution
* yanlış inheritance resolution
* yanlış constructor resolution
* yanlış nullability kontrolü
* yanlış primitive/reference ayrımı
* yanlış boxing/unboxing
* yanlış array opcode
* yanlış return opcode
* yanlış invoke opcode
* yanlış access flag
* yanlış class flag
* yanlış method flag
* yanlış field flag
* yanlış descriptor üretimi
* yanlış generic signature üretimi
* yanlış exception handling
* yanlış control-flow generation
* yanlış label kullanımı
* yanlış branch target
* eksik jump
* unreachable kodun yanlış işlenmesi
* yanlış source-level semantic check
* yanlış IR generation
* IR → bytecode dönüşümünde bilgi kaybı

Her bug için **kök nedeni** bul.

Sadece:

> "Burada hata olabilir."

deme.

Şu zinciri mümkün olduğunca göster:

```text
Source syntax
    ↓
ANTLR parse
    ↓
semantic/type checking
    ↓
IR
    ↓
IR transformation
    ↓
bytecode emission
    ↓
JVM class structure
    ↓
JVM verification/execution
```

Bug'ın hangi aşamada başladığını belirt.

---

# 4. JVM BYTECODE VE VERIFIER DENETİMİ

Bu bölüm özellikle önemlidir.

Compiler'ın ürettiği bytecode'un JVM açısından geçerli olup olmadığını incele.

Aşağıdaki hata sınıflarını özellikle ara:

* `VerifyError`
* `ClassFormatError`
* `IncompatibleClassChangeError`
* `NoSuchMethodError`
* `NoSuchFieldError`
* `AbstractMethodError`
* `IllegalAccessError`
* `InstantiationError`
* `BootstrapMethodError`
* `ExceptionInInitializerError`
* `IncompatibleClassChangeError`
* yanlış stack map frame
* yanlış operand stack height
* operand stack type mismatch
* yanlış local variable type
* primitive/reference mismatch
* constructor invocation kurallarının ihlali
* `<init>` çağrısının yanlış kullanılması
* `uninitializedThis`
* constructor'da `this` kullanımı
* constructor delegation problemleri
* `INVOKEVIRTUAL` / `INVOKEINTERFACE` / `INVOKESTATIC` / `INVOKESPECIAL` yanlışlığı
* yanlış field opcode
* `GETFIELD` / `PUTFIELD` / `GETSTATIC` / `PUTSTATIC` yanlışlığı
* `IRETURN`, `LRETURN`, `FRETURN`, `DRETURN`, `ARETURN`, `RETURN` yanlış kullanımı
* primitive array opcode hataları
* `NEWARRAY` / `ANEWARRAY` / `MULTIANEWARRAY`
* boxing/unboxing hataları
* method descriptor ile gerçek stack değerlerinin uyuşmaması
* method return descriptor ile gerçek return opcode'un uyuşmaması
* static/non-static method ayrımının bozulması
* static/non-static field ayrımının bozulması
* interface/class invocation uyuşmazlığı
* superclass/interface metadata uyuşmazlığı
* access flags uyuşmazlığı
* final class/method ihlalleri
* abstract method implementasyon eksiklikleri
* bridge/synthetic method gerektiren durumlar
* generic erasure sonrası signature problemleri
* inner class / outer class metadata problemleri
* nestmate / enclosing method / inner class attribute problemleri
* lambda/metafactory bytecode problemleri
* invokedynamic problemleri
* exception handler range problemleri
* label/frame tutarsızlıkları
* unreachable bytecode
* branch sonrası yanlış frame
* try/catch içinde stack state uyuşmazlığı

Özellikle ASM kullanılıyorsa ASM API'sinin doğru kullanılıp kullanılmadığını incele.

Gerekli gördüğün yerlerde:

```text
ClassWriter
MethodVisitor
FieldVisitor
ClassVisitor
Label
Type
Opcodes
Handle
ConstantDynamic
```

kullanımlarını JVM specification açısından değerlendir.

---

# 5. HARD-CODED YAPILARI TESPİT ET

Compiler içinde gelecekte bug oluşturabilecek hardcoded davranışları bul.

Örneğin:

* belirli class isimlerine özel davranış
* belirli method isimlerine özel opcode
* belirli primitive type'lara özel ama diğer eşdeğer type'ları unutan logic
* belirli package isimlerine bağlı davranış
* belirli generic type'lara özel kontrol
* `"java.lang.Object"` gibi stringlerin gereksiz şekilde dağınık kullanılması
* descriptor'ların elle string olarak oluşturulması
* getter/setter isimlerinin hardcoded oluşturulması
* constructor isimlerinin hardcoded olması
* `main` detection logic'inin fragile olması
* inner class isimlerinin hardcoded hesaplanması
* synthetic method isimlerinin hardcoded olması
* JVM internal name'lerinin yanlış/dağınık oluşturulması
* primitive wrapper mapping'lerinin birden fazla yerde farklı olması
* opcode mapping'lerinin dağınık olması
* nullability kararlarının hardcoded olması
* data class davranışının record davranışıyla karıştırılması

Her hardcoded yapı için:

1. Neden problem?
2. Hangi durumda bug üretir?
3. Mevcut kodda nerede?
4. Daha doğru abstraction ne?
5. Büyük refactor gerekli mi?
6. Minimal düzeltme mümkün mü?

belirt.

---

# 6. DATA CLASS ≠ JAVA RECORD

Bu konuya özel ve derinlemesine bir analiz yap.

Ocean `data class` ile Java `record` aynı şey değildir.

Ocean data class'larının Java record'a otomatik olarak indirgenip indirgenemeyeceğini veya mevcut compiler'ın yanlışlıkla record semantiği uygulayıp uygulamadığını incele.

Ocean data class şu özelliklere sahip olabilir:

* getter/setter
* mutable fields
* superclass
* child classes
* inheritance
* constructor davranışları
* generated methods
* `copy`
* `equals`
* `hashCode`
* `toString`
* extension methods
* normal class üyeleri
* Ocean'a özgü semantic özellikler

Syntax kılavuzundaki data class tanımını referans al. Data class'ın otomatik olarak `equals`, `hashCode`, `toString`, `copy` üretebildiği belirtiliyor.

Java record ise JVM/Java açısından farklı semantik ve kısıtlamalara sahiptir.

## İstediğim analiz

Compiler'da data class ve record arasında şu anda herhangi bir yanlış eşleştirme olup olmadığını bul.

Özellikle:

* `ACC_RECORD`
* Record attribute
* RecordComponent
* canonical constructor
* accessor method
* field semantics
* finality
* inheritance
* superclass
* subclass
* mutable state
* setter
* generated methods
* reflection behavior
* JVM class file representation

konularını incele.

### Çözüm önerisi üret

Data class'ı Java record'dan ayırmak için en uygun compiler modelini öner.

Örneğin:

```text
isDataClass
isRecord
```

gibi flag'lerin gerekli olup olmadığını değerlendir.

Ancak sadece flag eklemeyi çözüm olarak kabul etme.

Şunları da incele:

```text
Parser
    ↓
AST / semantic model
    ↓
IRClass
    ↓
IRField
    ↓
IRMethod
    ↓
IR → JVM bytecode
```

Data class bilgisinin hangi aşamada kaybolduğunu veya record ile yanlış birleştirildiğini tespit et.

Özellikle data class'ın inheritance destekleyebilmesi halinde Java record'a dönüştürülmesinin neden semantik olarak yanlış olabileceğini analiz et.

---

# 7. FIXME'LERİ SİSTEMATİK OLARAK İNCELE

Compiler içerisindeki bütün:

```text
FIXME
```

etiketlerini bul.

Her FIXME için:

1. Dosya
2. Satır
3. FIXME metni
4. Bug'ın gerçek nedeni
5. Bug'ın hangi compiler aşamasından kaynaklandığı
6. Mevcut davranış
7. Beklenen davranış
8. Önerilen çözüm
9. Yan etkiler
10. Test senaryosu

hazırla.

FIXME açıklaması eski veya eksik görünüyorsa bunu ayrıca belirt.

Bir FIXME'nin artık çözüldüğünü düşünüyorsan bunu da kanıtla.

---

# 8. COMPILER MİMARİSİNİ KORU

Öneriler verirken mevcut mimariyi tamamen çöpe atıp:

> "Bunu baştan yaz."

gibi öneriler üretme.

Öncelikle mevcut architecture üzerinden minimal ve güvenli çözüm üret.

Özellikle:

```text
ANTLR
↓
PreScanner / parsing
↓
semantic analysis
↓
type checking
↓
IR
↓
IR optimization
↓
ASM emission
↓
.class
```

zincirindeki mevcut sorumlulukları korumaya çalış.

Bir refactor öneriyorsan:

* neden gerekli?
* hangi bug'ı çözüyor?
* hangi mevcut davranışı riske atıyor?
* minimal alternatif var mı?

sorularını cevapla.

---

# 9. TYPE SYSTEM DENETİMİ

Ocean type system'i ayrıca incele.

Özellikle:

* primitive
* boxed/reference
* nullable primitive
* nullable reference
* generic type
* generic return type
* wildcard/bound
* variance
* arrays
* generic arrays
* Object
* interface
* superclass
* inner class
* anonymous/synthetic class
* data class

arasındaki ayrımların compiler'da tutarlı olup olmadığını kontrol et.

Şu tip hataları özellikle ara:

```text
primitive → reference
reference → primitive
nullable → non-null
non-null → nullable
generic → erased type
boxed → primitive
primitive array → reference array
interface → class
class → interface
```

Her dönüşümün JVM bytecode seviyesinde doğru olup olmadığını da kontrol et.

---

# 10. INHERITANCE / INNER CLASS / CONSTRUCTOR DENETİMİ

Özellikle aşağıdakileri incele:

* superclass resolution
* interface implementation
* nested class
* static inner class
* non-static inner class
* outer instance
* constructor synthetic parameters
* `this`
* `super`
* constructor chaining
* primary constructor
* generated constructor
* overloaded constructor
* generic superclass
* generic inner class
* inner class inheritance

Compiler'ın inner class ↔ outer class ilişkisini nasıl tuttuğunu incele.

Özellikle map/registry tabanlı yapıların:

```text
globalInnerClassOuterMap
globalInnerClassUsedOuterMap
```

gibi yapılarda zincirleme inheritance/outer resolution sırasında mutation veya traversal problemleri oluşturup oluşturmadığını kontrol et.

Aynı zamanda:

* duplicate class detection
* nested duplicate class
* FQCN generation
* JVM internal name generation

arasındaki tutarlılığı denetle.

---

# 11. METHOD INVOCATION DENETİMİ

Tüm method invocation noktalarını incele.

Şunların doğru seçildiğini kontrol et:

```text
INVOKESTATIC
INVOKEVIRTUAL
INVOKEINTERFACE
INVOKESPECIAL
```

Özellikle:

* interface method
* default interface method
* static method
* private method
* constructor
* super method
* inherited method
* generic method
* extension method
* Java interop
* inner class method

durumlarını ayrı ayrı test et.

Bir method'un source-level'de geçerli olması, JVM invocation seviyesinde doğru olduğu anlamına gelmez.

---

# 12. FIELD / PROPERTY / GETTER / SETTER DENETİMİ

Field erişimlerinin tamamını incele.

Şunları kontrol et:

```text
GETFIELD
PUTFIELD
GETSTATIC
PUTSTATIC
```

Data class generated getter/setter logic'ini özellikle incele.

Şunların karıştırılmadığından emin ol:

```text
field
getter
setter
property
record accessor
```

Bir data class field'ının getter'a sahip olması onun Java record component olduğu anlamına gelmemeli.

---

# 13. RETURN / STACK ANALİZİ

Compiler'daki bütün return generation kodlarını incele.

Özellikle:

```text
IRETURN
LRETURN
FRETURN
DRETURN
ARETURN
RETURN
```

seçiminin source return type ile kesin olarak uyumlu olduğunu doğrula.

Şu senaryoları ayrıca test et:

```text
int
long
float
double
boolean
char
short
byte
Object
String
array
nullable primitive
nullable reference
generic T
void
```

Generic return type'ın erasure sonrası JVM descriptor'ıyla source-level type arasında mismatch oluşup oluşmadığını kontrol et.

---

# 14. BYTECODE'UN JVM TARAFINDAN DOĞRULANMASINI SAĞLAYAN TESTLER

Sadece compiler'ın compile aşamasını değil, üretilen `.class` dosyalarının gerçekten JVM tarafından yüklenmesini de düşün.

Gerekli yerlerde test stratejisi öner:

```text
compile
→ class generation
→ JVM class loading
→ verification
→ execution
```

Aşağıdaki durumların compile başarılı olduğu halde runtime'da patlayıp patlamadığını araştır:

```text
VerifyError
ClassFormatError
NoSuchMethodError
NoSuchFieldError
AbstractMethodError
IllegalAccessError
InstantiationError
```

Mümkünse minimal reproduction üret.

Örneğin:

```text
Ocean source
→ generated bytecode
→ expected JVM failure
```

şeklinde.

---

# 15. TESTLERİ SADECE OKUMA, TEST AÇIĞI ARA

Mevcut testleri incele.

Ama yalnızca mevcut testlerin geçip geçmediğine bakma.

Compiler'da bulunan her kritik mekanizma için test coverage boşluğu ara.

Özellikle:

* constructor
* inheritance
* data class
* inner class
* generic
* nullable
* primitive/reference
* interface
* default method
* lambda
* method reference
* async
* exception
* synchronization
* switch
* loops
* array
* slicing
* boxing/unboxing
* JVM invocation

için eksik testler belirle.

Bir bug için test önerirken testin **hangi bug'ı yakaladığını** açıkça belirt.

---

# 16. REGRESSION RİSKİ

Bir çözüm önerisinin mevcut compiler'daki başka özellikleri bozup bozmayacağını değerlendir.

Örneğin data class değişikliği:

```text
data class
→ getter
→ setter
→ equals
→ hashCode
→ toString
→ copy
→ inheritance
→ constructor
```

gibi başka mekanizmaları etkileyebilir.

Benzer şekilde:

```text
IRClass
→ bytecode emitter
→ inner class
→ generic
→ inheritance
```

arasında yan etkiler olabilir.

Bunları özellikle belirt.

---

# 17. ÇIKTI FORMATI

Sonuçları aşağıdaki formatta raporla.

## A. Executive Summary

Kısa özet:

```text
Toplam kritik problem:
Toplam yüksek riskli problem:
Toplam orta riskli problem:
Toplam düşük riskli problem:
FIXME sayısı:
JVM verification riski:
Data class / record problemi:
Hardcoded problem sayısı:
Test açığı:
```

Burada sayı uydurma. Gerçek tarama sonucunu kullan.

---

## B. Kritik Bulgular

Her problem:

```text
ID:
Kategori:
Severity:
Dosya:
Satır:
Fonksiyon / Class:

Problem:
...

Kök neden:
...

Mevcut davranış:
...

Beklenen davranış:
...

JVM etkisi:
...

Önerilen çözüm:
...

Alternatif çözüm:
...

Regression riski:
...

Önerilen test:
...
```

şeklinde raporla.

Severity:

```text
CRITICAL
HIGH
MEDIUM
LOW
```

olabilir.

Severity'nin nedenini açıklamalısın.

---

## C. JVM Verification Bulguları

Ayrı bölüm oluştur:

```text
Problem
→ Bytecode sebebi
→ JVM açısından neden geçersiz
→ Hangi durumda ortaya çıkar
→ Minimal reproduction
→ Önerilen düzeltme
```

---

## D. Data Class vs Record Analizi

Ayrı bölüm:

```text
Mevcut durum
↓
Sorunun kaynağı
↓
Ocean data class semantiği
↓
Java record semantiği
↓
Compiler'daki çakışma
↓
Önerilen compiler modeli
↓
IR değişiklikleri
↓
Bytecode emitter değişiklikleri
↓
Test planı
```

şeklinde.

---

## E. FIXME Raporu

Her FIXME için ayrı kayıt:

```text
FIXME:
Dosya:
Satır:
Durum:
Gerçek problem:
Kök neden:
Çözüm:
Test:
```

---

## F. Hardcoded Logic Raporu

Şu formatta:

```text
Dosya:
Kod:
Hardcoded davranış:
Neden problem:
Hangi durumda kırılır:
Önerilen abstraction:
Minimal düzeltme:
```

---

## G. Test Eksikleri

Eksik testleri listele:

```text
Test:
Amaç:
Yakalanması gereken problem:
Beklenen sonuç:
```

---

## H. Önceliklendirilmiş Çözüm Planı

En sonunda çözüm önerilerini teknik olarak grupla:

```text
Phase 1 — JVM correctness / VerifyError riskleri
Phase 2 — Type system correctness
Phase 3 — Data class / record separation
Phase 4 — Constructor / inheritance
Phase 5 — Hardcoded logic
Phase 6 — FIXME cleanup
Phase 7 — Test coverage
```

Ancak bunları "en iyi/en kötü" şeklinde değerlendirme. Önceliklendirmeyi **risk ve bağımlılık** üzerinden yap.

---

# 18. ÇOK ÖNEMLİ: KODU DEĞİŞTİRMEDEN ÖNCE ANALİZ ET

Bu görev sırasında ilk aşamada doğrudan kodu değiştirme.

Önce:

1. Projeyi oku.
2. Architecture'ı çıkar.
3. Problem noktalarını tespit et.
4. Kök nedenleri belirle.
5. Çözüm önerilerini oluştur.
6. Test stratejisini oluştur.

Eğer çalışma ortamında kod değişikliği yapman gerekiyorsa, önce hangi dosyaların neden değişmesi gerektiğini raporla.

Kod değişikliği yapmadan önce mevcut davranışın nedenini anlamaya çalış.

---

# 19. FALSE POSITIVE'DEN KAÇIN

Her şüpheli kodu bug olarak işaretleme.

Örneğin:

```text
Bu kod alışılmadık görünüyor.
```

tek başına bug değildir.

Bir şeyi problem olarak işaretlemek için mümkün olduğunca:

* grammar
* semantic rules
* IR
* JVM specification
* ASM behavior
* mevcut test
* generated bytecode
* source/bytecode karşılaştırması

ile destekle.

Emin olmadığın durumda:

```text
CONFIRMED
LIKELY
POSSIBLE
```

şeklinde confidence belirt.

---

# 20. JAVA / JVM SEMANTİĞİNİ OCEAN'A ZORLA UYGULAMA

Ocean JVM üzerinde çalışan bir dil olsa da Ocean'ın source-level semantiğini Java'nın source-level semantiğiyle otomatik olarak eşitleme.

Örneğin:

```text
Ocean data class
≠ Java record

Ocean variable
≠ Kotlin var

Ocean value
≠ doğrudan Kotlin val varsayımı

Ocean primary constructor
≠ her Java class için constructor shortcut

Ocean nullable
≠ JVM'de ayrı bir runtime type
```

Önce Ocean'ın kendi semantic modelini belirle, sonra JVM'e doğru şekilde map edilip edilmediğini kontrol et.

---

# 21. ÖZEL OLARAK ARANACAK KOD KALIPLARI

Kod taraması sırasında özellikle şu kalıpları ara:

```text
hardcoded string
hardcoded opcode
hardcoded descriptor
hardcoded internal name
if (type.equals(...))
if (className.equals(...))
methodName.equals(...)
instanceof
cast
Type.getType(...)
Type.getMethodDescriptor(...)
visitMethod(...)
visitField(...)
visitInsn(...)
visitVarInsn(...)
visitTypeInsn(...)
visitFieldInsn(...)
visitMethodInsn(...)
visitJumpInsn(...)
visitFrame(...)
visitTryCatchBlock(...)
```

Ayrıca:

```text
Map
Set
List
global registry
static state
cache
```

kullanımlarında stale state / mutation / compilation-order dependency olup olmadığını kontrol et.

Özellikle compiler'ın aynı JVM process'i içinde birden fazla source file/class compile etmesi durumunda state'in bir compilation'dan diğerine sızıp sızmadığını araştır.

---

# 22. DETERMINISM

Aynı source'un:

```text
aynı compiler process'inde
farklı compile sıralarında
farklı source file sıralarında
farklı class dependency sıralarında
```

compile edilmesi halinde farklı bytecode veya farklı semantic sonuç üretip üretmediğini incele.

Özellikle:

```text
static global registry
Map iteration order
Set iteration order
class discovery order
inner class resolution
generic resolution
```

alanlarına dikkat et.

---

# 23. SON KONTROL

Analizin sonunda şu soruların her birine cevap ver:

1. Compiler'ın JVM'e geçersiz bytecode üretmesine neden olabilecek yerler nerede?
2. En yüksek JVM verification riski hangi kod yollarında?
3. Data class ve Java record şu anda nerede birbirine karışıyor?
4. Data class'ın inheritance/getter/setter gibi özellikleri doğru temsil ediliyor mu?
5. FIXME'lerden hangileri gerçek compiler bug'ı?
6. Hangi hardcoded logic'ler ileride yeni language feature'larında kırılabilir?
7. Type system ile bytecode emitter arasında hangi bilgi kayıpları var?
8. Constructor generation güvenli mi?
9. Inner class / outer class resolution güvenli mi?
10. Generic type information bytecode üretimine kadar doğru taşınıyor mu?
11. Primitive/reference ayrımı tüm compiler boyunca korunuyor mu?
12. Interface/class invocation ayrımı güvenli mi?
13. Return opcode generation tamamen type-safe mi?
14. Compiler global state nedeniyle compilation-order dependent olabilir mi?
15. Hangi testler bu sorunları şu anda yakalayamıyor?
16. Hangi sorunlar önce çözülmeli ve neden?

---

# 24. ÖNEMLİ SONUÇ KURALI

Analizin sonunda sadece:

> "Compiler genel olarak iyi görünüyor."

gibi yüzeysel bir sonuç verme.

Gerçekten problem bulduysan somut olarak göster.

Problem bulamadığın alanlarda da:

```text
INCELENDİ — belirgin problem bulunamadı
```

şeklinde belirt.

Ama test edilmemiş bir alanı:

```text
Sorunsuz
```

olarak raporlama.

---

## Nihai amaç

Bu çalışmanın amacı Ocean compiler'ı baştan yazmak değil.

Amaç:

```text
Ocean Source
      ↓
ANTLR
      ↓
Semantic Analysis
      ↓
Type System
      ↓
IR
      ↓
IR Transformations
      ↓
ASM Bytecode Generation
      ↓
JVM Class Verification
      ↓
Runtime
```

zincirindeki **semantic mismatch, state corruption, incomplete implementation, hardcoded behavior, invalid bytecode ve JVM-level failure risklerini** mümkün olduğunca eksiksiz ortaya çıkarmaktır.

Özellikle daha önce ortaya çıkmış veya ortaya çıkması muhtemel `VerifyError`, `ClassFormatError`, constructor/inner-class problemleri, primitive/reference uyuşmazlıkları, generic type resolution problemleri ve data-class/record ayrımını gözden kaçırma.

Analizini mevcut kodun gerçek davranışına dayandır; varsayım yaptığın yerleri açıkça belirt.

```

### Package Responsibilities

* `ocean.compiler`: Contains the main entry points, compilation session management, pre-scanner, symbol tables, type checker, overload resolution, and compiler configuration.
* `ocean.compiler.ir`: Contains all typed Intermediate Representation nodes, the visitor pattern contracts, and IR optimization passes (Constant Folding, Dead Code Elimination, Method Inlining, IR Dumper).
* `ocean.compiler.legacy`: Archived direct AST-to-bytecode visitors and earlier compiler versions (V1 and V2) kept for reference and backward compatibility.
* `ocean.stdlib`: Runtime libraries and specialized collections (boxing-free primitive lists, string builders, JSON parser, HTTP client, math, regex).
* `ocean.utils`: Auxiliary utilities such as standalone string-based type inference.

---

# Classes

## Core Compiler Engine (`ocean.compiler`)

### `OceanRunnerV3`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Primary CLI entry point and orchestration driver for the modern IR-driven Ocean compiler pipeline.
* **Fields:**
  * `packageCache` — Thread-safe cache mapping file paths to resolved package names.
  * `currentRunClasses` — Set of fully qualified class names compiled during the current run.
  * `programArgs` — Array of runtime command-line arguments to pass to the target program.
  * `activeClasspath` — Classpath string configured for compilation and execution.
  * `compiledInMemoryClasses` — Cache of in-memory generated bytecodes.
* **Methods:**
  * `main(String[] args)` — Entry point parsing CLI flags (`-cp`, `--json`, `-c`, `-debug`), initializing session, and running compilation.
  * `compile(List<Path> sources)` — Executes the multi-pass compilation pipeline across dirty sources.
  * `compile(String sourcePath)` — Convenience overload to compile a single source file path.
  * `isDirty(Path p)` — Determines whether a source file or its dependencies have changed since the last build.
  * `executeMain(String mainClassName)` — Loads and runs the main class using reflection.
  * `cleanBinaryDir(List<Path> dirtySources)` — Deletes stale `.class` files corresponding to dirty sources.
  * `saveBytecode(Map<String, byte[]> generated, Path sourcePath)` — Writes generated bytecode files to the output directory.
  * `discoverSources(String[] args)` — Resolves target source files and dependencies from command-line arguments.
* **Important Relationships:** Orchestrates `CompilationSession`, `PreScanner`, `IRGenerator`, `IRSemanticAnalyzer`, `IROptimizerPipeline`, and `IRToBytecodeEmitter`.

### `OceanRunner`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Backward compatibility bridge delegating CLI invocations to `OceanRunnerV3`.
* **Fields:** None.
* **Methods:**
  * `main(String[] args)` — Forwards execution arguments directly to `OceanRunnerV3.main`.
* **Important Relationships:** Delegates to `OceanRunnerV3`.

### `CompilationSession`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Encapsulates state for a compilation pass, including global symbol registries, dependency graphs, reflection caches, and cache serialization.
* **Fields:**
  * `messages` — List of compilation messages, errors, and warnings recorded during the session.
  * `activeSession` — ThreadLocal instance tracking the active session per compilation thread.
  * `globalFieldRegistry` — Map storing field descriptors keyed by class name and field name.
  * `globalFieldMutability` — Map tracking mutability flags (`variable` vs `value`) of fields.
  * `globalFieldStaticity` — Map tracking static modifiers of fields.
  * `globalFieldAccess` — Map storing ASM access flags for fields.
  * `globalMethodRegistry` — Map storing method descriptors keyed by class name and method name.
  * `globalMethodGenericReturnTypeRegistry` — Map storing generic return types of methods.
  * `globalMethodStaticity` — Map tracking static modifiers of methods.
  * `globalMethodAccess` — Map storing ASM access flags for methods.
  * `globalOverloadRegistry` — Map tracking all method overload descriptors per method name.
  * `globalMethodTypeParametersRegistry` — Map tracking method-level type parameters.
  * `globalMethodThrowsRegistry` — Map storing declared thrown exceptions per method.
  * `globalMethodParamsRegistry` — Map storing parameter metadata (names, types, default expressions).
  * `globalExtensionMethodRegistry` — Map storing extension methods registered per receiver type.
  * `globalSuperClassRegistry` — Map tracking superclass internal paths per class.
  * `globalClassAccess` — Map storing ASM access flags for classes.
  * `globalInterfaceRegistry` — Map tracking implemented interface names per class.
  * `globalTypeParameterRegistry` — Map storing class-level generic type parameter bounds.
  * `globalAbstractClassSet` — Set of class names marked as abstract.
  * `globalIsInterfaceSet` — Set of type names that are interfaces.
  * `globalSealedClassSet` — Set of class names marked as sealed.
  * `globalExplicitRestrictsSet` — Set of sealed classes having explicit `restricts` declarations.
  * `globalDataClassSet` — Set of class names marked as `data class`.
  * `globalAsyncMethodSet` — Set of method keys declared as `async`.
  * `globalPermittedSubclassesRegistry` — Map tracking permitted subclass lists for sealed classes.
  * `globalSubclassStatusRegistry` — Map tracking modifier status (`final`, `sealed`, `non-sealed`) of subclasses.
  * `sourceFileMetadata` — Map caching file modification times, package names, declared types, and dependency fingerprints.
  * `classDependencies` — Map tracking class-to-class compilation dependencies for incremental builds.
  * `reflectionClassCache` — Cache for dynamically resolved Java classes.
* **Methods:**
  * `getActiveSession()` — Retrieves the current thread's active compilation session.
  * `setActiveSession(CompilationSession session)` — Binds a compilation session to the calling thread.
  * `clearActiveSession()` — Unbinds and cleans thread-local state for the current session.
  * `isSubType(String sub, String sup)` — Checks whether a type is a subtype of another across Ocean and Java hierarchies.
  * `loadFromCache(Path cacheFile)` — Deserializes compiler cache data from disk if timestamps match.
  * `saveToCache(Path cacheFile)` — Serializes session metadata and dependency trees to disk.
  * `removeClassesBySimpleNames(Set<String> simpleNames)` — Clears registry entries for classes targeted for recompilation.
  * `removeRegistryEntriesForFqcn(String fqcn)` — Purges all field, method, and hierarchy records for a specific class.
  * `rollbackDirtyMetadata(Set<String> pathKeys)` — Discards uncommitted cache metadata upon a compilation failure.
* **Important Relationships:** Central state repository referenced by `CompilerRegistry`, `SymbolTable`, `IRSemanticAnalyzer`, and `OceanRunnerV3`.

### `CompilerRegistry`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Provides static delegating accessors to session registries via ThreadLocal proxies.
* **Fields:**
  * `globalFieldRegistry` — Delegating map proxy to active session's field registry.
  * `globalFieldMutability` — Delegating map proxy to active session's field mutability registry.
  * `globalFieldStaticity` — Delegating map proxy to active session's field staticity registry.
  * `globalFieldAccess` — Delegating map proxy to active session's field access flags registry.
  * `globalMethodRegistry` — Delegating map proxy to active session's method registry.
  * `globalMethodGenericReturnTypeRegistry` — Delegating map proxy to generic return types.
  * `globalMethodStaticity` — Delegating map proxy to method staticity.
  * `globalMethodAccess` — Delegating map proxy to method access flags.
  * `globalOverloadRegistry` — Delegating map proxy to method overloads.
  * `globalMethodTypeParametersRegistry` — Delegating map proxy to method type parameters.
  * `globalMethodThrowsRegistry` — Delegating map proxy to method throws declarations.
  * `globalMethodParamsRegistry` — Delegating map proxy to method parameter definitions.
  * `globalExtensionMethodRegistry` — Delegating map proxy to extension methods.
  * `globalSuperClassRegistry` — Delegating map proxy to superclass registry.
  * `globalClassAccess` — Delegating map proxy to class access flags.
  * `globalInterfaceRegistry` — Delegating map proxy to implemented interfaces.
  * `globalTypeParameterRegistry` — Delegating map proxy to generic type parameters.
  * `globalAbstractClassSet` — Delegating set proxy to abstract classes.
  * `globalIsInterfaceSet` — Delegating set proxy to interfaces.
  * `globalSealedClassSet` — Delegating set proxy to sealed classes.
  * `globalExplicitRestrictsSet` — Delegating set proxy to explicit sealed restriction classes.
  * `globalDataClassSet` — Delegating set proxy to data classes.
  * `globalAsyncMethodSet` — Delegating set proxy to async methods.
  * `globalPermittedSubclassesRegistry` — Delegating map proxy to permitted sealed subclasses.
  * `globalSubclassStatusRegistry` — Delegating map proxy to subclass modifier status.
  * `globalFunctionalInterfaceRegistry` — Delegating set proxy to SAM functional interfaces.
  * `globalListLikeOwnerRegistry` — Delegating set proxy to list-like generic container types.
  * `globalMapLikeOwnerRegistry` — Delegating set proxy to map-like generic container types.
  * `implicitImportPrefixes` — List of packages imported implicitly (`java.lang.`, `ocean.stdlib.`).
* **Methods:**
  * `clearAll()` — Resets and clears all delegating registries and auxiliary compiler caches.
* **Important Relationships:** Wraps `CompilationSession` via `ThreadLocalDelegatingMap` and `ThreadLocalDelegatingSet`.

### `CompilerReporter`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Centralized diagnostic logging facility collecting errors, warnings, and informational messages.
* **Fields:**
  * `fallbackMessages` — Thread-safe fallback message list used when no session is active.
* **Methods:**
  * `report(Level level, String file, int line, int column, String code, String text, String context)` — Records a compiler diagnostic message.
  * `error(String file, int line, int column, String text, String context)` — Records a compilation error.
  * `warning(String file, int line, int column, String text, String context)` — Records a compilation warning.
  * `info(String file, int line, int column, String text, String context)` — Records an informational message.
  * `hasErrors()` — Checks whether any error-level diagnostic was recorded.
  * `getMessages()` — Returns the list of recorded messages in the active session.
  * `clear()` — Clears all recorded diagnostics.
  * `printSummary()` — Formats and prints a human-readable diagnostic report to stdout.
  * `printJson()` — Outputs diagnostic messages formatted as a JSON array for tool integrations.
  * `determineErrorCode(String text, String context)` — Maps diagnostic message patterns to standardized error codes (`E0001`–`E0014`).
* **Important Relationships:** Integrated into `OceanErrorListener`, `IRSemanticAnalyzer`, and `OceanRunnerV3`.

### `CompilerConfig`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** Single source of truth for compiler-wide configuration constants and type aliases.
* **Fields:**
  * `BYTECODE_VERSION` — Target JVM bytecode version (`Opcodes.V17`).
  * `OCEAN_TYPE_ALIASES` — Immutable mapping of Ocean keyword types to JVM descriptors.
* **Methods:** None (private constructor).
* **Important Relationships:** Referenced by `IRGenerator`, `SymbolTable`, and `IRToBytecodeEmitter`.

### `PreScanner`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** First-pass AST visitor that gathers class headers, member signatures, access modifiers, type bounds, and extension methods into `CompilerRegistry`.
* **Fields:**
  * `currentFile` — Name of the file being pre-scanned.
  * `currentFilePackage` — Package path of the current file.
  * `currentClassName` — Qualified name of the current class being scanned.
  * `currentSuperName` — Superclass name of the current class.
  * `importedClasses` — Map of simple names to imported class paths.
  * `importedWildcards` — List of wildcard package imports.
  * `importedStaticMembers` — Map of static member imports.
  * `importedStaticWildcards` — List of static wildcard imports.
  * `session` — Active compilation session.
  * `symbolTable` — Symbol table instance populated during pre-scanning.
* **Methods:**
  * `visit(ParseTree tree)` — Initiates pre-scan traversal on the ANTLR parse tree.
  * `visitPackageDeclaration(OceanParser.PackageDeclarationContext ctx)` — Records package namespace.
  * `visitImportStatement(OceanParser.ImportStatementContext ctx)` — Populates explicit and wildcard import tables.
  * `visitClassDeclaration(OceanParser.ClassDeclarationContext ctx)` — Registers class, hierarchy, sealed restrictions, and data class signatures.
  * `visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx)` — Registers interface contracts and superinterfaces.
  * `visitEnumDeclaration(OceanParser.EnumDeclarationContext ctx)` — Registers enum types and enum constants.
  * `visitFunctionDeclaration(OceanParser.FunctionDeclarationContext ctx)` — Registers method descriptors, throws clauses, and accessibility flags.
  * `visitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx)` — Registers constructor parameter signatures and overloads.
  * `visitFieldDeclaration(OceanParser.FieldDeclarationContext ctx)` — Registers field types, mutability, and accessibility flags.
  * `visitExtensionFunctionDeclaration(OceanParser.ExtensionFunctionDeclarationContext ctx)` — Registers extension methods against receiver types.
* **Important Relationships:** Extends `OceanBaseVisitor<Void>`; populates `CompilerRegistry` and `CompilationSession`.

### `SymbolTable`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Manages lexical scopes, local variable allocations, variable types, mutability, initialization state, and lambda capture tracking.
* **Fields:**
  * `scopes` — Deque of scope frames mapping variable names to `VariableInfo`.
  * `indexStack` — Deque tracking local variable slot indices across nested scopes.
  * `nextIndex` — Next available local variable index slot in JVM frame.
  * `isStaticContext` — Indicates whether the current scope is static.
  * `imports` — Map of active imports for descriptor resolution.
  * `typeParams` — Set of active generic type parameter names.
  * `session` — Active compilation session.
* **Methods:**
  * `enterScope()` — Pushes a new lexical block scope frame.
  * `exitScope()` — Pops the current lexical block scope frame.
  * `declareVariable(String name, String type, boolean isFinal, boolean isInitialized)` — Allocates and registers a local variable.
  * `getIndex(String name)` — Resolves the local variable JVM slot index.
  * `getType(String name)` — Resolves the JVM type descriptor of a local variable.
  * `getRawType(String name)` — Resolves the uncleaned raw generic type of a variable.
  * `isFinal(String name)` — Checks whether a variable is declared immutable (`value`).
  * `isInitialized(String name)` — Checks whether a variable has been definitely assigned.
  * `markInitialized(String name)` — Marks a local variable as initialized.
  * `markMutated(String name, ParserRuleContext ctx)` — Marks a variable as reassigned and checks lambda capture validity.
  * `markCaptured(String name, ParserRuleContext ctx)` — Marks a variable as captured by a closure.
  * `reserveLocalSlots(String descriptor)` — Allocates JVM variable slots taking 64-bit (`double`/`long`) 2-slot rules into account.
  * `getDescriptor(String type, Map<String, String> imports, Set<String> typeParams)` — Resolves a human-readable or aliased type to a JVM descriptor.
  * `clearCaches()` — Clears descriptor and negative class lookup caches.
* **Important Relationships:** Used by `IRGenerator`, `IRSemanticAnalyzer`, and `PreScanner`.

### `AnalysisContext`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Tracks scope stacks, type descriptors, class member context, and loop/switch depths during semantic analysis.
* **Fields:**
  * `scopes` — Scope stack holding variable `TypeInfo` instances.
  * `currentClass` — Name of the enclosing class.
  * `currentMethod` — Name of the enclosing method.
  * `inStaticContext` — Flag indicating whether execution is within a static member.
  * `inLoop` — Flag indicating whether analysis is inside a loop.
  * `loopDepth` — Nesting depth of active loops.
  * `inSwitch` — Flag indicating whether analysis is inside a switch construct.
  * `switchDepth` — Nesting depth of active switches.
  * `currentMethodReturnType` — Expected return descriptor of the enclosing method.
  * `currentFile` — Current file name being analyzed.
  * `symbolTable` — Associated symbol table.
* **Methods:**
  * `enterScope()` / `exitScope()` — Manages lexical scope nesting.
  * `declareVariable(String name, String descriptor, String rawType, boolean isFinal, boolean isInitialized)` — Registers variable type metadata.
  * `lookupVariable(String name)` — Searches the scope hierarchy for a variable's `TypeInfo`.
  * `enterClass(...)` / `exitClass()` — Manages class context state transitions.
  * `enterMethod(String methodName, boolean isStatic, String returnType, List<String> thrownExceptions)` / `exitMethod()` — Manages method context transitions.
  * `enterLoop()` / `exitLoop()` — Tracks loop boundaries for break/continue validation.
  * `enterSwitch()` / `exitSwitch()` — Tracks switch statement boundaries.
  * `isBreakAllowed()` — Checks whether `stop` (break) is valid in the current context.
* **Important Relationships:** Coordinates with `SymbolTable` and `TypeInfo`.

### `TypeChecker`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Enforces type compatibility, assignability rules, primitive widening, boxing/unboxing, and null safety.
* **Fields:**
  * `WIDENING_TABLE` — Primitive widening conversion rule matrix.
  * `cleanDescriptorCache` — Cache of stripped/cleaned JVM descriptors.
* **Methods:**
  * `isAssignable(String targetDesc, String sourceDesc, CompilationSession session)` — Determines whether a source type can be assigned to a target type.
  * `isNullable(String desc)` — Checks whether a descriptor denotes a nullable type (`?` suffix or `null`).
  * `cleanDescriptor(String desc)` — Strips generic signatures and nullability markers from a descriptor.
  * `getInternalName(String desc)` — Extracts the JVM internal class path from an object descriptor.
  * `isPrimitive(String desc)` — Checks if a descriptor is a JVM primitive (`I`, `Z`, `D`, `F`, `J`, `B`, `C`, `S`).
  * `box(String desc)` — Returns the boxed wrapper class descriptor for a primitive.
  * `unbox(String desc)` — Returns the primitive descriptor corresponding to a wrapper class.
  * `isWideningConvertible(String from, String to)` — Checks if primitive widening is allowed.
  * `clearCaches()` — Clears descriptor cleaning caches.
* **Important Relationships:** Relies on `CompilationSession.isSubType` and `OceanTypeSystem`.

### `OceanTypeSystem`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** Central registry for built-in primitive and object type descriptors, type aliasing, and reflection resolution.
* **Fields:**
  * `OBJECT_DESC` — Constant descriptor for `java.lang.Object` (`"Ljava/lang/Object;"`).
  * `STRING_DESC` — Constant descriptor for `java.lang.String` (`"Ljava/lang/String;"`).
  * `NUMBER_DESC` — Constant descriptor for `java.lang.Number` (`"Ljava/lang/Number;"`).
  * `BIGDECIMAL_DESC` — Constant descriptor for `java.math.BigDecimal` (`"Ljava/math/BigDecimal;"`).
  * `BUILTIN_DESCRIPTORS` — Map of keyword names to JVM descriptors.
  * `BUILTIN_INTERNAL_NAMES` — Map of keyword names to JVM internal paths.
* **Methods:**
  * `getBuiltinDescriptor(String typeName)` — Resolves primitive or alias keywords to JVM descriptors.
  * `getBuiltinInternalName(String typeName)` — Resolves keyword types to internal class paths.
  * `wrapObjectType(String internalName)` — Formats an internal path into an `L...;` descriptor.
  * `forName(String className)` — Thread-safe reflection loader respecting context classloaders.
* **Important Relationships:** Foundation for `TypeChecker`, `IRGenerator`, `SymbolTable`, and `PreScanner`.

### `TypeInferenceEngine`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** Infers expression types from AST parse trees and contextual symbol tables.
* **Fields:**
  * `FALLBACK` — Fallback descriptor (`Ljava/lang/Object;`).
* **Methods:**
  * `inferType(ParserRuleContext ctx, InferenceContext context)` — Computes the inferred descriptor for an expression node.
  * `inferNumberType(String text)` — Infers specific numeric descriptor (`I`, `L`, `F`, `D`) based on numeric literal suffixes.
* **Important Relationships:** Interacts with `OceanParser` and `OceanTypeSystem`.

### `OverloadResolver`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Resolves method overloads, constructor signatures, dynamic receiver dispatch, and extension method invocations.
* **Fields:**
  * `hierarchyCache` — Cache of inheritance hierarchies per class owner.
  * `reflectedMethodsCache` — Cache of reflected method descriptors.
  * `reflectedInterfacesCache` — Cache of reflected interface hierarchies.
  * `paramDescriptorsCache` — Cache of parsed parameter descriptor lists.
* **Methods:**
  * `getClassHierarchy(String owner, String methodName)` — Returns class inheritance chain for method resolution.
  * `resolveBestMethod(String owner, String methodName, List<String> argTypes, CompilationSession session)` — Resolves best-matching method descriptor.
  * `resolveBestConstructor(String owner, List<String> argTypes, CompilationSession session)` — Resolves best-matching constructor descriptor.
  * `resolveExtensionMethod(String receiverType, String methodName, List<String> argTypes, CompilationSession session)` — Resolves applicable extension method.
  * `clearCaches()` — Flushes all reflection and hierarchy caches.
* **Important Relationships:** Used by `IRGenerator`, `IRSemanticAnalyzer`, and `TypeChecker`.

### `ModifierHelper`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** Inspects AST modifier lists and computes ASM access flags.
* **Fields:** None.
* **Methods:**
  * `hasModifier(List<OceanParser.ModifierContext> modifiers, String keyword)` — Checks for presence of a specific keyword modifier.
  * `isStatic(List<OceanParser.ModifierContext> modifiers)` — Checks for `static`.
  * `isAbstract(List<OceanParser.ModifierContext> modifiers)` — Checks for `abstract`.
  * `isFinal(List<OceanParser.ModifierContext> modifiers)` — Checks for `final`.
  * `isPublic(List<OceanParser.ModifierContext> modifiers)` — Checks for `public`.
  * `isPrivate(List<OceanParser.ModifierContext> modifiers)` — Checks for `private`.
  * `isProtected(List<OceanParser.ModifierContext> modifiers)` — Checks for `protected`.
  * `isSynchronized(List<OceanParser.ModifierContext> modifiers)` — Checks for `sync` or `lock`.
  * `isOverride(List<OceanParser.ModifierContext> modifiers)` — Checks for `override`.
  * `isData(List<OceanParser.ModifierContext> modifiers)` — Checks for `data`.
  * `isAsync(List<OceanParser.ModifierContext> modifiers)` — Checks for `async`.
  * `isNative(List<OceanParser.ModifierContext> modifiers)` — Checks for `native`.
  * `toAsmAccess(List<OceanParser.ModifierContext> modifiers)` — Combines modifiers into ASM integer bitmask flags (including `ACC_NATIVE`).
* **Important Relationships:** Utility used across `PreScanner`, `IRGenerator`, and `IRToBytecodeEmitter`.

### `OceanTokenStreamFactory`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Custom token stream filter disambiguating right-shift operators (`>>`, `>>>`) into individual generic closing angle brackets (`>`).
* **Fields:** None.
* **Methods:**
  * `createTokenStream(OceanLexer lexer)` — Processes raw token stream and splits shift tokens into discrete `>` tokens.
* **Important Relationships:** Wraps `OceanLexer` before passing tokens to `OceanParser`.

### `OceanErrorListener`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** ANTLR error listener forwarding syntax errors to `CompilerReporter`.
* **Fields:**
  * `fileName` — Source file name associated with the listener.
* **Methods:**
  * `syntaxError(...)` — Intercepts ANTLR syntax errors and records them via `CompilerReporter.error`.
* **Important Relationships:** Extends `BaseErrorListener`; integrates with `CompilerReporter`.

### `StackTrackingMethodVisitor`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** ASM `MethodVisitor` decorator tracking simulated JVM operand stack height for runtime verification and debugging.
* **Fields:**
  * `stackHeight` — Current operand stack height counter.
  * `methodName` — Name of the method being visited.
* **Methods:**
  * `visitInsn(int opcode)` — Tracks stack delta for single-byte instructions.
  * `visitVarInsn(int opcode, int var)` — Tracks stack delta for local variable loads/stores.
  * `visitFieldInsn(int opcode, String owner, String name, String desc)` — Tracks stack delta for field access.
  * `visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf)` — Tracks stack delta for invocations.
  * `visitJumpInsn(int opcode, Label label)` — Tracks stack delta for branches.
  * `visitLdcInsn(Object value)` — Tracks stack delta for constant loads.
* **Important Relationships:** Extends `MethodVisitor`.

### `ThreadLocalDelegatingMap<K, V>`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** ThreadLocal-aware delegating `Map` routing all map operations to the active thread's `CompilationSession`.
* **Fields:**
  * `mapSelector` — Function extracting the target map from a `CompilationSession`.
* **Methods:**
  * Implements all standard `java.util.Map` methods delegating to `mapSelector.apply(session)`.
* **Important Relationships:** Implements `Map<K, V>`.

### `ThreadLocalDelegatingSet<E>`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** ThreadLocal-aware delegating `Set` routing all set operations to the active thread's `CompilationSession`.
* **Fields:**
  * `setSelector` — Function extracting the target set from a `CompilationSession`.
* **Methods:**
  * Implements all standard `java.util.Set` methods delegating to `setSelector.apply(session)`.
* **Important Relationships:** Implements `Set<E>`.

### `TypeInfo`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Holds type, initialization, and mutability metadata for variables during semantic analysis.
* **Fields:**
  * `descriptor` — JVM type descriptor.
  * `rawType` — Raw type including generic parameters.
  * `originalDescriptor` — Declared type descriptor prior to refinement.
  * `isFinal` — True if declared with `value` or `final`.
  * `isInitialized` — True if definitely assigned.
  * `usageCount` — Read counter for dead code detection.
  * `isCaptured` — True if captured by closure.
  * `isMutated` — True if reassigned after declaration.
* **Methods:**
  * `getDescriptor()` / `setDescriptor(String descriptor)` — Accessors for type descriptor.
  * `getRawType()` — Accessor for raw generic type string.
  * `isFinal()` — Checks immutability.
  * `isInitialized()` / `markInitialized()` — Queries and updates definite assignment.
  * `incrementUsage()` / `isUnused()` — Tracks variable read usage.
  * `humanReadable()` — Formats descriptor into human-friendly type name.
* **Important Relationships:** Managed inside `AnalysisContext`.

### `StringHelper`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** String unescaping utility for escape sequences in string literals.
* **Fields:** None.
* **Methods:**
  * `unescapeString(String s)` — Converts escape characters (`\n`, `\t`, `\"`, `\\`, etc.) into literal characters.
* **Important Relationships:** Used by `IRGenerator` and `IRConstantFolder`.

### `CompilationException`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Runtime exception thrown on critical compiler errors requiring immediate termination of the compilation pass.
* **Fields:** None.
* **Methods:**
  * `CompilationException(String message)` — Constructor with error message.
* **Important Relationships:** Extends `RuntimeException`.

---

## Intermediate Representation & Optimizations (`ocean.compiler.ir`)

### `IRNode`
* **Package:** `ocean.compiler.ir`
* **Type:** abstract class
* **Responsibility:** Base class for all nodes in the Ocean Intermediate Representation tree.
* **Fields:**
  * `lineNumber` — Source line number.
  * `columnNumber` — Source column number.
* **Methods:**
  * `setLocation(int line, int col)` — Sets source location coordinates.
  * `getLineNumber()` / `getColumnNumber()` — Returns source location coordinates.
  * `accept(IRVisitor visitor)` — Abstract visitor dispatch entry point.
* **Important Relationships:** Root of the IR class hierarchy.

### `IRVisitor`
* **Package:** `ocean.compiler.ir`
* **Type:** interface
* **Responsibility:** Visitor pattern contract for traversing all IR node types.
* **Methods:**
  * `visitCompilationUnit(IRCompilationUnit node)` — Visits compilation unit node.
  * `visitClass(IRClass node)` — Visits class declaration node.
  * `visitInterface(IRInterface node)` — Visits interface declaration node.
  * `visitEnum(IREnum node)` — Visits enum declaration node.
  * `visitMethod(IRMethod node)` — Visits method declaration node.
  * `visitField(IRField node)` — Visits field declaration node.
  * `visitBlock(IRBlock node)` — Visits statement block node.
  * `visitAssignment(IRAssignment node)` — Visits assignment node.
  * `visitIf(IRIfStatement node)` — Visits if statement node.
  * `visitWhile(IRWhileStatement node)` — Visits while loop node.
  * `visitDoWhile(IRDoWhileStatement node)` — Visits do-while loop node.
  * `visitForStatement(IRForStatement node)` — Visits for loop node.
  * `visitReturn(IRReturnStatement node)` — Visits return statement node.
  * `visitResultStatement(IRResultStatement node)` — Visits result expression statement node.
  * `visitExprStatement(IRExprStatement node)` — Visits expression wrapper statement node.
  * `visitVariableDecl(IRVariableDecl node)` — Visits variable declaration statement node.
  * `visitThrow(IRThrowStatement node)` — Visits throw statement node.
  * `visitTryCatch(IRTryCatchStatement node)` — Visits try-catch-finally statement node.
  * `visitSwitch(IRSwitchStatement node)` — Visits switch statement node.
  * `visitSwitchExpression(IRSwitchExpression node)` — Visits switch expression node.
  * `visitStop(IRStopStatement node)` — Visits break statement node.
  * `visitSkip(IRSkipStatement node)` — Visits continue statement node.
  * `visitLockStatement(IRLockStatement node)` — Visits synchronized lock statement node.
  * `visitLabeled(IRLabeledStatement node)` — Visits labeled statement node.
  * `visitLiteral(IRLiteral node)` — Visits constant literal node.
  * `visitVariableAccess(IRVariableAccess node)` — Visits variable/field access node.
  * `visitBinaryOp(IRBinaryOp node)` — Visits binary operation node.
  * `visitUnaryOp(IRUnaryOp node)` — Visits unary operation node.
  * `visitTernary(IRTernaryExpression node)` — Visits ternary conditional node.
  * `visitCast(IRCastExpression node)` — Visits cast expression node.
  * `visitInstanceof(IRInstanceof node)` — Visits instanceof check node.
  * `visitMethodCall(IRMethodCall node)` — Visits method invocation node.
  * `visitNewObject(IRNewObject node)` — Visits object instantiation node.
  * `visitArrayCreation(IRArrayCreation node)` — Visits array instantiation node.
  * `visitArrayLiteral(IRArrayLiteral node)` — Visits array literal initializer node.
  * `visitArrayAccess(IRArrayAccess node)` — Visits array indexing node.
  * `visitLambda(IRLambdaExpression node)` — Visits lambda expression node.
  * `visitAwaitExpression(IRAwaitExpression node)` — Visits async await expression node.
  * `visitInterpolatedString(IRInterpolatedString node)` — Visits interpolated string node.
  * `visitOceanOutput(IROceanOutput node)` — Visits OceanOutput node.
* **Important Relationships:** Implemented by `BaseIRVisitor`, `IRSemanticAnalyzer`, `IRToBytecodeEmitter`, `IRDumper`, `IRConstantFolder`, `IRDeadCodeEliminator`, and `IRMethodInliner`.

### `BaseIRVisitor`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Default no-op implementation of `IRVisitor` recursively traversing child nodes.
* **Methods:**
  * Implements all `IRVisitor` methods with default recursive traversal behavior.
* **Important Relationships:** Implements `IRVisitor`; extended by `IRSemanticAnalyzer`, `IRToBytecodeEmitter`, `IRDumper`, and optimizer passes.

### `IRCompilationUnit`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Represents a top-level compilation unit (source file) containing declared types.
* **Fields:**
  * `types` — List of top-level `IRNode` types (classes, interfaces, enums).
* **Methods:**
  * `getTypes()` — Returns declared top-level types.
  * `addType(IRNode type)` — Adds a top-level type declaration.
  * `accept(IRVisitor visitor)` — Dispatches visitor to `visitCompilationUnit`.
* **Important Relationships:** Extends `IRNode`.

### `IRClass`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Represents a class definition with members, hierarchy, annotations, and sealed/data/inline class metadata.
* **Fields:**
  * `name` — Class internal path.
  * `superName` — Superclass internal path.
  * `isAbstract` — True if class is abstract.
  * `interfaces` — List of implemented interface paths.
  * `fields` — List of declared `IRField`s.
  * `methods` — List of declared `IRMethod`s.
  * `staticBlocks` — List of static initializer `IRBlock`s.
  * `annotations` — List of attached `IRAnnotation`s.
  * `permittedSubclasses` — List of permitted subclass paths for sealed classes.
  * `isSealed` — True if class is sealed.
  * `isDataClass` — True if class is a data class.
  * `isNonSealed` — True if class is non-sealed.
  * `accessFlags` — ASM accessibility flags.
* **Methods:**
  * `getName()` / `getSuperName()` / `isAbstract()` — Basic metadata accessors.
  * `isInlineValueClass()` — Checks if class is an inline value class (single field with `@Inline`).
  * `getInlineField()` — Returns the single underlying field of an inline value class.
  * `addField(IRField field)` / `addMethod(IRMethod method)` / `addStaticBlock(IRBlock block)` — Member mutators.
  * `accept(IRVisitor visitor)` — Dispatches visitor to `visitClass`.
* **Important Relationships:** Extends `IRNode`.

### `IRMethod`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Represents a method or constructor declaration with signature, parameters, body, annotations, and exception specifications.
* **Fields:**
  * `name` — Method name.
  * `descriptor` — JVM method descriptor.
  * `parameters` — List of parameter definitions (`IRParameter`).
  * `body` — Root statement body (`IRStatement`).
  * `isStatic` — True if method is static.
  * `isAbstract` — True if method is abstract.
  * `isAsync` — True if method is asynchronous.
  * `accessFlags` — ASM accessibility flags.
  * `exceptions` — List of declared thrown exception internal class names.
  * `annotations` — List of method annotations.
  * `defaultValue` — Default return expression if applicable.
* **Methods:**
  * `getName()` / `getDescriptor()` / `getBody()` / `getParameters()` — Basic metadata accessors.
  * `isStatic()` / `isAbstract()` / `isAsync()` — Modifier query methods.
  * `addParameter(IRParameter param)` / `addAnnotation(IRAnnotation annotation)` — Mutators.
  * `accept(IRVisitor visitor)` — Dispatches visitor to `visitMethod`.
* **Important Relationships:** Extends `IRNode`.

### `IRField`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Represents a class field declaration.
* **Fields:**
  * `name` — Field name.
  * `typeDescriptor` — JVM type descriptor.
  * `isStatic` — True if field is static.
  * `initialValue` — Optional initialization expression (`IRExpression`).
  * `accessFlags` — ASM accessibility flags.
  * `annotations` — List of field annotations.
* **Methods:**
  * `getName()` / `getTypeDescriptor()` / `getInitialValue()` / `isStatic()` — Accessors.
  * `accept(IRVisitor visitor)` — Dispatches visitor to `visitField`.
* **Important Relationships:** Extends `IRNode`.

### `IRInterface`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Represents an interface declaration.
* **Fields:**
  * `name` — Interface internal path.
  * `superInterfaces` — List of extended interface names.
  * `methods` — List of interface `IRMethod` definitions.
  * `permittedSubclasses` — Permitted implementor paths for sealed interfaces.
  * `isSealed` / `isNonSealed` — Sealed interface flags.
* **Methods:**
  * `getName()` / `getSuperInterfaces()` / `getMethods()` — Accessors.
  * `accept(IRVisitor visitor)` — Dispatches visitor to `visitInterface`.
* **Important Relationships:** Extends `IRNode`.

### `IREnum`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Represents an enum declaration with constants and members.
* **Fields:**
  * `name` — Enum internal path.
  * `constants` — List of enum constant names.
  * `constantArguments` — List of constructor argument expression lists per constant.
  * `fields` — List of enum fields.
  * `methods` — List of enum methods.
* **Methods:**
  * `getName()` / `getConstants()` / `getConstantArguments()` / `getFields()` / `getMethods()` — Accessors.
  * `accept(IRVisitor visitor)` — Dispatches visitor to `visitEnum`.
* **Important Relationships:** Extends `IRNode`.

### `IRBlock`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Represents a scoped block containing a sequential list of statements.
* **Fields:**
  * `statements` — List of child `IRStatement`s.
* **Methods:**
  * `getStatements()` — Returns statement list.
  * `addStatement(IRStatement stmt)` — Appends a statement.
  * `accept(IRVisitor visitor)` — Dispatches visitor to `visitBlock`.
* **Important Relationships:** Extends `IRStatement`.

### `IRStatement`
* **Package:** `ocean.compiler.ir`
* **Type:** abstract class
* **Responsibility:** Abstract base class for all IR statements.
* **Methods:**
  * `accept(IRVisitor visitor)` — Dispatches visitor.
* **Important Relationships:** Extends `IRNode`.

### `IRExpression`
* **Package:** `ocean.compiler.ir`
* **Type:** abstract class
* **Responsibility:** Abstract base class for all value-producing IR expressions.
* **Fields:**
  * `inferredType` — JVM type descriptor inferred for this expression.
* **Methods:**
  * `getInferredType()` / `setInferredType(String inferredType)` — Accessors for inferred type.
  * `accept(IRVisitor visitor)` — Dispatches visitor.
* **Important Relationships:** Extends `IRNode`.

### `IRGenerator`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** AST-to-IR converter translating ANTLR parse trees into typed IR node trees.
* **Fields:**
  * `currentFile` — Current file name.
  * `importedClasses` — Active class imports map.
  * `importedWildcards` — Active wildcard package imports.
  * `symbolTable` — Active symbol table.
  * `currentClassName` — Qualified name of the current enclosing class.
  * `currentSuperName` — Name of superclass.
  * `currentMethodReturnType` — Expected return descriptor of current method.
  * `packageName` — Package path of the compilation unit.
  * `syntheticLambdaMethods` — List of synthetic lambda methods generated during translation.
  * `anonymousClasses` — List of synthetic anonymous classes created during translation.
* **Methods:**
  * `visitCompilationUnit(OceanParser.CompilationUnitContext ctx)` — Translates a compilation unit AST to `IRCompilationUnit`.
  * `visitClassDeclaration(OceanParser.ClassDeclarationContext ctx)` — Translates class AST to `IRClass`.
  * `visitFunctionDeclaration(OceanParser.FunctionDeclarationContext ctx)` — Translates method AST to `IRMethod`.
  * `visitBlock(OceanParser.BlockContext ctx)` — Translates block AST to `IRBlock`.
  * `visitAssignment(OceanParser.AssignmentContext ctx)` — Translates assignment AST to `IRAssignment`.
  * `visitIfStatement(OceanParser.IfStatementContext ctx)` — Translates if-statement AST to `IRIfStatement`.
  * `visitWhileStatement(OceanParser.WhileStatementContext ctx)` — Translates while-loop AST to `IRWhileStatement`.
  * `visitForStatement(OceanParser.ForStatementContext ctx)` — Translates for-loop AST to `IRForStatement`.
  * `visitSwitchStatement(OceanParser.SwitchStatementContext ctx)` — Translates switch AST to `IRSwitchStatement`.
  * `visitPrimaryExpr(OceanParser.PrimaryExprContext ctx)` — Translates primary expressions to corresponding IR expressions.
  * `clearCaches()` — Clears reflection and method resolution caches.
* **Important Relationships:** Extends `OceanBaseVisitor<IRNode>`.

### `IRSemanticAnalyzer`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Validates semantic correctness directly on the typed IR tree.
* **Fields:**
  * `currentFile` — Name of file being analyzed.
  * `session` — Active compilation session.
  * `symbolTable` — Symbol table for variable/type resolution.
  * `importedClasses` — Map of imported class names.
  * `importedWildcards` — Set of wildcard package imports.
  * `currentClassFqcn` — Qualified class path.
  * `isStaticContext` — Flag indicating static execution context.
  * `loopDepth` — Active loop nesting depth.
  * `switchDepth` — Active switch nesting depth.
  * `nullabilityScopes` — Scope stack tracking flow-sensitive nullability.
  * `caughtExceptionsStack` — Scope stack tracking active try-catch exception handlers.
  * `currentMethodDeclaredThrows` — List of declared thrown exception descriptors.
* **Methods:**
  * `visitClass(IRClass node)` — Checks inheritance, abstract method implementations, and sealed class constraints.
  * `visitMethod(IRMethod node)` — Validates method signatures, return paths, and exception specifications.
  * `visitAssignment(IRAssignment node)` — Validates assignment compatibility, mutability (`value` vs `variable`), and nullability.
  * `visitVariableDecl(IRVariableDecl node)` — Validates variable initialization and duplicate declarations.
  * `visitMethodCall(IRMethodCall node)` — Validates method existence, accessibility, argument types, and unhandled checked exceptions.
  * `visitBinaryOp(IRBinaryOp node)` — Checks operand type compatibility and arithmetic constraints.
  * `visitReturn(IRReturnStatement node)` — Validates returned expression against enclosing method's return type.
  * `hasErrors()` — Checks if semantic analysis generated errors.
  * `clearCaches()` — Clears reflection and field caches.
* **Important Relationships:** Extends `BaseIRVisitor`.

### `IROptimizerPipeline`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Unified multi-pass IR optimization coordinator.
* **Fields:**
  * `inliner` — `IRMethodInliner` instance for method inlining.
  * `constantFolder` — `IRConstantFolder` instance for compile-time expression evaluation.
  * `deadCodeEliminator` — `IRDeadCodeEliminator` instance for unreachable code pruning.
* **Methods:**
  * `optimize(IRNode node)` — Runs Pass 1 (Inlining), Pass 2 (Constant Folding), and Pass 3 (Dead Code Elimination) on the IR tree.
* **Important Relationships:** Coordinates `IRMethodInliner`, `IRConstantFolder`, and `IRDeadCodeEliminator`.

### `IRConstantFolder`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Ahead-of-Time (AOT) compile-time constant evaluator simplifying arithmetic, bitwise, string concatenation, and boolean logic on IR nodes.
* **Fields:**
  * Internal constant evaluation dispatch tables.
* **Methods:**
  * `optimize(IRNode node)` — Traverses and simplifies constant subexpressions across the IR tree.
  * `foldBinaryOp(IRBinaryOp node)` — Evaluates constant binary operations (`+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `>`, `&&`, `||`, `&`, `|`, `^`, `<<`, `>>`).
  * `foldUnaryOp(IRUnaryOp node)` — Evaluates constant unary operations (`-`, `!`, `~`).
* **Important Relationships:** Extends `BaseIRVisitor`.

### `IRDeadCodeEliminator`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Prunes dead code, unreachable branches in `if`/`switch` statements, and code following unconditional returns/throws.
* **Fields:** None.
* **Methods:**
  * `optimize(IRNode node)` — Traverses the IR tree and removes dead statements and unreachable branches.
* **Important Relationships:** Extends `BaseIRVisitor`.

### `IRMethodInliner`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Inlines small pure methods and `@Inline` annotated functions directly at call sites on the IR tree.
* **Fields:** None.
* **Methods:**
  * `optimize(IRNode node)` — Locates inline candidates and replaces `IRMethodCall` nodes with inlined expression bodies.
* **Important Relationships:** Extends `BaseIRVisitor`.

### `IRToBytecodeEmitter`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Bytecode generation visitor translating optimized IR nodes into JVM class bytecode via OW2 ASM.
* **Fields:**
  * `classWriter` — ASM `ClassWriter` instance for the current class.
  * `methodVisitor` — ASM `MethodVisitor` for the current method.
  * `generatedClasses` — Map of class names to compiled bytecode byte arrays.
  * `sourceFileName` — Source file name for debugging attributes.
  * `localVariables` — Map of variable names to JVM local variable indices.
  * `scopes` — Lexical scope frames for variable index tracking.
  * `stopLabels` / `skipLabels` — Stacks tracking break/continue jump targets.
* **Methods:**
  * `visitClass(IRClass node)` — Generates JVM class header, fields, methods, constructors, and static initializers.
  * `visitMethod(IRMethod node)` — Generates bytecode instructions for method body.
  * `visitAssignment(IRAssignment node)` — Generates variable or field store instructions (`ISTORE`, `ASTORE`, `PUTFIELD`, `PUTSTATIC`).
  * `visitBinaryOp(IRBinaryOp node)` — Emits arithmetic and comparison opcodes (`IADD`, `ISUB`, `IMUL`, `IDIV`, `IF_ICMPEQ`, etc.).
  * `visitMethodCall(IRMethodCall node)` — Emits invocation opcodes (`INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`, `INVOKESPECIAL`).
  * `visitNewObject(IRNewObject node)` — Emits `NEW`, `DUP`, and constructor `INVOKESPECIAL`.
  * `visitTryCatch(IRTryCatchStatement node)` — Generates exception handler tables and finally block wrappers.
  * `getGeneratedClasses()` — Returns the map of generated class names to `.class` byte arrays.
* **Important Relationships:** Extends `BaseIRVisitor`; uses OW2 ASM (`ClassWriter`, `MethodVisitor`, `Opcodes`).

### `IRDumper`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Formats and dumps the complete IR node tree into a structured, human-readable indented string representation for debugging.
* **Fields:**
  * `sb` — Internal StringBuilder accumulator.
  * `indent` — Current indentation level.
* **Methods:**
  * `dump(IRNode root)` — Returns formatted IR string representation of the given root node.
* **Important Relationships:** Extends `BaseIRVisitor`.

---

## Ocean Standard Library (`ocean.stdlib`)

### `RuntimeUtils`
* **Package:** `ocean.stdlib`
* **Type:** class
* **Responsibility:** Core runtime helper providing standard I/O scanner, generic arithmetic dispatch, comparison, and slicing support.
* **Fields:**
  * `scanner` — Lazy-initialized standard input `Scanner`.
* **Methods:**
  * `getScanner()` — Returns shared standard input `Scanner`.
  * `add(Number left, Number right)` — Generic runtime numeric addition.
  * `subtract(Number left, Number right)` — Generic runtime numeric subtraction.
  * `multiply(Number left, Number right)` — Generic runtime numeric multiplication.
  * `divide(Number left, Number right)` — Generic runtime numeric division.
  * `mod(Number left, Number right)` — Generic runtime numeric modulo.
  * `compare(Object left, Object right)` — Generic runtime polymorphic comparison supporting `Comparable` and numeric promotion.

### Primitive List Collections
* **Package:** `ocean.stdlib`
* **Types:** Classes (`OceanIntList`, `OceanDoubleList`, `OceanLongList`, `OceanFloatList`, `OceanShortList`, `OceanByteList`, `OceanCharList`, `OceanBooleanList`)
* **Responsibility:** Specialized, high-performance unboxed primitive array list structures eliminating boxing overhead.
* **Key Fields:**
  * `data` — Backing primitive array (`int[]`, `double[]`, `long[]`, etc.).
  * `size` — Number of elements stored.
* **Key Methods:**
  * `add(...)` / `get(int index)` / `set(int index, ...)` — Direct primitive element access.
  * `size()` / `isEmpty()` / `clear()` — Collection operations.
  * `sort()` / `reverse()` / `contains(...)` — In-place transformations and queries.

### Standard Collections & Functional Data Structures
* **Package:** `ocean.stdlib`
* **Types:** Classes
  * `OceanList<T>` — Generic dynamic array list wrapping `java.util.ArrayList` with functional extensions (`map`, `filter`, `fold`, `slice`).
  * `OceanMap<K, V>` — Generic hash map wrapping `java.util.HashMap`.
  * `OceanSet<T>` — Generic hash set wrapping `java.util.HashSet`.
  * `OceanStack<T>` — LIFO stack implementation.
  * `OceanQueue<T>` — FIFO queue implementation.
  * `OceanBitSet` — Dense bit array representation.
  * `OceanPair<A, B>` — 2-tuple record structure (`first`, `second`).
  * `OceanTriple<A, B, C>` — 3-tuple record structure (`first`, `second`, `third`).
  * `OceanResult<T, E>` — Monadic Result container (`Ok(T)` / `Err(E)`) for functional error handling.
  * `OceanOptional<T>` — Monadic Optional container (`Some(T)` / `None`).
  * `OceanCounter` — Mutable integer counter wrapper.

### Utilities, IO & Networking
* **Package:** `ocean.stdlib`
* **Types:** Classes
  * `OceanFile` — File reading, writing, appending, and existence checking.
  * `OceanHttp` / `OceanHttpResponse` — Synchronous and asynchronous HTTP GET/POST client based on OkHttp.
  * `OceanJson` — JSON serializer and parser wrapping Google Gson / Jackson.
  * `OceanMath` — Mathematical constants and utility functions.
  * `OceanRegex` — Regular expression matching and searching utility.
  * `OceanString` / `OceanStringBuilder` / `StrBuilder` — High-efficiency string manipulation and buffering tools.
  * `OceanDate` / `OceanTime` — Date and time parsing/formatting utilities.

---

# Interfaces / Inheritance

### Compiler & IR Visitor Hierarchy
```
IRNode (abstract class)
├── IRCompilationUnit
├── IRClass
├── IRInterface
├── IREnum
├── IRField
├── IRMethod
├── IRStatement (abstract class)
│   ├── IRBlock
│   ├── IRVariableDecl
│   ├── IRAssignment
│   ├── IRIfStatement
│   ├── IRWhileStatement
│   ├── IRDoWhileStatement
│   ├── IRForStatement
│   ├── IRSwitchStatement
│   ├── IRReturnStatement
│   ├── IRResultStatement
│   ├── IRStopStatement
│   ├── IRSkipStatement
│   ├── IRThrowStatement
│   ├── IRTryCatchStatement
│   ├── IRLockStatement
│   ├── IRLabeledStatement
│   └── IRExprStatement
└── IRExpression (abstract class)
    ├── IRLiteral
    ├── IRVariableAccess
    ├── IRBinaryOp
    ├── IRUnaryOp
    ├── IRTernaryExpression
    ├── IRCastExpression
    ├── IRInstanceof
    ├── IRMethodCall
    ├── IRNewObject
    ├── IRArrayCreation
    ├── IRArrayLiteral
    ├── IRArrayAccess
    ├── IRLambdaExpression
    ├── IRAwaitExpression
    ├── IRSwitchExpression
    ├── IRInterpolatedString
    └── IROceanOutput

IRVisitor (interface)
└── BaseIRVisitor (implements IRVisitor)
    ├── IRSemanticAnalyzer
    ├── IRToBytecodeEmitter
    ├── IRDumper
    ├── IRConstantFolder
    ├── IRDeadCodeEliminator
    └── IRMethodInliner
```

### Delegation & ANTLR Visitors
```
OceanBaseVisitor<T> (ANTLR generated)
├── PreScanner (extends OceanBaseVisitor<Void>)
└── IRGenerator (extends OceanBaseVisitor<IRNode>)

java.util.Map<K, V>
└── ThreadLocalDelegatingMap<K, V> (implements Map<K, V>)

java.util.Set<E>
└── ThreadLocalDelegatingSet<E> (implements Set<E>)

org.objectweb.asm.MethodVisitor
└── StackTrackingMethodVisitor (extends MethodVisitor)
```

---

# Data Flow

```
+--------------------------+
|  Source File (.ocean)    |
+--------------------------+
             |
             v [ANTLR Lexer / Token Stream Factory]
+--------------------------+
|     Filtered Tokens      |
+--------------------------+
             |
             v [ANTLR Parser]
+--------------------------+
|  ParseTree (CST / AST)   |
+--------------------------+
             |
             +----------------------------+
             |                            |
             v [PreScanner Pass 1]        v [IRGenerator Pass 2]
+--------------------------+    +--------------------------+
| CompilerRegistry/Session |    |   Initial IRNode Tree    |
+--------------------------+    +--------------------------+
             |                               |
             +--------------+----------------+
                            |
                            v [IRSemanticAnalyzer]
             +------------------------------+
             | Validated Typed IRNode Tree  |
             +------------------------------+
                            |
                            v [IROptimizerPipeline (Inlining, Folding, DCE)]
             +------------------------------+
             |  Optimized IRNode Tree       |
             +------------------------------+
                            |
                            v [IRToBytecodeEmitter (OW2 ASM)]
             +------------------------------+
             | Map<String, byte[]> Bytecode |
             +------------------------------+
                            |
             +--------------+---------------+
             |                              |
             v [Disk / Cache Output]        v [URLClassLoader / Java Reflection]
+--------------------------+    +--------------------------+
|   .class / Cache Files   |    |    Program Execution     |
+--------------------------+    +--------------------------+
```

---

# Entry Points

1. **CLI / Compiler Entry Point:**
   * Class: `ocean.compiler.OceanRunnerV3`
   * Method: `public static void main(String[] args)`
   * Direct delegation wrapper: `ocean.compiler.OceanRunner.main(String[] args)`
2. **Programmatic Compilation Entry Point:**
   * Class: `ocean.compiler.OceanRunnerV3`
   * Method: `public boolean compile(List<Path> sources)` / `public boolean compile(String sourcePath)`
3. **Command Line Launcher Scripts:**
   * Windows Batch Wrapper: `ocean.bat <SourceFile.ocean>`
   * Gradle Runner Tasks: `./gradlew run -PoceanArgs="..."` or `./gradlew runV3 -PoceanArgs="..."`

---

# Build & Configuration

* **JDK Target:** Java 17+ (Compiles bytecode with target `Opcodes.V17`).
* **Build System:** Gradle (using `gradlew` / `gradlew.bat`).
* **Key Dependencies:**
  * ANTLR 4.13.1 (`org.antlr:antlr4:4.13.1`, `org.antlr:antlr4-runtime:4.13.1`) — Grammar compilation and parser runtime.
  * OW2 ASM 9.6 (`org.ow2.asm:asm:9.6`, `org.ow2.asm:asm-util:9.6`) — JVM bytecode generation and manipulation.
  * Google Guava 33.0.0-jre — Core utility collections.
  * Google Gson 2.10.1 & Jackson Databind 2.16.1 — JSON processing.
  * OkHttp 4.12.0 — Standard library HTTP networking.
  * JUnit 5.10.1 (`org.junit.jupiter:junit-jupiter-api:5.10.1`) — Test framework.
* **Key Gradle Tasks:**
  * `generateGrammarSource`: Compiles `src/main/antlr/Ocean.g4` into `src/generated/java` with the `-visitor` option.
  * `shadowJar`: Builds the standalone executable fat JAR `build/libs/Ocean-all.jar` with `Main-Class: ocean.compiler.OceanRunner`.
  * `run` / `runV3`: Executes the compiler with runtime classpath and arguments passed via `-PoceanArgs="..."`.
  * `copyOceanDeps`: Copies all runtime dependency JARs to `build/ocean_deps`.

---

# Testing

* **Test Location:** `examples/` containing 300+ comprehensive integration, stress, feature, and negative test `.ocean` scripts.
* **Test Runners:**
  * `test_logical_correctness.ps1`: Multi-threaded parallel test suite executing tests through `ocean.bat` and verifying `STATUS: PASSED` markers.
  * `test_error_correctness.ps1` / `test_negative_correctness.ps1`: Verifies that invalid syntax, semantic violations, and type errors correctly trigger expected compilation errors without crashes.
  * `run_all_tests.ps1`: Sequential test runner executing all `.ocean` tests and producing a `test_results.csv` report.
  * `run_fast_tests.ps1` / `run_v3.ps1`: Quick smoke test runners for core features.
* **Test Verification Scope:**
  * Syntax, parsing, and precedence verification.
  * Type inference, generics, bounds, and variance checking.
  * Class inheritance, interfaces, abstract classes, sealed classes, and diamond hierarchies.
  * Null-safety, smart-casting, safe call operators (`?.`), and unboxing.
  * Async/await workflows, multi-threaded pipelines, and locks.
  * Method inlining, constant folding, and dead code elimination accuracy.
  * Specialized unboxed primitive collection performance and correctness.

---

# Important Design Decisions

1. **Explicit IR Layer vs Direct AST-to-Bytecode:**
   Earlier Ocean compiler versions (V1/V2 in `legacy`) traversed ANTLR `ParseTree` directly during bytecode generation, which coupled semantic validation with emission and limited multi-pass optimizations. Version 3.0 introduced the dedicated `ocean.compiler.ir` package, enabling ahead-of-time inlining, constant folding, dead code elimination, and independent semantic checking.
2. **ThreadLocal Session State for Parallel Compilation:**
   `CompilationSession` holds all instance state, while `CompilerRegistry` wraps sessions via `ThreadLocalDelegatingMap` and `ThreadLocalDelegatingSet`. This allows source files to be parsed, analyzed, and compiled across multiple parallel worker threads without cross-thread contamination.
3. **Decoupled Two-Pass Architecture (`PreScanner` before `IRGenerator`):**
   Top-level symbols, interfaces, and method descriptors are harvested across all dirty sources in Pass 1 (`PreScanner`) before translating method bodies in Pass 2 (`IRGenerator`). This solves mutual recursion, forward class references, and circular dependencies without multi-file header order dependencies.
4. **Flow-Sensitive Null Safety & Definite Assignment:**
   `IRSemanticAnalyzer` tracks nullability state across branch conditions, enabling Kotlin-like smart casting where checked nullable references (`T?`) are treated as non-null (`T`) within verified `if` branches.
5. **Specialized Primitive Collections (`OceanIntList`, `OceanDoubleList` etc.):**
   To prevent JVM auto-boxing overhead in compute-intensive loops and benchmarks, the standard library provides specialized primitive array lists alongside generic collections.

---

# Maintenance Notes

To keep `PROJECT.md` accurate and up to date as the codebase evolves:

* **Adding New Classes:** When introducing a new compiler class, IR node, or stdlib component, add its specification under `# Classes` with Package, Type, Responsibility, Fields, Methods, and Important Relationships.
* **Modifying Responsibilities or Signatures:** If class responsibilities, key fields, or public methods change, update the corresponding entry in `# Classes`.
* **Updating the Pipeline or Architecture:** If new compilation passes, optimization stages, or runtime subsystems are introduced, update `# Architecture`, `# Pipeline`, and `# Data Flow`.
* **Modifying Build Configuration:** If bytecode target version, Gradle tasks, or external library dependencies are updated, update `# Build & Configuration`.
* **Consistency Rule:** `PROJECT.md` must strictly describe verified repository code without adding speculative or non-existent features.

---

# 9-Stage Comprehensive Compiler Optimization & Refactoring Log

A comprehensive 9-stage inspection, bug fixing, optimization, dead code removal, ClassLoader standardization, and performance refactoring pass was completed and verified across all 289 test suites (244 logical + 5 interactive + 40 negative = 289 tests):

1. **Stage 1 (Session & Incremental Check):** Standardized file path normalization caches, concurrency safety on `CompilationSession`, and fast timestamp/dependency fingerprint validation.
2. **Stage 2 (Parallel Parsing):** Streamlined right-shift generic token disambiguation, token stream factory caching, and thread-safe error reporting via `OceanErrorListener`.
3. **Stage 3 (Pre-Scanning Pass 1):** Verified top-level symbol discovery, interface registrations, generic bounds harvesting, and circular dependency resolution across parallel parse trees.
4. **Stage 4 (IR Construction Pass 2):** Fixed primitive numeric bounds checks, integer/long boundary handling, and variable scope index allocation during high-level IR node generation.
5. **Stage 5 (Semantic Analysis):** Fast-pathed length-1 descriptor cleaning in `TypeChecker`, whitelisted stdlib in `IRSemanticAnalyzer` access control, removed debug prints, and cached descriptor parameter parsing.
6. **Stage 6 (IR Optimization Pipeline):** Standardized ClassLoader resolution in `IRDeadCodeEliminator` and `IRConstantFolder` using context-aware `OceanTypeSystem.forName`, and structured `@Inline` diagnostics in `IRMethodInliner` via `CompilerReporter`.
7. **Stage 7 (Bytecode Emission Pass 3):** Fixed duplicate `labelStep` and phantom stack pushes in `IRToBytecodeEmitter.visitForStatement` for long/float/double loops, eliminated duplicate `extractParamTypes` and unused fields, and integrated `StackTrackingMethodVisitor` warnings into `CompilerReporter`.
8. **Stage 8 (Standard Library & Runtime Helpers):** Fixed `ArrayIndexOutOfBoundsException` and eliminated char array allocations in `OceanList.stringCompare` using `compareTo`, improved `isSorted` with `Number`/`Comparable` support, and synchronized UTF-8 `RuntimeUtils.getScanner`.
9. **Stage 9 (Compiler Pipeline Finalization):** Cleaned up duplicate nested try-catch handlers in `OceanRunnerV3`, standardized CLI error reporting, and updated project maintenance documentation.

---

# Round 2: Ultra-Deep 9-Stage Scrutiny & Precision Bug Hunt Log

A second, ultra-deep pass across all compiler passes was executed with strict per-stage verification (289/289 tests PASS):
* **Stage 1 & 4 (Session & IR Construction):** Unified `CompilationSession.cachedForName` and `IRGenerator.forName` to route consistently through `OceanTypeSystem.forName(name)`, preserving sentinel class resolution and thread-context classloader lookups.
* **Stage 2, 3, 5, 6 (Grammar, PreScanner, Semantics & Optimizer):** Conducted exhaustive line-by-line validation of generic parsing, two-pass symbol registration, flow-sensitive nullability checks, recursive method inlining prevention, and dead code pruning.
* **Stage 7 (Bytecode Generation Pass 3):** Fixed `float` (`FLOAD`) parameter loading opcode in `IRToBytecodeEmitter.emitSingleBridge` (previously defaulted to `ILOAD`), and unified parameter count calculations across `countParameters` to use `parseParameterTypes(desc).size()`.
* **Stage 8 & 9 (Stdlib, Runtime & CLI):** Verified zero-allocation collection operations, verified UTF-8 scanner handling, and validated clean teardown cycles in `OceanRunnerV3`.
* **IRGenerator Deep Audit:** Fixed generic type parameter scoping in `visitClassDeclaration` and `visitNormalMethod` with local snapshot preservation/restoration, improved non-null type inference in `visitNullCoalescingExpr` (`??`), and standardized `resolveTypeName` reflection lookups.
* **IRSemanticAnalyzer Deep Audit:** Added reflection fallback in `visitNewObject` to intercept direct instantiations of standard library / external abstract classes and interfaces, isolated `currentMethodIsAsync` inside `visitLambda` to prevent illegal `await` expressions in synchronous lambdas, preserved generic type scoping across `visitClass`, and cleaned up error message punctuation.
* **PreScanner Deep Audit:** Registered default `<init>` constructors for anonymous classes in `CompilerRegistry.globalOverloadRegistry`, unified wildcard import resolution with `OceanTypeSystem.forName`, added `PreScanner.clearCaches()` hooked into `CompilerRegistry.clearAll()`, and added active class FQCN session tracking for annotation declarations.
* **OverloadResolver Deep Audit:** Added unboxing plus widening conversion (`Integer` -> `long`, `Float` -> `double`) in `isParamAssignable`, enforced `cleanOwner` in `getClassHierarchy` entry to normalize `hierarchyCache` keys, and improved `isMoreSpecific` primitive-to-reference preference.
* **SymbolTable Deep Audit:** Made wildcard ambiguity error reporting completely null-safe against detached compilation sessions, and included `currentPackage` in `descriptorCache` key generation to ensure package-isolated type resolution caching.
* **TypeInferenceEngine Deep Audit:** Enhanced `ArrayAccessExprContext` to correctly infer value types (`V`) from generic `Map<K, V>` structures using `splitGenericsParts`, dynamically resolved index parameter types for overloaded index accessors, and ensured clean receiver descriptors in `MethodRefExprContext`.
* **TypeChecker Deep Audit:** Separated boolean and numeric branches in `getCommonType` to prevent invalid boolean promotion of mixed integer-boolean expressions, used cleaned descriptors in `isComparable` to enforce String comparison restrictions, and made `isBoxedEquivalent` symmetric.
* **OceanTypeSystem Deep Audit:** Fixed `isAsyncFutureType` by replacing incorrect `isStringType` check with `isClassType`, added `OceanTypeSystem.clearCaches()` hooked into `CompilerRegistry.clearAll()`, and standardized reflection class resolution with `OceanTypeSystem.forName`.
* **IROptimizer Pipeline Deep Audit:** Expanded `IRMethodInliner.substituteParameters` to recursively map parameters within `IRMethodCall`, `IRCastExpression`, `IRInstanceof`, and `IRArrayAccess`, added `Float` unary negation folding in `IRConstantFolder.foldUnaryOp`, corrected `charAt` constant fold literal descriptor to `"C"`, and registered `IRConstantFolder.clearCaches()` in `CompilerRegistry.clearAll()`.
