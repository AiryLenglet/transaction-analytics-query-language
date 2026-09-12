grammar Taql;

// =====================================================================
//  TAQL - Transaction Analytics Query Language
//
//  A query states its own values. There are no placeholders to fill in: what
//  a query asks for is what it says, and a reader needs nothing but the text.
//
//  Two statement shapes share one filter / expression language:
//
//    analysis by <keys> { <measures> } over { <filters> } top N by <m>
//    list { <projections> } over { <filters> } sort by <k> top N
// =====================================================================

query          : statement EOF ;

statement      : analysisStatement
               | flatStatement
               ;

analysisStatement : ANALYSIS BY groupKeyList measureBlock queryClause* ;

flatStatement     : LIST projectionBlock queryClause* ;

// Written 'from ... over ... sort by ... top'. The grammar stays permissive
// and the AST builder enforces both the order and the absence of duplicates,
// so a query written the wrong way round gets a semantic error naming the
// clause and the order, rather than an opaque parse failure on a token.
queryClause    : fromClause
               | overClause
               | sortClause
               | topClause
               ;

fromClause     : FROM identifier ;
overClause     : OVER LBRACE (predicate (COMMA? predicate)*)? RBRACE ;
sortClause     : SORT BY sortItem (COMMA? sortItem)* ;
sortItem       : expression (ASC | DESC)? ;
topClause      : TOP countExpr (BY identifier)? withinClause? ;
countExpr      : INT ;

// ---------- analysis ----------

groupKeyList   : groupKey (COMMA? groupKey)* ;
groupKey       : (identifier EQ)? expression ;

measureBlock   : LBRACE measure (COMMA? measure)* RBRACE ;

// Aggregates and window functions share this shape; which one a measure is
// depends on the function name, and the resolver decides. Splitting them in the
// grammar would make 'rank(total)' a parse error rather than a named one.
measure        : (identifier EQ)? measureBody (WHEN predicate)? withinClause? orderedClause? ;

// A measure has to aggregate -- a bare column in here is the one thing SQL will
// not let a grouped query select. The grammar accepts it anyway so the AST
// builder can say so: rejecting it here gives 'no viable alternative' where the
// answer is "aggregate it, or group by it".
//
// 'sum(x)' matches both alternatives; the first wins, which is what keeps
// DISTINCT working and keeps 'upper(x)' reaching the resolver by name.
measureBody    : identifier LPAREN (DISTINCT? expression)? RPAREN   # AggregateCall
               | expression                                        # NotAnAggregate
               ;

withinClause   : WITHIN identifier (COMMA identifier)* ;
orderedClause  : ORDERED BY identifier (ASC | DESC)? ;

// ---------- flat ----------

projectionBlock : LBRACE projection (COMMA? projection)* RBRACE ;
projection      : (identifier EQ)? expression ;

// ---------- predicates ----------

predicate      : orPredicate ;
orPredicate    : andPredicate (OR andPredicate)* ;
andPredicate   : unaryPredicate (AND unaryPredicate)* ;
unaryPredicate : NOT unaryPredicate                        # NotPredicate
               | LPAREN predicate RPAREN                   # ParenPredicate
               | comparison                                # ComparisonPredicate
               ;

comparison     : expression NOT? IN inSource               # InComparison
               | expression IS NOT? NULL                   # NullComparison
               | expression NOT? LIKE expression           # LikeComparison
               | expression compareOp expression           # OpComparison
               ;

compareOp      : EQ | NEQ | LT | LTE | GT | GTE ;

inSource       : listLiteral                               # InList
               | expression RANGE expression               # InRange
               ;

listLiteral    : LBRACKET (expression (COMMA expression)*)? RBRACKET ;

// ---------- expressions ----------

