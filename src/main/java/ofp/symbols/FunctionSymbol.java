package ofp.symbols;
import ofp.types.OFPType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A function signature: its declared return type and ordered parameter list. */
public class FunctionSymbol extends Symbol {
  private final List<ParamSymbol> params = new ArrayList<>();
  public FunctionSymbol(String name, OFPType returnType) {
    super(name, returnType);
  }
  public void addParam(ParamSymbol parameter) {
    params.add(parameter);
  }
  public List<ParamSymbol> getParams() {
    return Collections.unmodifiableList(params);
  }
}
