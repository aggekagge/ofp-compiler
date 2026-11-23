package ofp.symbols;
import ofp.types.OFPType;

/** Common name and type information shared by variables, parameters, and functions. */
public class Symbol {
  protected final String name;
  protected final OFPType type;
  public Symbol(String name, OFPType type) {
    this.name = name;
    this.type = type;
  }
  public String getName() {
    return name;
  }
  public OFPType getType() {
    return type;
  }
  @Override public String toString() {
    return name + ":" + type;
  }
}
