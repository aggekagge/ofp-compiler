grammar OFP;

// ---------------- PARSER RULES ----------------

program
    : functionDecl* main functionDecl* EOF
    ;

main
    : 'void' 'main' '(' ')' block
    ;

functionDecl
    : type ID '(' paramList? ')' block
    ;

paramList
    : param (',' param)*
    ;

param
    : type ID
    ;


block
    : '{' stmt* '}'
    ;


varDecl
    : type ID ('=' expr)?
    ;

assignment
    : ID '=' expr
    ;

arrayAssign
    : ID '[' expr ']' '=' expr
    ;


returnStmt
    : 'return' expr?
    ;


printStmt
    : ('print' | 'println') '(' expr ')'
    ;


stmtOrBlock
    : block
    | stmt
    ;


ifStmt
    : 'if' '(' expr ')' stmtOrBlock ('else' stmtOrBlock)?
    ;


whileStmt
    : 'while' '(' expr ')' stmtOrBlock
    ;

exprStmt
    : expr
    ;


stmt
    : varDecl ';'                #varDeclStmt
    | assignment ';'             #assignStmt
    | arrayAssign ';'            #arrayAssignStmt
    | ifStmt                     #ifStmtRule
    | whileStmt                  #whileStmtRule
    | returnStmt ';'             #returnStmtRule
    | printStmt ';'              #printStmtRule
    | exprStmt ';'               #exprStmtRule 
    ;

// ---------------- EXPRESSIONS ----------------


expr
    : ID '(' argList? ')'                #funcCallExpr
    | ID '[' expr ']'                    #arrayAccessExpr
    | expr '.' 'length'                  #lengthExpr
    | 'new' baseType '[' expr ']'        #newArrayExpr
    | literal                            #literalExpr
    | ID                                 #idExpr
    | '(' expr ')'                       #parExpr
    | '-' expr                           #unaryMinusExpr
    | expr ('*'|'/') expr                #mulDivExpr
    | expr ('+'|'-') expr                #addSubExpr
    | expr ('<'|'>'|'==') expr           #compExpr
    ;


argList
    : expr (',' expr)*
    ;

// ---------------- LITERALS & TYPES ----------------

literal
    : INTLIT       #intLiteral
    | FLOATLIT     #floatLiteral
    | BOOLLIT      #boolLiteral
    | CHARLIT      #charLiteral
    | STRINGLIT    #stringLiteral
    | arrayLit     #arrayLiteral
    ;

arrayLit
    : '{' (expr (',' expr)*)? '}'
    ;

type
    : 'int'              #intType
    | 'float'            #floatType
    | 'bool'             #boolType
    | 'char'             #charType
    | 'string'           #stringType
    | 'int' '[' ']'      #intArrayType
    | 'float' '[' ']'    #floatArrayType
    | 'char' '[' ']'     #charArrayType
    | 'void'             #voidType
    ;

baseType
    : 'int'
    | 'float'
    | 'char'
    ;

// ---------------- LEXER RULES ----------------

BOOLLIT
    : 'true' | 'false'
    ;

ID
    : [a-zA-Z]+
    ;

INTLIT
    : '0' | [1-9][0-9]*
    ;

FLOATLIT
    : ('0' | [1-9][0-9]*) '.' [0-9]+
    ;


CHARLIT
    : '\'' [a-zA-Z] '\''
    ;

STRINGLIT
    : '"' [a-zA-Z!.,?=:() ]* '"'
    ;

WS
    : [ \t\r\n\uFEFF]+ -> skip   
    ;



COMMENT
    : '#' ~[\r\n]* -> skip 
    ;
