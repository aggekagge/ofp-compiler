package ofp.semantics;

import ofp.parser.OFPBaseListener;
import ofp.parser.OFPParser;
import ofp.symbols.FunctionSymbol;
import ofp.symbols.ParamSymbol;
import ofp.symbols.Scope;
import ofp.symbols.VarSymbol;
import ofp.types.OFPType;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;

/** First semantic pass: register functions, parameters, and lexical block scopes. */
public class SymbolTableListener extends OFPBaseListener {
  private final ParseTreeProperty<Scope> scopes = new ParseTreeProperty<>();
  private Scope currentScope = null;
  private FunctionSymbol currentFunction = null;
  private int errorCount = 0;

  public ParseTreeProperty<Scope> getScopes(){ return scopes; }
  public int getErrorCount(){ return errorCount; }

  private void error(ParserRuleContext ctx, String msg){
    int line = ctx.getStart().getLine();
    int col  = ctx.getStart().getCharPositionInLine();
    System.err.printf("SymbolTable error (%d,%d): %s%n", line, col, msg);
    errorCount++;
  }

  @Override public void enterProgram(OFPParser.ProgramContext ctx){
    currentScope = new Scope(null); // global
    scopes.put(ctx, currentScope);
  }

  @Override public void exitProgram(OFPParser.ProgramContext ctx){
    currentScope = null;
  }

  // Register main as a void function with its own scope.
  @Override public void enterMain(OFPParser.MainContext ctx){
    FunctionSymbol f = new FunctionSymbol("main", OFPType.VOID);
    if (!currentScope.define(f)) error(ctx, "Duplicate function: main");
    currentFunction = f;

    currentScope = new Scope(currentScope); // function scope for main
    scopes.put(ctx, currentScope);
  }
  @Override public void exitMain(OFPParser.MainContext ctx){
    currentScope = currentScope.getEnclosingScope();
    currentFunction = null;
  }

  // Register the function signature, its parameters, and its scope.
  @Override public void enterFunctionDecl(OFPParser.FunctionDeclContext ctx){
    String name = ctx.ID().getText();
    OFPType ret = OFPType.getTypeFor(ctx.type().getText());
    FunctionSymbol f = new FunctionSymbol(name, ret);
    if (!currentScope.define(f)) error(ctx, "Duplicate function: " + name);
    currentFunction = f;

    currentScope = new Scope(currentScope);
    scopes.put(ctx, currentScope);

    if (ctx.paramList()!=null){
      for (OFPParser.ParamContext p : ctx.paramList().param()){
        String pn = p.ID().getText();
        OFPType pt = OFPType.getTypeFor(p.type().getText());
        ParamSymbol ps = new ParamSymbol(pn, pt);
        currentFunction.addParam(ps);
        if (!currentScope.define(ps)) error(p, "Duplicate parameter: " + pn);
      }
    }
  }
  @Override public void exitFunctionDecl(OFPParser.FunctionDeclContext ctx){
    currentScope = currentScope.getEnclosingScope();
    currentFunction = null;
  }

  // Every braced block introduces a lexical scope.
  @Override public void enterBlock(OFPParser.BlockContext ctx){
    currentScope = new Scope(currentScope);
    scopes.put(ctx, currentScope);
  }
  @Override public void exitBlock(OFPParser.BlockContext ctx){
    currentScope = currentScope.getEnclosingScope();
  }

  // Register a variable in the current block.
  @Override public void enterVarDecl(OFPParser.VarDeclContext ctx){
    String name = ctx.ID().getText();
    OFPType t = OFPType.getTypeFor(ctx.type().getText());
    if (!currentScope.define(new VarSymbol(name, t))){
      error(ctx, "Duplicate variable in same scope: " + name);
    }
  }
}
