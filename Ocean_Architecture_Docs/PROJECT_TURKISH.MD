# Projeye Genel Bakış (Project Overview)

Ocean, Java Sanal Makinesi (JVM) üzerinde çalışmak üzere tasarlanmış, statik tipli ve yüksek performanslı bir programlama dilidir. Modern dil ergonomisini (tip çıkarımı, null güvenliği, lambdalar, örüntü eşleme, async/await, sealed sınıflar, inline value sınıfları, data sınıfları ve extension fonksiyonları), özelleştirilmiş unboxed ilkel (primitive) koleksiyonlar, derleme anında sabit katlama (AOT Constant Folding), metot inlining ve ara temsil (Intermediate Representation - IR) optimizasyon hattı ile birleştirir.

Ocean derleyicisinin ön ucu (frontend), sözcüksel ve sözdizimsel ayrıştırma için ANTLR4 kullanır; kaynak dosyaları paralel olarak ayrıştırır, anlamsal (semantik) kısıtlamaları doğrular, Soyut Sözdizim Ağacını (AST) tipli bir Ara Temsile (IR) dönüştürür, çok aşamalı IR optimizasyonlarını uygular ve OW2 ASM kütüphanesi aracılığıyla standart JVM bayt kodunu (hedef JVM sürümü Java 17 / Opcodes.V17) üretir.

---

# Mimari (Architecture)

Ocean derleyici mimarisi; çok geçişli, bileşenleri birbirinden ayrık ve IR güdümlü bir derleme modelini takip eder:

```
+-----------------------------------------------------------------------------------+
|                                Ocean CLI / Çalıştırıcı                            |
|                  (OceanRunner -> OceanRunnerV3 / CompilationSession)              |
+-----------------------------------------------------------------------------------+
                                         |
     +-----------------------------------+-----------------------------------+
     |                                                                       |
     v                                                                       v
+-----------------------------+                         +-----------------------------+
|    Artımlı Derleme Motoru   |                         |      Derleyici Kayıt Defteri|
|  - Bağımlılık Takibi        |                         |  - Global Tip & İmzalar     |
|  - Zaman Damgası & Parmak İzi|                        |  - ThreadLocal Oturum Verisi|
|  - Binary & Önbellek Yönetici|                        |  - Dinamik Tip Çözümleme    |
+-----------------------------+                         +-----------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                 Derleme Motoru                                    |
|                                                                                   |
|  1. Lexer & Parser (ANTLR4) ---> CST / ParseTree                                  |
|  2. PreScanner (1. Geçiş)   ---> Oturum ve Global Kayıt Defterlerini Doldurur     |
|  3. IRGenerator (2. Geçiş)  ---> Tipli Yüksek Seviye IR Ağacını İnşa Eder         |
|  4. IRSemanticAnalyzer      ---> Tip Kontrolü, Erişim Denetimi, Kesin Atama Doğr. |
|  5. IROptimizerPipeline     ---> Inlining, Sabit Katlama, Ölü Kod Temizleme       |
|  6. IRToBytecodeEmitter     ---> OW2 ASM ile JVM Bayt Kodu Üretir                 |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                        JVM Çalışma Zamanı & Standart Kütüphane                    |
|  - Yüksek performanslı kutulamasız ilkel koleksiyonlar (OceanIntList vb.)         |
|  - Çalışma zamanı yardımcıları (RuntimeUtils, OceanString, OceanJson, OceanHttp)   |
|  - Java Standart Kütüphane uyumluluğu ve Reflection fallback mekanizması          |
+-----------------------------------------------------------------------------------+
```

### Temel Mimari Bileşenler

1. **Oturum ve Kayıt Katmanı (`CompilationSession`, `CompilerRegistry`)**:
   Global tip imzalarını, sınıf hiyerarşilerini, metot aşırı yüklemelerini (overload), alan yerleşimlerini, sealed sınıf kısıtlarını ve bağımlılık çizgesini yönetir. Güvenli paralel derleme sağlamak için `ThreadLocal` delegasyonları kullanır.
2. **Ayrıştırma ve AST Aşaması (`OceanLexer`, `OceanParser`, `OceanTokenStreamFactory`)**:
   Ocean kaynak kodunu ANTLR ayrıştırma ağaçlarına dönüştürür; iç içe generic token'ları (`>>`, `>>>`) için özel ayrıştırma uygular.
3. **Ön Tarama Aşaması (`PreScanner`)**:
   Kod üretiminden önce sınıf isimlerini, metot imzalarını, alan tiplerini ve generic sınırlarını toplayarak karşılıklı ve döngüsel bağımlılıkları çözer.
4. **Ara Temsil Katmanı (`ocean.compiler.ir.*`)**:
   Semantik kontrolü ve kod üretimini doğrudan ANTLR ayrıştırma ağacından ayıran, nesne yönelimli, açık bir ara temsil ağacıdır.
5. **Semantik Analiz (`IRSemanticAnalyzer`, `SymbolTable`, `TypeChecker`, `OverloadResolver`)**:
   Tip kontrolünü, null güvenliğini, kesin atama denetimini, istisna sözleşmelerini, erişim belirteçlerini ve akışa duyarlı akıllı tür dönüştürmeyi (smart cast) doğrudan IR ağacı üzerinde gerçekleştirir.
6. **IR Optimizasyon Hattı (`IROptimizerPipeline`, `IRMethodInliner`, `IRConstantFolder`, `IRDeadCodeEliminator`)**:
   AST'den bağımsız 3 geçişli optimizasyon uygular: küçük ve saf metotları inline eder, derleme anında sabit ifadeleri hesaplar ve ölü dalları temizler.
7. **Bayt Kodu Üretimi (`IRToBytecodeEmitter`, `StackTrackingMethodVisitor`)**:
   Optimize edilmiş IR düğümlerini ziyaret ederek OW2 ASM kütüphanesi ile satır numarası hata ayıklama bilgilerini içeren JVM `.class` dosyaları üretir.
8. **Çalışma Zamanı ve Standart Kütüphane (`ocean.stdlib.*`)**:
   Kutulama (boxing) maliyeti olmayan ilkel tür listeleri, genel koleksiyonlar, fonksiyonel demetler (tuple), sonuç monadları, dosya I/O, ağ ve JSON araçları sunar.

---

# Derleme ve Çalışma Akışı (Pipeline)

Ocean derleyicisinin sıralı çalışma ve derleme akışı şu şekildedir:

`Kaynak Kod (.ocean) -> Lexer/Parser -> PreScan -> IR Üretimi -> Semantik Analiz -> IR Optimizasyonu -> Bayt Kodu Üretimi -> Disk/Önbellek Çıktısı -> Çalıştırma`

### Ayrıntılı Akış Tablosu

| Aşama | Önemli Sınıflar | Girdi | Çıktı | Açıklama |
|---|---|---|---|---|
| **1. Oturum & Artımlı Kontrol** | `OceanRunnerV3`, `CompilationSession` | Dosya Yolları, Önbellek Dosyası | Değişen (Dirty) Dosyalar | Dosya değiştirilme zamanları, bildirilen tipler ve bağımlılık parmak izleri kontrol edilerek yalnızca değişen dosyalar derlemeye alınır. Geçerli önbellek yüklenir. |
| **2. Paralel Ayrıştırma** | `OceanLexer`, `OceanParser`, `OceanTokenStreamFactory`, `OceanErrorListener` | Kaynak Metin | `ParseTree` (ProgramContext) | Ocean kaynak dosyaları paralel iş parçacıklarında tokenize edilir ve sözdizim ağacına dönüştürülür. Sağ kaydırma generic token'ları düzeltilir. |
| **3. Ön Tarama (1. Geçiş)** | `PreScanner`, `CompilerRegistry`, `SymbolTable` | `ParseTree` | Doldurulmuş Kayıt Defterleri | Paket bildirimleri, sınıf, arayüz, enum tanımları, üst tipler, metot başlıkları, alanlar, extension fonksiyonları ve generic parametreler toplanır. |
| **4. IR İnşası (2. Geçiş)** | `IRGenerator`, `SymbolTable`, `OceanTypeSystem` | `ParseTree` | Yüksek Seviye `IRNode` Ağacı (`IRCompilationUnit`) | ANTLR CST düğümleri yapısal IR düğümlerine (`IRClass`, `IRMethod`, `IRBlock`, `IRExpression` vb.) dönüştürülür. Değişken kapsamları çözümlenir. |
| **5. Semantik Analiz** | `IRSemanticAnalyzer`, `TypeChecker`, `OverloadResolver`, `SymbolTable` | `IRNode` Ağacı | Doğrulanmış `IRNode` Ağacı / Teşhis Hataları | Tip uyumluluğu, kesin atama, null güvenliği, override geçerliliği, erişim belirteçleri, sealed sınıf kısıtları ve fırlatılan checked exception sözleşmeleri denetlenir. |
| **6. IR Optimizasyonu** | `IROptimizerPipeline`, `IRMethodInliner`, `IRConstantFolder`, `IRDeadCodeEliminator` | `IRNode` Ağacı | Optimize Edilmiş `IRNode` Ağacı | 3 geçişli optimizasyon: metot inlining, derleme anında sabit katlama (constant folding) ve ölü kodların (dead code) temizlenmesi uygulanır. |
| **7. Bayt Kodu Üretimi** | `IRToBytecodeEmitter`, `StackTrackingMethodVisitor` | Optimize Edilmiş `IRNode` Ağacı | `Map<String, byte[]>` (Sınıf Bayt Kodları) | ASM kütüphanesiyle IR düğümleri gezilerek hassas satır numarası tablolarıyla JVM bayt kodu üretilir. |
| **8. Kalıcılık & Önbellekleme** | `OceanRunnerV3`, `CompilationSession` | Bayt Kodu Haritaları & Meta Veri | `.class` Dosyaları & `compiler_cache.dat` | Üretilen sınıflar diske (`build/classes/java/main`) yazılır; oturum meta verileri ve bağımlılık çizgeleri önbelleğe kaydedilir. |
| **9. Çalıştırma (Opsiyonel)** | `OceanRunnerV3` | Bayt Kodu / Sınıf Adı | Program Çıktısı / Çıkış Kodu | İzole bir `URLClassLoader` ile üretilen sınıflar yüklenir ve ana sınıfın `main(String[])` metodu çalıştırılır. |

---

# Dizin ve Paket Yapısı (Directory / Package Structure)

```
Ocean/
├── src/
│   ├── main/
│   │   ├── antlr/
│   │   │   └── Ocean.g4                              # Ocean dili için ANTLR4 gramer tanımı
│   │   ├── java/
│   │   │   └── org/
│   │   │       └── Ocean/
│   │   │           ├── Compiler/                     # Çekirdek derleyici motoru ve semantik analiz
│   │   │           │   ├── ir/                       # Ara Temsil (IR) AST düğüm tanımları ve optimize ediciler
│   │   │           │   └── legacy/                   # Eski derleyici sürümleri (V1/V2 AST ziyaretçileri)
│   │   │           ├── stdlib/                       # Ocean standart kütüphanesi (koleksiyonlar, ilkel türler, IO)
│   │   │           └── utils/                        # Yardımcı sınıflar (tip çıkarım araçları)
│   └── generated/
│       └── java/                                     # ANTLR tarafından üretilen lexer, parser ve visitor sınıfları
├── build.gradle                                      # Gradle yapılandırma dosyası (ShadowJar, ANTLR, JUnit)
├── ocean.bat                                         # Ocean programlarını derleyen ve çalıştıran CLI script'i
├── test_logical_correctness.ps1                      # Test paketinin mantıksal doğruluğunu denetleyen test koşucusu
└── test_error_correctness.ps1                        # Derleme hatalarının yakalanmasını denetleyen negatif test koşucusu
```

