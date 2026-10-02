package ocean.compiler;

import java.util.*;

/**
 * Semantik analiz sırasında kapsam (scope) yığını ve tip bilgisi takibi yapar.
 * Bytecode üretiminden bağımsız, sadece doğrulama amaçlıdır.
 */
public class AnalysisContext {

    private final Deque<Map<String, TypeInfo>> scopes = new ArrayDeque<>();
    private String currentClass;
    private String currentMethod;
    private boolean inStaticContext;
    private boolean inLoop;
    private int loopDepth;
    private boolean inSwitch;
    private int switchDepth;
    private String currentMethodReturnType;
    private final String currentFile;
    private final SymbolTable symbolTable;

    private Map<String, String> classFields = new HashMap<>();
    private Map<String, Boolean> fieldStaticity = new HashMap<>();
    private Map<String, String> classMethods = new HashMap<>();
    private Map<String, Boolean> methodStaticity = new HashMap<>();

    private final Deque<ClassContextState> classStates = new ArrayDeque<>();

    private record ClassContextState(String className, Map<String, String> fields, Map<String, Boolean> fieldStatic, Map<String, String> methods, Map<String, Boolean> methodStatic) {
    }

    public AnalysisContext(String currentFile, SymbolTable symbolTable) {
        this.currentFile = currentFile;
        this.symbolTable = symbolTable;
        enterScope();
    }

    // ========== Scope Yönetimi ==========

    public void enterScope() {
        scopes.push(new HashMap<>());
        if (symbolTable != null) symbolTable.enterScope();
    }

    public void exitScope() {
        if (scopes.size() > 1) {
            scopes.pop();
            if (symbolTable != null) symbolTable.exitScope();
        }
    }

    public TypeInfo declareVariable(String name, String descriptor, boolean isFinal, boolean isInitialized) {
        return declareVariable(name, descriptor, descriptor, isFinal, isInitialized);
    }

    public TypeInfo declareVariable(String name, String descriptor, String rawType, boolean isFinal, boolean isInitialized) {
        Map<String, TypeInfo> current = scopes.peek();
        if (current != null && current.containsKey(name)) {
            return null; // Zaten tanımlı — çağıran hata üretmeli
        }
        TypeInfo info = new TypeInfo(descriptor, rawType, isFinal, isInitialized);
        if (current != null) {
            current.put(name, info);
        }
        if (symbolTable != null) {
            symbolTable.declareVariable(name, rawType != null ? rawType : descriptor, isFinal, isInitialized);
        }
        return info;
    }

    public void markInitialized(String name) {
        TypeInfo info = lookupVariable(name);
        if (info != null) {
            info.markInitialized();
        }
        if (symbolTable != null) {
            symbolTable.markInitialized(name);
        }
    }

    public Map<String, Boolean> getVariableInitializationState() {
        Map<String, Boolean> state = new HashMap<>();
        for (Map<String, TypeInfo> scope : scopes) {
            for (Map.Entry<String, TypeInfo> entry : scope.entrySet()) {
                state.put(entry.getKey(), entry.getValue().isInitialized());
            }
        }
        return state;
    }

    public void setVariableInitializationState(Map<String, Boolean> state) {
        for (Map.Entry<String, Boolean> entry : state.entrySet()) {
            String varName = entry.getKey();
            boolean isInit = entry.getValue();
            TypeInfo info = lookupVariable(varName);
            if (info != null) {
                info.setInitialized(isInit);
            }
            if (symbolTable != null) {
                symbolTable.setInitialized(varName, isInit);
            }
        }
    }

