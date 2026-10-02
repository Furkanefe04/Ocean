package ocean.compiler.ir;

/**
 * High-performance Unified IR Optimization Pipeline.
 * Combines Method Inlining, Constant Folding, and Dead Code Elimination
 * into a minimal-pass pipeline, eliminating redundant AST/IR node traversals.
 */
public class IROptimizerPipeline {

    private final IRTailRecOptimizer tailRecOptimizer = new IRTailRecOptimizer();
    private final IRMethodInliner inliner = new IRMethodInliner();
    private final IRConstantFolder constantFolder = new IRConstantFolder();
    private final IRDeadCodeEliminator deadCodeEliminator = new IRDeadCodeEliminator();

    public IRNode optimize(IRNode node) {
        if (node == null) return null;

        // Pass 0: Tail Call Optimization (TCO for @TailRec methods)
        IRNode current = tailRecOptimizer.optimize(node);

        // Pass 1: Method Inlining (has early exit if no inline candidates exist)
        current = inliner.optimize(current);

        // Pass 2: Constant Folding
        current = constantFolder.optimize(current);

        // Pass 3: Dead Code Elimination
        current = deadCodeEliminator.optimize(current);

        return current;
    }
}