### Paket Sorumlulukları

* `ocean.compiler`: Giriş noktalarını, oturum yönetimini, ön tarayıcıyı, sembol tablosunu, tip denetleyicisini ve derleyici ayarlarını barındırır.
* `ocean.compiler.ir`: Tipli Ara Temsil (IR) düğüm hiyerarşisini, ziyaretçi (visitor) arayüzlerini ve optimizasyon geçişlerini (Constant Folding, Dead Code Elimination, Inlining) barındırır.
* `ocean.compiler.legacy`: IR öncesi doğrudan AST'den bayt koduna geçiş yapan eski mimari sınıflarını referans amacıyla barındırır.
* `ocean.stdlib`: Kutulama yapmayan ilkel sayı listelerini, fonksiyonel koleksiyonları, JSON, HTTP, regex, dosya ve çalışma zamanı yardımcılarını içerir.
* `ocean.utils`: Metin tabanlı bağımsız tip çıkarımı gibi genel yardımcı araçları barındırır.

---

# Sınıflar (Classes)

## Çekirdek Derleyici Sınıfları (`ocean.compiler`)

### `OceanRunnerV3`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Modern IR güdümlü Ocean derleyicisinin ana CLI giriş noktası ve yürütme yöneticisi.
* **Fields:**
  * `packageCache` — Dosya yollarını çözümlenmiş paket adlarına eşleyen eşzamanlı önbellek.
  * `currentRunClasses` — Mevcut derleme turunda derlenen tam nitelikli sınıf adları kümesi.
  * `programArgs` — Hedef programa aktarılacak komut satırı argümanları dizisi.
  * `activeClasspath` — Derleme ve çalıştırma için kullanılan aktif classpath metni.
  * `compiledInMemoryClasses` — Bellek içinde üretilen bayt kodlarının önbelleği.
* **Methods:**
  * `main(String[] args)` — CLI bayraklarını (`-cp`, `--json`, `-c`, `-debug`) ayrıştırır, oturumu başlatır ve derlemeyi tetikler.
  * `compile(List<Path> sources)` — Değişen kaynak dosyalar üzerinde çok geçişli derleme hattını yürütür.
  * `compile(String sourcePath)` — Tek bir kaynak dosya yolunu derlemek için kolaylık sağlayan aşırı yükleme.
  * `isDirty(Path p)` — Dosyanın veya bağımlılıklarının son derlemeden bu yana değişip değişmediğini kontrol eder.
  * `executeMain(String mainClassName)` — Üretilen ana sınıfı reflection kullanarak yükler ve çalıştırır.
  * `cleanBinaryDir(List<Path> dirtySources)` — Değişen dosyalara ait eski `.class` çıktılarını temizler.
  * `saveBytecode(Map<String, byte[]> generated, Path sourcePath)` — Üretilen bayt kodlarını hedef dizine yazar.
  * `discoverSources(String[] args)` — Komut satırı argümanlarından kaynak dosyaları ve bağımlılıkları tespit eder.
* **Important Relationships:** `CompilationSession`, `PreScanner`, `IRGenerator`, `IRSemanticAnalyzer`, `IROptimizerPipeline` ve `IRToBytecodeEmitter` sınıflarını koordine eder.

### `OceanRunner`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Geriye dönük uyumluluk için çağrıları doğrudan `OceanRunnerV3` sınıfına yönlendiren köprü sınıf.
* **Fields:** Yok.
* **Methods:**
  * `main(String[] args)` — Argümanları doğrudan `OceanRunnerV3.main` metoduna iletir.
* **Important Relationships:** `OceanRunnerV3` sınıfına delege eder.

### `CompilationSession`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Derleme oturumu boyunca global sembol tablolarını, bağımlılık çizgelerini, önbellekleri ve kalıcılığı yöneten merkezi durum deposu.
* **Fields:**
  * `messages` — Oturum boyunca toplanan hata, uyarı ve bilgi iletileri listesi.
  * `activeSession` — İş parçacığı başına aktif oturumu tutan ThreadLocal değişkeni.
  * `globalFieldRegistry` — Sınıf ve alan adına göre alan tiplerini tutan kayıt haritası.
  * `globalFieldMutability` — Alanların değiştirilebilirlik (`variable` / `value`) durumunu tutan harita.
  * `globalFieldStaticity` — Alanların static olup olmadığını tutan harita.
  * `globalFieldAccess` — Alanların ASM erişim bayraklarını tutan harita.
  * `globalMethodRegistry` — Metot imzalarını ve tanımlayıcılarını tutan harita.
  * `globalMethodGenericReturnTypeRegistry` — Metotların generic dönüş tiplerini tutan harita.
  * `globalMethodStaticity` — Metotların static olup olmadığını tutan harita.
  * `globalMethodAccess` — Metotların ASM erişim bayraklarını tutan harita.
  * `globalOverloadRegistry` — Metot adına göre tüm aşırı yükleme tanımlayıcılarını saklayan harita.
  * `globalMethodTypeParametersRegistry` — Metot seviyesindeki generic tip parametrelerini tutan harita.
  * `globalMethodThrowsRegistry` — Metotların bildirdiği checked exception listesini saklayan harita.
  * `globalMethodParamsRegistry` — Metot parametre meta verilerini saklayan harita.
  * `globalExtensionMethodRegistry` — Alıcı (receiver) tiplerine göre extension metotlarını saklayan harita.
  * `globalSuperClassRegistry` — Sınıfların üst sınıf yollarını tutan harita.
  * `globalClassAccess` — Sınıfların erişim belirteç bayraklarını tutan harita.
  * `globalInterfaceRegistry` — Sınıfların uyguladığı arayüzleri tutan harita.
  * `globalTypeParameterRegistry` — Sınıf seviyesi generic tip parametrelerini saklayan harita.
  * `globalAbstractClassSet` — Soyut (abstract) sınıflar kümesi.
  * `globalIsInterfaceSet` — Arayüz tipleri kümesi.
  * `globalSealedClassSet` — Sealed sınıflar kümesi.
  * `globalExplicitRestrictsSet` — Açık `restricts` bildirimine sahip sealed sınıflar kümesi.
  * `globalDataClassSet` — Data sınıfları kümesi.
  * `globalAsyncMethodSet` — Asenkron (`async`) metotlar kümesi.
  * `globalPermittedSubclassesRegistry` — Sealed sınıfların izin verilen alt sınıflarını saklayan harita.
  * `globalSubclassStatusRegistry` — Alt sınıfların belirteç durumlarını (`final`, `sealed`, `non-sealed`) tutan harita.
  * `sourceFileMetadata` — Dosya değiştirilme zamanlarını, paket adlarını ve parmak izlerini saklayan harita.
  * `classDependencies` — Artımlı derleme için sınıflar arası bağımlılık ilişkilerini saklayan harita.
  * `reflectionClassCache` — Reflection ile yüklenen Java sınıflarının önbelleği.
* **Methods:**
  * `getActiveSession()` — Çağrıyı yapan iş parçacığının aktif oturumunu döndürür.
  * `setActiveSession(CompilationSession session)` — Bir oturumu mevcut iş parçacığına bağlar.
  * `clearActiveSession()` — İş parçacığına bağlı oturum durumunu temizler.
  * `isSubType(String sub, String sup)` — İki tip arasındaki alt tip/üst tip hiyerarşisini doğrular.
  * `loadFromCache(Path cacheFile)` — Zaman damgaları eşleşiyorsa önbellek dosyasını oturuma yükler.
  * `saveToCache(Path cacheFile)` — Oturum durumunu ve bağımlılık ağacını diske önbellek olarak serileştirir.
  * `removeClassesBySimpleNames(Set<String> simpleNames)` — Yeniden derlenecek sınıflara ait kayıtları temizler.
  * `removeRegistryEntriesForFqcn(String fqcn)` — Belirli bir sınıfa ait tüm alan, metot ve hiyerarşi kayıtlarını siler.
  * `rollbackDirtyMetadata(Set<String> pathKeys)` — Hatalı derleme durumunda geçici önbellek verilerini geri alır.
* **Important Relationships:** `CompilerRegistry`, `SymbolTable` ve `IRSemanticAnalyzer` için merkezi durum kaynağıdır.

### `CompilerRegistry`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Aktif oturumdaki kayıt defterlerine statik erişim sağlayan ThreadLocal proxy katmanı.
* **Fields:**
  * `globalFieldRegistry` — Aktif oturumun alan kayıt defterine delege eden statik harita.
  * `globalFieldMutability` — Alan değiştirilebilirlik defteri delegasyonu.
  * `globalFieldStaticity` — Alan staticlik defteri delegasyonu.
  * `globalFieldAccess` — Alan erişim bayrakları delegasyonu.
  * `globalMethodRegistry` — Metot kayıt defteri delegasyonu.
  * `globalMethodGenericReturnTypeRegistry` — Metot generic dönüş tipleri delegasyonu.
  * `globalMethodStaticity` — Metot staticlik defteri delegasyonu.
  * `globalMethodAccess` — Metot erişim bayrakları delegasyonu.
  * `globalOverloadRegistry` — Metot aşırı yükleme defteri delegasyonu.
  * `globalMethodTypeParametersRegistry` — Metot generic parametreleri delegasyonu.
  * `globalMethodThrowsRegistry` — Metot istisna bildirimleri delegasyonu.
  * `globalMethodParamsRegistry` — Metot parametre meta verileri delegasyonu.
  * `globalExtensionMethodRegistry` — Extension metotları defteri delegasyonu.
  * `globalSuperClassRegistry` — Üst sınıf kayıt defteri delegasyonu.
  * `globalClassAccess` — Sınıf erişim bayrakları delegasyonu.
  * `globalInterfaceRegistry` — Uygulanan arayüzler defteri delegasyonu.
  * `globalTypeParameterRegistry` — Generic tip parametreleri delegasyonu.
  * `globalAbstractClassSet` — Soyut sınıflar kümesi delegasyonu.
  * `globalIsInterfaceSet` — Arayüzler kümesi delegasyonu.
  * `globalSealedClassSet` — Sealed sınıflar kümesi delegasyonu.
  * `globalExplicitRestrictsSet` — Açık kısıtlı sealed sınıflar kümesi delegasyonu.
  * `globalDataClassSet` — Data sınıfları kümesi delegasyonu.
  * `globalAsyncMethodSet` — Asenkron metotlar kümesi delegasyonu.
  * `globalPermittedSubclassesRegistry` — İzin verilen alt sınıflar defteri delegasyonu.
  * `globalSubclassStatusRegistry` — Alt sınıf belirteç durumları delegasyonu.
  * `globalFunctionalInterfaceRegistry` — SAM fonksiyonel arayüzleri kümesi delegasyonu.
  * `globalListLikeOwnerRegistry` — Liste benzeri kapsayıcı tipler kümesi delegasyonu.
  * `globalMapLikeOwnerRegistry` — Harita benzeri kapsayıcı tipler kümesi delegasyonu.
  * `implicitImportPrefixes` — Otomatik içe aktarılan paket ön ekleri listesi (`java.lang.`, `ocean.stdlib.`).
