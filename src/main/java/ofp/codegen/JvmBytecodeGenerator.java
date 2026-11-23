package ofp.codegen;

import ofp.parser.OFPBaseVisitor;
import ofp.parser.OFPParser;
import ofp.symbols.FunctionSymbol;
import ofp.symbols.ParamSymbol;
import ofp.symbols.Scope;
import ofp.symbols.Symbol;
import ofp.symbols.VarSymbol;
import ofp.types.OFPType;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Emits JVM class files from a semantically validated and type-annotated program. */
public class JvmBytecodeGenerator extends OFPBaseVisitor<Type> implements Opcodes {
  private final ParseTreeProperty<Scope> scopes;
  private final ParseTreeProperty<OFPType> expressionTypes;
  private Scope currentScope;
  private ClassWriter cw;
  private String internalClassName;

  // Stack of active method generators.
  private final Deque<GeneratorAdapter> genStack = new ArrayDeque<>();

  // The generator for the method currently being emitted.
  private GeneratorAdapter g() { return genStack.peek(); }

  private final Deque<FunctionSymbol> funStack = new ArrayDeque<>();

  // Local slots keyed by resolved symbols, including shadowed declarations.
  private final Map<Symbol,Integer> locals = new HashMap<>();

  // Parameter positions keyed by resolved symbols.
  private final Map<Symbol,Integer> params = new HashMap<>();

  public JvmBytecodeGenerator(ParseTreeProperty<Scope> scopes,
                ParseTreeProperty<OFPType> expressionTypes) {
    this.scopes = scopes;
    this.expressionTypes = expressionTypes;
  }

  public byte[] generate(OFPParser.ProgramContext tree, String className) {
    // JVM internal class names use slashes instead of dots.
    internalClassName = className.replace('.', '/');
    cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(V1_8,
        ACC_PUBLIC | ACC_SUPER,
        internalClassName,
        null,
        "java/lang/Object",
        null);

    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();

    // Emit the entry point and all declared functions.
    visit(tree);
    cw.visitEnd();
    return cw.toByteArray();
  }

  private void pushScope(ParserRuleContext ctx) {
    currentScope = scopes.get(ctx);
  }

  private void popScope() {
    if (currentScope != null) {
      currentScope = currentScope.getEnclosingScope();
    }
  }

  private static boolean isArrayType(OFPType t) {
    return t == OFPType.INT_ARR ||
       t == OFPType.FLOAT_ARR ||
       t == OFPType.CHAR_ARR;
  }

  // Map source language types to JVM representations.

  private static Type asmTypeOf(OFPType t) {
    if (t == null) return null;

    if (t == OFPType.INT)        return Type.INT_TYPE;
    if (t == OFPType.FLOAT)      return Type.DOUBLE_TYPE;
    if (t == OFPType.BOOL)       return Type.BOOLEAN_TYPE;
    if (t == OFPType.CHAR)       return Type.CHAR_TYPE;
    if (t == OFPType.STRING)     return Type.getType(String.class);

    if (t == OFPType.INT_ARR)    return Type.getType(int[].class);
    if (t == OFPType.FLOAT_ARR)  return Type.getType(double[].class);
    if (t == OFPType.CHAR_ARR)   return Type.getType(char[].class);

    if (t == OFPType.VOID)       return Type.VOID_TYPE;

    throw new RuntimeException("Unsupported OFPType: " + t);
  }

  private static OFPType elementTypeOf(OFPType arr) {
    if (arr == OFPType.INT_ARR)   return OFPType.INT;
    if (arr == OFPType.FLOAT_ARR) return OFPType.FLOAT;
    if (arr == OFPType.CHAR_ARR)  return OFPType.CHAR;
    return null;
  }

  private static boolean isNumeric(Type t) {
    return Type.INT_TYPE.equals(t) || Type.DOUBLE_TYPE.equals(t);
  }

  @Override
  public Type visitProgram(OFPParser.ProgramContext ctx) {
    pushScope(ctx);
    super.visitProgram(ctx);
    popScope();
    return null;
  }

