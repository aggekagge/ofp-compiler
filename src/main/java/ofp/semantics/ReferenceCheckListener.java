package ofp.semantics;

import ofp.parser.OFPBaseListener;
import ofp.parser.OFPParser;
import ofp.symbols.Scope;
import ofp.symbols.Symbol;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;

/** Second semantic pass: resolve names against the completed symbol tables. */
public class ReferenceCheckListener extends OFPBaseListener {
  private final ParseTreeProperty<Scope> scopes;
  private Scope currentScope;

  public ReferenceCheckListener(ParseTreeProperty<Scope> scopes){ this.scopes = scopes; }

  private void error(ParserRuleContext ctx, String msg){
    int line = ctx.getStart().getLine();
    int col  = ctx.getStart().getCharPositionInLine();
    System.err.printf("Semantic error (%d,%d): %s%n", line, col, msg);
    errorCount++;
  }

  private int errorCount = 0;
  public int getErrorCount() { return errorCount; }

  @Override public void enterProgram(OFPParser.ProgramContext ctx){ currentScope = scopes.get(ctx); }
  @Override public void enterMain(OFPParser.MainContext ctx){ currentScope = scopes.get(ctx); }
  @Override public void enterFunctionDecl(OFPParser.FunctionDeclContext ctx){ currentScope = scopes.get(ctx); }
  @Override public void enterBlock(OFPParser.BlockContext ctx){ currentScope = scopes.get(ctx); }

  @Override public void exitBlock(OFPParser.BlockContext ctx){ currentScope = currentScope.getEnclosingScope(); }
  @Override public void exitFunctionDecl(OFPParser.FunctionDeclContext ctx){ currentScope = currentScope.getEnclosingScope(); }
  @Override public void exitMain(OFPParser.MainContext ctx){ currentScope = currentScope.getEnclosingScope(); }

  @Override public void enterIdExpr(OFPParser.IdExprContext ctx){
    String name = ctx.ID().getText();
    if (currentScope.resolve(name) == null){
      error(ctx, "Undeclared identifier: " + name);
    }
  }

  @Override public void enterFuncCallExpr(OFPParser.FuncCallExprContext ctx){
    String fname = ctx.ID().getText();
    Symbol s = currentScope.resolve(fname);
    if (s == null) {
      error(ctx, "Call to undeclared function: " + fname);
    }
  }

  private void checkVariable(ParserRuleContext ctx, String name) {
    if (currentScope.resolve(name) == null) error(ctx, "Undeclared identifier: " + name);
  }

  @Override public void enterAssignment(OFPParser.AssignmentContext ctx) {
    checkVariable(ctx, ctx.ID().getText());
  }

  @Override public void enterArrayAssign(OFPParser.ArrayAssignContext ctx) {
    checkVariable(ctx, ctx.ID().getText());
  }

  @Override public void enterArrayAccessExpr(OFPParser.ArrayAccessExprContext ctx) {
    checkVariable(ctx, ctx.ID().getText());
  }
}
