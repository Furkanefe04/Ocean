grammar Ocean;
 
@header {
    package ocean.compiler;
}

/* Lexer Rules - Keywords */
VARIABLE: 'variable' ;
VALUE: 'value' ;
BOOL_TYPE: 'bool' ;
INT_TYPE: 'int' ;
FLOAT_TYPE: 'float' ;
DOUBLE_TYPE: 'double' ;
CHAR_TYPE: 'char' ;
LONG_TYPE: 'long' ;
SHORT_TYPE: 'short' ;
BYTE_TYPE: 'byte' ;
STRING_TYPE: 'String' ;
CLASS: 'class' ;
MAIN: 'main' ;
FUNCTION: 'function' ;
TRYING: 'trying' ;
FROM: 'from' ;
TO: 'to' ;
WITH: 'with' ;
DECREASING: 'decreasing' ;
INCREASING: 'increasing' ;
OCEAN_INPUT: 'OceanInput' ;
OCEAN_OUTPUT: 'OceanOutput' ;
IN: 'in' ;
SYNC: 'sync' ;
LOCK: 'lock' ;
SKIP_KW: 'skip' ;
STOP_KW: 'stop' ;
ABSTRACT: 'abstract' ;
THROW: 'throw' ;
THROWS: 'throws' ;
INSTANCEOF: 'instanceof' ;
DO: 'do' ;
PUBLIC: 'public' ;
PRIVATE: 'private' ;
PROTECTED: 'protected' ;
AT: '@' ;
RESULT_KW: 'result' ;

IMPORT: 'import' ;
PACKAGE: 'package' ;
EXTENDS: 'extends' ;
IMPLEMENTS: 'implements' ;
INTERFACE: 'interface' ;
ENUM: 'enum' ;
STATIC: 'static' ;
FINAL: 'final' ;
VOID: 'void' ;
SUPER: 'super' ;
IF: 'if' ;
ELSE: 'else' ;
FOR: 'for' ;
WHILE: 'while' ;
RETURN: 'return' ;
CATCH: 'catch' ;
FINALLY: 'finally' ;
SWITCH: 'switch' ;
CASE: 'case' ;
DEFAULT: 'default' ;
NEW: 'new' ;
TRUE: 'true' ;
FALSE: 'false' ;
NULL_KW: 'null' ;
THIS: 'this' ;
DATA: 'data' ;
ANNOTATION_KW: 'annotation' ;
SEALED: 'sealed' ;
NON_SEALED: 'non-sealed' ;
RESTRICTS: 'restricts' ;
ASYNC: 'async' ;
AWAIT: 'await' ;
NATIVE: 'native' ;
WHEN: 'when' ;
VERIFY: 'verify' ;


/* Identifiers and Literals */
UNDERSCORE: '_' ;
IDENTIFIER: [a-zA-Z_\u00c7\u00e7\u011e\u011f\u0130\u0131\u00d6\u00f6\u015e\u015f\u00dc\u00fc][a-zA-Z0-9_\u00c7\u00e7\u011e\u011f\u0130\u0131\u00d6\u00f6\u015e\u015f\u00dc\u00fc]* ;
NUMBER: HEX_FLOAT
      | HEX_INT
      | BIN_INT
      | DEC_FLOAT
      | DEC_INT
      ;

fragment HEX_FLOAT
    : '0' [xX] (HexDigits ('.' HexDigits?)? | '.' HexDigits) [pP] [+-]? DecDigits [fFdD]?
    ;

fragment HEX_INT
    : '0' [xX] HexDigits [lL]?
    ;

fragment BIN_INT
    : '0' [bB] BinDigits [lL]?
    ;

fragment DEC_FLOAT
    : DecDigits '.' DecDigits ExponentPart? [fFdD]?
    | '.' DecDigits ExponentPart? [fFdD]?
    | DecDigits ExponentPart [fFdD]?
    | DecDigits ExponentPart? [fFdD]
    ;

fragment DEC_INT
    : DecDigits [lL]?
    ;

fragment DecDigits
    : [0-9] ([0-9_]* [0-9])?
    ;

fragment HexDigits
    : [0-9a-fA-F] ([0-9a-fA-F_]* [0-9a-fA-F])?
    ;

fragment BinDigits
    : [01] ([01_]* [01])?
    ;

fragment ExponentPart
    : [eE] [+-]? DecDigits
    ;