* **Methods:**
  * `clearAll()` — Tüm delegasyon haritalarını ve derleyici içi alt önbellekleri sıfırlar.
* **Important Relationships:** `ThreadLocalDelegatingMap` ve `ThreadLocalDelegatingSet` kullanarak `CompilationSession` nesnesine bağlanır.

### `CompilerReporter`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Derleyici hata, uyarı ve bilgi teşhislerini toplayan, biçimlendiren ve raporlayan merkezi sınıf.
* **Fields:**
  * `fallbackMessages` — Aktif bir oturum bulunmadığında kullanılan yedek ileti listesi.
* **Methods:**
  * `report(Level level, String file, int line, int column, String code, String text, String context)` — Teşhis iletisini kaydeder.
  * `error(String file, int line, int column, String text, String context)` — Hata iletisi ekler.
  * `warning(String file, int line, int column, String text, String context)` — Uyarı iletisi ekler.
  * `info(String file, int line, int column, String text, String context)` — Bilgi iletisi ekler.
  * `hasErrors()` — Kaydedilmiş herhangi bir hata olup olmadığını bildirir.
  * `getMessages()` — Kayıtlı tüm iletilerin listesini döndürür.
  * `clear()` — Kayıtlı iletileri temizler.
  * `printSummary()` — Teşhis raporunu konsola renkli ve biçimlendirilmiş olarak basar.
  * `printJson()` — IDE ve araç entegrasyonları için iletileri JSON dizisi olarak basar.
  * `determineErrorCode(String text, String context)` — Hata metnine göre standart hata kodunu (`E0001`–`E0014`) belirler.
* **Important Relationships:** `OceanErrorListener`, `IRSemanticAnalyzer` ve `OceanRunnerV3` tarafından kullanılır.

### `CompilerConfig`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** Derleyici genelindeki sabit yapılandırma değerleri ve tip takma adları için tek referans noktasıdır.
* **Fields:**
  * `BYTECODE_VERSION` — Hedef JVM bayt kodu sürümü sabiti (`Opcodes.V17`).
  * `OCEAN_TYPE_ALIASES` — Ocean anahtar kelime tiplerinin JVM tanımlayıcı eşlemeleri.
* **Methods:** Yok (private yapıcı metot).
* **Important Relationships:** `IRGenerator`, `SymbolTable` ve `IRToBytecodeEmitter` tarafından referans alınır.

### `PreScanner`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** 1. geçiş AST ziyaretçisi; sınıf başlıklarını, üye imzalarını, erişim belirteçlerini ve extension fonksiyonlarını toplayıp `CompilerRegistry`'yi doldurur.
* **Fields:**
  * `currentFile` — Taranmakta olan dosya adı.
  * `currentFilePackage` — Dosyanın ait olduğu paket yolu.
  * `currentClassName` — Taranan mevcut sınıfın tam adı.
  * `currentSuperName` — Mevcut sınıfın üst sınıf adı.
  * `importedClasses` — İçe aktarılan sınıfların eşleme haritası.
  * `importedWildcards` — Joker karakterli paket içe aktarım listesi.
  * `importedStaticMembers` — İçe aktarılan static üyeler haritası.
  * `importedStaticWildcards` — Joker karakterli static içe aktarım listesi.
  * `session` — Aktif derleme oturumu.
  * `symbolTable` — Tarama sırasında kullanılan sembol tablosu.
* **Methods:**
  * `visit(ParseTree tree)` — AST üzerinde ön tarama gezintisini başlatır.
  * `visitPackageDeclaration(OceanParser.PackageDeclarationContext ctx)` — Paket alan adını kaydeder.
  * `visitImportStatement(OceanParser.ImportStatementContext ctx)` — İçe aktarılan sınıfları ve paketleri kaydeder.
  * `visitClassDeclaration(OceanParser.ClassDeclarationContext ctx)` — Sınıf, hiyerarşi, sealed kısıtları ve data sınıfı imzalarını kaydeder.
  * `visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx)` — Arayüz sözleşmelerini ve genişletilen arayüzleri kaydeder.
  * `visitEnumDeclaration(OceanParser.EnumDeclarationContext ctx)` — Enum tiplerini ve sabitlerini kaydeder.
  * `visitFunctionDeclaration(OceanParser.FunctionDeclarationContext ctx)` — Metot tanımlayıcılarını, throws bildirimlerini ve erişim bayraklarını kaydeder.
  * `visitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx)` — Yapıcı metot parametre imzalarını kaydeder.
  * `visitFieldDeclaration(OceanParser.FieldDeclarationContext ctx)` — Alan tiplerini, değiştirilebilirlik ve erişim bayraklarını kaydeder.
  * `visitExtensionFunctionDeclaration(OceanParser.ExtensionFunctionDeclarationContext ctx)` — Extension metotlarını hedef alıcı tipe kaydeder.
* **Important Relationships:** `OceanBaseVisitor<Void>` sınıfını genişletir; `CompilerRegistry` ve `CompilationSession`'ı doldurur.

### `SymbolTable`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Sözcüksel kapsamları (scope), yerel değişken indislerini, tipleri, sabitlik durumunu ve lambda değişken yakalamalarını yönetir.
* **Fields:**
  * `scopes` — Değişken bilgilerini tutan kapsam yığını.
  * `indexStack` — Kapsamlar arası JVM yerel değişken indislerini takip eden yığın.
  * `nextIndex` — Sıradaki boş JVM yerel değişken yuva indisi.
  * `isStaticContext` — Kapsamın static bir bağlamda olup olmadığını belirtir.
  * `imports` — Tip çözümü için kullanılan içe aktarım haritası.
  * `typeParams` — Aktif generic tip parametreleri kümesi.
  * `session` — Aktif derleme oturumu.
* **Methods:**
  * `enterScope()` — Yeni bir yerel blok kapsamı açar.
  * `exitScope()` — Mevcut yerel blok kapsamını kapatır.
  * `declareVariable(String name, String type, boolean isFinal, boolean isInitialized)` — Yerel değişken tanımlar ve JVM yuvası tahsis eder.
  * `getIndex(String name)` — Değişkenin JVM yerel değişken indisini döndürür.
  * `getType(String name)` — Değişkenin JVM tip tanımlayıcısını döndürür.
  * `getRawType(String name)` — Değişkenin ham generic tip metnini döndürür.
  * `isFinal(String name)` — Değişkenin `value` ile sabit tanımlanıp tanımlanmadığını bildirir.
  * `isInitialized(String name)` — Değişkenin kesin atanıp atanmadığını bildirir.
  * `markInitialized(String name)` — Değişkeni kesin atanmış olarak işaretler.
  * `markMutated(String name, ParserRuleContext ctx)` — Değişkenin yeniden atandığını kaydeder ve lambda kapanış kurallarını doğrular.
  * `markCaptured(String name, ParserRuleContext ctx)` — Değişkenin bir lambda tarafından yakalandığını işaretler.
  * `reserveLocalSlots(String descriptor)` — 64-bit (`double`/`long`) 2 yuva kuralına göre yerel değişken yuvası ayırır.
  * `getDescriptor(String type, Map<String, String> imports, Set<String> typeParams)` — Tip adını JVM tip tanımlayıcısına dönüştürür.
  * `clearCaches()` — Tanımlayıcı ve negatif sınıf önbelleklerini temizler.
* **Important Relationships:** `IRGenerator`, `IRSemanticAnalyzer` ve `PreScanner` tarafından kullanılır.

### `AnalysisContext`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Semantik analiz sırasında kapsam yığınını, değişken tiplerini, sınıf üyelerini ve döngü/switch derinliklerini takip eder.
* **Fields:**
  * `scopes` — Değişken `TypeInfo` nesnelerini tutan kapsam yığını.
  * `currentClass` — Kapsayan sınıfın adı.
  * `currentMethod` — Kapsayan metodun adı.
  * `inStaticContext` — Static bağlam bayrağı.
  * `inLoop` — Döngü içi analiz bayrağı.
  * `loopDepth` — Aktif döngü derinlik sayacı.
  * `inSwitch` — Switch içi analiz bayrağı.
  * `switchDepth` — Aktif switch derinlik sayacı.
  * `currentMethodReturnType` — Kapsayan metodun beklenen dönüş tipi.
  * `currentFile` — İncelenen dosya adı.
  * `symbolTable` — Bağlı sembol tablosu.
* **Methods:**
  * `enterScope()` / `exitScope()` — Kapsam derinliğini yönetir.
  * `declareVariable(String name, String descriptor, String rawType, boolean isFinal, boolean isInitialized)` — Tip meta verisini kaydeder.
  * `lookupVariable(String name)` — Değişkenin `TypeInfo` kaydını arar.
  * `enterClass(...)` / `exitClass()` — Sınıf bağlamı geçişlerini yönetir.
  * `enterMethod(...)` / `exitMethod()` — Metot bağlamı geçişlerini yönetir.
  * `enterLoop()` / `exitLoop()` — Döngü sınırlarını takip eder.
  * `enterSwitch()` / `exitSwitch()` — Switch sınırlarını takip eder.
  * `isBreakAllowed()` — Mevcut konumda `stop` (break) ifadesinin geçerli olup olmadığını bildirir.
* **Important Relationships:** `SymbolTable` ve `TypeInfo` ile birlikte çalışır.

### `TypeChecker`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Tipler arası atanabilirlik, ilkel tür genişletme (widening), kutulama/kutudan çıkarma (boxing/unboxing) ve null güvenliği kontrollerini sağlar.
* **Fields:**
  * `WIDENING_TABLE` — İlkel tür genişletme dönüşüm tablosu matrisi.
  * `cleanDescriptorCache` — Temizlenmiş tanımlayıcı metin önbelleği.
* **Methods:**
  * `isAssignable(String targetDesc, String sourceDesc, CompilationSession session)` — Kaynak tipin hedef tipe atanabilirliğini denetler.
  * `isNullable(String desc)` — Tipin nullable (`?` veya `null`) olup olmadığını denetler.
  * `cleanDescriptor(String desc)` — Tanımlayıcıdan generic ve nullability eklerini temizler.
  * `getInternalName(String desc)` — Nesne tanımlayıcısından JVM iç paket yolunu ayıklar.
  * `isPrimitive(String desc)` — Tanımlayıcının ilkel JVM tipi olup olmadığını bildirir.
  * `box(String desc)` — İlkel tip için sarmalayıcı sınıf tanımlayıcısını döndürür.
  * `unbox(String desc)` — Sarmalayıcı sınıf için ilkel tip tanımlayıcısını döndürür.
  * `isWideningConvertible(String from, String to)` — İlkel tür genişletmesinin geçerli olup olmadığını kontrol eder.
  * `clearCaches()` — Tanımlayıcı temizleme önbelleğini sıfırlar.