  @Override
  public Type visitMain(OFPParser.MainContext ctx) {
    pushScope(ctx);
    funStack.push((FunctionSymbol) currentScope.getEnclosingScope().resolve("main"));

    Method m = new Method("main", Type.VOID_TYPE, new Type[]{ Type.getType(String[].class) });

    GeneratorAdapter ga = new GeneratorAdapter(ACC_PUBLIC | ACC_STATIC, m, null, null, cw);

    genStack.push(ga);

    // Start a fresh set of local and parameter bindings.
    locals.clear();
    params.clear();

    visit(ctx.block());
    ga.returnValue();
    ga.endMethod();
    genStack.pop();
    funStack.pop();
    popScope();
    return null;
  }

  @Override
  public Type visitFunctionDecl(OFPParser.FunctionDeclContext ctx) {
    pushScope(ctx);
    String fname = ctx.ID().getText();

    // Find the function signature in the enclosing global scope.
    Scope parent = currentScope.getEnclosingScope();
    Symbol sym = (parent != null) ? parent.resolve(fname) : null;
    if (!(sym instanceof FunctionSymbol)) {
      throw new RuntimeException("Missing FunctionSymbol for function: " + fname);
    }
    FunctionSymbol fs = (FunctionSymbol) sym;
    funStack.push(fs);

    List<ParamSymbol> ps = fs.getParams();
    Type[] argTypes = new Type[ps.size()];
    for (int i = 0; i < ps.size(); i++) {
      argTypes[i] = asmTypeOf(ps.get(i).getType());
    }

    Method m = new Method(fname, asmTypeOf(fs.getType()), argTypes);
    GeneratorAdapter ga =
        new GeneratorAdapter(ACC_PRIVATE | ACC_STATIC, m, null, null, cw);
    genStack.push(ga);

    // Reset bindings for this function.
    locals.clear();
    params.clear();
    for (int i = 0; i < ps.size(); i++) {
      params.put(currentScope.resolve(ps.get(i).getName()), i);
    }

    visit(ctx.block());

    // Void functions may fall through to an implicit return.
    if (fs.getType() == OFPType.VOID) {
      ga.returnValue();
    }
    ga.endMethod();
    genStack.pop();
    funStack.pop();
    popScope();
    return null;
  }

  @Override
  public Type visitBlock(OFPParser.BlockContext ctx) {
    // Use the lexical scope associated with this block.
    pushScope(ctx);
    super.visitBlock(ctx);
    popScope();
    return null;
  }

  // Statements
  @Override
  public Type visitVarDeclStmt(OFPParser.VarDeclStmtContext ctx) {

    OFPType ofpT = OFPType.getTypeFor(ctx.varDecl().type().getText());
    Type asmT = asmTypeOf(ofpT);
    String name = ctx.varDecl().ID().getText();

    GeneratorAdapter ga = g();
    OFPParser.ExprContext init = ctx.varDecl().expr();

    // Reserve a local variable slot.
    int localIndex = ga.newLocal(asmT);
    locals.put(currentScope.resolveLocal(name), localIndex);

    if (init != null) {
      Type rhsT = visit(init);
      // Convert numeric values to the declared representation.
      if (!isArrayType(ofpT) && isNumeric(asmT)) {
        coerceNumericIfNeeded(rhsT, asmT);
      }
      ga.storeLocal(localIndex, asmT);
    } else {
      if (isArrayType(ofpT) ||
        asmT.getSort() == Type.OBJECT ||
        asmT.getSort() == Type.ARRAY) {
        ga.visitInsn(ACONST_NULL);
      } else {
        pushDefault(asmT);
      }
      ga.storeLocal(localIndex, asmT);
    }

    return null;
  }

  @Override
  public Type visitAssignStmt(OFPParser.AssignStmtContext ctx) {
    // Store an expression in a scalar or array variable.

    String name = ctx.assignment().ID().getText();
    Symbol s = currentScope.resolve(name);
    if (!(s instanceof VarSymbol) && !(s instanceof ParamSymbol)) {
      throw new RuntimeException("Not a variable: " + name);
    }

    OFPType ofpT = s.getType();
    Type asmT = asmTypeOf(ofpT);
    Type rhsT = visit(ctx.assignment().expr());

    if (!isArrayType(ofpT) && isNumeric(asmT)) {
      coerceNumericIfNeeded(rhsT, asmT);
    }

    storeVariable(name, asmT);
    return null;
  }

