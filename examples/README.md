# Example programs

Start with `hello_world.ofp`, then try `language_overview.ofp` and the focused
examples below. Run any example with:

```sh
python ofp.py run examples/recursion.ofp
python ofp.py run examples/recursion.ofp --backend python
```

| Example | Demonstrates |
| --- | --- |
| [hello_world.ofp](hello_world.ofp) | Entry point and console output |
| [language_overview.ofp](language_overview.ofp) | Scalars, arrays, conditions, loops, and mixed arithmetic |
| [arithmetic.ofp](arithmetic.ofp) | Precedence, parentheses, and numeric expressions |
| [floating_point.ofp](floating_point.ofp) | Floating-point functions and comparisons |
| [booleans.ofp](booleans.ofp) | Boolean parameters and conditional returns |
| [functions.ofp](functions.ofp) | Functions and nested `if`/`else` branches |
| [loops.ofp](loops.ofp) | Accumulating a sum with a `while` loop |
| [recursion.ofp](recursion.ofp) | Recursive multiplication, division, and Fibonacci |
| [arrays.ofp](arrays.ofp) | Reversing arrays, finding a maximum, and selection sort |
| [strings.ofp](strings.ofp) | String indexing and character-array mutation |

Expected stdout for every example lives in `tests/expected/` and is checked
against both generated backends by `python ofp.py test`.
