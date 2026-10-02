package ocean.compiler.legacy;

import ocean.compiler.*;

import org.antlr.v4.runtime.tree.ParseTree;
import ocean.compiler.OceanParser;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derleme zamannda sabit ifadeleri hesaplar (Constant Folding).
 * Mevcut foldConstant mantn geniletir ve tm visit metodlarnda kullanlabilir.
 * Desteklenen sabit katlama trleri:
 * - Aritmetik: 5 + 10 15, 3 * 4 12
 * - Mantksal: true && false false
 * - Karlatrma: 5 > 3 true
 * - String: "Hello" + " World" "Hello World"
 * - Unary: -5, !true false
 * - Bitwise: ~0 -1, 5 << 2 20
 * - Ternary: true ? 1 : 2 1
 */
public class ConstantFolder {

    /**
     * Verilen AST dmn sabit katlama ile deerlendirir.
     * @return Sabit deer (Integer, Long, Double, Boolean, String) veya null (katlanamaz)
     */
    public static Object fold(ParseTree ctx) {
        switch (ctx) {
            case null -> {
                return null;
            }

            // === Primer deerler ===
            case OceanParser.PrimaryExprContext primaryExprContext -> {
                OceanParser.PrimaryContext p = primaryExprContext.primary();
                return foldPrimary(p);
            }


            // === Aritmetik: + - ===
            case OceanParser.AddSubExprContext addSubExprContext -> {
                return foldAddSub(addSubExprContext);
            }


            // === Aritmetik: * / % ===
            case OceanParser.MulDivModExprContext mulDivModExprContext -> {
                return foldMulDivMod(mulDivModExprContext);
            }


            // === Mantksal: && ===
            case OceanParser.LogicalAndExprContext logicalAndExprContext -> {
                return foldLogicalAnd(logicalAndExprContext);
            }


            // === Mantksal: || ===
            case OceanParser.LogicalOrExprContext logicalOrExprContext -> {
                return foldLogicalOr(logicalOrExprContext);
            }


            // === Karlatrma: < > <= >= ===
            case OceanParser.ComparisonExprContext comparisonExprContext -> {
                return foldComparison(comparisonExprContext);
            }


            // === Eitlik: == != ===
            case OceanParser.EqualityExprContext equalityExprContext -> {
                return foldEquality(equalityExprContext);
            }


            // === Unary: - ! ~ ===
            case OceanParser.UnaryExprContext unaryExprContext -> {
                return foldUnary(unaryExprContext);
            }


            // === Shift: << >> >>> ===
            case OceanParser.ShiftExprContext shiftExprContext -> {
                return foldShift(shiftExprContext);
            }


            // === Bitwise: & ^ | ===
            case OceanParser.BitAndExprContext bitAndExprContext -> {
                return foldBitwise(ctx);
            }
            case OceanParser.BitOrExprContext bitOrExprContext -> {
                return foldBitwise(ctx);
            }
            case OceanParser.BitXorExprContext bitXorExprContext -> {
                return foldBitwise(ctx);
            }


            // === Ternary: cond ? a : b ===
            case OceanParser.TernaryExprContext ternaryExprContext -> {
                return foldTernary(ternaryExprContext);
            }


            // === Cast: (int) 5.5 5 ===
            case OceanParser.CastExprContext castExprContext -> {
                return foldCast(castExprContext);
            }
            default -> {
            }
        }

        // === Member Access - dynamic constant resolution (e.g. Long.MAX_VALUE, Math.PI) ===
        if (ctx instanceof OceanParser.MemberCallExprContext || ctx instanceof OceanParser.PrimaryContext) {
            String text = ctx.getText().replace(" ", "");
            Object resolved = resolveJavaConstant(text);
            if (resolved != UNRESOLVED) return resolved;
        }

        return null;
    }

    // Sentinel: marks a failed resolution so we do not retry via reflection each time
    private static final Object UNRESOLVED = new Object();
    private static final ConcurrentHashMap<String, Object> CONSTANT_CACHE =
            new ConcurrentHashMap<>();