  @Override
  public Type visitArrayAssignStmt(OFPParser.ArrayAssignStmtContext ctx) {
    // a[i] = expr;

    String name = ctx.arrayAssign().ID().getText();
    Symbol s = currentScope.resolve(name);
    if (s == null) throw new RuntimeException("Undeclared array: " + name);

    OFPType arrT = s.getType();
    OFPType elemOfp = elementTypeOf(arrT);
    if (elemOfp == null) {
      throw new RuntimeException("Not an array variable: " + name);
    }

    Type arrAsm  = asmTypeOf(arrT);
    Type elemAsm = asmTypeOf(elemOfp);

    // Push the array reference.
    loadVariable(name, arrAsm);

    // Push the index.
    Type idxT = visit(ctx.arrayAssign().expr(0));
    coerceNumericIfNeeded(idxT, Type.INT_TYPE);

    // Push the new element value.
    Type valT = visit(ctx.arrayAssign().expr(1));
    if (isNumeric(elemAsm)) {
      coerceNumericIfNeeded(valT, elemAsm);
    }

    if (elemAsm.equals(Type.INT_TYPE)) {
      g().arrayStore(Type.INT_TYPE);
    } else if (elemAsm.equals(Type.DOUBLE_TYPE)) {
      g().arrayStore(Type.DOUBLE_TYPE);
    } else if (elemAsm.equals(Type.CHAR_TYPE)) {
      g().arrayStore(Type.CHAR_TYPE);
    } else {
      throw new RuntimeException("Unsupported array element type: " + elemAsm);
    }

    return null;
  }

  @Override
  public Type visitReturnStmtRule(OFPParser.ReturnStmtRuleContext ctx) {
    // Emit a typed return value, or return from a void function.

    FunctionSymbol fs = funStack.peek();
    if (fs == null) return null;

    GeneratorAdapter ga = g();
    if (ctx.returnStmt().expr() != null) {
      Type got = visit(ctx.returnStmt().expr());
      Type want = asmTypeOf(fs.getType());
      if (isNumeric(got) && isNumeric(want)) {
        coerceNumericIfNeeded(got, want);
      }
      ga.returnValue();
    } else {
      ga.returnValue();
    }
    return null;
  }

  @Override
  public Type visitIfStmtRule(OFPParser.IfStmtRuleContext ctx) {
    // if (expr) stmtOrBlock [else stmtOrBlock]

    Type condT = visit(ctx.ifStmt().expr());
    ensureBooleanOnStack(condT);

    org.objectweb.asm.Label elseL = new org.objectweb.asm.Label();
    org.objectweb.asm.Label endL  = new org.objectweb.asm.Label();

    // Jump to the else branch when the condition is false.
    g().ifZCmp(GeneratorAdapter.EQ, elseL);

    visit(ctx.ifStmt().stmtOrBlock(0));
    g().goTo(endL);

    // else
    g().mark(elseL);
    if (ctx.ifStmt().stmtOrBlock().size() == 2) {
      visit(ctx.ifStmt().stmtOrBlock(1));
    }
    g().mark(endL);
    return null;
  }

  @Override
  public Type visitWhileStmtRule(OFPParser.WhileStmtRuleContext ctx) {
    // while (expr) stmtOrBlock

    org.objectweb.asm.Label start = new org.objectweb.asm.Label();
    org.objectweb.asm.Label done  = new org.objectweb.asm.Label();

    g().mark(start);
    Type condT = visit(ctx.whileStmt().expr());
    ensureBooleanOnStack(condT);
    g().ifZCmp(GeneratorAdapter.EQ, done);

    visit(ctx.whileStmt().stmtOrBlock());
    g().goTo(start);

    g().mark(done);
    return null;
  }

