package ofp.symbols;
import java.util.LinkedHashMap;
import java.util.Map;

/** A lexical scope with declarations local to it and a link to its enclosing scope. */
public class Scope {
  private final Scope enclosingScope;
  private final Map<String, Symbol> symbols = new LinkedHashMap<>();

  public Scope(Scope enclosingScope) {
    this.enclosingScope = enclosingScope;
  }

  public Scope getEnclosingScope() {
    return enclosingScope;
  }

  // Return false when the name is already declared in this scope.
  public boolean define(Symbol symbol) {
    return symbols.putIfAbsent(symbol.getName(), symbol) == null;
  }

  public Symbol resolveLocal(String name) {
    return symbols.get(name);
  }

  public Symbol resolve(String name) {
    Symbol symbol = symbols.get(name);
    if (symbol != null) return symbol;
    return (enclosingScope == null) ? null : enclosingScope.resolve(name);
  }

  @Override public String toString() {
    return symbols.values().toString();
  }
}
