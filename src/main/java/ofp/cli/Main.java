package ofp.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import ofp.codegen.JvmBytecodeGenerator;
import ofp.codegen.PythonGenerator;
import ofp.compiler.CompilerPipeline;

/** Java entry point; the Python launcher handles building and executing generated programs. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
      usage();
      return;
    }
    if (args.length < 2 || args.length > 3) {
      usage();
      System.exit(2);
    }
    String command = args[0];
    if (!command.equals("check") && !command.equals("python") && !command.equals("jvm")) {
      System.err.println("Unknown command: " + command);
      usage();
      System.exit(2);
    }
    if ((command.equals("check") && args.length != 2) || (!command.equals("check") && args.length != 3)) {
      usage();
      System.exit(2);
    }

    Path source = Path.of(args[1]).toAbsolutePath().normalize();
    if (!Files.isRegularFile(source) || !source.toString().endsWith(".ofp")) {
      System.err.println("Expected an existing .ofp source file: " + source);
      System.exit(2);
    }

    try {
      CompilerPipeline.Analysis analysis = CompilerPipeline.analyze(source);
      if (!analysis.successful()) {
        System.err.printf("Compilation failed: %d error(s).%n", analysis.errors());
        System.exit(1);
      }
      if (command.equals("check")) {
        System.out.println("OK: " + source.getFileName() + " (0 errors)");
        return;
      }

      Path output = Path.of(args[2]).toAbsolutePath().normalize();
      if (output.equals(source)) {
        System.err.println("Output path must differ from the source file.");
        System.exit(2);
      }
      if (command.equals("python")) {
        if (!output.toString().endsWith(".py")) throw new IllegalArgumentException("Python output must end in .py");
        String code = new PythonGenerator(analysis.scopes(), analysis.expressionTypes()).visit(analysis.program());
        Files.createDirectories(output.getParent());
        Files.writeString(output, code, StandardCharsets.UTF_8);
      } else {
        String filename = output.getFileName().toString();
        if (!filename.endsWith(".class")) throw new IllegalArgumentException("JVM output must end in .class");
        String className = filename.substring(0, filename.length() - 6);
        if (!className.matches("[A-Za-z_][A-Za-z0-9_]*")) {
          throw new IllegalArgumentException("JVM output filename must be a simple class name");
        }
        byte[] bytecode = new JvmBytecodeGenerator(analysis.scopes(), analysis.expressionTypes())
            .generate(analysis.program(), className);
        Files.createDirectories(output.getParent());
        Files.write(output, bytecode);
      }
      System.out.println("Generated: " + output);
    } catch (IOException | IllegalArgumentException exception) {
      System.err.println("Error: " + exception.getMessage());
      System.exit(2);
    }
  }

  private static void usage() {
    System.out.println("Usage: java ofp.cli.Main check <source.ofp>");
    System.out.println("       java ofp.cli.Main python <source.ofp> <output.py>");
    System.out.println("       java ofp.cli.Main jvm <source.ofp> <Output.class>");
    System.out.println("Or use: python ofp.py --help");
  }
}
