package ocean.compiler.legacy;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;
/**
 * JVM işlem yığını (operand stack) yüksekliğini izleyen ve yığın hatalarını denetleyen MethodVisitor sınıfı.
 */
public class StackTrackingMethodVisitor extends MethodVisitor {
    private int stackHeight = 0;
    private final String methodName;

    public StackTrackingMethodVisitor(MethodVisitor mv, String methodName) {
        super(Opcodes.ASM9, mv);
        this.methodName = methodName;
    }

    private void log(String insn, int delta) {
        int old = stackHeight;
        stackHeight += delta;
        if (stackHeight < 0) {
            System.err.println("[STACK-ERROR] " + methodName + ": " + insn + " caused stack to go negative (" + old + " -> " + stackHeight + ")");
        }
    }

    @Override
    public void visitInsn(int opcode) {
        int delta = 0;
        switch (opcode) {
            case Opcodes.ACONST_NULL:
            case Opcodes.ICONST_M1:
            case Opcodes.ICONST_0:
            case Opcodes.ICONST_1:
            case Opcodes.ICONST_2:
            case Opcodes.ICONST_3:
            case Opcodes.ICONST_4:
            case Opcodes.ICONST_5:
            case Opcodes.FCONST_0:
            case Opcodes.FCONST_1:
            case Opcodes.FCONST_2, Opcodes.DUP_X2, Opcodes.DUP_X1, Opcodes.DUP, Opcodes.I2L, Opcodes.I2D, Opcodes.F2L,
                 Opcodes.F2D:
                delta = 1; break;
            case Opcodes.LCONST_0:
            case Opcodes.LCONST_1:
            case Opcodes.DCONST_0:
            case Opcodes.DCONST_1, Opcodes.DUP2_X2, Opcodes.DUP2_X1, Opcodes.DUP2:
                delta = 2; break;
            case Opcodes.IALOAD:
            case Opcodes.FALOAD:
            case Opcodes.AALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD, Opcodes.L2I, Opcodes.L2F, Opcodes.D2I, Opcodes.D2F, Opcodes.ARETURN, Opcodes.IRETURN,
                 Opcodes.FRETURN, Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.POP, Opcodes.IADD,
                 Opcodes.FADD, Opcodes.ISUB, Opcodes.FSUB, Opcodes.IMUL, Opcodes.FMUL, Opcodes.IDIV, Opcodes.FDIV,
                 Opcodes.IREM, Opcodes.FREM, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.IAND, Opcodes.IOR,
                 Opcodes.IXOR, Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG:
                delta = -1; break;
            case Opcodes.LALOAD:
            case Opcodes.DALOAD:
                break; // -2 + 2
            case Opcodes.IASTORE:
            case Opcodes.FASTORE:
            case Opcodes.AASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
                delta = -3; break;
            case Opcodes.LASTORE:
            case Opcodes.DASTORE:
                delta = -4; break;
            case Opcodes.POP2:
            case Opcodes.LADD:
            case Opcodes.DADD:
            case Opcodes.LSUB:
            case Opcodes.DSUB:
            case Opcodes.LMUL:
            case Opcodes.DMUL:
            case Opcodes.LDIV:
            case Opcodes.DDIV:
            case Opcodes.LREM:
            case Opcodes.DREM:
            case Opcodes.LAND:
            case Opcodes.LOR:
            case Opcodes.LXOR, Opcodes.LRETURN, Opcodes.DRETURN:
                delta = -2; break;
            case Opcodes.LSHL:
            case Opcodes.LSHR:
            case Opcodes.LUSHR:
                delta = -1; break; // -2 - 1 + 2
            case Opcodes.SWAP:
                break;
            case Opcodes.I2F:
            case Opcodes.L2D:
            case Opcodes.F2I:
            case Opcodes.D2L:
                break;
            case Opcodes.RETURN:
                break;
            case Opcodes.ARRAYLENGTH:
                break; // -1 + 1
        }
        log("Insn " + opcode, delta);
        super.visitInsn(opcode);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        int delta = 0;
        switch (opcode) {
            case Opcodes.ILOAD:
            case Opcodes.FLOAD:
            case Opcodes.ALOAD:
                delta = 1; break;
            case Opcodes.LLOAD:
            case Opcodes.DLOAD:
                delta = 2; break;
            case Opcodes.ISTORE:
            case Opcodes.FSTORE:
            case Opcodes.ASTORE:
                delta = -1; break;
            case Opcodes.LSTORE:
            case Opcodes.DSTORE:
                delta = -2; break;
            case Opcodes.RET:
                break;
        }
        log("VarInsn " + opcode, delta);
        super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        int delta = 0;
        switch (opcode) {
            case Opcodes.NEW:
                delta = 1; break;
            case Opcodes.ANEWARRAY, Opcodes.INSTANCEOF:
                break; // -1 + 1
            case Opcodes.CHECKCAST:
                break;
        }
        log("TypeInsn " + opcode, delta);
        super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        int size = (desc.equals("J") || desc.equals("D")) ? 2 : 1;
        int delta = switch (opcode) {
            case Opcodes.GETSTATIC -> size;
            case Opcodes.PUTSTATIC -> -size;
            case Opcodes.GETFIELD -> size - 1;
            case Opcodes.PUTFIELD -> -size - 1;
            default -> 0;
        };
        log("FieldInsn " + opcode, delta);
        super.visitFieldInsn(opcode, owner, name, desc);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        int delta;
        int argSize = getArgsSize(desc);
        int retSize = getReturnSize(desc);
        delta = retSize - argSize;
        if (opcode != Opcodes.INVOKESTATIC) {
            delta -= 1; // receiver
        }
        log("MethodInsn " + name, delta);
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    private int getArgsSize(String desc) {
        int size = 0;
        String params = desc.substring(1, desc.lastIndexOf(')'));
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                i = params.indexOf(';', i) + 1;
                size += 1;
            } else if (c == '[') {
                while (params.charAt(i) == '[') i++;
                if (params.charAt(i) == 'L') i = params.indexOf(';', i) + 1;
                else i++;
                size += 1;
            } else {
                size += (c == 'J' || c == 'D') ? 2 : 1;
                i++;
            }
        }
        return size;
    }

    private int getReturnSize(String desc) {
        String ret = desc.substring(desc.lastIndexOf(')') + 1);
        if (ret.equals("V")) return 0;
        if (ret.equals("J") || ret.equals("D")) return 2;
        return 1;
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        int delta = 0;
        switch (opcode) {
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE:
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
                delta = -1; break;
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE:
                delta = -2; break;
            case Opcodes.GOTO:
                break;
        }
        log("JumpInsn " + opcode, delta);
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLdcInsn(Object value) {
        int delta = (value instanceof Long || value instanceof Double) ? 2 : 1;
        log("LdcInsn", delta);
        super.visitLdcInsn(value);
    }
}