    /**
     * Değişkeni tüm scope'larda arar.
     * @return null ise değişken tanımsız
     */
    public TypeInfo lookupVariable(String name) {
        for (Map<String, TypeInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

    /**
     * Değişkenin mevcut scope'ta (en içteki blokta) tanımlı olup olmadığını kontrol eder.
     */
    public boolean isDefinedInCurrentScope(String name) {
        Map<String, TypeInfo> current = scopes.peek();
        return current != null && current.containsKey(name);
    }

    // ========== Sınıf Bağlamı ==========

    public void enterClass(String className, Map<String, String> fields,
                           Map<String, Boolean> fieldStatic,
                           Map<String, String> methods,
                           Map<String, Boolean> methodStatic) {
        if (this.currentClass != null) {
            classStates.push(new ClassContextState(this.currentClass, this.classFields, this.fieldStaticity, this.classMethods, this.methodStaticity));
        }
        this.currentClass = className;
        this.classFields = fields != null ? new HashMap<>(fields) : new HashMap<>();
        this.fieldStaticity = fieldStatic != null ? new HashMap<>(fieldStatic) : new HashMap<>();
        this.classMethods = methods != null ? new HashMap<>(methods) : new HashMap<>();
        this.methodStaticity = methodStatic != null ? new HashMap<>(methodStatic) : new HashMap<>();
    }

    public void exitClass() {
        if (!classStates.isEmpty()) {
            ClassContextState state = classStates.pop();
            this.currentClass = state.className;
            this.classFields = state.fields;
            this.fieldStaticity = state.fieldStatic;
            this.classMethods = state.methods;
            this.methodStaticity = state.methodStatic;
        } else {
            this.currentClass = null;
            this.classFields.clear();
            this.fieldStaticity.clear();
            this.classMethods.clear();
            this.methodStaticity.clear();
        }
    }

    public String getCurrentClass() {
        return currentClass;
    }

    public boolean hasField(String name) {
        return classFields.containsKey(name);
    }

    public String getFieldType(String name) {
        return classFields.get(name);
    }

    public boolean isFieldStatic(String name) {
        return fieldStaticity.getOrDefault(name, false);
    }

    public boolean hasMethod(String name) {
        return classMethods.containsKey(name);
    }

    public String getMethodDescriptor(String name) {
        return classMethods.get(name);
    }

    public boolean isMethodStatic(String name) {
        return methodStaticity.getOrDefault(name, false);
    }

    // ========== Metot Bağlamı ==========

    private final Deque<MethodContextState> methodStates = new ArrayDeque<>();
    private int currentMethodOuterScopeCount = 0;

    private record MethodContextState(String methodName, boolean isStatic, String returnType, int outerScopeCount, List<String> thrownExceptions) {
    }

    private List<String> currentMethodThrownExceptions = new ArrayList<>();

    public List<String> getCurrentMethodThrownExceptions() {
        return currentMethodThrownExceptions;
    }

    /** Backward-compatible overload (no throws list). */
    public void enterMethod(String methodName, boolean isStatic, String returnType) {
        enterMethod(methodName, isStatic, returnType, null);
    }

    public void enterMethod(String methodName, boolean isStatic, String returnType, List<String> thrownExceptions) {
        if (this.currentMethod != null) {
            methodStates.push(new MethodContextState(this.currentMethod, this.inStaticContext, this.currentMethodReturnType, this.currentMethodOuterScopeCount, this.currentMethodThrownExceptions));
        }
        this.currentMethod = methodName;
        this.inStaticContext = isStatic;
        this.currentMethodReturnType = returnType;
        this.currentMethodOuterScopeCount = scopes.size();
        this.currentMethodThrownExceptions = thrownExceptions != null ? new ArrayList<>(thrownExceptions) : new ArrayList<>();
        enterScope();
    }

    public void exitMethod() {
        exitScope();
        if (!methodStates.isEmpty()) {
            MethodContextState state = methodStates.pop();
            this.currentMethod = state.methodName;
            this.inStaticContext = state.isStatic;
            this.currentMethodReturnType = state.returnType;
            this.currentMethodOuterScopeCount = state.outerScopeCount;
            this.currentMethodThrownExceptions = state.thrownExceptions != null ? state.thrownExceptions : new ArrayList<>();
        } else {
            this.currentMethod = null;
            this.inStaticContext = false;
            this.currentMethodReturnType = "V";
            this.currentMethodOuterScopeCount = 0;
            this.currentMethodThrownExceptions = new ArrayList<>();
        }
    }

    public boolean isCapturedVariable(String name) {
        List<Map<String, TypeInfo>> scopesList = new ArrayList<>(scopes);
        for (int i = 0; i < scopesList.size(); i++) {
            if (scopesList.get(i).containsKey(name)) {
                return i >= (scopesList.size() - currentMethodOuterScopeCount);
            }
        }
        return false;
    }

    public String getCurrentMethod() {
        return currentMethod;
    }

    public boolean isInStaticContext() {
        return inStaticContext;
    }

    public String getCurrentMethodReturnType() {
        return currentMethodReturnType;
    }

    // ========== Döngü ve Switch Bağlamı ==========

    public void enterLoop() {
        loopDepth++;
        inLoop = true;
    }

    public void exitLoop() {
        loopDepth--;
        inLoop = loopDepth > 0;
    }

    public boolean isInLoop() {
        return inLoop;
    }

    public void enterSwitch() {
        switchDepth++;
        inSwitch = true;
    }

    public void exitSwitch() {
        switchDepth--;
        inSwitch = switchDepth > 0;
    }

    /** stop (break) hem döngü hem switch içinde geçerlidir */
    public boolean isBreakAllowed() {
        return inLoop || inSwitch;
    }

    // ========== Dosya ==========

    public String getCurrentFile() {
        return currentFile;
    }

    /**
     * İnsan okunabilir bağlam bilgisi döndürür (hata mesajları için).
     * Örnek: "ErrorTest.main"
     */
    public String getContextString() {
        if (currentClass != null && currentMethod != null) {
            return currentClass + "." + currentMethod;
        }
        if (currentClass != null) {
            return currentClass;
        }
        return "Global";
    }
}
