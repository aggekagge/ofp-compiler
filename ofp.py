#!/usr/bin/env python3
"""Build, check, compile, and run OFP programs without third-party Python packages."""

import argparse
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent
BUILD_ROOT = ROOT / "build"
GRAMMAR = ROOT / "src/main/antlr/OFP.g4"
LIBRARIES = (
    ROOT / "lib/antlr-4.13.1-complete.jar",
    ROOT / "lib/antlr4-runtime-4.13.1.jar",
    ROOT / "lib/asm-all-5.0.1.jar",
)


class BuildError(RuntimeError):
    """An actionable build failure that can be shown without a Python traceback."""


@dataclass(frozen=True)
class Build:
    directory: Path
    classes: Path

    @property
    def classpath(self):
        return os.pathsep.join(str(path) for path in (self.classes, *LIBRARIES[1:]))


def build_project(force=False, quiet=False):
    """Generate the parser and compile Java; reuse only a matching, completed build."""
    for tool in ("java", "javac"):
        if shutil.which(tool) is None:
            raise BuildError(f"Missing {tool}. Install JDK 17 or newer and add its bin directory to PATH.")
    for library in LIBRARIES:
        if not library.is_file():
            raise BuildError(f"Missing bundled dependency: {library.relative_to(ROOT)}")

    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    fingerprint = hashlib.sha256()
    for path in (Path(__file__).resolve(), GRAMMAR, *sources, *LIBRARIES):
        fingerprint.update(path.relative_to(ROOT).as_posix().encode())
        fingerprint.update(path.read_bytes())
    fingerprint.update(str(shutil.which("javac")).encode())
    build_parent = BUILD_ROOT / "compiler"
    build_parent.mkdir(parents=True, exist_ok=True)
    build_id = fingerprint.hexdigest()[:16]
    directory = build_parent / build_id
    if force:
        directory = Path(tempfile.mkdtemp(prefix=build_id + "-", dir=build_parent))
    build = Build(directory, directory / "classes")
    marker = directory / ".complete"
    if marker.is_file() and (build.classes / "ofp/cli/Main.class").is_file():
        _update_editor_sources(directory / "generated-src/ofp/parser")
        return build

    if not quiet:
        print("Building OFP (ANTLR parser + Java compiler)...", file=sys.stderr, flush=True)
    generated = directory / "generated-src/ofp/parser"
    generated.mkdir(parents=True, exist_ok=True)
    build.classes.mkdir(parents=True, exist_ok=True)
    _build_step(["java", "-jar", LIBRARIES[0], "-Dlanguage=Java", "-visitor", "-listener",
                 "-package", "ofp.parser", "-Xexact-output-dir", "-o", generated, GRAMMAR])
    generated_sources = sorted(generated.glob("*.java"))
    _build_step(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", build.classpath,
                 "-d", build.classes, *generated_sources, *sources])
    marker.write_text("Generated from the grammar and Java sources.\n", encoding="utf-8")
    _update_editor_sources(generated)
    return build


def _update_editor_sources(generated):
    """Expose one stable source directory to VS Code without adding generated files to Git."""
    editor_sources = BUILD_ROOT / "generated-src/ofp/parser"
    editor_sources.mkdir(parents=True, exist_ok=True)
    for source in generated.glob("*.java"):
        shutil.copy2(source, editor_sources / source.name)


def _build_step(command):
    try:
        result = subprocess.run([str(arg) for arg in command], cwd=ROOT, capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=60)
    except subprocess.TimeoutExpired as exception:
        raise BuildError("Build step timed out after 60 seconds.") from exception
    if result.returncode:
        raise BuildError((result.stderr or result.stdout).strip())


def compiler_command(build, action, source, output=None):
    command = ["java", "-cp", build.classpath, "ofp.cli.Main", action, str(source)]
    if output is not None:
        command.append(str(output))
    return command


def default_output(source, backend):
    directory = BUILD_ROOT / "programs"
    if backend == "python":
        return directory / (source.stem + ".py")
    class_name = "OFP_" + re.sub(r"[^A-Za-z0-9_]", "_", source.stem)
    return directory / (class_name + ".class")


def program_command(output, backend):
    if backend == "python":
        return [sys.executable, str(output)]
    return ["java", "-cp", str(output.parent), output.stem]


def main():
    parser = argparse.ArgumentParser(description="OFP: a small language compiler with Python and JVM backends.")
    commands = parser.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build", help="generate the parser and compile Java")
    build_parser.add_argument("--rebuild", action="store_true", help="force a fresh build")
    commands.add_parser("test", help="build from scratch and run the regression suite")
    check_parser = commands.add_parser("check", help="check syntax, names, and types")
    check_parser.add_argument("source", type=Path)
    for name, description in (("compile", "generate Python source or a JVM class"),
                              ("run", "compile and execute a program")):
        command_parser = commands.add_parser(name, help=description)
        command_parser.add_argument("source", type=Path)
        command_parser.add_argument("--backend", choices=("jvm", "python"), default="jvm")
        if name == "compile":
            command_parser.add_argument("-o", "--output", type=Path, help="output file (default: build/programs/)")
    args = parser.parse_args()

    if args.command == "test":
        return subprocess.run([sys.executable, str(ROOT / "tests/run_tests.py")], cwd=ROOT).returncode
    if hasattr(args, "source"):
        args.source = args.source.resolve()
        if not args.source.is_file() or args.source.suffix != ".ofp":
            parser.error(f"expected an existing .ofp file: {args.source}")
    try:
        build = build_project(force=getattr(args, "rebuild", False))
        if args.command == "build":
            print(f"Build ready: {build.classes.relative_to(ROOT)}")
            return 0
        if args.command == "check":
            return subprocess.run(compiler_command(build, "check", args.source)).returncode

        output = (getattr(args, "output", None) or default_output(args.source, args.backend)).resolve()
        result = subprocess.run(compiler_command(build, args.backend, args.source, output),
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
        if result.stderr:
            print(result.stderr, end="", file=sys.stderr)
        if result.returncode:
            return result.returncode
        if args.command == "compile":
            print(result.stdout, end="")
            return 0
        return subprocess.run(program_command(output, args.backend)).returncode
    except (BuildError, OSError) as exception:
        print(f"Error: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