* **Important Relationships:** `CompilationSession.isSubType` ve `OceanTypeSystem` ile birlikte çalışır.

### `OceanTypeSystem`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** Yerleşik tipler, ilkel türler, tip takma adları ve reflection sınıf yüklemelerini yöneten merkezi tip sistemi.
* **Fields:**
  * `OBJECT_DESC` — `java.lang.Object` tanımlayıcısı (`"Ljava/lang/Object;"`).
  * `STRING_DESC` — `java.lang.String` tanımlayıcısı (`"Ljava/lang/String;"`).
  * `NUMBER_DESC` — `java.lang.Number` tanımlayıcısı (`"Ljava/lang/Number;"`).
  * `BIGDECIMAL_DESC` — `java.math.BigDecimal` tanımlayıcısı (`"Ljava/math/BigDecimal;"`).
  * `BUILTIN_DESCRIPTORS` — Anahtar kelime - JVM tanımlayıcı eşleme haritası.
  * `BUILTIN_INTERNAL_NAMES` — Anahtar kelime - JVM iç yolu eşleme haritası.
* **Methods:**
  * `getBuiltinDescriptor(String typeName)` — Yerleşik tip adının JVM tanımlayıcısını döndürür.
  * `getBuiltinInternalName(String typeName)` — Yerleşik tip adının JVM iç paket yolunu döndürür.
  * `wrapObjectType(String internalName)` — İç sınıf yolunu `L...;` formatına dönüştürür.
  * `forName(String className)` — Context classloader'a duyarlı güvenli reflection sınıf yükleyicisi.
* **Important Relationships:** `TypeChecker`, `IRGenerator` ve `SymbolTable` tarafından temel tip referansı olarak kullanılır.

### `TypeInferenceEngine`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** AST parse tree düğümlerinden ve sembol tablosu bağlamından ifade tiplerini çıkarır.
* **Fields:**
  * `FALLBACK` — Varsayılan dönüş tanımlayıcısı (`Ljava/lang/Object;`).
* **Methods:**
  * `inferType(ParserRuleContext ctx, InferenceContext context)` — İfade düğümünün çıkarılan JVM tip tanımlayıcısını hesaplar.
  * `inferNumberType(String text)` — Sayısal soneklere göre (`I`, `L`, `F`, `D`) sayı tipini belirler.
* **Important Relationships:** `OceanParser` ve `OceanTypeSystem` ile etkileşimdedir.

### `OverloadResolver`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Metot aşırı yüklemelerini (overload), yapıcı metot çağrılarını, dinamik alıcı çözümlemesini ve extension metotlarını çözümler.
* **Fields:**
  * `hierarchyCache` — Sınıf başına kalıtım hiyerarşisi önbelleği.
  * `reflectedMethodsCache` — Yansıtılan metot tanımlayıcıları önbelleği.
  * `reflectedInterfacesCache` — Yansıtılan arayüz hiyerarşisi önbelleği.
  * `paramDescriptorsCache` — Parametre tanımlayıcı listeleri önbelleği.
* **Methods:**
  * `getClassHierarchy(String owner, String methodName)` — Metot arama için sınıf kalıtım zincirini döndürür.
  * `resolveBestMethod(String owner, String methodName, List<String> argTypes, CompilationSession session)` — En uygun metot tanımlayıcısını çözümler.
  * `resolveBestConstructor(String owner, List<String> argTypes, CompilationSession session)` — En uygun yapıcı metot tanımlayıcısını çözümler.
  * `resolveExtensionMethod(String receiverType, String methodName, List<String> argTypes, CompilationSession session)` — Alıcı tipe uygun extension metodunu çözümler.
  * `clearCaches()` — Reflection ve hiyerarşi önbelleklerini temizler.
* **Important Relationships:** `IRGenerator`, `IRSemanticAnalyzer` ve `TypeChecker` tarafından kullanılır.

### `ModifierHelper`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** AST belirteç listelerini inceleyerek ASM erişim bayraklarını hesaplar.
* **Fields:** Yok.
* **Methods:**
  * `hasModifier(List<OceanParser.ModifierContext> modifiers, String keyword)` — Belirli bir anahtar kelime belirtecinin varlığını denetler.
  * `isStatic(...)` / `isAbstract(...)` / `isFinal(...)` / `isPublic(...)` / `isPrivate(...)` / `isProtected(...)` — Belirteç sorguları.
  * `isSynchronized(...)` — `sync` veya `lock` belirteçlerini sorgular.
  * `isOverride(...)` / `isData(...)` / `isAsync(...)` / `isNative(...)` — Dil özel belirteçlerini sorgular.
  * `toAsmAccess(List<OceanParser.ModifierContext> modifiers)` — Belirteçleri birleştirip ASM tam sayı bitmask bayrağı üretir (`ACC_NATIVE` dahil).
* **Important Relationships:** `PreScanner`, `IRGenerator` ve `IRToBytecodeEmitter` tarafından ortak kullanılır.

### `OceanTokenStreamFactory`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Sağ kaydırma operatörlerini (`>>`, `>>>`) generic kapatma parantezlerine (`>`) ayrıştıran özel token akış fabrikası.
* **Fields:** Yok.
* **Methods:**
  * `createTokenStream(OceanLexer lexer)` — Token akışını filtreleyerek birleşik shift token'larını tekil `>` token'larına böler.
* **Important Relationships:** `OceanLexer` çıktısını `OceanParser`'a bağlar.

### `OceanErrorListener`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** ANTLR sözdizim hatalarını yakalayıp `CompilerReporter`'a aktaran hata dinleyicisi.
* **Fields:**
  * `fileName` — Dinleyiciye bağlı kaynak dosya adı.
* **Methods:**
  * `syntaxError(...)` — ANTLR sözdizim hatalarını yakalar ve `CompilerReporter.error` çağrısına dönüştürür.
* **Important Relationships:** `BaseErrorListener` sınıfını genişletir.

### `StackTrackingMethodVisitor`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Bayt kodu üretimi sırasında JVM operand yığını yüksekliğini simüle ederek yığın taşması/altına düşmesi hatalarını denetleyen ASM ziyaretçi sarmalayıcısı.
* **Fields:**
  * `stackHeight` — Mevcut simüle edilen yığın yüksekliği.
  * `methodName` — Ziyaret edilen metot adı.
* **Methods:**
  * `visitInsn(int opcode)` — Tek baytlık komutların yığın değişimini takip eder.
  * `visitVarInsn(int opcode, int var)` — Yerel değişken yükleme/saklama komutlarının yığın değişimini takip eder.
  * `visitFieldInsn(int opcode, String owner, String name, String desc)` — Alan erişim komutlarının yığın değişimini takip eder.
  * `visitMethodInsn(...)` — Metot çağrılarının yığın değişimini takip eder.
  * `visitJumpInsn(int opcode, Label label)` — Dallanma komutlarının yığın değişimini takip eder.
  * `visitLdcInsn(Object value)` — Sabit yükleme komutlarının yığın değişimini takip eder.
* **Important Relationships:** `org.objectweb.asm.MethodVisitor` sınıfını genişletir.

### `ThreadLocalDelegatingMap<K, V>`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Harita işlemlerini çağrıyı yapan iş parçacığının aktif `CompilationSession` nesnesine yönlendiren ThreadLocal harita uygulaması.
* **Fields:**
  * `mapSelector` — Oturumdan hedef haritayı seçen fonksiyonel arayüz.
* **Methods:**
  * Standart `java.util.Map` metotlarını delege ederek uygular.
* **Important Relationships:** `Map<K, V>` arayüzünü uygular.

### `ThreadLocalDelegatingSet<E>`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Küme işlemlerini çağrıyı yapan iş parçacığının aktif `CompilationSession` nesnesine yönlendiren ThreadLocal küme uygulaması.
* **Fields:**
  * `setSelector` — Oturumdan hedef kümeyi seçen fonksiyonel arayüz.
* **Methods:**
  * Standart `java.util.Set` metotlarını delege ederek uygular.
* **Important Relationships:** `Set<E>` arayüzünü uygular.

### `TypeInfo`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Değişkenlerin tipini, kesin atanmışlık durumunu, sabitliğini ve kullanım istatistiğini tutan meta veri sınıfı.
* **Fields:**
  * `descriptor` — JVM tip tanımlayıcısı.
  * `rawType` — Generic parametreleri içeren ham tip metni.
  * `originalDescriptor` — İnce ayar öncesi orijinal tanımlayıcı.
  * `isFinal` — `value` ile tanımlanmış sabitlik bayrağı.
  * `isInitialized` — Kesin atanmışlık bayrağı.
  * `usageCount` — Ölü kod tespiti için okuma sayısı sayacı.
  * `isCaptured` — Lambda tarafından yakalanma bayrağı.
  * `isMutated` — Değiştirilme bayrağı.
* **Methods:**
  * `getDescriptor()` / `setDescriptor(String descriptor)` — Tanımlayıcı erişimcileri.
  * `getRawType()` — Ham tip metni erişimcisi.
  * `isFinal()` — Sabitlik kontrolü.
  * `isInitialized()` / `markInitialized()` — Kesin atama sorgulama ve işaretleme.
  * `incrementUsage()` / `isUnused()` — Değişken kullanım takibi.
  * `humanReadable()` — İnsan tarafından okunabilir tip adı üretir.
* **Important Relationships:** `AnalysisContext` içinde kullanılır.

### `StringHelper`
* **Package:** `ocean.compiler`
* **Type:** class (final)
* **Responsibility:** Metin sabitlerindeki kaçış dizilerini (`\n`, `\t`, `\"` vb.) unescape eden yardımcı sınıf.
* **Fields:** Yok.
* **Methods:**
  * `unescapeString(String s)` — Kaçış karakterlerini gerçek karakter karşılıklarına dönüştürür.
* **Important Relationships:** `IRGenerator` ve `IRConstantFolder` tarafından kullanılır.

### `CompilationException`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Derleme sürecinin derhal durdurulmasını gerektiren kritik yapısal hatalarda fırlatılan özel istisna sınıfı.
* **Fields:** Yok.
* **Methods:**
  * `CompilationException(String message)` — Hata mesajı alan yapıcı metot.
* **Important Relationships:** `RuntimeException` sınıfını genişletir.

---

## Ara Temsil ve Optimizasyon Sınıfları (`ocean.compiler.ir`)

### `IRNode`
* **Package:** `ocean.compiler.ir`
* **Type:** abstract class
* **Responsibility:** Tüm Ocean Ara Temsil (IR) ağacı düğümlerinin temel soyut sınıfı.
* **Fields:**
  * `lineNumber` — Kaynak kod satır numarası.
  * `columnNumber` — Kaynak kod sütun numarası.
* **Methods:**
  * `setLocation(int line, int col)` — Konum koordinatlarını belirler.
  * `getLineNumber()` / `getColumnNumber()` — Konum koordinatlarını döndürür.
  * `accept(IRVisitor visitor)` — Visitor desenini işleten soyut metot.
