package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MapElementControlTest: Generic tip parametresi bound ihlali ve T <: U bağımlı
 * tip parametresi doğrulaması için derleme hata testleri.
 *
 * Test senaryoları:
 *  1. DenemeMap<Integer, String> → T <: Number, String ≮ Number → HATA
 *  2. DenemeMap<Integer, Integer> → T <: Number, Integer <: Number → BAŞARILI
 *  3. class Foo<U, T <: U> — T <: U bağımlı bound kontrolü
 *  4. class Foo<T <: U, U> — U, T'den sonra tanımlı → HATA
 */
public class MapElementControlTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        ClassMetadataCache.clearCaches();
    }

    private Map<String, byte[]> compile(String code, String className) {
        return CompilerTestHelper.compileToBytecodeMap(code, className);
    }

    // ─── Test 1: T <: Number kısıtlaması ihlali ────────────────────────────────

    @Test
    public void testDenemeMapStringViolatesNumberBound() {
        String code = """
                import java.lang.Number;
                import java.lang.Integer;

                class DenemeMap<U, T <: Number> {
                    U key;
                    T val;
                    void function setKey(U u) { key = u; }
                    void function setVal(T t) { val = t; }
                    public String function get() {
                        return key.toString() + " : " + val.toString();
                    }
                }

                class MapBoundViolationTest {
                    main() {
                        // String <: Number değil → derleme hatası bekleniyor
                        DenemeMap<Integer, String> denemeMap = new DenemeMap<>();
                        denemeMap.setKey(70);
                    }
                }
                """;
        // String, Number'ın alt tipi değil → CompilationException atılmalı
        assertThrows(CompilationException.class, () -> compile(code, "MapBoundViolationTest"),
                "DenemeMap<Integer, String> T <: Number kısıtlamasını ihlal ettiği için derlenmemeli");
    }

    // ─── Test 2: T <: Number — geçerli kullanım ────────────────────────────────

    @Test
    public void testDenemeMapIntegerSatisfiesNumberBound() {
        String code = """
                import java.lang.Number;
                import java.lang.Integer;

                class DenemeMap<U, T <: Number> {
                    U key;
                    T val;
                    void function setKey(U u) { key = u; }
                    void function setVal(T t) { val = t; }
                    public String function get() {
                        return key.toString() + " : " + val.toString();
                    }
                }

                class MapBoundSuccessTest {
                    main() {
                        // Integer <: Number → geçerli
                        DenemeMap<String, Integer> m = new DenemeMap<>();
                        m.setKey("hello");
                        m.setVal(42);
                        OceanOutput(m.get());
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "MapBoundSuccessTest");
        assertNotNull(bytecode);
        assertFalse(bytecode.isEmpty());
    }

    // ─── Test 3: T <: U bağımlı bound — geçerli kullanım ──────────────────────

    @Test
    public void testDependentBoundTExtendsUSuccess() {
        String code = """
                import java.lang.Integer;
                import java.lang.Number;

                // U = Number, T = Integer → Integer <: Number → geçerli
                class DependentBoundBox<U, T <: U> {
                    U upper;
                    T lower;
                }

                class DependentBoundSuccessTest {
                    main() {
                        DependentBoundBox<Number, Integer> box = new DependentBoundBox<>();
                        OceanOutput("dependent bound ok");
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "DependentBoundSuccessTest");
        assertNotNull(bytecode);
        assertFalse(bytecode.isEmpty());
    }

    // ─── Test 4: T <: U bağımlı bound — ihlal ──────────────────────────────────

    @Test
    public void testDependentBoundTExtendsUViolation() {
        String code = """
                import java.lang.Integer;
                import java.lang.String;
                import java.lang.Number;

                class DependentBoundBox<U, T <: U> {
                    U upper;
                    T lower;
                }

                class DependentBoundFailTest {
                    main() {
                        // U = Integer, T = String → String <: Integer değil → HATA
                        DependentBoundBox<Integer, String> box = new DependentBoundBox<>();
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "DependentBoundFailTest"),
                "DependentBoundBox<Integer, String> T <: U ihlali nedeniyle derlenmemeli");
    }

    // ─── Test 5: Parametre sıra hatası — T <: U ama U sonra tanımlı ────────────

    @Test
    public void testDependentBoundOutOfOrderDeclarationError() {
        String code = """
                // T <: U tanımında U, T'den sonra geliyor → sıra hatası
                class OutOfOrderBound<T <: U, U> {
                    T t;
                    U u;
                }

                class OutOfOrderBoundTest {
                    main() {
                        OceanOutput("should not compile");
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "OutOfOrderBoundTest"),
                "class OutOfOrderBound<T <: U, U> tanımında U, T'den sonra geldiği için derlenmemeli");
    }

    // ─── Test 6: T <: U — aynı tip (reflexivity) ───────────────────────────────

    @Test
    public void testDependentBoundSameTypeSatisfied() {
        String code = """
                import java.lang.Integer;

                class ReflexiveBoundBox<U, T <: U> {
                    U upper;
                    T lower;
                }

                class ReflexiveBoundTest {
                    main() {
                        // U = Integer, T = Integer → Integer <: Integer → geçerli
                        ReflexiveBoundBox<Integer, Integer> box = new ReflexiveBoundBox<>();
                        OceanOutput("reflexive bound ok");
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "ReflexiveBoundTest");
        assertNotNull(bytecode);
        assertFalse(bytecode.isEmpty());
    }

    // ─── Test 7: T <: Number & Comparable — birden fazla bound ──────────────────

    @Test
    public void testMultiBoundWithTypedArgSuccess() {
        String code = """
                import java.lang.Number;
                import java.lang.Comparable;
                import java.lang.Integer;

                class MultiBoundMap<K, V <: Number & Comparable<V>> {
                    K key;
                    V val;
                }

                class MultiBoundMapTest {
                    main() {
                        // Integer <: Number && Integer <: Comparable<Integer> → geçerli
                        MultiBoundMap<String, Integer> m = new MultiBoundMap<>();
                        OceanOutput("multi bound map ok");
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "MultiBoundMapTest");
        assertNotNull(bytecode);
        assertFalse(bytecode.isEmpty());
    }
    // ─── Test 8: Kullanıcının tam kodu — Inner class DenemeMap<U, T <: U> ihlali ────

    @Test
    public void testInnerClassDependentBoundViolation() {
        String code = """
                class MapElementControlTest {

                    class DenemeMap<U , T <: U> {
                        U key;
                        T val;
                        void function setKey(U u){
                            key = u;
                        }
                        void function setVal(T t) {
                            val = t;
                        }
                        public String function get() {
                            return key.toString()+" : "+val.toString();
                        }
                    }

                    main(){
                        // U = Integer, T = String → String <: Integer değil → HATA
                        DenemeMap<Integer,String> denemeMap = new DenemeMap<>();
                        denemeMap.setKey(70);
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "MapElementControlTest"),
                "İç içe tanımlanmış DenemeMap<Integer, String> için T <: U ihlali derleme hatası fırlatmalı");
    }
}
