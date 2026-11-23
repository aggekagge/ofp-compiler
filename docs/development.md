# Development

## Requirements

- JDK 17 or newer, with both `java` and `javac` available on `PATH`.
- Python 3.11 or newer, available as `python` (or use `python3` in the commands).

The build uses the JAR files in `lib/`. It needs no Maven, Gradle, Python packages,
or dependency downloads.

## Build and run

```sh
python ofp.py build
python ofp.py check examples/language_overview.ofp
python ofp.py run examples/recursion.ofp
python ofp.py run examples/recursion.ofp --backend python
```

The default backend is `jvm`. Builds are reused when their inputs match. After
changing the installed JDK in place, use `python ofp.py build --rebuild` to force a
fresh build.

To inspect the generated files:

```sh
python ofp.py compile examples/hello_world.ofp --backend python -o build/demo/hello.py
python ofp.py compile examples/hello_world.ofp --backend jvm -o build/demo/Hello.class
python build/demo/hello.py
java -cp build/demo Hello
```

JVM output filenames must be simple class names followed by `.class`.

## Tests

```sh
python ofp.py test
```

The suite regenerates the parser and compiles Java in a new build directory. For
each example and valid regression fixture, it checks the frontend and executes
both generated targets against a saved output file. For each invalid fixture, it
checks all three compiler commands, expected diagnostics, the error total, the
exit code, and the absence of generated output.

Each test process has a 30-second timeout. Full commands, output, and diagnostics
are saved to `build/test-results.json`; generated programs remain under
`build/test-runs/` for inspection.

To add a regression case:

1. Add a valid program to `tests/valid/` and its expected stdout to
   `tests/expected/<same-name>.txt`.
2. For invalid input, add a program to `tests/invalid/` and diagnostic fragments,
   one per line, to `tests/invalid/<same-name>.txt`.
3. Run the test suite. New fixtures are discovered automatically.

The GitHub Actions workflow is configured to run the suite on Windows and Linux
with JDK 17 and Python 3.11. The workflow is defined in
[`ci.yml`](../.github/workflows/ci.yml).

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Build/check/compilation succeeded, or tests all passed |
| `1` | Source validation failed, or a test failed |
| `2` | Incorrect arguments, missing tools/files, or build/setup failure |

`run` returns the generated program's exit code after successful compilation.

## VS Code

Open `OFP.code-workspace` and run **OFP: build** once. Java sources and generated
parser sources are configured in the workspace. Build, test, and demo tasks are
included. The optional ANTLR extension can use **Debug OFP parse tree** to inspect
`examples/strings.ofp`; change its input path to inspect another program.

## Repository hygiene

The repository contains the current compiler, examples, tests, and documentation.
Old assignment copies and temporary verification checkouts have been removed.
`build/`, Python caches, compiled classes, and operating-system metadata are ignored
by Git. The launcher recreates generated parser sources and other build products
when needed, so they do not need to be included in a commit.

The three JAR files in `lib/` are intentional dependencies and should be included
in a checkout. The project originated as university coursework; keep that context
when describing it and credit collaborators for their own contributions.