* **Important Relationships:** Tüm IR düğüm hiyerarşisinin köküdür.

### `IRVisitor`
* **Package:** `ocean.compiler.ir`
* **Type:** interface
* **Responsibility:** Tüm IR düğümleri için ziyaretçi (Visitor) arayüzü sözleşmesi.
* **Methods:**
  * `visitCompilationUnit(IRCompilationUnit node)` — Derleme birimi düğümünü ziyaret eder.
  * `visitClass(IRClass node)` — Sınıf düğümünü ziyaret eder.
  * `visitInterface(IRInterface node)` — Arayüz düğümünü ziyaret eder.
  * `visitEnum(IREnum node)` — Enum düğümünü ziyaret eder.
  * `visitMethod(IRMethod node)` — Metot düğümünü ziyaret eder.
  * `visitField(IRField node)` — Alan düğümünü ziyaret eder.
  * `visitBlock(IRBlock node)` — Blok düğümünü ziyaret eder.
  * `visitAssignment(IRAssignment node)` — Atama düğümünü ziyaret eder.
  * `visitIf(IRIfStatement node)` — If ifadesi düğümünü ziyaret eder.
  * `visitWhile(IRWhileStatement node)` — While döngüsü düğümünü ziyaret eder.
  * `visitDoWhile(IRDoWhileStatement node)` — Do-while döngüsü düğümünü ziyaret eder.
  * `visitForStatement(IRForStatement node)` — For döngüsü düğümünü ziyaret eder.
  * `visitReturn(IRReturnStatement node)` — Return ifadesi düğümünü ziyaret eder.
  * `visitResultStatement(IRResultStatement node)` — Result ifadesi düğümünü ziyaret eder.
  * `visitExprStatement(IRExprStatement node)` — İfade deyimi düğümünü ziyaret eder.
  * `visitVariableDecl(IRVariableDecl node)` — Değişken bildirim düğümünü ziyaret eder.
  * `visitThrow(IRThrowStatement node)` — Throw ifadesi düğümünü ziyaret eder.
  * `visitTryCatch(IRTryCatchStatement node)` — Try-catch-finally düğümünü ziyaret eder.
  * `visitSwitch(IRSwitchStatement node)` — Switch ifadesi düğümünü ziyaret eder.
  * `visitSwitchExpression(IRSwitchExpression node)` — Switch ifade değeri düğümünü ziyaret eder.
  * `visitStop(IRStopStatement node)` — Stop (break) düğümünü ziyaret eder.
  * `visitSkip(IRSkipStatement node)` — Skip (continue) düğümünü ziyaret eder.
  * `visitLockStatement(IRLockStatement node)` — Lock/sync düğümünü ziyaret eder.
  * `visitLabeled(IRLabeledStatement node)` — Etiketli ifade düğümünü ziyaret eder.
  * `visitLiteral(IRLiteral node)` — Sabit değer düğümünü ziyaret eder.
  * `visitVariableAccess(IRVariableAccess node)` — Değişken/alan erişim düğümünü ziyaret eder.
  * `visitBinaryOp(IRBinaryOp node)` — İkili işlem düğümünü ziyaret eder.
  * `visitUnaryOp(IRUnaryOp node)` — Tekli işlem düğümünü ziyaret eder.
  * `visitTernary(IRTernaryExpression node)` — Üçlü koşul düğümünü ziyaret eder.
  * `visitCast(IRCastExpression node)` — Tip dönüştürme düğümünü ziyaret eder.
  * `visitInstanceof(IRInstanceof node)` — Instanceof düğümünü ziyaret eder.
  * `visitMethodCall(IRMethodCall node)` — Metot çağrısı düğümünü ziyaret eder.
  * `visitNewObject(IRNewObject node)` — Nesne örnekleme düğümünü ziyaret eder.
  * `visitArrayCreation(IRArrayCreation node)` — Dizi oluşturma düğümünü ziyaret eder.
  * `visitArrayLiteral(IRArrayLiteral node)` — Dizi sabiti düğümünü ziyaret eder.
  * `visitArrayAccess(IRArrayAccess node)` — Dizi eleman erişim düğümünü ziyaret eder.
  * `visitLambda(IRLambdaExpression node)` — Lambda ifadesi düğümünü ziyaret eder.
  * `visitAwaitExpression(IRAwaitExpression node)` — Await ifadesi düğümünü ziyaret eder.
  * `visitInterpolatedString(IRInterpolatedString node)` — Enterpole metin düğümünü ziyaret eder.
  * `visitOceanOutput(IROceanOutput node)` — OceanOutput düğümünü ziyaret eder.
* **Important Relationships:** `BaseIRVisitor`, `IRSemanticAnalyzer`, `IRToBytecodeEmitter`, `IRDumper`, `IRConstantFolder`, `IRDeadCodeEliminator` ve `IRMethodInliner` tarafından uygulanır.

### `BaseIRVisitor`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Çocuk düğümleri özyinelemeli olarak gezen varsayılan boş `IRVisitor` uygulaması.
* **Methods:**
  * `IRVisitor` arayüzündeki tüm ziyaret metotlarını varsayılan özyinelemeli geçiş davranışıyla uygular.
* **Important Relationships:** `IRVisitor`'ı uygular; `IRSemanticAnalyzer`, `IRToBytecodeEmitter`, `IRDumper` ve optimizer sınıfları tarafından genişletilir.

### `IRCompilationUnit`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Kaynak dosyayı ve içindeki en üst seviye tip bildirimlerini temsil eden IR kök düğümü.
* **Fields:**
  * `types` — Bildirilen üst düzey `IRNode` tipleri (sınıflar, arayüzler, enumlar) listesi.
* **Methods:**
  * `getTypes()` — Tip listesini döndürür.
  * `addType(IRNode type)` — Üst düzey tip ekler.
  * `accept(IRVisitor visitor)` — `visitCompilationUnit` metoduna yönlendirir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IRClass`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Sınıf yapısını, üyelerini, hiyerarşisini, anotasyonlarını ve sealed/data/inline durumlarını temsil eden IR düğümü.
* **Fields:**
  * `name` — Sınıfın iç paket yolu.
  * `superName` — Üst sınıfın iç yolu.
  * `isAbstract` — Soyut sınıf bayrağı.
  * `interfaces` — Uygulanan arayüz yolları listesi.
  * `fields` — Bildirilen `IRField` listesi.
  * `methods` — Bildirilen `IRMethod` listesi.
  * `staticBlocks` — Static başlangıç `IRBlock` listesi.
  * `annotations` — Sınıfa ekli `IRAnnotation` listesi.
  * `permittedSubclasses` — Sealed sınıf için izin verilen alt sınıf yolları.
  * `isSealed` — Sealed sınıf bayrağı.
  * `isDataClass` — Data sınıfı bayrağı.
  * `isNonSealed` — Non-sealed sınıf bayrağı.
  * `accessFlags` — ASM erişim bayrakları.
* **Methods:**
  * `getName()` / `getSuperName()` / `isAbstract()` — Temel meta veri erişimcileri.
  * `isInlineValueClass()` — Tek alanlı `@Inline` sınıfı olup olmadığını kontrol eder.
  * `getInlineField()` — Inline value sınıfının altındaki tek alanı döndürür.
  * `addField(IRField field)` / `addMethod(IRMethod method)` / `addStaticBlock(IRBlock block)` — Üye ekleme metotları.
  * `accept(IRVisitor visitor)` — `visitClass` metoduna yönlendirir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IRMethod`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Metot veya yapıcı metot bildirimini, parametrelerini, gövdesini, istisnalarını ve anotasyonlarını temsil eden IR düğümü.
* **Fields:**
  * `name` — Metot adı.
  * `descriptor` — JVM metot tanımlayıcısı.
  * `parameters` — Parametre listesi (`IRParameter`).
  * `body` — Metot gövdesi (`IRStatement`).
  * `isStatic` — Static metot bayrağı.
  * `isAbstract` — Soyut metot bayrağı.
  * `isAsync` — Asenkron metot bayrağı.
  * `accessFlags` — ASM erişim bayrakları.
  * `exceptions` — Bildirilen checked exception sınıf adları listesi.
  * `annotations` — Metot anotasyonları listesi.
  * `defaultValue` — Varsa varsayılan dönüş ifadesi.
* **Methods:**
  * `getName()` / `getDescriptor()` / `getBody()` / `getParameters()` — Meta veri erişimcileri.
  * `isStatic()` / `isAbstract()` / `isAsync()` — Belirteç sorgulama metotları.
  * `addParameter(IRParameter param)` / `addAnnotation(IRAnnotation annotation)` — Parametre ve anotasyon ekleme metotları.
  * `accept(IRVisitor visitor)` — `visitMethod` metoduna yönlendirir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IRField`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Sınıf alan bildirimini temsil eden IR düğümü.
* **Fields:**
  * `name` — Alan adı.
  * `typeDescriptor` — JVM tip tanımlayıcısı.
  * `isStatic` — Static alan bayrağı.
  * `initialValue` — İlk değer atama ifadesi (`IRExpression`).
  * `accessFlags` — ASM erişim bayrakları.
  * `annotations` — Alan anotasyonları listesi.
* **Methods:**
  * `getName()` / `getTypeDescriptor()` / `getInitialValue()` / `isStatic()` — Erişimciler.
  * `accept(IRVisitor visitor)` — `visitField` metoduna yönlendirir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IRInterface`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Arayüz bildirimini temsil eden IR düğümü.
* **Fields:**
  * `name` — Arayüz iç yolu.
  * `superInterfaces` — Genişletilen arayüzler listesi.
  * `methods` — Arayüz metotları listesi.
  * `permittedSubclasses` — Sealed arayüzler için izin verilen uygulayıcılar.
  * `isSealed` / `isNonSealed` — Sealed arayüz bayrakları.
* **Methods:**
  * `getName()` / `getSuperInterfaces()` / `getMethods()` — Erişimciler.
  * `accept(IRVisitor visitor)` — `visitInterface` metoduna yönlendirir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IREnum`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Enum bildirimini, sabitlerini ve üyelerini temsil eden IR düğümü.
* **Fields:**
  * `name` — Enum iç yolu.
  * `constants` — Enum sabit isimleri listesi.
  * `constantArguments` — Sabitlerin yapıcı metot argüman listeleri.
  * `fields` — Enum alanları listesi.
  * `methods` — Enum metotları listesi.
* **Methods:**
  * `getName()` / `getConstants()` / `getConstantArguments()` / `getFields()` / `getMethods()` — Erişimciler.
  * `accept(IRVisitor visitor)` — `visitEnum` metoduna yönlendirir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IRBlock`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Kapsamlı bir deyim bloğunu temsil eden IR düğümü.
* **Fields:**
  * `statements` — Blok içindeki sıralı `IRStatement` listesi.
* **Methods:**
  * `getStatements()` — Deyim listesini döndürür.
  * `addStatement(IRStatement stmt)` — Bloğa deyim ekler.
  * `accept(IRVisitor visitor)` — `visitBlock` metoduna yönlendirir.