  @Override
  public Type visitPrintStmtRule(OFPParser.PrintStmtRuleContext ctx) {
    // print(expr); println(expr);

    boolean isPrintln = ctx.printStmt().getChild(0).getText().equals("println");
    GeneratorAdapter ga = g();

    // System.out
    ga.getStatic(Type.getType(System.class),
          "out",
          Type.getType(java.io.PrintStream.class));

    // Push the value to print.
    Type et = visit(ctx.printStmt().expr());

    Method m;
    Type psType = Type.getType(java.io.PrintStream.class);

    // Choose the PrintStream overload for the value type.
    if (et.equals(Type.INT_TYPE)) {
      m = new Method(isPrintln ? "println" : "print",
             Type.VOID_TYPE,
             new Type[]{ Type.INT_TYPE });
    } else if (et.equals(Type.DOUBLE_TYPE)) {
      m = new Method(isPrintln ? "println" : "print",
             Type.VOID_TYPE,
             new Type[]{ Type.DOUBLE_TYPE });
    } else if (et.equals(Type.BOOLEAN_TYPE)) {
      m = new Method(isPrintln ? "println" : "print",
             Type.VOID_TYPE,
             new Type[]{ Type.BOOLEAN_TYPE });
    } else if (et.equals(Type.CHAR_TYPE)) {
      m = new Method(isPrintln ? "println" : "print",
             Type.VOID_TYPE,
             new Type[]{ Type.CHAR_TYPE });
    } else if (et.equals(Type.getType(String.class))) {
      m = new Method(isPrintln ? "println" : "print",
             Type.VOID_TYPE,
             new Type[]{ Type.getType(String.class) });
    } else {
      throw new RuntimeException("Unsupported type in print/println: " + et);
    }

    ga.invokeVirtual(psType, m);
    return null;
  }

  @Override
  public Type visitExprStmtRule(OFPParser.ExprStmtRuleContext ctx) {
    Type result = visit(ctx.exprStmt().expr());
    if (!result.equals(Type.VOID_TYPE)) {
      if (result.getSize() == 2) g().pop2();
      else g().pop();
    }
    return null;
  }

  // Expressions

  @Override
  public Type visitArrayLiteral(OFPParser.ArrayLiteralContext ctx) {
    OFPType arrayType = expressionTypes.get(ctx.getParent());
    Type elementType = asmTypeOf(elementTypeOf(arrayType));
    List<OFPParser.ExprContext> elements = ctx.arrayLit().expr();
    g().push(elements.size());
    g().newArray(elementType);
    for (int i = 0; i < elements.size(); i++) {
      g().dup();
      g().push(i);
      Type actual = visit(elements.get(i));
      coerceNumericIfNeeded(actual, elementType);
      g().arrayStore(elementType);
    }
    return asmTypeOf(arrayType);
  }

  @Override
  public Type visitIntLiteral(OFPParser.IntLiteralContext ctx) {
    int v = Integer.parseInt(ctx.getText());
    g().push(v);
    return Type.INT_TYPE;
  }

  @Override
  public Type visitFloatLiteral(OFPParser.FloatLiteralContext ctx) {
    double d = Double.parseDouble(ctx.getText());
    g().push(d);
    return Type.DOUBLE_TYPE;
  }

  @Override
  public Type visitBoolLiteral(OFPParser.BoolLiteralContext ctx) {
    boolean b = ctx.getText().equals("true");
    g().push(b);
    return Type.BOOLEAN_TYPE;
  }

  @Override
  public Type visitCharLiteral(OFPParser.CharLiteralContext ctx) {
    String s = ctx.getText();
    char c = s.charAt(1);
    g().push(c);
    return Type.CHAR_TYPE;
  }

  @Override
  public Type visitStringLiteral(OFPParser.StringLiteralContext ctx) {
    String raw = ctx.getText();
    String s = raw.substring(1, raw.length() - 1);
    g().push(s);
    return Type.getType(String.class);
  }

  @Override
  public Type visitIdExpr(OFPParser.IdExprContext ctx) {
    String name = ctx.ID().getText();
    Symbol s = currentScope.resolve(name);
    if (s == null) throw new RuntimeException("Undeclared identifier: " + name);

    OFPType ofpT = s.getType();
    Type asmT = asmTypeOf(ofpT);
    loadVariable(name, asmT);
    return asmT;
  }

