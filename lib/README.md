# Bundled build dependencies

These JARs were supplied with the original coursework and are kept here so the
project can build without a dependency manager or downloads.

| File | Purpose |
| --- | --- |
| `antlr-4.13.1-complete.jar` | Generate Java lexer, parser, listener, and visitor sources from the grammar |
| `antlr4-runtime-4.13.1.jar` | Runtime classes used by the generated parser |
| `asm-all-5.0.1.jar` | Generate JVM class files using ASM and `GeneratorAdapter` |

ANTLR and ASM are third-party projects. Their versions and titles can be inspected
in each JAR's `META-INF/MANIFEST.MF`. The compiler's Java sources are under
`src/main/java/`; generated ANTLR code belongs under `build/`.