    /**
     * Resolves "ClassName.FIELD_NAME" to its value via reflection.
     * Supports any public static field in java.lang.*, java.math.*, java.util.*.
     * Results are cached so reflection runs at most once per unique expression per compilation.
     */
    private static Object resolveJavaConstant(String expr) {
        Object cached = CONSTANT_CACHE.get(expr);
        if (cached != null) return cached;

        int dot = expr.lastIndexOf('.');
        if (dot <= 0 || dot >= expr.length() - 1) {
            CONSTANT_CACHE.put(expr, UNRESOLVED);
            return UNRESOLVED;
        }
        String simpleName = expr.substring(0, dot);
        String fieldName = expr.substring(dot + 1);

        String[] prefixes = {"java.lang.", "java.math.", "java.util.", ""};
        for (String prefix : prefixes) {
            try {
                Class<?> cls = Class.forName(prefix + simpleName);
                Field f = cls.getField(fieldName);
                if (!Modifier.isStatic(f.getModifiers())) continue;
                Object value = f.get(null);
                if (value instanceof Byte || value instanceof Short)
                    value = ((Number) value).intValue();
                if (value instanceof Integer || value instanceof Long || value instanceof Float ||
                        value instanceof Double || value instanceof Boolean || value instanceof Character ||
                        value instanceof String || value instanceof BigDecimal) {
                    CONSTANT_CACHE.put(expr, value);
                    return value;
                }
            } catch (Exception ignored) {
            }
        }
        CONSTANT_CACHE.put(expr, UNRESOLVED);
        return UNRESOLVED;
    }

    /**
     * fadenin sabit olup olmadn hzlca kontrol eder (fold yapmadan).
     */
    public static boolean isConstant(ParseTree ctx) {
        return fold(ctx) != null;
    }

    /**
     * Boolean sabit ise deerini dndrr.
     * Dead code elimination iin kullanlr.
     */
    public static Boolean foldToBoolean(ParseTree ctx) {
        Object result = fold(ctx);
        if (result instanceof Boolean) return (Boolean) result;
        return null;
    }

    // ========== Metodlar ==========

    private static Object foldPrimary(OceanParser.PrimaryContext p) {
        if (p instanceof OceanParser.NumberPrimaryContext) {
            String val = ((OceanParser.NumberPrimaryContext) p).NUMBER().getText();
            char last = Character.toUpperCase(val.charAt(val.length() - 1));
            if (last == 'F') {
                String numPart = val.substring(0, val.length() - 1);
                return Float.parseFloat(numPart);
            }
            if (last == 'D') {
                String numPart = val.substring(0, val.length() - 1);
                return Double.parseDouble(numPart);
            }
            if (last == 'L') {
                String numPart = val.substring(0, val.length() - 1);
                return Long.parseLong(numPart);
            }
            if (val.contains(".")) {
                return Double.parseDouble(val);
            }

            try {
                long n = Long.parseLong(val);
                if (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE) return n;
                return (int) n;
            } catch (NumberFormatException e) {
                return new BigDecimal(val);
            }
        }
        if (p instanceof OceanParser.StringPrimaryContext sp) {
            if (sp.STRING_LITERAL() != null) {
                String s = sp.STRING_LITERAL().getText();
                return unescapeString(s.substring(1, s.length() - 1));
            } else {
                String s = sp.MULTILINE_STRING().getText();
                return unescapeString(s.substring(3, s.length() - 3));
            }
        }
        if (p instanceof OceanParser.CharPrimaryContext) {
            String s = ((OceanParser.CharPrimaryContext) p).CHAR_LITERAL().getText();
            String inner = s.substring(1, s.length() - 1);
            if (inner.isEmpty()) return '\0';
            if (inner.startsWith("\\")) {
                String unescaped = unescapeString(inner);
                return unescaped.isEmpty() ? '\0' : unescaped.charAt(0);
            }
            return inner.charAt(0);
        }
        if (p instanceof OceanParser.TruePrimaryContext) return true;
        if (p instanceof OceanParser.FalsePrimaryContext) return false;
        if (p instanceof OceanParser.ParenthesizedPrimaryContext)
            return fold(((OceanParser.ParenthesizedPrimaryContext) p).expression());

        return null;
    }

    private static Object foldAddSub(OceanParser.AddSubExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left == null || right == null) return null;
        String op = ctx.op.getText();

        // String konkatenasyon
        if (op.equals("+") && (left instanceof String || right instanceof String)) {
            return left + String.valueOf(right);
        }