  @Override
  public Type visitParExpr(OFPParser.ParExprContext ctx) {
    // (expr)
    return visit(ctx.expr());
  }

  @Override
  public Type visitNewArrayExpr(OFPParser.NewArrayExprContext ctx) {
    // new baseType[expr]

    OFPType elemOfp = OFPType.getTypeFor(ctx.baseType().getText());
    Type elemAsm = asmTypeOf(elemOfp);

    Type lenT = visit(ctx.expr());
    coerceNumericIfNeeded(lenT, Type.INT_TYPE);

    if (elemAsm.equals(Type.INT_TYPE)) {
      g().newArray(Type.INT_TYPE);
      return Type.getType(int[].class);
    } else if (elemAsm.equals(Type.DOUBLE_TYPE)) {
      g().newArray(Type.DOUBLE_TYPE);
      return Type.getType(double[].class);
    } else if (elemAsm.equals(Type.CHAR_TYPE)) {
      g().newArray(Type.CHAR_TYPE);
      return Type.getType(char[].class);
    } else {
      throw new RuntimeException("Unsupported new array element type: " + elemAsm);
    }
  }

  @Override
  public Type visitArrayAccessExpr(OFPParser.ArrayAccessExprContext ctx) {
    // Read an array element or string character.

    String name = ctx.ID().getText();
    Symbol s = currentScope.resolve(name);
    if (s == null) throw new RuntimeException("Undeclared array: " + name);

    OFPType arrT = s.getType();

    // String indexing becomes a call to String.charAt(int).
    if (arrT == OFPType.STRING) {
      Type strT = Type.getType(String.class);

      // Push the string reference.
      loadVariable(name, strT);

      // Calculate the character index.
      Type idxT = visit(ctx.expr());
      coerceNumericIfNeeded(idxT, Type.INT_TYPE);

      // Call String.charAt(int).
      Method m = new Method("charAt", Type.CHAR_TYPE, new Type[]{ Type.INT_TYPE });
      g().invokeVirtual(strT, m);

      return Type.CHAR_TYPE;
    }

    OFPType elemOfp = elementTypeOf(arrT);
    if (elemOfp == null) throw new RuntimeException("Not an array: " + name);

    Type arrAsm  = asmTypeOf(arrT);
    Type elemAsm = asmTypeOf(elemOfp);

    // Push the array reference.
    loadVariable(name, arrAsm);

    // Push the index.
    Type idxT = visit(ctx.expr());
    coerceNumericIfNeeded(idxT, Type.INT_TYPE);

    if (elemAsm.equals(Type.INT_TYPE)) {
      g().arrayLoad(Type.INT_TYPE);
    } else if (elemAsm.equals(Type.DOUBLE_TYPE)) {
      g().arrayLoad(Type.DOUBLE_TYPE);
    } else if (elemAsm.equals(Type.CHAR_TYPE)) {
      g().arrayLoad(Type.CHAR_TYPE);
    } else {
      throw new RuntimeException("Unsupported array element type: " + elemAsm);
    }

    return elemAsm;
  }

  @Override
  public Type visitLengthExpr(OFPParser.LengthExprContext ctx) {
    // Read the length of an array or string.
    Type t = visit(ctx.expr());
    if (t.getSort() == Type.ARRAY) {
      g().arrayLength();
      return Type.INT_TYPE;
    }
    if (t.equals(Type.getType(String.class))) {
      Method m = new Method("length", Type.INT_TYPE, new Type[]{});
      g().invokeVirtual(Type.getType(String.class), m);
      return Type.INT_TYPE;
    }
    throw new RuntimeException(".length only valid on arrays and strings");
  }

