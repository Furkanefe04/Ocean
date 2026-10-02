package ocean.compiler.ir;

import java.util.List;

/**
 * Represent a Lambda Expression in the Ocean IR tree.
 */
public class IRLambdaExpression extends IRExpression {
    private final String targetInterface;
    private final String samMethodName;
    private final String samMethodDesc;
    private final List<String> parameterNames;
    private final List<String> parameterTypes;
    private final IRNode body;
    private final List<String> capturedNames;
    private final List<String> capturedTypes;
    private final String lambdaMethodName;
    private final boolean isStatic;

    public IRLambdaExpression(String targetInterface, String samMethodName, String samMethodDesc,
                              List<String> parameterNames, List<String> parameterTypes, IRNode body,
                              List<String> capturedNames, List<String> capturedTypes,
                              String lambdaMethodName, boolean isStatic) {
        this.targetInterface = targetInterface;
        this.samMethodName = samMethodName;
        this.samMethodDesc = samMethodDesc;
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
        this.body = body;
        this.capturedNames = capturedNames;
        this.capturedTypes = capturedTypes;
        this.lambdaMethodName = lambdaMethodName;
        this.isStatic = isStatic;
        setTypeDescriptor(targetInterface);
    }

    public String getTargetInterface() { return targetInterface; }
    public String getSamMethodName() { return samMethodName; }
    public String getSamMethodDesc() { return samMethodDesc; }
    public List<String> getParameterNames() { return parameterNames; }
    public List<String> getParameterTypes() { return parameterTypes; }
    public IRNode getBody() { return body; }
    public List<String> getCapturedNames() { return capturedNames; }
    public List<String> getCapturedTypes() { return capturedTypes; }
    public String getLambdaMethodName() { return lambdaMethodName; }
    public boolean isStatic() { return isStatic; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitLambda(this);
    }
}
