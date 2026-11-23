"""End-to-end tests for the frontend, generated Python, and generated JVM classes."""

import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ofp


def execute(command):
    try:
        result = subprocess.run(command, cwd=ofp.ROOT, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=30)
        return {"command": command, "exit_code": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr}
    except subprocess.TimeoutExpired:
        return {"command": command, "exit_code": None, "stdout": "",
                "stderr": "Timed out after 30 seconds"}


def output_matches(actual, expected):
    actual_lines, expected_lines = actual.splitlines(), expected.splitlines()
    if len(actual_lines) != len(expected_lines):
        return False
    for actual_line, expected_line in zip(actual_lines, expected_lines):
        if actual_line == expected_line:
            continue
        if expected_line in ("true", "false") and actual_line.lower() == expected_line:
            continue
        try:
            if math.isclose(float(actual_line), float(expected_line), rel_tol=1e-9, abs_tol=1e-9):
                continue
        except ValueError:
            pass
        return False
    return True


def main():
    valid = sorted((ofp.ROOT / "examples").glob("*.ofp"))
    valid += sorted((ofp.ROOT / "tests/valid").glob("*.ofp"))
    invalid = sorted((ofp.ROOT / "tests/invalid").glob("*.ofp"))
    if not valid or not invalid:
        print("Expected both valid programs and invalid test fixtures.", file=sys.stderr)
        return 2
    for source in valid:
        if not (ofp.ROOT / "tests/expected" / (source.stem + ".txt")).is_file():
            print(f"Missing expected output for {source.name}", file=sys.stderr)
            return 2
    for source in invalid:
        if not source.with_suffix(".txt").is_file():
            print(f"Missing expected diagnostics for {source.name}", file=sys.stderr)
            return 2

    print("Building from source, including a freshly generated ANTLR parser...", flush=True)
    build = ofp.build_project(force=True, quiet=True)
    test_parent = ofp.BUILD_ROOT / "test-runs"
    test_parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=test_parent))
    report = {"build": str(build.directory), "artifacts": str(work), "checks": []}

    def record(source, stage, results, problem=""):
        check = {"file": source.relative_to(ofp.ROOT).as_posix(), "stage": stage,
                 "passed": not problem, "problem": problem, "processes": results}
        report["checks"].append(check)
        print(f"{'FAIL' if problem else 'PASS'} {stage:14} {check['file']}", flush=True)
        if problem:
            for line in problem.splitlines()[:8]:
                print("     " + line)

    for source in valid:
        expected = (ofp.ROOT / "tests/expected" / (source.stem + ".txt")).read_text(encoding="utf-8")
        checked = execute(ofp.compiler_command(build, "check", source))
        problem = "" if checked["exit_code"] == 0 and not checked["stderr"] else (
            checked["stderr"] or "Frontend rejected a valid program.")
        record(source, "check", [checked], problem)
        for backend in ("python", "jvm"):
            output = work / ofp.default_output(source, backend).name
            generated = execute(ofp.compiler_command(build, backend, source, output))
            processes = [generated]
            problem = ""
            if generated["exit_code"] != 0 or generated["stderr"] or not output.is_file():
                problem = generated["stderr"] or "No output file generated."
            else:
                execution = execute(ofp.program_command(output, backend))
                processes.append(execution)
                if execution["exit_code"] != 0 or execution["stderr"]:
                    problem = execution["stderr"] or "Generated program failed."
                elif not output_matches(execution["stdout"], expected):
                    problem = f"Expected {expected!r}, got {execution['stdout']!r}"
            record(source, backend, processes, problem)

    for source in invalid:
        diagnostics = source.with_suffix(".txt").read_text(encoding="utf-8").splitlines()
        for action in ("check", "python", "jvm"):
            output = None if action == "check" else work / ("invalid_" + ofp.default_output(source, action).name)
            result = execute(ofp.compiler_command(build, action, source, output))
            problem = ""
            if result["exit_code"] != 1:
                problem = f"Expected compilation exit code 1, got {result['exit_code']}: {result['stderr']}"
            elif any(fragment not in result["stderr"] for fragment in diagnostics if fragment):
                problem = "Missing expected diagnostics:\n" + result["stderr"]
            elif output is not None and output.exists():
                problem = "Invalid input produced an output artifact."
            else:
                summary = re.search(r"Compilation failed: (\d+) error\(s\)", result["stderr"])
                actual = len(re.findall(r"^(?:Syntax|SymbolTable|Semantic|Type) error", result["stderr"], re.MULTILINE))
                if summary is None or int(summary[1]) != actual:
                    problem = "Error summary does not match the emitted diagnostics."
            record(source, "reject/" + action, [result], problem)

    passed = sum(check["passed"] for check in report["checks"])
    failed = len(report["checks"]) - passed
    report["summary"] = {"passed": passed, "failed": failed}
    report_path = ofp.BUILD_ROOT / "test-results.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\n{passed} passed, {failed} failed. Report: {report_path}")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ofp.BuildError, OSError) as exception:
        print(f"Test setup failed: {exception}", file=sys.stderr)
        sys.exit(2)