  @Override
  public Type visitFuncCallExpr(OFPParser.FuncCallExprContext ctx) {

    String fname = ctx.ID().getText();
    Symbol s = currentScope.resolve(fname);
    if (!(s instanceof FunctionSymbol)) {
      throw new RuntimeException("Not a function: " + fname);
    }
    FunctionSymbol fs = (FunctionSymbol) s;

    List<ParamSymbol> ps = fs.getParams();
    Type[] argTypes = new Type[ps.size()];
    for (int i = 0; i < ps.size(); i++) {
      argTypes[i] = asmTypeOf(ps.get(i).getType());
    }

    List<OFPParser.ExprContext> args =
        (ctx.argList() == null) ? Collections.emptyList() : ctx.argList().expr();

    for (int i = 0; i < args.size(); i++) {
      Type got = visit(args.get(i));
      Type want = argTypes[i];
      if (isNumeric(got) && isNumeric(want)) {
        coerceNumericIfNeeded(got, want);
      }
    }

    Method m = new Method(fname, asmTypeOf(fs.getType()), argTypes);
    g().invokeStatic(Type.getObjectType(internalClassName), m);

    return asmTypeOf(fs.getType());
  }

  @Override
  public Type visitUnaryMinusExpr(OFPParser.UnaryMinusExprContext ctx) {

    Type t = visit(ctx.expr());
    if (t.equals(Type.INT_TYPE)) {
      g().math(GeneratorAdapter.NEG, Type.INT_TYPE);
      return Type.INT_TYPE;
    } else {
      coerceNumericIfNeeded(t, Type.DOUBLE_TYPE);
      g().math(GeneratorAdapter.NEG, Type.DOUBLE_TYPE);
      return Type.DOUBLE_TYPE;
    }
  }

  @Override
  public Type visitMulDivExpr(OFPParser.MulDivExprContext ctx) {

    Type res = asmTypeOf(expressionTypes.get(ctx));
    Type lt = visit(ctx.expr(0));
    coerceNumericIfNeeded(lt, res);
    Type rt = visit(ctx.expr(1));
    coerceNumericIfNeeded(rt, res);

    String op = ctx.getChild(1).getText();

    if (res.equals(Type.INT_TYPE)) {
      if ("*".equals(op)) g().math(GeneratorAdapter.MUL, Type.INT_TYPE);
      else                g().math(GeneratorAdapter.DIV, Type.INT_TYPE);
      return Type.INT_TYPE;
    } else {
      if ("*".equals(op)) g().math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
      else                g().math(GeneratorAdapter.DIV, Type.DOUBLE_TYPE);
      return Type.DOUBLE_TYPE;
    }
  }

  @Override
  public Type visitAddSubExpr(OFPParser.AddSubExprContext ctx) {

    // Convert each operand while it is on top of the stack, before emitting the next one.
    Type res = asmTypeOf(expressionTypes.get(ctx));
    Type lt = visit(ctx.expr(0));
    coerceNumericIfNeeded(lt, res);
    Type rt = visit(ctx.expr(1));
    coerceNumericIfNeeded(rt, res);

    String op = ctx.getChild(1).getText();

    if (res.equals(Type.INT_TYPE)) {
      if ("+".equals(op)) g().math(GeneratorAdapter.ADD, Type.INT_TYPE);
      else                g().math(GeneratorAdapter.SUB, Type.INT_TYPE);
      return Type.INT_TYPE;
    } else {
      if ("+".equals(op)) g().math(GeneratorAdapter.ADD, Type.DOUBLE_TYPE);
      else                g().math(GeneratorAdapter.SUB, Type.DOUBLE_TYPE);
      return Type.DOUBLE_TYPE;
    }
  }

  @Override
  public Type visitCompExpr(OFPParser.CompExprContext ctx) {
    // Emit numeric or character comparison, producing a boolean.

    Type lt = visit(ctx.expr(0));
    Type rt = visit(ctx.expr(1));
    String op = ctx.getChild(1).getText();

    Type res = (lt.equals(Type.DOUBLE_TYPE) || rt.equals(Type.DOUBLE_TYPE))
         ? Type.DOUBLE_TYPE
         : Type.INT_TYPE;

    if (isNumeric(lt) && isNumeric(res)) coerceNumericIfNeeded(lt, res);
    if (isNumeric(rt) && isNumeric(res)) coerceNumericIfNeeded(rt, res);

    org.objectweb.asm.Label trueL = new org.objectweb.asm.Label();
    org.objectweb.asm.Label endL  = new org.objectweb.asm.Label();

    if (res.equals(Type.DOUBLE_TYPE)) {
      g().visitInsn(DCMPL);
      if ("<".equals(op))      g().ifZCmp(GeneratorAdapter.LT, trueL);
      else if (">".equals(op)) g().ifZCmp(GeneratorAdapter.GT, trueL);
      else if ("==".equals(op))g().ifZCmp(GeneratorAdapter.EQ, trueL);
      else throw new RuntimeException("Unsupported comparison op: " + op);
    } else {
      if ("<".equals(op))      g().ifICmp(GeneratorAdapter.LT, trueL);
      else if (">".equals(op)) g().ifICmp(GeneratorAdapter.GT, trueL);
      else if ("==".equals(op))g().ifICmp(GeneratorAdapter.EQ, trueL);
      else throw new RuntimeException("Unsupported comparison op: " + op);
    }

    g().push(false);
    g().goTo(endL);
    g().mark(trueL);
    g().push(true);
    g().mark(endL);

    return Type.BOOLEAN_TYPE;
  }