        // Safe Integer/Long promotion
        if ((left instanceof Integer || left instanceof Long) && (right instanceof Integer || right instanceof Long)) {
            long l = ((Number) left).longValue();
            long r = ((Number) right).longValue();

            if (op.equals("+")) {
                long res;
                try {
                    res = Math.addExact(l, r);
                } catch (ArithmeticException e) {
                    return new BigDecimal(l).add(new BigDecimal(r));
                }
                // If both were Integer, check if result still fits in Integer
                if (left instanceof Integer && right instanceof Integer) {
                    if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) return res;
                    return (int) res;
                }
                return res;
            } else {
                long res;
                try {
                    res = Math.subtractExact(l, r);
                } catch (ArithmeticException e) {
                    return new BigDecimal(l).subtract(new BigDecimal(r));
                }
                if (left instanceof Integer && right instanceof Integer) {
                    if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) return res;
                    return (int) res;
                }
                return res;
            }
        }
        // BigDecimal aritmetik
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            BigDecimal l = (left instanceof BigDecimal) ? (BigDecimal) left : new BigDecimal(left.toString());
            BigDecimal r = (right instanceof BigDecimal) ? (BigDecimal) right : new BigDecimal(right.toString());
            return op.equals("+") ? l.add(r) : l.subtract(r);
        }
        // Double aritmetik
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue(), r = ((Number) right).doubleValue();
            double res = op.equals("+") ? l + r : l - r;
            if (Double.isInfinite(res) || Double.isNaN(res)) {
                try {
                    BigDecimal bl = new BigDecimal(left.toString());
                    BigDecimal br = new BigDecimal(right.toString());
                    return op.equals("+") ? bl.add(br) : bl.subtract(br);
                } catch (Exception ignored) {
                }
            }
            return res;
        }
        return null;
    }

    private static Object foldMulDivMod(OceanParser.MulDivModExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left == null || right == null) return null;
        String op = ctx.op.getText();

        if ((left instanceof Integer || left instanceof Long) && (right instanceof Integer || right instanceof Long)) {
            long l = ((Number) left).longValue();
            long r = ((Number) right).longValue();
            if (r == 0 && !op.equals("*")) return null;

            if (op.equals("*")) {
                long res;
                try {
                    res = Math.multiplyExact(l, r);
                } catch (ArithmeticException e) {
                    return new BigDecimal(l).multiply(new BigDecimal(r));
                }
                if (left instanceof Integer && right instanceof Integer) {
                    if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) return res;
                    return (int) res;
                }
                return res;
            }

            long res = op.equals("/") ? (l / r) : (l % r);
            if (left instanceof Integer && right instanceof Integer) return (int) res;
            return res;
        }
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            BigDecimal l = (left instanceof BigDecimal) ? (BigDecimal) left : new BigDecimal(left.toString());
            BigDecimal r = (right instanceof BigDecimal) ? (BigDecimal) right : new BigDecimal(right.toString());
            if (r.signum() == 0 && !op.equals("*")) return null;
            switch (op) {
                case "*":
                    return l.multiply(r);
                case "/":
                    try {
                        return l.divide(r, MathContext.DECIMAL128);
                    } catch (Exception e) {
                        return null;
                    }
                case "%":
                    try {
                        return l.remainder(r);
                    } catch (Exception e) {
                        return null;
                    }
            }
        }
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue(), r = ((Number) right).doubleValue();
            if (r == 0 && !op.equals("*")) return null;
            double res = switch (op) {
                case "*" -> l * r;
                case "/" -> l / r;
                case "%" -> l % r;
                default -> 0;
            };
            if (Double.isInfinite(res) || Double.isNaN(res)) {
                try {
                    BigDecimal bl = new BigDecimal(left.toString());
                    BigDecimal br = new BigDecimal(right.toString());
                    switch (op) {
                        case "*":
                            return bl.multiply(br);
                        case "/":
                            return bl.divide(br, MathContext.DECIMAL128);
                        case "%":
                            return bl.remainder(br);
                    }
                } catch (Exception ignored) {
                }
            }
            return res;
        }
        return null;
    }

    private static Object foldLogicalAnd(OceanParser.LogicalAndExprContext ctx) {
        Object left = fold(ctx.expression(0));
        // Ksa devre: false && ? = false
        if (left instanceof Boolean && !(Boolean) left) return false;
        Object right = fold(ctx.expression(1));
        if (left instanceof Boolean && right instanceof Boolean)
            return right;
        return null;
    }

    private static Object foldLogicalOr(OceanParser.LogicalOrExprContext ctx) {
        Object left = fold(ctx.expression(0));
        // Ksa devre: true || ? = true
        if (left instanceof Boolean && (Boolean) left) return true;
        Object right = fold(ctx.expression(1));
        if (left instanceof Boolean && right instanceof Boolean)
            return right;
        return null;
    }

    private static Object foldComparison(OceanParser.ComparisonExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue(), r = ((Number) right).doubleValue();
            switch (ctx.op.getText()) {
                case "<":
                    return l < r;
                case ">":
                    return l > r;
                case "<=":
                    return l <= r;
                case ">=":
                    return l >= r;
            }
        }
        return null;
    }

    private static Object foldEquality(OceanParser.EqualityExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left != null && right != null) {
            boolean eq = left.equals(right);
            return ctx.op.getText().equals("==") == eq;
        }
        return null;
    }

    private static Object foldUnary(OceanParser.UnaryExprContext ctx) {
        Object val = fold(ctx.expression());
        if (val == null) return null;
        switch (ctx.op.getText()) {
            case "!":
                if (val instanceof Boolean) return !(Boolean) val;
                break;
            case "-":
                switch (val) {
                    case Integer i -> {
                        return -i;
                    }
                    case Long l -> {
                        return -l;
                    }
                    case Double v -> {
                        return -v;
                    }
                    case BigDecimal decimal -> {
                        return decimal.negate();
                    }
                    default -> {
                    }
                }
                break;
            case "~":
                if (val instanceof Integer) return ~(Integer) val;
                if (val instanceof Long) return ~(Long) val;
                break;
        }
        return null;
    }

    private static Object foldShift(OceanParser.ShiftExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left instanceof Number && right instanceof Number) {
            var l = (left instanceof Long) ? (Long) left : (Integer) left;
            var r = (right instanceof Long) ? (Long) right : (Integer) right;
            switch (ctx.op.getText()) {
                case "<<":
                    return l << r;
                case ">>":
                    return l >> r;
                case ">>>":
                    return l >>> r;
            }
        }
        return null;
    }

    private static Object foldBitwise(ParseTree ctx) {
        OceanParser.ExpressionContext left, right;
        String op;
        switch (ctx) {
            case OceanParser.BitAndExprContext bitAndExprContext -> {
                left = bitAndExprContext.expression(0);
                right = bitAndExprContext.expression(1);
                op = "&";
            }
            case OceanParser.BitOrExprContext bitOrExprContext -> {
                left = bitOrExprContext.expression(0);
                right = bitOrExprContext.expression(1);
                op = "|";
            }
            case OceanParser.BitXorExprContext bitXorExprContext -> {
                left = bitXorExprContext.expression(0);
                right = bitXorExprContext.expression(1);
                op = "^";
            }
            case null, default -> {
                return null;
            }
        }

        Object lv = fold(left), rv = fold(right);
        if (lv instanceof Integer && rv instanceof Integer) {
            int l = (Integer) lv, r = (Integer) rv;
            switch (op) {
                case "&":
                    return l & r;
                case "|":
                    return l | r;
                case "^":
                    return l ^ r;
            }
        }
        return null;
    }

    private static Object foldTernary(OceanParser.TernaryExprContext ctx) {
        Object cond = fold(ctx.expression(0));
        if (cond instanceof Boolean) {
            return (Boolean) cond ? fold(ctx.expression(1)) : fold(ctx.expression(2));
        }
        return null;
    }

    private static Object foldCast(OceanParser.CastExprContext ctx) {
        Object val = fold(ctx.expression());
        if (val == null) return null;
        String targetType = ctx.type().getText();
        if (val instanceof Number) {
            switch (targetType) {
                case "int":
                    return ((Number) val).intValue();
                case "long":
                    return ((Number) val).longValue();
                case "double":
                    return ((Number) val).doubleValue();
                case "float":
                    return (double) ((Number) val).floatValue();
                case "byte":
                    return ((Number) val).byteValue();
                case "short":
                    return ((Number) val).shortValue();
                case "char":
                    return (char) ((Number) val).intValue();
            }
        } else if (val instanceof Character) {
            char c = (Character) val;
            switch (targetType) {
                case "int":
                    return (int) c;
                case "long":
                    return (long) c;
                case "double":
                    return (double) c;
                case "float":
                    return (float) c;
                case "byte":
                    return (byte) c;
                case "short":
                    return (short) c;
                case "char":
                    return c;
            }
        }
        return null;
    }

    public static String unescapeString(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n':
                        sb.append('\n');
                        i++;
                        break;
                    case 't':
                        sb.append('\t');
                        i++;
                        break;
                    case 'r':
                        sb.append('\r');
                        i++;
                        break;
                    case 'b':
                        sb.append('\b');
                        i++;
                        break;
                    case 'f':
                        sb.append('\f');
                        i++;
                        break;
                    case '"':
                        sb.append('\"');
                        i++;
                        break;
                    case '\'':
                        sb.append('\'');
                        i++;
                        break;
                    case '\\':
                        sb.append('\\');
                        i++;
                        break;
                    case '0':
                        sb.append('\0');
                        i++;
                        break;
                    default:
                        sb.append(c);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