* **Important Relationships:** `IRStatement` sınıfını genişletir.

### `IRStatement`
* **Package:** `ocean.compiler.ir`
* **Type:** abstract class
* **Responsibility:** Tüm IR deyimlerinin (statement) temel soyut sınıfı.
* **Methods:**
  * `accept(IRVisitor visitor)` — Visitor desenini işletir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IRExpression`
* **Package:** `ocean.compiler.ir`
* **Type:** abstract class
* **Responsibility:** Değer üreten tüm IR ifadelerinin (expression) temel soyut sınıfı.
* **Fields:**
  * `inferredType` — İfade için çıkarılmış olan JVM tip tanımlayıcısı.
* **Methods:**
  * `getInferredType()` / `setInferredType(String inferredType)` — Çıkarılan tip erişimcileri.
  * `accept(IRVisitor visitor)` — Visitor desenini işletir.
* **Important Relationships:** `IRNode` sınıfını genişletir.

### `IRGenerator`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** ANTLR sözdizim ağacını yüksek seviyeli tipli IR düğüm ağacına dönüştürür.
* **Fields:**
  * `currentFile` — Mevcut dosya adı.
  * `importedClasses` — İçe aktarılan sınıf eşlemeleri.
  * `importedWildcards` — Joker karakterli içe aktarımlar.
  * `symbolTable` — Aktif sembol tablosu.
  * `currentClassName` — Kapsayan sınıfın adı.
  * `currentSuperName` — Üst sınıfın adı.
  * `currentMethodReturnType` — Mevcut metodun beklenen dönüş tipi.
  * `packageName` — Derleme biriminin paket yolu.
  * `syntheticLambdaMethods` — Çevrim sırasında üretilen sentetik lambda metotları listesi.
  * `anonymousClasses` — Çevrim sırasında üretilen anonim sınıflar listesi.
* **Methods:**
  * `visitCompilationUnit(...)` — CST'yi `IRCompilationUnit` düğümüne dönüştürür.
  * `visitClassDeclaration(...)` — Sınıf CST'sini `IRClass` düğümüne dönüştürür.
  * `visitFunctionDeclaration(...)` — Metot CST'sini `IRMethod` düğümüne dönüştürür.
  * `visitBlock(...)` — Blok CST'sini `IRBlock` düğümüne dönüştürür.
  * `visitAssignment(...)` — Atama CST'sini `IRAssignment` düğümüne dönüştürür.
  * `visitIfStatement(...)` — If CST'sini `IRIfStatement` düğümüne dönüştürür.
  * `visitWhileStatement(...)` — While CST'sini `IRWhileStatement` düğümüne dönüştürür.
  * `visitForStatement(...)` — For CST'sini `IRForStatement` düğümüne dönüştürür.
  * `visitSwitchStatement(...)` — Switch CST'sini `IRSwitchStatement` düğümüne dönüştürür.
  * `visitPrimaryExpr(...)` — Birincil ifadeleri ilgili IR ifade düğümlerine dönüştürür.
  * `clearCaches()` — Reflection ve metot çözümleme önbelleklerini temizler.
* **Important Relationships:** `OceanBaseVisitor<IRNode>` sınıfını genişletir.

### `IRSemanticAnalyzer`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Semantik doğrulama kurallarını doğrudan tipli IR ağacı üzerinde denetler.
* **Fields:**
  * `currentFile` — Analiz edilen dosya adı.
  * `session` — Aktif derleme oturumu.
  * `symbolTable` — Sembol tablosu.
  * `importedClasses` — İçe aktarılan sınıflar.
  * `importedWildcards` — Joker karakterli içe aktarımlar.
  * `currentClassFqcn` — Tam sınıf yolu.
  * `isStaticContext` — Static bağlam bayrağı.
  * `loopDepth` — Aktif döngü derinliği.
  * `switchDepth` — Aktif switch derinliği.
  * `nullabilityScopes` — Akışa duyarlı null güvenliği yığını.
  * `caughtExceptionsStack` — Try-catch bloklarında yakalanan checked exception yığını.
  * `currentMethodDeclaredThrows` — Metotta bildirilen checked exception listesi.
* **Methods:**
  * `visitClass(IRClass node)` — Kalıtımı, soyut metot gövdelerini ve sealed kısıtlarını doğrular.
  * `visitMethod(IRMethod node)` — Metot dönüş yollarını ve istisna sözleşmelerini doğrular.
  * `visitAssignment(IRAssignment node)` — Atama uyumluluğunu, `value` sabitliğini ve null atanabilirliğini doğrular.
  * `visitVariableDecl(IRVariableDecl node)` — Değişken bildirimlerini ve ilk atamaları denetler.
  * `visitMethodCall(IRMethodCall node)` — Metot varlığını, erişilebilirliğini, argüman tiplerini ve fırlatılan checked exception'ları denetler.
  * `visitBinaryOp(IRBinaryOp node)` — İşlenen tiplerinin ikili işlem uyumluluğunu doğrular.
  * `visitReturn(IRReturnStatement node)` — Dönüş ifadesinin metodun dönüş tipiyle uyumunu denetler.
  * `hasErrors()` — Semantik analiz sırasında hata oluşup oluşmadığını bildirir.
  * `clearCaches()` — Reflection ve alan önbelleklerini temizler.
* **Important Relationships:** `BaseIRVisitor` sınıfını genişletir.

### `IROptimizerPipeline`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Çok geçişli IR optimizasyonlarını birleştiren ve koordine eden sınıf.
* **Fields:**
  * `inliner` — Metot inlining optimizasyonunu yürüten `IRMethodInliner` örneği.
  * `constantFolder` — Sabit katlama optimizasyonunu yürüten `IRConstantFolder` örneği.
  * `deadCodeEliminator` — Ölü kod temizliğini yürüten `IRDeadCodeEliminator` örneği.
* **Methods:**
  * `optimize(IRNode node)` — IR ağacı üzerinde sırasıyla Inlining (1. Geçiş), Constant Folding (2. Geçiş) ve Dead Code Elimination (3. Geçiş) işlemlerini uygular.
* **Important Relationships:** `IRMethodInliner`, `IRConstantFolder` ve `IRDeadCodeEliminator` sınıflarını koordine eder.

### `IRConstantFolder`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Derleme anında bilinen aritmetik, mantıksal, bitwise ve metin işlemlerini hesaplayarak sabitlere indirger (AOT Constant Folding).
* **Fields:**
  * Sabit değerlendirme yönlendirme tabloları.
* **Methods:**
  * `optimize(IRNode node)` — IR ağacındaki sabit alt ifadeleri hesaplayıp basitleştirir.
  * `foldBinaryOp(IRBinaryOp node)` — İkili işlemleri (`+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `>`, `&&`, `||`, `&`, `|`, `^`, `<<`, `>>`) derleme anında hesaplar.
  * `foldUnaryOp(IRUnaryOp node)` — Tekli işlemleri (`-`, `!`, `~`) derleme anında hesaplar.
* **Important Relationships:** `BaseIRVisitor` sınıfını genişletir.

### `IRDeadCodeEliminator`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Koşulu her zaman yanlış olan dalları, `return`/`throw` sonrası ulaşılamayan deyimleri ve ölü blokları IR ağacından temizler.
* **Fields:** Yok.
* **Methods:**
  * `optimize(IRNode node)` — IR ağacını gezerek ölü deyimleri ve ulaşılamaz dalları budar.
* **Important Relationships:** `BaseIRVisitor` sınıfını genişletir.

### `IRMethodInliner`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Küçük, saf veya `@Inline` ile işaretlenmiş metotları çağrı noktalarında doğrudan gövdeleriyle değiştirir.
* **Fields:** Yok.
* **Methods:**
  * `optimize(IRNode node)` — Inline adaylarını tespit eder ve `IRMethodCall` düğümlerini inline edilmiş gövdeyle değiştirir.
* **Important Relationships:** `BaseIRVisitor` sınıfını genişletir.

### `IRToBytecodeEmitter`
* **Package:** `ocean.compiler`
* **Type:** class
* **Responsibility:** Optimize edilmiş IR ağacını gezerek OW2 ASM ile JVM bayt kodu üreten sınıf.
* **Fields:**
  * `classWriter` — Mevcut sınıf için ASM `ClassWriter` nesnesi.
  * `methodVisitor` — Mevcut metot için ASM `MethodVisitor` nesnesi.
  * `generatedClasses` — Üretilen sınıf adı - bayt dizisi eşleme haritası.
  * `sourceFileName` — Hata ayıklama çıktısı için kaynak dosya adı.
  * `localVariables` — Değişken adı - JVM yerel değişken indisi haritası.
  * `scopes` — Değişken indis yönetimi için kapsam yığınları.
  * `stopLabels` / `skipLabels` — Break ve continue zıplama hedefleri yığınları.
* **Methods:**
  * `visitClass(IRClass node)` — Sınıf başlığını, alanlarını, metotlarını ve static bloklarını üretir.
  * `visitMethod(IRMethod node)` — Metot gövdesine ait bayt kodu talimatlarını üretir.
  * `visitAssignment(IRAssignment node)` — Yerel değişken veya alan saklama (`ISTORE`, `ASTORE`, `PUTFIELD`, `PUTSTATIC`) talimatlarını yayar.
  * `visitBinaryOp(IRBinaryOp node)` — Aritmetik ve karşılaştırma opkodlarını (`IADD`, `ISUB`, `IMUL`, `IDIV`, `IF_ICMPEQ` vb.) yayar.
  * `visitMethodCall(IRMethodCall node)` — Metot çağırma opkodlarını (`INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`, `INVOKESPECIAL`) yayar.
  * `visitNewObject(IRNewObject node)` — `NEW`, `DUP` ve yapıcı `INVOKESPECIAL` komutlarını üretir.
  * `visitTryCatch(IRTryCatchStatement node)` — İstisna işleme tablolarını ve finally sarmalayıcılarını üretir.
  * `getGeneratedClasses()` — Üretilen tüm sınıflara ait bayt kodlarını döndürür.
* **Important Relationships:** `BaseIRVisitor` sınıfını genişletir; OW2 ASM kütüphanesini kullanır.

### `IRDumper`
* **Package:** `ocean.compiler.ir`
* **Type:** class
* **Responsibility:** Hata ayıklama ve inceleme için IR ağacını hiyerarşik ve okunabilir metin formatında dökümleyen yardımcı sınıf.
* **Fields:**
  * `sb` — Metin biriktirici `StringBuilder`.
  * `indent` — Mevcut girinti seviyesi.
* **Methods:**
  * `dump(IRNode root)` — Verilen IR kök düğümünün biçimlendirilmiş metin dökümünü döndürür.
* **Important Relationships:** `BaseIRVisitor` sınıfını genişletir.

---

## Ocean Standart Kütüphane Sınıfları (`ocean.stdlib`)

### `RuntimeUtils`
* **Package:** `ocean.stdlib`
* **Type:** class
* **Responsibility:** Çalışma zamanında genel giriş/çıkış tarayıcısı, dinamik aritmetik işlemler, karşılaştırma ve dilimleme yardımcılarını sağlar.
* **Fields:**
  * `scanner` — Tembel başlatılan paylaşımlı `Scanner` nesnesi.