  // Push the default value for a scalar type.
  private void pushDefault(Type t) {
    if (t.equals(Type.INT_TYPE))         g().push(0);
    else if (t.equals(Type.DOUBLE_TYPE)) g().push(0.0);
    else if (t.equals(Type.BOOLEAN_TYPE))g().push(false);
    else if (t.equals(Type.CHAR_TYPE))   g().push('\0');
    else                                 g().visitInsn(ACONST_NULL);
  }

  // Ensure a branch condition uses a boolean representation.
  // Zero represents false; nonzero represents true.

  private void ensureBooleanOnStack(Type onTop) {
    if (onTop.equals(Type.BOOLEAN_TYPE)) return;

    org.objectweb.asm.Label trueL = new org.objectweb.asm.Label();
    org.objectweb.asm.Label endL  = new org.objectweb.asm.Label();

    if (onTop.equals(Type.DOUBLE_TYPE)) {
      g().push(0.0);
      g().math(GeneratorAdapter.SUB, Type.DOUBLE_TYPE);
      g().ifZCmp(GeneratorAdapter.NE, trueL);
    } else if (onTop.equals(Type.INT_TYPE) || onTop.equals(Type.CHAR_TYPE)) {
      g().ifZCmp(GeneratorAdapter.NE, trueL);
    } else {
      throw new RuntimeException("Condition must be bool|int|float|char");
    }

    g().push(false);
    g().goTo(endL);
    g().mark(trueL);
    g().push(true);
    g().mark(endL);
  }

  // Convert a numeric value while it is on top of the operand stack.
  private void coerceNumericIfNeeded(Type from, Type to) {
    if (from == null || to == null) return;
    if (!isNumeric(from) || !isNumeric(to)) return;
    if (from.equals(to)) return;

    if (from.equals(Type.INT_TYPE) && to.equals(Type.DOUBLE_TYPE)) {
      g().cast(Type.INT_TYPE, Type.DOUBLE_TYPE);
    } else if (from.equals(Type.DOUBLE_TYPE) && to.equals(Type.INT_TYPE)) {
      g().cast(Type.DOUBLE_TYPE, Type.INT_TYPE);
    } else {
      throw new RuntimeException("Unsupported numeric cast: " + from + " -> " + to);
    }
  }

  // Load the variable identified by the current lexical scope.
  private void loadVariable(String name, Type t) {
    // Parameters are accessed through method argument positions.
    Symbol symbol = currentScope.resolve(name);
    if (params.containsKey(symbol)) {
      int argIndex = params.get(symbol);
      g().loadArg(argIndex);
      return;
    }
    // Other variables use local slots.
    Integer idx = locals.get(symbol);
    if (idx == null) {
      throw new RuntimeException("Unknown local variable: " + name);
    }
    g().loadLocal(idx, t);
  }

  // Store the top stack value in the resolved variable.
  private void storeVariable(String name, Type t) {
    Symbol symbol = currentScope.resolve(name);
    if (params.containsKey(symbol)) {
      int argIndex = params.get(symbol);
      g().storeArg(argIndex);
      return;
    }
    Integer idx = locals.get(symbol);
    if (idx == null) {
      throw new RuntimeException("Unknown local variable (store): " + name);
    }
    g().storeLocal(idx, t);
  }
}
