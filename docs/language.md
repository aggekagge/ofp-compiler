# OFP language guide

OFP is a small, statically typed language with C-style blocks and a required
`void main()` entry point. Functions can appear before or after `main`.

## A first program

```ofp
void main() {
    println("Hello, world!");
}
```

Statements end in `;`. Comments start with `#` and continue to the end of the line.
Braces group statements and introduce a lexical scope.

## Types and variables

| Type | Example | Notes |
| --- | --- | --- |
| `int` | `int count = 10;` | JVM target uses signed 32-bit integers |
| `float` | `float factor = 2.5;` | JVM target uses 64-bit `double` |
| `bool` | `bool ready = true;` | `true` and `false` literals |
| `char` | `char letter = 'A';` | Character literals contain one ASCII letter |
| `string` | `string message = "Hello!";` | Read-only indexing and `.length` |
| `int[]`, `float[]`, `char[]` | `int[] values = {1, 2, 3};` | One-dimensional mutable arrays |
| `void` | `void announce() { ... }` | Function return type only |

Assignments, arguments, and return values must match their declared type. Mixing
`int` and `float` in arithmetic produces a `float`; assigning that result to an
`int` is an error.

Scalar variables without initializers receive `0`, `0.0`, `false`, or the null
character as appropriate. Uninitialized strings and arrays hold a null reference;
initialize them before indexing or accessing `.length`.

## Expressions

Arithmetic supports `+`, `-`, `*`, `/`, and unary `-`. Parentheses change precedence.
Integer division truncates toward zero: `7 / 2` is `3`, and `-7 / 2` is `-3`.
Division with a floating-point operand produces a floating-point result.

Comparisons support `<`, `>`, and `==`. Operands must have matching numeric types;
`==` also accepts matching `char` operands. The result is a `bool`.

The grammar does not include `<=`, `>=`, `!=`, logical operators, or string
concatenation.

## Control flow and functions

```ofp
int sumUpTo(int limit) {
    int total = 0;
    int current = 1;
    while (current < limit + 1) {
        total = total + current;
        current = current + 1;
    }
    return total;
}

void main() {
    if (sumUpTo(10) == 55)
        println("Correct!");
    else
        println("Try again.");
}
```

`if` and `while` conditions must be boolean. Each body can be a single statement
or a braced block. Functions support parameters, return values, recursion, and
forward references. `return;` exits a void function, including `main`.

## Arrays and strings

```ofp
void main() {
    int[] values = new int[3];
    values[0] = 7;
    println(values.length);
    println(values[0]);

    string word = "Hello";
    println(word[0]);
}
```

Indices and array sizes must be integers. Arrays can also be initialized with
braced literals, including empty literals where a declared array type supplies
the element type. String elements can be read but cannot be assigned.

`print(value)` leaves the cursor on the same line; `println(value)` appends a
newline. Output supports scalar values and strings. Print array elements in a
loop rather than passing a whole array to `print`.

## Deliberate scope and remaining limitations

- Identifiers contain ASCII letters only. File names can contain underscores.
- String literals allow letters, spaces, and `! . , ? = : ( )`; escapes and digits
  inside string literals are not part of the grammar.
- There are no global variables, classes, imports, input statements, or
  multidimensional arrays.
- The semantic passes do not yet prove definite assignment or that every path in
  a non-void function returns a value. Write explicit returns on all such paths.
- Python integers are unbounded; JVM integers use 32 bits. Overflow behavior is
  not normalized between targets. Array bounds, negative indices, null access,
  and division by zero also follow the target runtime.
- Python prints booleans as `True`/`False`, while Java prints `true`/`false`.
  Tests allow this difference and normal floating-point rounding.
