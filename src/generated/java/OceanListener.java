// Generated from Ocean.g4 by ANTLR 4.13.1

    package ocean.compiler;

import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link OceanParser}.
 */
public interface OceanListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link OceanParser#program}.
	 * @param ctx the parse tree
	 */
	void enterProgram(OceanParser.ProgramContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#program}.
	 * @param ctx the parse tree
	 */
	void exitProgram(OceanParser.ProgramContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#compilationUnit}.
	 * @param ctx the parse tree
	 */
	void enterCompilationUnit(OceanParser.CompilationUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#compilationUnit}.
	 * @param ctx the parse tree
	 */
	void exitCompilationUnit(OceanParser.CompilationUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#packageDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterPackageDeclaration(OceanParser.PackageDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#packageDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitPackageDeclaration(OceanParser.PackageDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#importStatement}.
	 * @param ctx the parse tree
	 */
	void enterImportStatement(OceanParser.ImportStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#importStatement}.
	 * @param ctx the parse tree
	 */
	void exitImportStatement(OceanParser.ImportStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#classDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterClassDeclaration(OceanParser.ClassDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#classDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitClassDeclaration(OceanParser.ClassDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#interfaceDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#interfaceDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#restrictsClause}.
	 * @param ctx the parse tree
	 */
	void enterRestrictsClause(OceanParser.RestrictsClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#restrictsClause}.
	 * @param ctx the parse tree
	 */
	void exitRestrictsClause(OceanParser.RestrictsClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#annotationDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationDeclaration(OceanParser.AnnotationDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#annotationDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationDeclaration(OceanParser.AnnotationDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#annotationMemberDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationMemberDeclaration(OceanParser.AnnotationMemberDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#annotationMemberDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationMemberDeclaration(OceanParser.AnnotationMemberDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#typeParameter}.
	 * @param ctx the parse tree
	 */
	void enterTypeParameter(OceanParser.TypeParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#typeParameter}.
	 * @param ctx the parse tree
	 */
	void exitTypeParameter(OceanParser.TypeParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#enumDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterEnumDeclaration(OceanParser.EnumDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#enumDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitEnumDeclaration(OceanParser.EnumDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#enumConstants}.
	 * @param ctx the parse tree
	 */
	void enterEnumConstants(OceanParser.EnumConstantsContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#enumConstants}.
	 * @param ctx the parse tree
	 */
	void exitEnumConstants(OceanParser.EnumConstantsContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#enumConstant}.
	 * @param ctx the parse tree
	 */
	void enterEnumConstant(OceanParser.EnumConstantContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#enumConstant}.
	 * @param ctx the parse tree
	 */
	void exitEnumConstant(OceanParser.EnumConstantContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#typeList}.
	 * @param ctx the parse tree
	 */
	void enterTypeList(OceanParser.TypeListContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#typeList}.
	 * @param ctx the parse tree
	 */
	void exitTypeList(OceanParser.TypeListContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#memberDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterMemberDeclaration(OceanParser.MemberDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#memberDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitMemberDeclaration(OceanParser.MemberDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#variableDeclarator}.
	 * @param ctx the parse tree
	 */
	void enterVariableDeclarator(OceanParser.VariableDeclaratorContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#variableDeclarator}.
	 * @param ctx the parse tree
	 */
	void exitVariableDeclarator(OceanParser.VariableDeclaratorContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#fieldDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterFieldDeclaration(OceanParser.FieldDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#fieldDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitFieldDeclaration(OceanParser.FieldDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#modifier}.
	 * @param ctx the parse tree
	 */
	void enterModifier(OceanParser.ModifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#modifier}.
	 * @param ctx the parse tree
	 */
	void exitModifier(OceanParser.ModifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#constructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#constructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NormalMethod}
	 * labeled alternative in {@link OceanParser#methodDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterNormalMethod(OceanParser.NormalMethodContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NormalMethod}
	 * labeled alternative in {@link OceanParser#methodDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitNormalMethod(OceanParser.NormalMethodContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MainMethod}
	 * labeled alternative in {@link OceanParser#methodDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterMainMethod(OceanParser.MainMethodContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MainMethod}
	 * labeled alternative in {@link OceanParser#methodDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitMainMethod(OceanParser.MainMethodContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#parameterList}.
	 * @param ctx the parse tree
	 */
	void enterParameterList(OceanParser.ParameterListContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#parameterList}.
	 * @param ctx the parse tree
	 */
	void exitParameterList(OceanParser.ParameterListContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#parameter}.
	 * @param ctx the parse tree
	 */
	void enterParameter(OceanParser.ParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#parameter}.
	 * @param ctx the parse tree
	 */
	void exitParameter(OceanParser.ParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#block}.
	 * @param ctx the parse tree
	 */
	void enterBlock(OceanParser.BlockContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#block}.
	 * @param ctx the parse tree
	 */
	void exitBlock(OceanParser.BlockContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LabeledStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterLabeledStmt(OceanParser.LabeledStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LabeledStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitLabeledStmt(OceanParser.LabeledStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SuperStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterSuperStmt(OceanParser.SuperStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SuperStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitSuperStmt(OceanParser.SuperStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ResultStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterResultStmt(OceanParser.ResultStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ResultStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitResultStmt(OceanParser.ResultStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code VariableDeclStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterVariableDeclStmt(OceanParser.VariableDeclStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code VariableDeclStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitVariableDeclStmt(OceanParser.VariableDeclStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LocalClassDeclStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterLocalClassDeclStmt(OceanParser.LocalClassDeclStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LocalClassDeclStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitLocalClassDeclStmt(OceanParser.LocalClassDeclStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code VerifyStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterVerifyStmt(OceanParser.VerifyStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code VerifyStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitVerifyStmt(OceanParser.VerifyStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExprStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterExprStmt(OceanParser.ExprStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExprStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitExprStmt(OceanParser.ExprStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AssignmentStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterAssignmentStmt(OceanParser.AssignmentStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AssignmentStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitAssignmentStmt(OceanParser.AssignmentStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code IfStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterIfStmt(OceanParser.IfStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code IfStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitIfStmt(OceanParser.IfStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ForStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterForStmt(OceanParser.ForStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ForStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitForStmt(OceanParser.ForStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code WhileStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterWhileStmt(OceanParser.WhileStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code WhileStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitWhileStmt(OceanParser.WhileStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code DoWhileStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterDoWhileStmt(OceanParser.DoWhileStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code DoWhileStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitDoWhileStmt(OceanParser.DoWhileStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ReturnStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterReturnStmt(OceanParser.ReturnStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ReturnStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitReturnStmt(OceanParser.ReturnStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TryStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterTryStmt(OceanParser.TryStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TryStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitTryStmt(OceanParser.TryStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LockStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterLockStmt(OceanParser.LockStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LockStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitLockStmt(OceanParser.LockStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SwitchStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterSwitchStmt(OceanParser.SwitchStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SwitchStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitSwitchStmt(OceanParser.SwitchStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ThrowStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterThrowStmt(OceanParser.ThrowStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ThrowStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitThrowStmt(OceanParser.ThrowStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code StopStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterStopStmt(OceanParser.StopStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code StopStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitStopStmt(OceanParser.StopStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SkipStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterSkipStmt(OceanParser.SkipStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SkipStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitSkipStmt(OceanParser.SkipStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BlockStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterBlockStmt(OceanParser.BlockStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BlockStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitBlockStmt(OceanParser.BlockStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#verifyStatement}.
	 * @param ctx the parse tree
	 */
	void enterVerifyStatement(OceanParser.VerifyStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#verifyStatement}.
	 * @param ctx the parse tree
	 */
	void exitVerifyStatement(OceanParser.VerifyStatementContext ctx);
	/**
	 * Enter a parse tree produced by the {@code FinalVarDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterFinalVarDecl(OceanParser.FinalVarDeclContext ctx);
	/**
	 * Exit a parse tree produced by the {@code FinalVarDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitFinalVarDecl(OceanParser.FinalVarDeclContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ValueDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterValueDecl(OceanParser.ValueDeclContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ValueDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitValueDecl(OceanParser.ValueDeclContext ctx);
	/**
	 * Enter a parse tree produced by the {@code VariableDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterVariableDecl(OceanParser.VariableDeclContext ctx);
	/**
	 * Exit a parse tree produced by the {@code VariableDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitVariableDecl(OceanParser.VariableDeclContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TypedVarDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterTypedVarDecl(OceanParser.TypedVarDeclContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TypedVarDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitTypedVarDecl(OceanParser.TypedVarDeclContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#assignment}.
	 * @param ctx the parse tree
	 */
	void enterAssignment(OceanParser.AssignmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#assignment}.
	 * @param ctx the parse tree
	 */
	void exitAssignment(OceanParser.AssignmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#expressionStatement}.
	 * @param ctx the parse tree
	 */
	void enterExpressionStatement(OceanParser.ExpressionStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#expressionStatement}.
	 * @param ctx the parse tree
	 */
	void exitExpressionStatement(OceanParser.ExpressionStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#ifStatement}.
	 * @param ctx the parse tree
	 */
	void enterIfStatement(OceanParser.IfStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#ifStatement}.
	 * @param ctx the parse tree
	 */
	void exitIfStatement(OceanParser.IfStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#forStatement}.
	 * @param ctx the parse tree
	 */
	void enterForStatement(OceanParser.ForStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#forStatement}.
	 * @param ctx the parse tree
	 */
	void exitForStatement(OceanParser.ForStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#forControl}.
	 * @param ctx the parse tree
	 */
	void enterForControl(OceanParser.ForControlContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#forControl}.
	 * @param ctx the parse tree
	 */
	void exitForControl(OceanParser.ForControlContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#whileStatement}.
	 * @param ctx the parse tree
	 */
	void enterWhileStatement(OceanParser.WhileStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#whileStatement}.
	 * @param ctx the parse tree
	 */
	void exitWhileStatement(OceanParser.WhileStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#doWhileStatement}.
	 * @param ctx the parse tree
	 */
	void enterDoWhileStatement(OceanParser.DoWhileStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#doWhileStatement}.
	 * @param ctx the parse tree
	 */
	void exitDoWhileStatement(OceanParser.DoWhileStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#returnStatement}.
	 * @param ctx the parse tree
	 */
	void enterReturnStatement(OceanParser.ReturnStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#returnStatement}.
	 * @param ctx the parse tree
	 */
	void exitReturnStatement(OceanParser.ReturnStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#tryStatement}.
	 * @param ctx the parse tree
	 */
	void enterTryStatement(OceanParser.TryStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#tryStatement}.
	 * @param ctx the parse tree
	 */
	void exitTryStatement(OceanParser.TryStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#resourceList}.
	 * @param ctx the parse tree
	 */
	void enterResourceList(OceanParser.ResourceListContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#resourceList}.
	 * @param ctx the parse tree
	 */
	void exitResourceList(OceanParser.ResourceListContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#resource}.
	 * @param ctx the parse tree
	 */
	void enterResource(OceanParser.ResourceContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#resource}.
	 * @param ctx the parse tree
	 */
	void exitResource(OceanParser.ResourceContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#catchClause}.
	 * @param ctx the parse tree
	 */
	void enterCatchClause(OceanParser.CatchClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#catchClause}.
	 * @param ctx the parse tree
	 */
	void exitCatchClause(OceanParser.CatchClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#lockBlockStatement}.
	 * @param ctx the parse tree
	 */
	void enterLockBlockStatement(OceanParser.LockBlockStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#lockBlockStatement}.
	 * @param ctx the parse tree
	 */
	void exitLockBlockStatement(OceanParser.LockBlockStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#switchStatement}.
	 * @param ctx the parse tree
	 */
	void enterSwitchStatement(OceanParser.SwitchStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#switchStatement}.
	 * @param ctx the parse tree
	 */
	void exitSwitchStatement(OceanParser.SwitchStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#switchCase}.
	 * @param ctx the parse tree
	 */
	void enterSwitchCase(OceanParser.SwitchCaseContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#switchCase}.
	 * @param ctx the parse tree
	 */
	void exitSwitchCase(OceanParser.SwitchCaseContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#defaultCase}.
	 * @param ctx the parse tree
	 */
	void enterDefaultCase(OceanParser.DefaultCaseContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#defaultCase}.
	 * @param ctx the parse tree
	 */
	void exitDefaultCase(OceanParser.DefaultCaseContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#switchExpressionCase}.
	 * @param ctx the parse tree
	 */
	void enterSwitchExpressionCase(OceanParser.SwitchExpressionCaseContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#switchExpressionCase}.
	 * @param ctx the parse tree
	 */
	void exitSwitchExpressionCase(OceanParser.SwitchExpressionCaseContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#defaultExpressionCase}.
	 * @param ctx the parse tree
	 */
	void enterDefaultExpressionCase(OceanParser.DefaultExpressionCaseContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#defaultExpressionCase}.
	 * @param ctx the parse tree
	 */
	void exitDefaultExpressionCase(OceanParser.DefaultExpressionCaseContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#switchLabel}.
	 * @param ctx the parse tree
	 */
	void enterSwitchLabel(OceanParser.SwitchLabelContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#switchLabel}.
	 * @param ctx the parse tree
	 */
	void exitSwitchLabel(OceanParser.SwitchLabelContext ctx);
	/**
	 * Enter a parse tree produced by the {@code RecordSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void enterRecordSwitchPattern(OceanParser.RecordSwitchPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code RecordSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void exitRecordSwitchPattern(OceanParser.RecordSwitchPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TypeSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void enterTypeSwitchPattern(OceanParser.TypeSwitchPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TypeSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void exitTypeSwitchPattern(OceanParser.TypeSwitchPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code UnnamedSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void enterUnnamedSwitchPattern(OceanParser.UnnamedSwitchPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code UnnamedSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void exitUnnamedSwitchPattern(OceanParser.UnnamedSwitchPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NullSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void enterNullSwitchPattern(OceanParser.NullSwitchPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NullSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void exitNullSwitchPattern(OceanParser.NullSwitchPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExprSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void enterExprSwitchPattern(OceanParser.ExprSwitchPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExprSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 */
	void exitExprSwitchPattern(OceanParser.ExprSwitchPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code RecordInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void enterRecordInstanceofPattern(OceanParser.RecordInstanceofPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code RecordInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void exitRecordInstanceofPattern(OceanParser.RecordInstanceofPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TypeInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void enterTypeInstanceofPattern(OceanParser.TypeInstanceofPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TypeInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void exitTypeInstanceofPattern(OceanParser.TypeInstanceofPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code UnnamedInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void enterUnnamedInstanceofPattern(OceanParser.UnnamedInstanceofPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code UnnamedInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void exitUnnamedInstanceofPattern(OceanParser.UnnamedInstanceofPatternContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NullInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void enterNullInstanceofPattern(OceanParser.NullInstanceofPatternContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NullInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 */
	void exitNullInstanceofPattern(OceanParser.NullInstanceofPatternContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#patternList}.
	 * @param ctx the parse tree
	 */
	void enterPatternList(OceanParser.PatternListContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#patternList}.
	 * @param ctx the parse tree
	 */
	void exitPatternList(OceanParser.PatternListContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ArrayMethodRefExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterArrayMethodRefExpr(OceanParser.ArrayMethodRefExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ArrayMethodRefExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitArrayMethodRefExpr(OceanParser.ArrayMethodRefExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ComparisonExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterComparisonExpr(OceanParser.ComparisonExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ComparisonExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitComparisonExpr(OceanParser.ComparisonExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NewObjectExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNewObjectExpr(OceanParser.NewObjectExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NewObjectExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNewObjectExpr(OceanParser.NewObjectExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MethodRefExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMethodRefExpr(OceanParser.MethodRefExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MethodRefExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMethodRefExpr(OceanParser.MethodRefExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code RangeSliceExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterRangeSliceExpr(OceanParser.RangeSliceExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code RangeSliceExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitRangeSliceExpr(OceanParser.RangeSliceExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BitOrExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBitOrExpr(OceanParser.BitOrExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BitOrExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBitOrExpr(OceanParser.BitOrExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LogicalAndExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLogicalAndExpr(OceanParser.LogicalAndExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LogicalAndExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLogicalAndExpr(OceanParser.LogicalAndExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PostfixExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPostfixExpr(OceanParser.PostfixExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PostfixExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPostfixExpr(OceanParser.PostfixExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NullCoalescingExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNullCoalescingExpr(OceanParser.NullCoalescingExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NullCoalescingExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNullCoalescingExpr(OceanParser.NullCoalescingExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code EqualityExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterEqualityExpr(OceanParser.EqualityExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code EqualityExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitEqualityExpr(OceanParser.EqualityExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SafeMemberCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterSafeMemberCallExpr(OceanParser.SafeMemberCallExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SafeMemberCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitSafeMemberCallExpr(OceanParser.SafeMemberCallExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code CastExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterCastExpr(OceanParser.CastExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code CastExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitCastExpr(OceanParser.CastExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPrimaryExpr(OceanParser.PrimaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPrimaryExpr(OceanParser.PrimaryExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code QualifiedSuperExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterQualifiedSuperExpr(OceanParser.QualifiedSuperExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code QualifiedSuperExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitQualifiedSuperExpr(OceanParser.QualifiedSuperExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ShiftExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterShiftExpr(OceanParser.ShiftExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ShiftExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitShiftExpr(OceanParser.ShiftExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TernaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterTernaryExpr(OceanParser.TernaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TernaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitTernaryExpr(OceanParser.TernaryExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MemberCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMemberCallExpr(OceanParser.MemberCallExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MemberCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMemberCallExpr(OceanParser.MemberCallExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ClassLiteralExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterClassLiteralExpr(OceanParser.ClassLiteralExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ClassLiteralExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitClassLiteralExpr(OceanParser.ClassLiteralExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ArrayAccessExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterArrayAccessExpr(OceanParser.ArrayAccessExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ArrayAccessExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitArrayAccessExpr(OceanParser.ArrayAccessExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LambdaExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLambdaExpr(OceanParser.LambdaExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LambdaExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLambdaExpr(OceanParser.LambdaExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BitAndExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBitAndExpr(OceanParser.BitAndExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BitAndExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBitAndExpr(OceanParser.BitAndExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AssignmentExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAssignmentExpr(OceanParser.AssignmentExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AssignmentExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAssignmentExpr(OceanParser.AssignmentExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code UnaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterUnaryExpr(OceanParser.UnaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code UnaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitUnaryExpr(OceanParser.UnaryExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code InstanceOfExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterInstanceOfExpr(OceanParser.InstanceOfExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code InstanceOfExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitInstanceOfExpr(OceanParser.InstanceOfExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PrefixExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPrefixExpr(OceanParser.PrefixExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PrefixExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPrefixExpr(OceanParser.PrefixExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SwitchExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterSwitchExpr(OceanParser.SwitchExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SwitchExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitSwitchExpr(OceanParser.SwitchExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LogicalOrExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLogicalOrExpr(OceanParser.LogicalOrExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LogicalOrExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLogicalOrExpr(OceanParser.LogicalOrExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AwaitExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAwaitExpr(OceanParser.AwaitExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AwaitExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAwaitExpr(OceanParser.AwaitExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MulDivModExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMulDivModExpr(OceanParser.MulDivModExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MulDivModExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMulDivModExpr(OceanParser.MulDivModExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code QualifiedThisExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterQualifiedThisExpr(OceanParser.QualifiedThisExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code QualifiedThisExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitQualifiedThisExpr(OceanParser.QualifiedThisExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AddSubExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAddSubExpr(OceanParser.AddSubExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AddSubExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAddSubExpr(OceanParser.AddSubExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BitXorExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBitXorExpr(OceanParser.BitXorExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BitXorExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBitXorExpr(OceanParser.BitXorExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MethodCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMethodCallExpr(OceanParser.MethodCallExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MethodCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMethodCallExpr(OceanParser.MethodCallExprContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#identifierList}.
	 * @param ctx the parse tree
	 */
	void enterIdentifierList(OceanParser.IdentifierListContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#identifierList}.
	 * @param ctx the parse tree
	 */
	void exitIdentifierList(OceanParser.IdentifierListContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NumberPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterNumberPrimary(OceanParser.NumberPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NumberPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitNumberPrimary(OceanParser.NumberPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code CharPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterCharPrimary(OceanParser.CharPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code CharPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitCharPrimary(OceanParser.CharPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code StringPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterStringPrimary(OceanParser.StringPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code StringPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitStringPrimary(OceanParser.StringPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TruePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterTruePrimary(OceanParser.TruePrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TruePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitTruePrimary(OceanParser.TruePrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code FalsePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterFalsePrimary(OceanParser.FalsePrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code FalsePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitFalsePrimary(OceanParser.FalsePrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NullPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterNullPrimary(OceanParser.NullPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NullPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitNullPrimary(OceanParser.NullPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code InterpolatedStringPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterInterpolatedStringPrimary(OceanParser.InterpolatedStringPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code InterpolatedStringPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitInterpolatedStringPrimary(OceanParser.InterpolatedStringPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ParenthesizedPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterParenthesizedPrimary(OceanParser.ParenthesizedPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ParenthesizedPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitParenthesizedPrimary(OceanParser.ParenthesizedPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code OceanOutputPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterOceanOutputPrimary(OceanParser.OceanOutputPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code OceanOutputPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitOceanOutputPrimary(OceanParser.OceanOutputPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code OceanInputPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterOceanInputPrimary(OceanParser.OceanInputPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code OceanInputPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitOceanInputPrimary(OceanParser.OceanInputPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ThisRefPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterThisRefPrimary(OceanParser.ThisRefPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ThisRefPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitThisRefPrimary(OceanParser.ThisRefPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SuperRefPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterSuperRefPrimary(OceanParser.SuperRefPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SuperRefPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitSuperRefPrimary(OceanParser.SuperRefPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ListLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterListLiteralPrimary(OceanParser.ListLiteralPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ListLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitListLiteralPrimary(OceanParser.ListLiteralPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SetLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterSetLiteralPrimary(OceanParser.SetLiteralPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SetLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitSetLiteralPrimary(OceanParser.SetLiteralPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MapLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterMapLiteralPrimary(OceanParser.MapLiteralPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MapLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitMapLiteralPrimary(OceanParser.MapLiteralPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ArrayLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterArrayLiteralPrimary(OceanParser.ArrayLiteralPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ArrayLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitArrayLiteralPrimary(OceanParser.ArrayLiteralPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PrimitiveTypePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterPrimitiveTypePrimary(OceanParser.PrimitiveTypePrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PrimitiveTypePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitPrimitiveTypePrimary(OceanParser.PrimitiveTypePrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code IdPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterIdPrimary(OceanParser.IdPrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code IdPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitIdPrimary(OceanParser.IdPrimaryContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#anyId}.
	 * @param ctx the parse tree
	 */
	void enterAnyId(OceanParser.AnyIdContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#anyId}.
	 * @param ctx the parse tree
	 */
	void exitAnyId(OceanParser.AnyIdContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#argumentList}.
	 * @param ctx the parse tree
	 */
	void enterArgumentList(OceanParser.ArgumentListContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#argumentList}.
	 * @param ctx the parse tree
	 */
	void exitArgumentList(OceanParser.ArgumentListContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#argument}.
	 * @param ctx the parse tree
	 */
	void enterArgument(OceanParser.ArgumentContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#argument}.
	 * @param ctx the parse tree
	 */
	void exitArgument(OceanParser.ArgumentContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#mapEntry}.
	 * @param ctx the parse tree
	 */
	void enterMapEntry(OceanParser.MapEntryContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#mapEntry}.
	 * @param ctx the parse tree
	 */
	void exitMapEntry(OceanParser.MapEntryContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#shiftOp}.
	 * @param ctx the parse tree
	 */
	void enterShiftOp(OceanParser.ShiftOpContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#shiftOp}.
	 * @param ctx the parse tree
	 */
	void exitShiftOp(OceanParser.ShiftOpContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void enterPrimitiveType(OceanParser.PrimitiveTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void exitPrimitiveType(OceanParser.PrimitiveTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#type}.
	 * @param ctx the parse tree
	 */
	void enterType(OceanParser.TypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#type}.
	 * @param ctx the parse tree
	 */
	void exitType(OceanParser.TypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#typeName}.
	 * @param ctx the parse tree
	 */
	void enterTypeName(OceanParser.TypeNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#typeName}.
	 * @param ctx the parse tree
	 */
	void exitTypeName(OceanParser.TypeNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#annotation}.
	 * @param ctx the parse tree
	 */
	void enterAnnotation(OceanParser.AnnotationContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#annotation}.
	 * @param ctx the parse tree
	 */
	void exitAnnotation(OceanParser.AnnotationContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#annotationElement}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationElement(OceanParser.AnnotationElementContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#annotationElement}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationElement(OceanParser.AnnotationElementContext ctx);
	/**
	 * Enter a parse tree produced by {@link OceanParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void enterTypeArguments(OceanParser.TypeArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link OceanParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void exitTypeArguments(OceanParser.TypeArgumentsContext ctx);
}