* **Methods:**
  * `getScanner()` — Paylaşımlı konsol giriş tarayıcısını döndürür.
  * `add(Number left, Number right)` — Dinamik çalışma zamanı sayısal toplama.
  * `subtract(Number left, Number right)` — Dinamik çalışma zamanı sayısal çıkarma.
  * `multiply(Number left, Number right)` — Dinamik çalışma zamanı sayısal çarpma.
  * `divide(Number left, Number right)` — Dinamik çalışma zamanı sayısal bölme.
  * `mod(Number left, Number right)` — Dinamik çalışma zamanı sayısal modül alma.
  * `compare(Object left, Object right)` — Sayısal genişletme ve `Comparable` destekli çok biçimli karşılaştırma.

### İlkel Tür Listeleri (Primitive Collections)
* **Package:** `ocean.stdlib`
* **Types:** Sınıflar (`OceanIntList`, `OceanDoubleList`, `OceanLongList`, `OceanFloatList`, `OceanShortList`, `OceanByteList`, `OceanCharList`, `OceanBooleanList`)
* **Responsibility:** JVM nesne kutulama (boxing) yükünü ortadan kaldıran, doğrudan ilkel dizi üzerinde çalışan yüksek performanslı liste uygulamaları.
* **Önemli Alanlar:**
  * `data` — İlkel türde veri dizisi (`int[]`, `double[]`, `long[]` vb.).
  * `size` — Saklanan eleman sayısı.
* **Önemli Metotlar:**
  * `add(...)` / `get(int index)` / `set(int index, ...)` — Doğrudan ilkel eleman erişim metotları.
  * `size()` / `isEmpty()` / `clear()` — Koleksiyon yönetim metotları.
  * `sort()` / `reverse()` / `contains(...)` — Yerinde sıralama ve arama metotları.

### Standart Koleksiyonlar ve Fonksiyonel Veri Yapıları
* **Package:** `ocean.stdlib`
* **Types:** Sınıflar
  * `OceanList<T>` — Fonksiyonel genişletmeler (`map`, `filter`, `fold`, `slice`) içeren genel dinamik liste.
  * `OceanMap<K, V>` — `java.util.HashMap` tabanlı genel anahtar-değer haritası.
  * `OceanSet<T>` — `java.util.HashSet` tabanlı genel küme.
  * `OceanStack<T>` — LIFO yığın veri yapısı.
  * `OceanQueue<T>` — FIFO kuyruk veri yapısı.
  * `OceanBitSet` — Yoğun bit dizisi uygulaması.
  * `OceanPair<A, B>` — İkili veri demeti (`first`, `second`).
  * `OceanTriple<A, B, C>` — Üçlü veri demeti (`first`, `second`, `third`).
  * `OceanResult<T, E>` — Fonksiyonel hata yönetimi için Sonuç monadı (`Ok(T)` / `Err(E)`).
  * `OceanOptional<T>` — Null güvenliği için Opsiyonel monadı (`Some(T)` / `None`).
  * `OceanCounter` — Değiştirilebilir tam sayı sayacı sarmalayıcısı.

### Yardımcı Araçlar, Dosya ve Ağ İşlemleri
* **Package:** `ocean.stdlib`
* **Types:** Sınıflar
  * `OceanFile` — Dosya okuma, yazma, ekleme ve varlık kontrolü aracı.
  * `OceanHttp` / `OceanHttpResponse` — OkHttp tabanlı senkron/asenkron HTTP istemcisi.
  * `OceanJson` — Gson / Jackson tabanlı JSON ayrıştırıcı ve serileştirici.
  * `OceanMath` — Matematiksel sabitler ve yardımcı hesaplama fonksiyonları.
  * `OceanRegex` — Düzenli ifade eşleştirme ve arama aracı.
  * `OceanString` / `OceanStringBuilder` / `StrBuilder` — Yüksek verimli metin işleme ve tamponlama araçları.
  * `OceanDate` / `OceanTime` — Tarih ve saat biçimlendirme/ayrıştırma araçları.

---

# Arayüzler ve Kalıtım İlişkileri (Interfaces / Inheritance)

### Derleyici ve IR Ziyaretçi Hiyerarşisi
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

### Delegasyon ve ANTLR Ziyaretçileri
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

# Veri Akışı (Data Flow)

```
+--------------------------+
|   Kaynak Dosya (.ocean)  |
+--------------------------+
             |
             v [ANTLR Lexer / Token Stream Factory]
+--------------------------+
|   Filtrelenmiş Token'lar |
+--------------------------+
             |
             v [ANTLR Parser]
+--------------------------+
|  Ayrıştırma Ağacı (CST)  |
+--------------------------+
             |
             +----------------------------+
             |                            |
             v [PreScanner 1. Geçiş]      v [IRGenerator 2. Geçiş]
+--------------------------+    +--------------------------+
| CompilerRegistry/Session |    |    İlk IRNode Ağacı      |
+--------------------------+    +--------------------------+
             |                               |
             +--------------+----------------+
                            |
                            v [IRSemanticAnalyzer]
             +------------------------------+
             | Doğrulanmış Tipli IR Ağacı   |
             +------------------------------+
                            |
                            v [IROptimizerPipeline (Inlining, Folding, DCE)]
             +------------------------------+
             |   Optimize Edilmiş IR Ağacı  |
             +------------------------------+
                            |
                            v [IRToBytecodeEmitter (OW2 ASM)]
             +------------------------------+
             | Map<String, byte[]> Bayt Kodu|
             +------------------------------+
                            |
             +--------------+---------------+
             |                              |
             v [Disk / Önbellek Çıktısı]    v [URLClassLoader / Java Reflection]
+--------------------------+    +--------------------------+
|   .class / Önbellek      |    |    Program Çalıştırma    |
+--------------------------+    +--------------------------+
```

---

# Giriş Noktaları (Entry Points)

1. **CLI / Derleyici Ana Giriş Noktası:**
   * Sınıf: `ocean.compiler.OceanRunnerV3`
   * Metot: `public static void main(String[] args)`
   * Yönlendirme köprüsü: `ocean.compiler.OceanRunner.main(String[] args)`
2. **Programatik Derleme Giriş Noktası:**
   * Sınıf: `ocean.compiler.OceanRunnerV3`
   * Metot: `public boolean compile(List<Path> sources)` / `public boolean compile(String sourcePath)`
3. **Komut Satırı Başlatıcı Script'leri:**
   * Windows Batch Wrapper: `ocean.bat <KaynakDosya.ocean>`
   * Gradle Görevleri: `./gradlew run -PoceanArgs="..."` veya `./gradlew runV3 -PoceanArgs="..."`

---

# Derleme ve Yapılandırma (Build & Configuration)

* **Hedef JDK Sürümü:** Java 17+ (Üretilen bayt kodu hedefi `Opcodes.V17`).
* **Derleme Aracı:** Gradle (`gradlew` / `gradlew.bat`).
* **Temel Bağımlılıklar:**
  * ANTLR 4.13.1 (`org.antlr:antlr4:4.13.1`, `org.antlr:antlr4-runtime:4.13.1`) — Gramer derleme ve ayrıştırma çalışma zamanı.
  * OW2 ASM 9.6 (`org.ow2.asm:asm:9.6`, `org.ow2.asm:asm-util:9.6`) — JVM bayt kodu üretimi ve manipülasyonu.
  * Google Guava 33.0.0-jre — Çekirdek yardımcı koleksiyonlar.
  * Google Gson 2.10.1 & Jackson Databind 2.16.1 — JSON işleme.
  * OkHttp 4.12.0 — Standart kütüphane HTTP istemcisi.
  * JUnit 5.10.1 (`org.junit.jupiter:junit-jupiter-api:5.10.1`) — Test çatısı.
* **Önemli Gradle Görevleri:**
  * `generateGrammarSource`: `src/main/antlr/Ocean.g4` dosyasını `-visitor` parametresiyle `src/generated/java` dizinine derler.
  * `shadowJar`: Bağımsız çalıştırılabilir `build/libs/Ocean-all.jar` fat JAR dosyasını üretir (`Main-Class: ocean.compiler.OceanRunner`).
  * `run` / `runV3`: Derleyiciyi çalışma zamanı classpath'i ve `-PoceanArgs="..."` parametreleriyle çalıştırır.
  * `copyOceanDeps`: Tüm çalışma zamanı bağımlılıklarını `build/ocean_deps` dizinine kopyalar.

---

# Test Mimarisi (Testing)

* **Test Dosyaları Konumu:** `examples/` dizininde yer alan 300'den fazla entegrasyon, stres, dil özelliği ve negatif test senaryosu içeren `.ocean` dosyası.
* **Test Koşucuları:**
  * `test_logical_correctness.ps1`: `ocean.bat` aracılığıyla testleri çok iş parçacıklı (parallel runspace) çalıştıran ve `STATUS: PASSED` etiketini doğrulayan ana test koşucusu.
  * `test_error_correctness.ps1` / `test_negative_correctness.ps1`: Geçersiz sözdizimi, tip uyuşmazlığı ve anlamsal hataların beklendiği gibi derleme hatası ürettiğini doğrulayan negatif test koşucuları.
  * `run_all_tests.ps1`: Tüm testleri sırayla çalıştırıp `test_results.csv` raporu üreten script.
  * `run_fast_tests.ps1` / `run_v3.ps1`: Hızlı duman (smoke) testleri.
* **Doğrulanan Alanlar:**
  * Sözdizimi, ayrıştırma ve operatör öncelikleri.
  * Tip çıkarımı, generic türler, sınırlar (bounds) ve varyans.
  * Sınıf kalıtımı, arayüzler, soyut sınıflar, sealed sınıflar ve elmas (diamond) hiyerarşileri.
  * Null güvenliği, akıllı tür dönüştürme (smart cast), güvenli çağrı (`?.`) ve unboxing.
  * Async/await iş akışları, çok iş parçacıklı yapılar ve kilitler (`lock`/`sync`).
  * Metot inlining, sabit katlama ve ölü kod temizleme doğruluğu.
  * Kutulamasız özel ilkel tür koleksiyonlarının performansı ve doğruluğu.

---

# Önemli Mimari ve Tasarım Kararları (Important Design Decisions)

1. **Doğrudan AST-Bayt Kodu Yerine Açık IR Katmanı:**
   Eski derleyici sürümleri (V1/V2) ANTLR `ParseTree` üzerinden doğrudan bayt kodu üretiyordu. Bu durum semantik analizi kod üretimiyle iç içe geçiriyor ve çok geçişli optimizasyonları engelliyordu. Sürüm 3.0 ile eklenen `ocean.compiler.ir` paketi; metot inlining, derleme anında sabit katlama, ölü kod temizleme ve bağımsız semantik kontrol sağladı.
2. **Paralel Derleme için ThreadLocal Oturum Durumu:**
   `CompilationSession` tüm nesne durumunu yönetirken, `CompilerRegistry` bu oturumu `ThreadLocalDelegatingMap` ve `ThreadLocalDelegatingSet` ile sarmalar. Böylece dosyalar birden çok iş parçacığında birbirinin durumunu bozmadan paralel derlenebilir.