expression     : LPAREN expression RPAREN                              # ParenExpr
               | (PLUS | MINUS) expression                             # UnaryExpr
               | expression (STAR | SLASH | PERCENT) expression        # MulExpr
               | expression (PLUS | MINUS) expression                  # AddExpr
               | matchExpr                                             # MatchWrapper
               | identifier LPAREN (expression (COMMA expression)*)? RPAREN # CallExpr
               | literal                                               # LiteralExpr
               | identifier                                            # FieldExpr
               ;

matchExpr      : MATCH expression LBRACE valueArm (COMMA? valueArm)* RBRACE  # MatchOnValue
               | MATCH LBRACE condArm (COMMA? condArm)* RBRACE              # MatchOnCondition
               ;

valueArm       : UNDERSCORE ARROW expression                # ValueDefaultArm
               | valuePattern ARROW expression              # ValuePatternArm
               ;
valuePattern   : listLiteral | literal ;

condArm        : UNDERSCORE ARROW expression                # CondDefaultArm
               | predicate ARROW expression                 # CondPredicateArm
               ;

literal        : STRING | INT | DECIMAL_LIT | TRUE | FALSE | NULL ;
identifier     : IDENT | QUOTED_IDENT ;

// =====================================================================
//  Lexer -- keywords are case-insensitive, identifiers are resolved
//  case-insensitively against the catalog.
// =====================================================================

ANALYSIS  : A N A L Y S I S ;
LIST      : L I S T ;
BY        : B Y ;
FROM      : F R O M ;
OVER      : O V E R ;
SORT      : S O R T ;
WITHIN    : W I T H I N ;
ORDERED   : O R D E R E D ;
TOP       : T O P ;
ASC       : A S C ;
DESC      : D E S C ;
MATCH     : M A T C H ;
WHEN      : W H E N ;
DISTINCT  : D I S T I N C T ;
AND       : A N D ;
OR        : O R ;
NOT       : N O T ;
IN        : I N ;
IS        : I S ;
LIKE      : L I K E ;
NULL      : N U L L ;
TRUE      : T R U E ;
FALSE     : F A L S E ;

ARROW     : '->' ;
RANGE     : '..' ;
NEQ       : '!=' | '<>' ;
LTE       : '<=' ;
GTE       : '>=' ;
EQ        : '=' ;
LT        : '<' ;
GT        : '>' ;
PLUS      : '+' ;
MINUS     : '-' ;
STAR      : '*' ;
SLASH     : '/' ;
PERCENT   : '%' ;
COMMA     : ',' ;
LPAREN    : '(' ;
RPAREN    : ')' ;
LBRACE    : '{' ;
RBRACE    : '}' ;
LBRACKET  : '[' ;
RBRACKET  : ']' ;

UNDERSCORE : '_' ;

DECIMAL_LIT  : [0-9]+ '.' [0-9]+ ;
INT          : [0-9]+ ;
STRING       : '\'' ( ~'\'' | '\'\'' )* '\'' ;
IDENT        : [a-zA-Z_] [a-zA-Z_0-9]* ;
QUOTED_IDENT : '`' ~'`'+ '`' ;

LINE_COMMENT  : '//' ~[\r\n]*    -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/'    -> channel(HIDDEN) ;
WS            : [ \t\r\n]+       -> channel(HIDDEN) ;

fragment A : [aA] ; fragment B : [bB] ; fragment C : [cC] ; fragment D : [dD] ;
fragment E : [eE] ; fragment F : [fF] ; fragment G : [gG] ; fragment H : [hH] ;
fragment I : [iI] ; fragment J : [jJ] ; fragment K : [kK] ; fragment L : [lL] ;
fragment M : [mM] ; fragment N : [nN] ; fragment O : [oO] ; fragment P : [pP] ;
fragment Q : [qQ] ; fragment R : [rR] ; fragment S : [sS] ; fragment T : [tT] ;
fragment U : [uU] ; fragment V : [vV] ; fragment W : [wW] ; fragment X : [xX] ;
fragment Y : [yY] ; fragment Z : [zZ] ;
