package ofp.semantics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import ofp.parser.OFPBaseVisitor;
import ofp.parser.OFPParser;
import ofp.symbols.FunctionSymbol;
import ofp.symbols.Scope;
import ofp.symbols.Symbol;
import ofp.types.OFPType;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;

/** Third semantic pass: validate types and annotate expressions for both backends. */
public class TypeChecker extends OFPBaseVisitor<OFPType> {
  private final ParseTreeProperty<Scope> scopes;
  private final ParseTreeProperty<OFPType> expressionTypes = new ParseTreeProperty<>();
  private final Deque<FunctionSymbol> functions = new ArrayDeque<>();
  private Scope currentScope;
  private int errorCount;

  public TypeChecker(ParseTreeProperty<Scope> scopes) {
    this.scopes = scopes;
  }

  public int getErrorCount() { return errorCount; }
  public ParseTreeProperty<OFPType> getExpressionTypes() { return expressionTypes; }

  @Override public OFPType visit(ParseTree tree) {
    OFPType type = super.visit(tree);
    if (tree instanceof OFPParser.ExprContext && type != null) {
      expressionTypes.put(tree, type);
    }
    return type;
  }

  private void error(ParserRuleContext ctx, String message) {
    System.err.printf("Type error (%d,%d): %s%n",
        ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), message);
    errorCount++;
  }

  private void pushScope(ParserRuleContext ctx) { currentScope = scopes.get(ctx); }
  private void popScope() { currentScope = currentScope.getEnclosingScope(); }

  @Override public OFPType visitProgram(OFPParser.ProgramContext ctx) {
    pushScope(ctx);
    super.visitProgram(ctx);
    popScope();
    return null;
  }

  @Override public OFPType visitMain(OFPParser.MainContext ctx) {
    pushScope(ctx);
    functions.push((FunctionSymbol) currentScope.getEnclosingScope().resolve("main"));
    visit(ctx.block());
    functions.pop();
    popScope();
    return null;
  }

  @Override public OFPType visitFunctionDecl(OFPParser.FunctionDeclContext ctx) {
    pushScope(ctx);
    functions.push((FunctionSymbol) currentScope.getEnclosingScope().resolve(ctx.ID().getText()));
    if (ctx.paramList() != null) {
      for (OFPParser.ParamContext parameter : ctx.paramList().param()) {
        if ("void".equals(parameter.type().getText())) error(parameter, "Parameter cannot have type void");
      }
    }
    visit(ctx.block());
    functions.pop();
    popScope();
    return null;
  }

  @Override public OFPType visitBlock(OFPParser.BlockContext ctx) {
    pushScope(ctx);
    for (OFPParser.StmtContext statement : ctx.stmt()) visit(statement);
    popScope();
    return null;
  }

  private void checkValue(ParserRuleContext ctx, OFPType expected, OFPParser.ExprContext value) {
    // An empty array literal gets its element type from the surrounding declaration or assignment.
    if (value instanceof OFPParser.LiteralExprContext literal
        && literal.literal() instanceof OFPParser.ArrayLiteralContext array
        && array.arrayLit().expr().isEmpty() && isArray(expected)) {
      expressionTypes.put(value, expected);
      return;
    }
    OFPType actual = visit(value);
    if (actual != null && expected != actual) {
      error(ctx, "Type mismatch: expected " + expected + ", got " + actual);
    }
  }

  @Override public OFPType visitVarDeclStmt(OFPParser.VarDeclStmtContext ctx) {
    var declaration = ctx.varDecl();
    OFPType declared = OFPType.getTypeFor(declaration.type().getText());
    if (declared == OFPType.VOID) error(ctx, "Variable cannot have type void");
    if (declaration.expr() != null) checkValue(ctx, declared, declaration.expr());
    return null;
  }

  @Override public OFPType visitAssignStmt(OFPParser.AssignStmtContext ctx) {
    Symbol symbol = currentScope.resolve(ctx.assignment().ID().getText());
    if (symbol instanceof FunctionSymbol) error(ctx, "Cannot assign to a function");
    else if (symbol != null) checkValue(ctx, symbol.getType(), ctx.assignment().expr());
    return null;
  }

  @Override public OFPType visitArrayAssignStmt(OFPParser.ArrayAssignStmtContext ctx) {
    Symbol symbol = currentScope.resolve(ctx.arrayAssign().ID().getText());
    if (symbol == null) return null;
    if (!isArray(symbol.getType())) error(ctx, "Array assignment requires an array");
    if (visit(ctx.arrayAssign().expr(0)) != OFPType.INT) error(ctx, "Array index must be int");
    checkValue(ctx, elementType(symbol.getType()), ctx.arrayAssign().expr(1));
    return null;
  }

  @Override public OFPType visitIfStmtRule(OFPParser.IfStmtRuleContext ctx) {
    if (visit(ctx.ifStmt().expr()) != OFPType.BOOL) error(ctx, "If condition must be bool");
    for (OFPParser.StmtOrBlockContext body : ctx.ifStmt().stmtOrBlock()) visit(body);
    return null;
  }

  @Override public OFPType visitWhileStmtRule(OFPParser.WhileStmtRuleContext ctx) {
    if (visit(ctx.whileStmt().expr()) != OFPType.BOOL) error(ctx, "While condition must be bool");
    visit(ctx.whileStmt().stmtOrBlock());
    return null;
  }

  @Override public OFPType visitReturnStmtRule(OFPParser.ReturnStmtRuleContext ctx) {
    OFPType expected = functions.peek().getType();
    OFPParser.ExprContext value = ctx.returnStmt().expr();
    if (value == null) {
      if (expected != OFPType.VOID) error(ctx, "Non-void function must return a value");
    } else if (expected == OFPType.VOID) {
      error(ctx, "void function cannot return a value");
    } else {
      checkValue(ctx, expected, value);
    }
    return null;
  }

  @Override public OFPType visitPrintStmtRule(OFPParser.PrintStmtRuleContext ctx) {
    OFPType type = visit(ctx.printStmt().expr());
    if (type == null || type == OFPType.VOID || isArray(type)) {
      error(ctx, "print/println expects int|float|bool|char|string");
    }
    return null;
  }

  @Override public OFPType visitExprStmtRule(OFPParser.ExprStmtRuleContext ctx) {
    visit(ctx.exprStmt().expr());
    return null;
  }

  // Wrappers must forward the expression type instead of aggregating punctuation tokens.
  @Override public OFPType visitLiteralExpr(OFPParser.LiteralExprContext ctx) { return visit(ctx.literal()); }
  @Override public OFPType visitParExpr(OFPParser.ParExprContext ctx) { return visit(ctx.expr()); }
  @Override public OFPType visitIntLiteral(OFPParser.IntLiteralContext ctx) { return OFPType.INT; }
  @Override public OFPType visitFloatLiteral(OFPParser.FloatLiteralContext ctx) { return OFPType.FLOAT; }
  @Override public OFPType visitBoolLiteral(OFPParser.BoolLiteralContext ctx) { return OFPType.BOOL; }
  @Override public OFPType visitCharLiteral(OFPParser.CharLiteralContext ctx) { return OFPType.CHAR; }
  @Override public OFPType visitStringLiteral(OFPParser.StringLiteralContext ctx) { return OFPType.STRING; }

  @Override public OFPType visitIdExpr(OFPParser.IdExprContext ctx) {
    Symbol symbol = currentScope.resolve(ctx.ID().getText());
    if (symbol instanceof FunctionSymbol) { error(ctx, "Function requires a call"); return null; }
    return symbol == null ? null : symbol.getType();
  }

  @Override public OFPType visitArrayLiteral(OFPParser.ArrayLiteralContext ctx) {
    OFPType element = null;
    for (OFPParser.ExprContext value : ctx.arrayLit().expr()) {
      OFPType type = visit(value);
      if (element == null) element = type;
      else if (element != type) error(value, "Array literal elements must have the same type");
    }
    if (element == OFPType.INT) return OFPType.INT_ARR;
    if (element == OFPType.FLOAT) return OFPType.FLOAT_ARR;
    if (element == OFPType.CHAR) return OFPType.CHAR_ARR;
    error(ctx, "Array literal needs int, float, or char elements (empty arrays need a declared type)");
    return null;
  }

  @Override public OFPType visitNewArrayExpr(OFPParser.NewArrayExprContext ctx) {
    if (visit(ctx.expr()) != OFPType.INT) error(ctx, "Array size must be int");
    return OFPType.getTypeFor(ctx.baseType().getText() + "[]");
  }

  @Override public OFPType visitArrayAccessExpr(OFPParser.ArrayAccessExprContext ctx) {
    Symbol symbol = currentScope.resolve(ctx.ID().getText());
    if (visit(ctx.expr()) != OFPType.INT) error(ctx, "Array index must be int");
    if (symbol == null) return null;
    OFPType element = elementType(symbol.getType());
    if (element == null) error(ctx, "Indexing requires an array or string");
    return element;
  }

  @Override public OFPType visitLengthExpr(OFPParser.LengthExprContext ctx) {
    OFPType type = visit(ctx.expr());
    if (!isArray(type) && type != OFPType.STRING) error(ctx, ".length allowed only on arrays or string");
    return OFPType.INT;
  }

  @Override public OFPType visitFuncCallExpr(OFPParser.FuncCallExprContext ctx) {
    Symbol symbol = currentScope.resolve(ctx.ID().getText());
    if (!(symbol instanceof FunctionSymbol function)) { error(ctx, "Not a function: " + ctx.ID().getText()); return null; }
    List<OFPParser.ExprContext> arguments = ctx.argList() == null ? List.of() : ctx.argList().expr();
    if (arguments.size() != function.getParams().size()) {
      error(ctx, "Wrong number of arguments to " + function.getName());
    }
    for (int i = 0; i < arguments.size(); i++) {
      if (i < function.getParams().size()) checkValue(ctx, function.getParams().get(i).getType(), arguments.get(i));
      else visit(arguments.get(i));
    }
    return function.getType();
  }

  @Override public OFPType visitUnaryMinusExpr(OFPParser.UnaryMinusExprContext ctx) {
    OFPType type = visit(ctx.expr());
    if (!isNumeric(type)) error(ctx, "Unary '-' needs numeric type");
    return type;
  }

  @Override public OFPType visitAddSubExpr(OFPParser.AddSubExprContext ctx) {
    return arithmeticResult(ctx, visit(ctx.expr(0)), visit(ctx.expr(1)));
  }

  @Override public OFPType visitMulDivExpr(OFPParser.MulDivExprContext ctx) {
    return arithmeticResult(ctx, visit(ctx.expr(0)), visit(ctx.expr(1)));
  }

  @Override public OFPType visitCompExpr(OFPParser.CompExprContext ctx) {
    OFPType left = visit(ctx.expr(0)), right = visit(ctx.expr(1));
    boolean equality = "==".equals(ctx.getChild(1).getText());
    if (left != right || !(isNumeric(left) || (equality && left == OFPType.CHAR))) {
      error(ctx, "Comparison requires matching numeric types (== also accepts char)");
    }
    return OFPType.BOOL;
  }

  private OFPType arithmeticResult(ParserRuleContext ctx, OFPType left, OFPType right) {
    if (!isNumeric(left) || !isNumeric(right)) {
      error(ctx, "Arithmetic needs int/float, got " + left + " and " + right);
      return null;
    }
    return left == OFPType.FLOAT || right == OFPType.FLOAT ? OFPType.FLOAT : OFPType.INT;
  }

  private static boolean isNumeric(OFPType type) { return type == OFPType.INT || type == OFPType.FLOAT; }
  private static boolean isArray(OFPType type) {
    return type == OFPType.INT_ARR || type == OFPType.FLOAT_ARR || type == OFPType.CHAR_ARR;
  }
  private static OFPType elementType(OFPType type) {
    if (type == OFPType.INT_ARR) return OFPType.INT;
    if (type == OFPType.FLOAT_ARR) return OFPType.FLOAT;
    if (type == OFPType.CHAR_ARR || type == OFPType.STRING) return OFPType.CHAR;
    return null;
  }
}
