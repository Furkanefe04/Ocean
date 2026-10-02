package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class SwitchCaseConstantsValidationTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch statement with foreign enum constant fails compilation")
    public void testSwitchEnumForeignConstantFails() {
        String code = """
                public enum Color { RED, GREEN }
                public enum Animal { DOG, CAT }
                public class TestForeignEnum {
                    public static String function test(Color c) {
                        switch (c) {
                            case DOG: return "DOG";
                            case RED: return "RED";
                            default:  return "DEF";
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestForeignEnum"));
        assertTrue(ex.getMessage().contains("not defined") || ex.getMessage().contains("Cannot resolve symbol"),
                "Expected foreign enum error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch statement with qualified foreign enum constant fails compilation")
    public void testSwitchEnumQualifiedForeignConstantFails() {
        String code = """
                public enum Color { RED, GREEN }
                public enum Animal { DOG, CAT }
                public class TestQualifiedForeignEnum {
                    public static String function test(Color c) {
                        switch (c) {
                            case Animal.DOG: return "DOG";
                            case RED: return "RED";
                            default:  return "DEF";
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestQualifiedForeignEnum"));
        assertTrue(ex.getMessage().contains("not defined"),
                "Expected foreign enum error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch expression with foreign enum constant fails compilation")
    public void testSwitchExprEnumForeignConstantFails() {
        String code = """
                public enum Color { RED, GREEN }
                public enum Animal { DOG, CAT }
                public class TestForeignEnumExpr {
                    public static String function test(Color c) {
                        return switch (c) {
                            case DOG -> "DOG";
                            case RED -> "RED";
                            default  -> "DEF";
                        };
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestForeignEnumExpr"));
        assertTrue(ex.getMessage().contains("not defined") || ex.getMessage().contains("Cannot resolve symbol"),
                "Expected foreign enum error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with byte selector and out-of-range case constant fails compilation")
    public void testSwitchByteOutOfRangeFails() {
        String code = """
                public class TestByteOverflow {
                    public static String function test(byte b) {
                        switch (b) {
                            case 1000: return "OVERFLOW";
                            default:   return "OK";
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestByteOverflow"));
        assertTrue(ex.getMessage().contains("exceeds bounds"),
                "Expected byte range error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with short selector and out-of-range case constant fails compilation")
    public void testSwitchShortOutOfRangeFails() {
        String code = """
                public class TestShortOverflow {
                    public static String function test(short s) {
                        switch (s) {
                            case 100000: return "OVERFLOW";
                            default:     return "OK";
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestShortOverflow"));
        assertTrue(ex.getMessage().contains("exceeds bounds"),
                "Expected short range error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with char selector and negative constant fails compilation")
    public void testSwitchCharNegativeFails() {
        String code = """
                public class TestCharNegative {
                    public static String function test(char c) {
                        switch (c) {
                            case -1: return "NEGATIVE";
                            default: return "OK";
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestCharNegative"));
        assertTrue(ex.getMessage().contains("exceeds bounds"),
                "Expected char range error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with char selector and overflow constant fails compilation")
    public void testSwitchCharOverflowFails() {
        String code = """
                public class TestCharOverflow {
                    public static String function test(char c) {
                        switch (c) {
                            case 70000: return "OVERFLOW";
                            default:    return "OK";
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestCharOverflow"));
        assertTrue(ex.getMessage().contains("exceeds bounds"),
                "Expected char range error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid enum and primitive switch constants compile and execute correctly")
    public void testValidSwitchEnumAndPrimitivesPass() throws Exception {
        String code = """
                public enum Color { RED, GREEN, BLUE }
                public class TestValidSwitches {
                    public static String function testEnum(Color c) {
                        return switch (c) {
                            case RED   -> "IS_RED";
                            case GREEN -> "IS_GREEN";
                            case BLUE  -> "IS_BLUE";
                        };
                    }
                    public static String function testByte(byte b) {
                        switch (b) {
                            case -128: return "MIN_BYTE";
                            case 127:  return "MAX_BYTE";
                            case 0:    return "ZERO";
                            default:   return "OTHER";
                        }
                    }
                    public static String function testShort(short s) {
                        switch (s) {
                            case -32768: return "MIN_SHORT";
                            case 32767:  return "MAX_SHORT";
                            default:     return "OTHER";
                        }
                    }
                    public static String function testChar(char c) {
                        switch (c) {
                            case 'A':   return "CHAR_A";
                            case 65535: return "MAX_CHAR";
                            default:    return "OTHER";
                        }
                    }
                }
                """;
        var classes = compileAndLoadAll("TestValidSwitches", code);
        Class<?> clazz = classes.get("TestValidSwitches");
        Class<?> colorClass = classes.get("Color");
        Object red = Enum.valueOf((Class<Enum>) colorClass, "RED");
        Method testEnum = clazz.getMethod("testEnum", colorClass);
        assertEquals("IS_RED", testEnum.invoke(null, red));

        Method testByte = clazz.getMethod("testByte", byte.class);
        assertEquals("MIN_BYTE", testByte.invoke(null, (byte) -128));
        assertEquals("MAX_BYTE", testByte.invoke(null, (byte) 127));
        assertEquals("ZERO", testByte.invoke(null, (byte) 0));
        assertEquals("OTHER", testByte.invoke(null, (byte) 42));

        Method testShort = clazz.getMethod("testShort", short.class);
        assertEquals("MIN_SHORT", testShort.invoke(null, (short) -32768));
        assertEquals("MAX_SHORT", testShort.invoke(null, (short) 32767));
        assertEquals("OTHER", testShort.invoke(null, (short) 100));

        Method testChar = clazz.getMethod("testChar", char.class);
        assertEquals("CHAR_A", testChar.invoke(null, 'A'));
        assertEquals("MAX_CHAR", testChar.invoke(null, (char) 65535));
        assertEquals("OTHER", testChar.invoke(null, 'Z'));
    }
}