CHAR_LITERAL: '\'' ( ~['\\\r\n] | '\\' ( 'u'+ [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] | [0-3] [0-7] [0-7] | [0-7] [0-7] | [0-7] | . ) ) '\'' ;
MULTILINE_STRING: '"""' ( ~'"' | '"' ~'"' | '""' ~'"' )* '"""' ;
MULTILINE_INTERPOLATED_STRING: '$"""' ( ~["\\{] | '\\' . | '{{' | '}}' | '{' INTERP_EXPR_CONTENT '}' | '"' ~'"' | '""' ~'"' )* '"""' ;
STRING_LITERAL: '"' (~["\r\n\\] | '\\' .)* '"' ;
INTERPOLATED_STRING: '$"' ( ~["\\{] | '\\' . | '{{' | '}}' | '{' INTERP_EXPR_CONTENT '}' )* '"' ;

fragment INTERP_EXPR_CONTENT: ( ~["'\\{}] | '\\' . | STRING_LITERAL | CHAR_LITERAL | '{' INTERP_EXPR_CONTENT '}' )* ;

/* Symbols */
LPAREN: '(' ;
RPAREN: ')' ;
LBRACE: '{' ;
RBRACE: '}' ;
LBRACK: '[' ;
RBRACK: ']' ;
SEMI: ';' ;
COMMA: ',' ;
DOT: '.' ;
RANGE: '..' ;
/* Compound assignment (must come before single-char tokens) */
URSHIFT_ASSIGN: '>>>=' ;
RSHIFT_ASSIGN: '>>=' ;
LSHIFT_ASSIGN: '<<=' ;
AMP_ASSIGN: '&=' ;
PIPE_ASSIGN: '|=' ;
CARET_ASSIGN: '^=' ;
PLUS_ASSIGN: '+=' ;
MINUS_ASSIGN: '-=' ;
STAR_ASSIGN: '*=' ;
SLASH_ASSIGN: '/=' ;
PERCENT_ASSIGN: '%=' ;

/* Increment / Decrement */
PLUS_PLUS: '++' ;
MINUS_MINUS: '--' ;

ASSIGN: '=' ;
GT: '>' ;
LT: '<' ;
BANG: '!' ;
TILDE: '~' ;
PLUS: '+' ;
MINUS: '-' ;
STAR: '*' ;
SLASH: '/' ;
PERCENT: '%' ;
AMP: '&' ;
PIPE: '|' ;
CARET: '^' ;
SAFE_DOT: '?.' ;
NULL_COALESCE: '??' | '?:' ;
DOUBLE_COLON: '::' ;
ARROW: '->' ;
UPPER_BOUND: '<:' ;
LOWER_BOUND: '>:' ;
QUESTION: '?' ;
COLON: ':' ;
LOGICAL_AND: '&&' ;
LOGICAL_OR: '||' ;
EQUALS: '==' ;
NOT_EQUALS: '!=' ;
LESS_EQUAL: '<=' ;
GREATER_EQUAL: '>=' ;
LSHIFT: '<<' ;
RSHIFT: '>>' ;
URSHIFT: '>>>' ;
ELLIPSIS: '...' ;
HASH: '#' ;



WS: [ \t\r\n]+ -> channel(HIDDEN) ;
LINE_COMMENT: '//' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT: '/*' .*? '*/' -> channel(HIDDEN) ;

/* Parser Rules */
program: compilationUnit* EOF;

compilationUnit: packageDeclaration? importStatement* (classDeclaration | interfaceDeclaration | enumDeclaration | annotationDeclaration) ;

packageDeclaration: PACKAGE (anyId DOT)* anyId SEMI ;

importStatement: IMPORT STATIC? (anyId DOT)* (anyId | STAR) SEMI ;

classDeclaration: annotation* modifier* CLASS anyId (LT typeParameter (COMMA typeParameter)* GT)? (LPAREN parameterList? RPAREN)? (EXTENDS type)? (IMPLEMENTS typeList)? restrictsClause? (LBRACE (memberDeclaration | statement)* RBRACE | SEMI?) ;

interfaceDeclaration: annotation* modifier* INTERFACE anyId (LT typeParameter (COMMA typeParameter)* GT)? ((EXTENDS | IMPLEMENTS) typeList)? restrictsClause? (LBRACE memberDeclaration* RBRACE | SEMI?) ;

restrictsClause: RESTRICTS typeList ;

annotationDeclaration: annotation* modifier* ANNOTATION_KW anyId LBRACE annotationMemberDeclaration* RBRACE ;

annotationMemberDeclaration: modifier* (type | VOID) anyId LPAREN RPAREN (DEFAULT expression)? SEMI ;

typeParameter: (PLUS | MINUS)? anyId ((UPPER_BOUND | EXTENDS) type (AMP type)*)? (LOWER_BOUND type)? ;

enumDeclaration: annotation* modifier* ENUM anyId (IMPLEMENTS typeList)? LBRACE enumConstants? (SEMI memberDeclaration*)? RBRACE ;

enumConstants: enumConstant (COMMA enumConstant)* ;
enumConstant: annotation* anyId (LPAREN argumentList? RPAREN)? (LBRACE (memberDeclaration | statement)* RBRACE)? ;

typeList: type (COMMA type)* ;

memberDeclaration: annotation* fieldDeclaration | annotation* constructorDeclaration | annotation* methodDeclaration | annotation* classDeclaration | annotation* interfaceDeclaration | annotation* enumDeclaration | annotation* annotationDeclaration | STATIC? block ;

variableDeclarator: anyId (ASSIGN expression)? ;

fieldDeclaration: modifier* FINAL type variableDeclarator (COMMA variableDeclarator)* SEMI
                | modifier* VALUE variableDeclarator (COMMA variableDeclarator)* SEMI
                | modifier* VARIABLE variableDeclarator (COMMA variableDeclarator)* SEMI
                | modifier* type variableDeclarator (COMMA variableDeclarator)* SEMI
                ;

modifier: STATIC | FINAL | ABSTRACT | SYNC | ASYNC | PUBLIC | PRIVATE | PROTECTED | DATA | SEALED | NON_SEALED | NATIVE | DEFAULT ;

constructorDeclaration: modifier* FUNCTION? anyId LPAREN parameterList? RPAREN (THROWS typeList)? block ;

methodDeclaration: modifier* LOCK? (LT typeParameter (COMMA typeParameter)* GT)? (type | VOID) (extType=type DOT)? FUNCTION anyId LPAREN parameterList? RPAREN (THROWS typeList)? (block | SEMI)  # NormalMethod
                  | modifier* LOCK? MAIN LPAREN parameterList? RPAREN (block | SEMI)                        # MainMethod
                  ;

parameterList: parameter (COMMA parameter)* ;
parameter: annotation* (VALUE | VARIABLE | FINAL? type)? ELLIPSIS? anyId (ASSIGN expression)? ;

block: LBRACE statement* RBRACE ;

statement: anyId COLON statement                           # LabeledStmt
          | SUPER LPAREN argumentList? RPAREN SEMI              # SuperStmt
          | RESULT_KW expression SEMI                       # ResultStmt
          | variableDeclaration SEMI                         # VariableDeclStmt
          | classDeclaration                                # LocalClassDeclStmt
          | verifyStatement                                 # VerifyStmt
          | expressionStatement SEMI                        # ExprStmt
          | assignment SEMI                                  # AssignmentStmt
          | ifStatement                                     # IfStmt
          | forStatement                                    # ForStmt
          | whileStatement                                  # WhileStmt
          | doWhileStatement                                # DoWhileStmt
          | returnStatement SEMI                            # ReturnStmt
          | tryStatement                                    # TryStmt
          | lockBlockStatement                              # LockStmt
          | switchStatement                                 # SwitchStmt
          | THROW expression SEMI                           # ThrowStmt
          | STOP_KW anyId? SEMI                             # StopStmt
          | SKIP_KW anyId? SEMI                             # SkipStmt
          | block                                           # BlockStmt
          ;

verifyStatement: VERIFY expression (COLON expression)? SEMI ;

variableDeclaration: FINAL type variableDeclarator (COMMA variableDeclarator)*         # FinalVarDecl
                   | VALUE variableDeclarator (COMMA variableDeclarator)*            # ValueDecl
                   | VARIABLE variableDeclarator (COMMA variableDeclarator)*         # VariableDecl
                   | type variableDeclarator (COMMA variableDeclarator)*             # TypedVarDecl
                   ;

assignment: expression op=(ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN | PERCENT_ASSIGN | AMP_ASSIGN | PIPE_ASSIGN | CARET_ASSIGN | LSHIFT_ASSIGN | RSHIFT_ASSIGN | URSHIFT_ASSIGN) expression ;

expressionStatement: expression ;

ifStatement: IF LPAREN expression RPAREN statement (ELSE statement)? ;

forStatement: FOR LPAREN forControl RPAREN statement
            | FOR LPAREN (VARIABLE | VALUE | type)? anyId IN expression RPAREN statement
            ;

forControl: (VARIABLE | VALUE | type) anyId FROM expression TO expression WITH (INCREASING | DECREASING) expression
          ;



whileStatement: WHILE LPAREN expression RPAREN statement ;

doWhileStatement: DO statement WHILE LPAREN expression RPAREN SEMI ;

returnStatement: RETURN expression? ;

tryStatement: TRYING resourceList? block catchClause* (FINALLY block)? ;
resourceList: LPAREN resource (SEMI resource)* SEMI? RPAREN ;
resource: (type | VARIABLE | VALUE)? anyId ASSIGN expression
        | anyId
        ;
catchClause: CATCH LPAREN type (PIPE type)* anyId RPAREN block ;

lockBlockStatement: LOCK LPAREN expression RPAREN block ;

switchStatement: SWITCH LPAREN expression RPAREN LBRACE switchCase* defaultCase? RBRACE ;
switchCase: CASE switchLabel (COLON statement* | ARROW (statement | block)) ;
defaultCase: DEFAULT (COLON statement* | ARROW (statement | block)) ;

switchExpressionCase: CASE switchLabel ARROW (expression | block) SEMI? ;
defaultExpressionCase: DEFAULT ARROW (expression | block) SEMI? ;

switchLabel: switchPattern (COMMA switchPattern)* (WHEN expression)? ;

switchPattern: type LPAREN patternList? RPAREN anyId?     # RecordSwitchPattern
             | (type | VARIABLE | VALUE) anyId             # TypeSwitchPattern
             | UNDERSCORE                                  # UnnamedSwitchPattern
             | NULL_KW                                     # NullSwitchPattern
             | expression                                  # ExprSwitchPattern
             ;

instanceofPattern: type LPAREN patternList? RPAREN anyId?     # RecordInstanceofPattern
                 | (type | VARIABLE | VALUE) anyId?           # TypeInstanceofPattern
                 | UNDERSCORE                                 # UnnamedInstanceofPattern
                 | NULL_KW                                    # NullInstanceofPattern
                 ;

patternList: switchPattern (COMMA switchPattern)* ;

expression: (typeName | primitiveType | VOID) (LBRACK RBRACK)* DOT CLASS # ClassLiteralExpr
          | typeName DOT THIS                                           # QualifiedThisExpr
          | typeName DOT SUPER                                          # QualifiedSuperExpr
          | expression DOT typeArguments? anyId (LPAREN argumentList? RPAREN)?         # MemberCallExpr
          | expression SAFE_DOT typeArguments? anyId (LPAREN argumentList? RPAREN)?    # SafeMemberCallExpr
          | expression LPAREN argumentList? RPAREN                # MethodCallExpr
          | expression LBRACK expression RBRACK                  # ArrayAccessExpr
          | expression LBRACK start=expression? RANGE end=expression? RBRACK # RangeSliceExpr
          | expression DOUBLE_COLON (anyId | NEW)                          # MethodRefExpr
          | (typeName | primitiveType) (LBRACK RBRACK)+ DOUBLE_COLON NEW   # ArrayMethodRefExpr
          | expression op=(PLUS_PLUS | MINUS_MINUS)              # PostfixExpr
          | op=(PLUS_PLUS | MINUS_MINUS) expression              # PrefixExpr
          | LPAREN type RPAREN expression                          # CastExpr
          | op=( BANG | TILDE | MINUS ) expression               # UnaryExpr
          | AWAIT expression                                      # AwaitExpr
          | expression op=( STAR | SLASH | PERCENT ) expression   # MulDivModExpr
          | expression op=( PLUS | MINUS ) expression         # AddSubExpr
          | expression op=shiftOp expression                  # ShiftExpr
          | expression op=( LT | GT | LESS_EQUAL | GREATER_EQUAL ) expression # ComparisonExpr
          | expression INSTANCEOF instanceofPattern              # InstanceOfExpr
          | expression op=( EQUALS | NOT_EQUALS ) expression       # EqualityExpr
          | expression AMP expression                      # BitAndExpr
          | expression CARET expression                      # BitXorExpr
          | expression PIPE expression                      # BitOrExpr
          | expression LOGICAL_AND expression                     # LogicalAndExpr
          | expression LOGICAL_OR expression                     # LogicalOrExpr
          | expression NULL_COALESCE expression                     # NullCoalescingExpr
          | expression QUESTION expression COLON expression         # TernaryExpr
          | <assoc=right> expression op=(ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN | PERCENT_ASSIGN | AMP_ASSIGN | PIPE_ASSIGN | CARET_ASSIGN | LSHIFT_ASSIGN | RSHIFT_ASSIGN | URSHIFT_ASSIGN) expression # AssignmentExpr
          | SWITCH LPAREN expression RPAREN LBRACE switchExpressionCase* defaultExpressionCase? RBRACE # SwitchExpr
          | primary                                              # PrimaryExpr
          | NEW type ( (LBRACK expression RBRACK)+ (LBRACK RBRACK)* | LPAREN argumentList? RPAREN (LBRACE (memberDeclaration | statement)* RBRACE)? ) # NewObjectExpr
          | LPAREN (parameterList | identifierList)? RPAREN ARROW (block | expression) # LambdaExpr
          ;

identifierList: anyId (COMMA anyId)* ;

primary: NUMBER                                     # NumberPrimary
       | CHAR_LITERAL                                # CharPrimary
       | (STRING_LITERAL | MULTILINE_STRING)          # StringPrimary
       | TRUE                                       # TruePrimary
       | FALSE                                      # FalsePrimary
       | NULL_KW                                       # NullPrimary
       | (INTERPOLATED_STRING | MULTILINE_INTERPOLATED_STRING) # InterpolatedStringPrimary
       | LPAREN expression RPAREN                          # ParenthesizedPrimary
       | OCEAN_OUTPUT (LPAREN argumentList? RPAREN)?          # OceanOutputPrimary
       | OCEAN_INPUT (LPAREN argumentList? RPAREN)?           # OceanInputPrimary
       | THIS                                       # ThisRefPrimary
       | SUPER                                      # SuperRefPrimary
       | LBRACK argumentList? RBRACK                        # ListLiteralPrimary
       | HASH LBRACE argumentList? RBRACE                   # SetLiteralPrimary
       | LBRACE (mapEntry (COMMA mapEntry)*)? RBRACE        # MapLiteralPrimary
       | LBRACE argumentList? RBRACE                        # ArrayLiteralPrimary
       | primitiveType                               # PrimitiveTypePrimary
       | anyId                                        # IdPrimary
       ;

anyId: IDENTIFIER | UNDERSCORE | STRING_TYPE | MAIN | OCEAN_INPUT | OCEAN_OUTPUT | IN | FROM | TO | WITH | INCREASING | DECREASING | SYNC | ASYNC | VARIABLE | VALUE | RESULT_KW | DATA | ANNOTATION_KW | NATIVE | FUNCTION | LOCK | SEALED | NON_SEALED | RESTRICTS | WHEN | VERIFY | CLASS | INTERFACE | ENUM ;

argumentList: argument (COMMA argument)* ;
argument: (anyId ASSIGN)? expression ;
mapEntry: expression COLON expression ;


shiftOp: LSHIFT | RSHIFT | URSHIFT | GT GT | GT GT GT ;

primitiveType: BOOL_TYPE | INT_TYPE | FLOAT_TYPE | DOUBLE_TYPE | CHAR_TYPE | LONG_TYPE | SHORT_TYPE | BYTE_TYPE | STRING_TYPE ;

type: (PLUS | MINUS | STAR)? (typeName | primitiveType) (LT (type (COMMA type)*)? GT)? QUESTION? (LBRACK RBRACK)* QUESTION? (AMP type)*
    | QUESTION ((EXTENDS | UPPER_BOUND | SUPER | LOWER_BOUND) type)?
    | STAR
    ;
typeName: anyId (DOT anyId)* ;

annotation: AT typeName (LPAREN (annotationElement (COMMA annotationElement)*)? RPAREN)? ;

annotationElement: anyId ASSIGN (expression | annotation) | (expression | annotation) ;

typeArguments: LT type (COMMA type)* GT ;

