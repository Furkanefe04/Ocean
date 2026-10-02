package ocean.compiler.ir;

/**
 * Ocean derleyicisi için Intermediate Representation (IR) temel düğümü.
 * AST ile Bytecode üretim aşaması arasına eklenen bu katman,
 * kod optimizasyonlarını (Constant Folding, Dead Code Elimination)
 * ve platformdan bağımsız (ör: LLVM, WebAssembly) kod üretimini sağlar.
 */
public abstract class IRNode {
    private int lineNumber;
    private int columnNumber;

    public void setLocation(int line, int col) {
        this.lineNumber = line;
        this.columnNumber = col;
    }

    public int getLineNumber() { return lineNumber; }
    public int getColumnNumber() { return columnNumber; }

    public abstract void accept(IRVisitor visitor);
}