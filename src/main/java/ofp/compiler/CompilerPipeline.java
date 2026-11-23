package ofp.compiler;

import java.io.IOException;
import java.nio.file.Path;
import ofp.parser.OFPLexer;
import ofp.parser.OFPParser;
import ofp.semantics.ReferenceCheckListener;
import ofp.semantics.SymbolTableListener;
import ofp.semantics.TypeChecker;
import ofp.symbols.Scope;
import ofp.types.OFPType;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

/** Shared frontend. Each phase must succeed before the next phase or a backend runs. */
public final class CompilerPipeline {
  private CompilerPipeline() {}

  public record Analysis(
      OFPParser.ProgramContext program,
      ParseTreeProperty<Scope> scopes,
      ParseTreeProperty<OFPType> expressionTypes,
      int errors) {
    public boolean successful() { return errors == 0; }
  }

  public static Analysis analyze(Path source) throws IOException {
    SyntaxErrors diagnostics = new SyntaxErrors();
    OFPLexer lexer = new OFPLexer(CharStreams.fromPath(source));
    lexer.removeErrorListeners();
    lexer.addErrorListener(diagnostics);
    OFPParser parser = new OFPParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    parser.addErrorListener(diagnostics);
    OFPParser.ProgramContext program = parser.program();
    if (diagnostics.count > 0) return new Analysis(program, null, null, diagnostics.count);

    SymbolTableListener symbols = new SymbolTableListener();
    ParseTreeWalker.DEFAULT.walk(symbols, program);
    if (symbols.getErrorCount() > 0) {
      return new Analysis(program, symbols.getScopes(), null, symbols.getErrorCount());
    }

    ReferenceCheckListener references = new ReferenceCheckListener(symbols.getScopes());
    ParseTreeWalker.DEFAULT.walk(references, program);
    if (references.getErrorCount() > 0) {
      return new Analysis(program, symbols.getScopes(), null, references.getErrorCount());
    }

    TypeChecker types = new TypeChecker(symbols.getScopes());
    types.visit(program);
    return new Analysis(program, symbols.getScopes(), types.getExpressionTypes(), types.getErrorCount());
  }

  private static final class SyntaxErrors extends BaseErrorListener {
    private int count;

    @Override public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
        int line, int column, String message, RecognitionException exception) {
      System.err.printf("Syntax error (%d,%d): %s%n", line, column, message);
      count++;
    }
  }
}
