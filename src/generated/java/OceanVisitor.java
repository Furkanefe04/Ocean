// Generated from Ocean.g4 by ANTLR 4.13.1

    package ocean.compiler;

import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link OceanParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface OceanVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link OceanParser#program}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProgram(OceanParser.ProgramContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#compilationUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCompilationUnit(OceanParser.CompilationUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#packageDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackageDeclaration(OceanParser.PackageDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#importStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImportStatement(OceanParser.ImportStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#classDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassDeclaration(OceanParser.ClassDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#interfaceDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#restrictsClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRestrictsClause(OceanParser.RestrictsClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#annotationDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnnotationDeclaration(OceanParser.AnnotationDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#annotationMemberDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnnotationMemberDeclaration(OceanParser.AnnotationMemberDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#typeParameter}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeParameter(OceanParser.TypeParameterContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#enumDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnumDeclaration(OceanParser.EnumDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#enumConstants}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnumConstants(OceanParser.EnumConstantsContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#enumConstant}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnumConstant(OceanParser.EnumConstantContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#typeList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeList(OceanParser.TypeListContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#memberDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMemberDeclaration(OceanParser.MemberDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#variableDeclarator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableDeclarator(OceanParser.VariableDeclaratorContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#fieldDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFieldDeclaration(OceanParser.FieldDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#modifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModifier(OceanParser.ModifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#constructorDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NormalMethod}
	 * labeled alternative in {@link OceanParser#methodDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNormalMethod(OceanParser.NormalMethodContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MainMethod}
	 * labeled alternative in {@link OceanParser#methodDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMainMethod(OceanParser.MainMethodContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#parameterList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameterList(OceanParser.ParameterListContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#parameter}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameter(OceanParser.ParameterContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBlock(OceanParser.BlockContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LabeledStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLabeledStmt(OceanParser.LabeledStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SuperStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSuperStmt(OceanParser.SuperStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ResultStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitResultStmt(OceanParser.ResultStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code VariableDeclStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableDeclStmt(OceanParser.VariableDeclStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LocalClassDeclStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLocalClassDeclStmt(OceanParser.LocalClassDeclStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code VerifyStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVerifyStmt(OceanParser.VerifyStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ExprStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExprStmt(OceanParser.ExprStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AssignmentStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignmentStmt(OceanParser.AssignmentStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code IfStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIfStmt(OceanParser.IfStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ForStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitForStmt(OceanParser.ForStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code WhileStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhileStmt(OceanParser.WhileStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code DoWhileStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDoWhileStmt(OceanParser.DoWhileStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ReturnStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReturnStmt(OceanParser.ReturnStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TryStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTryStmt(OceanParser.TryStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LockStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLockStmt(OceanParser.LockStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SwitchStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSwitchStmt(OceanParser.SwitchStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ThrowStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitThrowStmt(OceanParser.ThrowStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code StopStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStopStmt(OceanParser.StopStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SkipStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSkipStmt(OceanParser.SkipStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code BlockStmt}
	 * labeled alternative in {@link OceanParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBlockStmt(OceanParser.BlockStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#verifyStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVerifyStatement(OceanParser.VerifyStatementContext ctx);
	/**
	 * Visit a parse tree produced by the {@code FinalVarDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFinalVarDecl(OceanParser.FinalVarDeclContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ValueDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueDecl(OceanParser.ValueDeclContext ctx);
	/**
	 * Visit a parse tree produced by the {@code VariableDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableDecl(OceanParser.VariableDeclContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TypedVarDecl}
	 * labeled alternative in {@link OceanParser#variableDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypedVarDecl(OceanParser.TypedVarDeclContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignment(OceanParser.AssignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#expressionStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpressionStatement(OceanParser.ExpressionStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#ifStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIfStatement(OceanParser.IfStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#forStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitForStatement(OceanParser.ForStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#forControl}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitForControl(OceanParser.ForControlContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#whileStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhileStatement(OceanParser.WhileStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#doWhileStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDoWhileStatement(OceanParser.DoWhileStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#returnStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReturnStatement(OceanParser.ReturnStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#tryStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTryStatement(OceanParser.TryStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#resourceList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitResourceList(OceanParser.ResourceListContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#resource}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitResource(OceanParser.ResourceContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#catchClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCatchClause(OceanParser.CatchClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#lockBlockStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLockBlockStatement(OceanParser.LockBlockStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#switchStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSwitchStatement(OceanParser.SwitchStatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#switchCase}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSwitchCase(OceanParser.SwitchCaseContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#defaultCase}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefaultCase(OceanParser.DefaultCaseContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#switchExpressionCase}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSwitchExpressionCase(OceanParser.SwitchExpressionCaseContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#defaultExpressionCase}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefaultExpressionCase(OceanParser.DefaultExpressionCaseContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#switchLabel}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSwitchLabel(OceanParser.SwitchLabelContext ctx);
	/**
	 * Visit a parse tree produced by the {@code RecordSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRecordSwitchPattern(OceanParser.RecordSwitchPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TypeSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeSwitchPattern(OceanParser.TypeSwitchPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code UnnamedSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnnamedSwitchPattern(OceanParser.UnnamedSwitchPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NullSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNullSwitchPattern(OceanParser.NullSwitchPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ExprSwitchPattern}
	 * labeled alternative in {@link OceanParser#switchPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExprSwitchPattern(OceanParser.ExprSwitchPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code RecordInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRecordInstanceofPattern(OceanParser.RecordInstanceofPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TypeInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeInstanceofPattern(OceanParser.TypeInstanceofPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code UnnamedInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnnamedInstanceofPattern(OceanParser.UnnamedInstanceofPatternContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NullInstanceofPattern}
	 * labeled alternative in {@link OceanParser#instanceofPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNullInstanceofPattern(OceanParser.NullInstanceofPatternContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#patternList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPatternList(OceanParser.PatternListContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ArrayMethodRefExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArrayMethodRefExpr(OceanParser.ArrayMethodRefExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ComparisonExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparisonExpr(OceanParser.ComparisonExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NewObjectExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNewObjectExpr(OceanParser.NewObjectExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MethodRefExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMethodRefExpr(OceanParser.MethodRefExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code RangeSliceExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRangeSliceExpr(OceanParser.RangeSliceExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code BitOrExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBitOrExpr(OceanParser.BitOrExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LogicalAndExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLogicalAndExpr(OceanParser.LogicalAndExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PostfixExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPostfixExpr(OceanParser.PostfixExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NullCoalescingExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNullCoalescingExpr(OceanParser.NullCoalescingExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code EqualityExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEqualityExpr(OceanParser.EqualityExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SafeMemberCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSafeMemberCallExpr(OceanParser.SafeMemberCallExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code CastExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCastExpr(OceanParser.CastExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryExpr(OceanParser.PrimaryExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code QualifiedSuperExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQualifiedSuperExpr(OceanParser.QualifiedSuperExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ShiftExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShiftExpr(OceanParser.ShiftExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TernaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTernaryExpr(OceanParser.TernaryExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MemberCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMemberCallExpr(OceanParser.MemberCallExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ClassLiteralExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassLiteralExpr(OceanParser.ClassLiteralExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ArrayAccessExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArrayAccessExpr(OceanParser.ArrayAccessExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LambdaExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLambdaExpr(OceanParser.LambdaExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code BitAndExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBitAndExpr(OceanParser.BitAndExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AssignmentExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignmentExpr(OceanParser.AssignmentExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code UnaryExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnaryExpr(OceanParser.UnaryExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code InstanceOfExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInstanceOfExpr(OceanParser.InstanceOfExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrefixExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrefixExpr(OceanParser.PrefixExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SwitchExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSwitchExpr(OceanParser.SwitchExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LogicalOrExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLogicalOrExpr(OceanParser.LogicalOrExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AwaitExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAwaitExpr(OceanParser.AwaitExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MulDivModExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMulDivModExpr(OceanParser.MulDivModExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code QualifiedThisExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQualifiedThisExpr(OceanParser.QualifiedThisExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AddSubExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAddSubExpr(OceanParser.AddSubExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code BitXorExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBitXorExpr(OceanParser.BitXorExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MethodCallExpr}
	 * labeled alternative in {@link OceanParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMethodCallExpr(OceanParser.MethodCallExprContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#identifierList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifierList(OceanParser.IdentifierListContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NumberPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNumberPrimary(OceanParser.NumberPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code CharPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCharPrimary(OceanParser.CharPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code StringPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringPrimary(OceanParser.StringPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TruePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTruePrimary(OceanParser.TruePrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code FalsePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFalsePrimary(OceanParser.FalsePrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NullPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNullPrimary(OceanParser.NullPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code InterpolatedStringPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterpolatedStringPrimary(OceanParser.InterpolatedStringPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ParenthesizedPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParenthesizedPrimary(OceanParser.ParenthesizedPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code OceanOutputPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOceanOutputPrimary(OceanParser.OceanOutputPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code OceanInputPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOceanInputPrimary(OceanParser.OceanInputPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ThisRefPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitThisRefPrimary(OceanParser.ThisRefPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SuperRefPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSuperRefPrimary(OceanParser.SuperRefPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ListLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitListLiteralPrimary(OceanParser.ListLiteralPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SetLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSetLiteralPrimary(OceanParser.SetLiteralPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MapLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMapLiteralPrimary(OceanParser.MapLiteralPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ArrayLiteralPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArrayLiteralPrimary(OceanParser.ArrayLiteralPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimitiveTypePrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimitiveTypePrimary(OceanParser.PrimitiveTypePrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code IdPrimary}
	 * labeled alternative in {@link OceanParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdPrimary(OceanParser.IdPrimaryContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#anyId}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnyId(OceanParser.AnyIdContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#argumentList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArgumentList(OceanParser.ArgumentListContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#argument}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArgument(OceanParser.ArgumentContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#mapEntry}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMapEntry(OceanParser.MapEntryContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#shiftOp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShiftOp(OceanParser.ShiftOpContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#primitiveType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimitiveType(OceanParser.PrimitiveTypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitType(OceanParser.TypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#typeName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeName(OceanParser.TypeNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#annotation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnnotation(OceanParser.AnnotationContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#annotationElement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnnotationElement(OceanParser.AnnotationElementContext ctx);
	/**
	 * Visit a parse tree produced by {@link OceanParser#typeArguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeArguments(OceanParser.TypeArgumentsContext ctx);
}