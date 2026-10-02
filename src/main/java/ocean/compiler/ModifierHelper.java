package ocean.compiler;

import java.util.List;
import ocean.compiler.OceanParser;
import org.objectweb.asm.Opcodes;

/**
 * Utility for querying modifier lists on class/method/field declarations.
 * Eliminates the repeated pattern:
 *   for (ModifierContext mod : modifiers) { if ("static".equals(mod.getText())) ... }
 * that appears 20+ times across OceanToBytecodeVisitor and SemanticAnalyzer.
 */
public final class ModifierHelper {

    private ModifierHelper() {}

    /** Returns true if the modifier list contains the given keyword (e.g. "static", "abstract"). */
    public static boolean hasModifier(List<OceanParser.ModifierContext> modifiers, String keyword) {
        if (modifiers == null || modifiers.isEmpty()) return false;
        for (OceanParser.ModifierContext mod : modifiers) {
            if (keyword.equals(mod.getText())) return true;
        }
        return false;
    }

    public static boolean isStatic(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "static");
    }

    public static boolean isAbstract(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "abstract");
    }

    public static boolean isFinal(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "final");
    }

    public static boolean isPublic(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "public");
    }

    public static boolean isPrivate(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "private");
    }

    public static boolean isProtected(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "protected");
    }

    public static boolean isSynchronized(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "sync") || hasModifier(modifiers, "lock");
    }

    public static boolean isOverride(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "override");
    }

    public static boolean isData(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "data");
    }

    public static boolean isAsync(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "async");
    }

    public static boolean isNative(List<OceanParser.ModifierContext> modifiers) {
        return hasModifier(modifiers, "native");
    }

    /**
     * Computes the ASM access flags integer for a modifier list.
     * Combines PUBLIC/PRIVATE/PROTECTED/STATIC/ABSTRACT/FINAL/SYNCHRONIZED/NATIVE.
     */
    public static int toAsmAccess(List<OceanParser.ModifierContext> modifiers) {
        int access = 0;
        if (isPublic(modifiers))       access |= Opcodes.ACC_PUBLIC;
        if (isPrivate(modifiers))      access |= Opcodes.ACC_PRIVATE;
        if (isProtected(modifiers))    access |= Opcodes.ACC_PROTECTED;
        if (isStatic(modifiers))       access |= Opcodes.ACC_STATIC;
        if (isAbstract(modifiers))     access |= Opcodes.ACC_ABSTRACT;
        if (isFinal(modifiers))        access |= Opcodes.ACC_FINAL;
        if (isSynchronized(modifiers)) access |= Opcodes.ACC_SYNCHRONIZED;
        if (isNative(modifiers))       access |= Opcodes.ACC_NATIVE;
        return access;
    }

    /**
     * Computes the ASM access flags for a field.
     * Prevents ACC_SYNCHRONIZED (0x0020) on fields, mapping it to ACC_VOLATILE (0x0040)
     * and masking to only legal field access modifiers.
     */
    public static int toAsmFieldAccess(List<OceanParser.ModifierContext> modifiers) {
        int access = toAsmAccess(modifiers);
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            access &= ~Opcodes.ACC_SYNCHRONIZED;
            access |= Opcodes.ACC_VOLATILE;
        }
        return access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED |
                         Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_VOLATILE |
                         Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC);
    }
}