3. **İki Geçişli Ayrık Mimari (`IRGenerator` Öncesinde `PreScanner`):**
   1. geçişte (`PreScanner`) tüm değişen dosyalardaki sınıf, arayüz ve metot imzaları toplanır; 2. geçişte (`IRGenerator`) metot gövdeleri IR'a dönüştürülür. Bu sayede karşılıklı özyinelemeli sınıflar ve ileriye dönük referanslar dosya sırasından bağımsız olarak çözülür.
4. **Akışa Duyarlı Null Güvenliği ve Kesin Atama:**
   `IRSemanticAnalyzer`, koşullu dallarda nullability durumunu takip eder; Kotlin benzeri akıllı tür dönüştürme ile doğrulanmış `if` blokları içinde `T?` tipi otomatik olarak `T` kabul edilir.
5. **Özelleştirilmiş İlkel Koleksiyonlar (`OceanIntList`, `OceanDoubleList` vb.):**
   Yoğun hesaplama döngülerinde JVM otomatik kutulama (auto-boxing) maliyetini önlemek için standart kütüphanede genel tiplerin yanında ilkel tiplere özel dizi listeleri sunulmuştur.

---

# Bakım Notları (Maintenance Notes)

`PROJECT_TURKISH.MD` dosyasını kod tabanı değiştikçe güncel tutmak için:

* **Yeni Sınıf Eklendiğinde:** Yeni bir derleyici sınıfı, IR düğümü veya standart kütüphane bileşeni eklendiğinde `# Sınıflar` bölümüne Paket, Tip, Sorumluluk, Alanlar, Metotlar ve Önemli İlişkiler formatında ekleyin.
* **Sorumluluk veya İmzalar Değiştiğinde:** Sınıf sorumluluğu veya önemli metot imzaları değiştiğinde ilgili sınıf başlığını güncelleyin.
* **Pipeline veya Mimari Değiştiğinde:** Yeni derleme geçişleri, optimizasyon aşamaları veya çalışma zamanı alt sistemleri eklendiğinde `# Mimari`, `# Derleme ve Çalışma Akışı` ve `# Veri Akışı` bölümlerini güncelleyin.
* **Yapılandırma Değişikliklerinde:** Hedef bayt kodu sürümü, Gradle görevleri veya kütüphane sürümleri güncellendiğinde `# Derleme ve Yapılandırma` bölümünü güncelleyin.
* **Tutarlılık Kuralı:** Doküman yalnızca kodda doğrulanabilen gerçek özellikleri içermeli, varsayımsal veya depoda olmayan sınıfları içermemelidir.

---

# 9 Aşamalı Kapsamlı Derleyici İyileştirme ve Bakım Günlüğü

Derleyicinin 9 derleme aşamasının tamamında yapılan derinlemesine hata ayıklama, ölü kod temizliği, ClassLoader standartlaştırması ve performans refactoring çalışmaları tamamlanmış ve 289 testin (244 mantıksal + 5 interaktif + 40 negatif = 289 test) tamamında %100 başarıyla doğrulanmıştır:

1. **Aşama 1 (Oturum ve Artımlı Derleme):** Yol normalizasyon önbellekleri standardize edildi, `CompilationSession` üzerinde thread-safety ve bağımlılık parmak izi kontrolleri optimize edildi.
2. **Aşama 2 (Paralel Sözdizimsel Ayrıştırma):** Generic `>>` ve `>>>` sağa kaydırma ayrıştırması, token stream fabrikası ve `OceanErrorListener` paralel hata yönetimi doğrulandı.
3. **Aşama 3 (Ön Tarama Geçişi 1):** Üst düzey semboller, arayüz kayıtları, generic sınırlar ve döngüsel bağımlılık çözümlemeleri doğrulandı.
4. **Aşama 4 (IR Oluşturma Geçişi 2):** Primitif sayısal sınır taşmaları, tamsayı sınırları ve yerel değişken kapsam indekslemeleri çözüldü.
5. **Aşama 5 (Anlamsal Analiz):** `TypeChecker` tek karakterli descriptor temizliği için fast-path eklendi, `IRSemanticAnalyzer` stdlib erişim kontrolü beyaz listelendi, artık debug printleri kaldırıldı ve parametre ayrıştırmaları önbelleklendi.
6. **Aşama 6 (IR Optimizasyon Hattı):** `IRDeadCodeEliminator` ve `IRConstantFolder` içindeki reflection/ClassLoader çağrıları `OceanTypeSystem.forName` ile standardize edildi, `@Inline` uyarıları `CompilerReporter` sistemine bağlandı.
7. **Aşama 7 (Bayt Kodu Üretimi Geçişi 3):** `IRToBytecodeEmitter.visitForStatement` içinde `long`/`float`/`double` döngülerindeki yinelenen label ve sahte stack yükleme hatası düzeltildi, gereksiz yardımcı metotlar ve ölü alanlar temizlendi, `StackTrackingMethodVisitor` tanılamaları standardize edildi.
8. **Aşama 8 (Standart Kütüphane ve Çalışma Zamanı):** `OceanList.stringCompare` içindeki `ArrayIndexOutOfBoundsException` riski ve gereksiz dizi tahsisleri `compareTo` ile giderildi, `isSorted` mantığı `Number`/`Comparable` ile güçlendirildi, `RuntimeUtils.getScanner` UTF-8 ve `synchronized` ile thread-safe yapıldı.
9. **Aşama 9 (Pipeline Tamamlama ve Bakım):** `OceanRunnerV3` ikinci geçişindeki iç içe try-catch ve hata raporlama akışı sadeleştirildi, dokümantasyonlar güncellendi.

---

# 2. Tur: Ultra-Derin 9 Aşamalı Kritik Hata Avı ve İnceleme Günlüğü

Derleyicinin 9 aşamasının tamamında çok daha kapsamlı ve satır satır kod incelemesi yürütülmüş, her aşama tek tek kullanıcı onayı ile doğrulanmıştır (289/289 test BAŞARILI):
* **Aşama 1 & 4 (Oturum & IR Oluşturma):** `CompilationSession.cachedForName` ve `IRGenerator.forName` metotları `OceanTypeSystem.forName(name)` üzerinden standardize edildi; sentinel önbelleği ve thread-context classloader uyumu korundu.
* **Aşama 2, 3, 5, 6 (Gramer, PreScanner, Semantik & Optimizasyon):** Generic sağa kaydırma ayrıştırması, iki geçişli sembol kayıtları, akışa duyarlı nullability ve smart casting, özyinelemeli metot inlining kısıtlamaları ve ölü kod budama mantığı satır satır doğrulandı.
* **Aşama 7 (Bayt Kodu Üretimi Geçişi 3):** `IRToBytecodeEmitter.emitSingleBridge` içinde `float` (`F`) parametreleri için varsayılan olarak `ILOAD` çalışması hatası tespit edilip `FLOAD` ile düzeltildi; parametre sayısı tespiti `parseParameterTypes(desc).size()` ile tekilleştirildi.
* **Aşama 8 & 9 (Standart Kütüphane, Runtime & CLI):** Sıfır tahsisli koleksiyon fonksiyonları, UTF-8 scanner, ANSI/JSON raporlama ve `OceanRunnerV3` temizleme mekanizması doğrulandı.
* **IRGenerator Özel Derin Denetimi:** `visitClassDeclaration` ve `visitNormalMethod` içindeki generic tür parametrelerinin kapsam (scoping) yönetimi yerel anlık görüntü (snapshot) yedekleme/geri yükleme ile güvenceye alındı, `visitNullCoalescingExpr` (`??`) için kesin non-null tip çıkarımı yapıldı ve `resolveTypeName` reflection aramaları standardize edildi.
* **IRSemanticAnalyzer Özel Derin Denetimi:** `visitNewObject` içine eklenen reflection denetimiyle JDK/harici kütüphane arayüz ve soyut sınıflarının doğrudan örneklenebilmesi engellendi, `visitLambda` içinde `currentMethodIsAsync` izole edilerek senkron lambdalarda geçersiz `await` kullanım riski giderildi, `visitClass` generic kapsam temizliği sağlandı ve hata mesajı noktalama hatası düzeltildi.
* **PreScanner Özel Derin Denetimi:** Anonim sınıfların varsayılan `<init>` yapıcıları `CompilerRegistry.globalOverloadRegistry`'ye eklendi, wildcard içe aktarmalardaki yansıma `OceanTypeSystem.forName` ile tekilleştirildi, `PreScanner.clearCaches()` metodu `CompilerRegistry.clearAll()`'a bağlandı ve anotasyon bildirimlerinde oturum sınıfı (FQCN) güncellemesi eklendi.
* **OverloadResolver Özel Derin Denetimi:** `isParamAssignable` içine unbox sonrası ilkel genişletme (`Integer` -> `long`, `Float` -> `double`) desteği eklendi, `getClassHierarchy` girişinde `cleanOwner` çağrısıyla `hierarchyCache` anahtarları normalize edildi ve `isMoreSpecific` ilkel-referans kıyaslaması güçlendirildi.
* **SymbolTable Özel Derin Denetimi:** Çakışan wildcard import hata raporlamasındaki olası NPE riski `activeSession` null-güvenliği ile giderildi ve `descriptorCache` anahtar üretimine `currentPackage` dahil edilerek paketler arası tip önbellek izolasyonu sağlandı.
* **TypeInferenceEngine Özel Derin Denetimi:** Generic Map indeks erişimlerinde (`ArrayAccessExprContext`) `splitGenericsParts` ile değer tipi (`V`) çıkarımı sağlandı, aşırı yüklenmiş get metotları için indeks tipi dinamik olarak çözümlendi ve `MethodRefExprContext` içinde receiver descriptor temizleme güvenceye alındı.
* **TypeChecker Özel Derin Denetimi:** `getCommonType` içinde boolean ve sayısal tipler ayrıştırılarak tamsayı-boolean karışımlarının yanlışlıkla boolean'a yükseltilmesi önlendi, `isComparable` içinde String ilişkisel kıyaslama yasağı temiz descriptor ile sağlandı ve `isBoxedEquivalent` simetrik hale getirildi.
* **OceanTypeSystem Özel Derin Denetimi:** `isAsyncFutureType` metodundaki hatalı `isStringType` kontrolü `isClassType` ile düzeltildi, `OceanTypeSystem.clearCaches()` metodu `CompilerRegistry.clearAll()`'a bağlandı ve yansıma sınıf aramaları `OceanTypeSystem.forName` ile tekilleştirildi.
* **IROptimizer Pipeline Özel Derin Denetimi:** `IRMethodInliner.substituteParameters` içine `IRMethodCall`, `IRCastExpression`, `IRInstanceof` ve `IRArrayAccess` parametre ikame desteği eklendi, `IRConstantFolder.foldUnaryOp` içine `Float` unar negasyon katlaması dahil edildi, `charAt` sabit katlama sonucu `"C"` descriptor'ına bağlandı ve `IRConstantFolder.clearCaches()` `CompilerRegistry.clearAll()` döngüsüne bağlandı.
