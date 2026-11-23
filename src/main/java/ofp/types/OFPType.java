package ofp.types;
import java.util.HashMap;
import java.util.Map;

/** Interned source-language types; semantic passes compare these singleton instances. */
public final class OFPType {
  private static final Map<String,OFPType> INTERN = new HashMap<>();
  public static final OFPType INT = of("int");
  public static final OFPType FLOAT = of("float");
  public static final OFPType BOOL = of("bool");
  public static final OFPType CHAR = of("char");
  public static final OFPType STRING = of("string");
  public static final OFPType INT_ARR = of("int[]");
  public static final OFPType FLOAT_ARR = of("float[]");
  public static final OFPType CHAR_ARR = of("char[]");
  public static final OFPType VOID = of("void");

  public static OFPType of(String name) {
    return INTERN.computeIfAbsent(name, OFPType::new);
  }
  public static OFPType getTypeFor(String typeName) {
    return INTERN.get(typeName);
  }

  private final String name;
  private OFPType(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  @Override public String toString() {
    return name;
  }
}
