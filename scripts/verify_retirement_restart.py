"""CI-only: finish instrumentation, kill the app, verify Application startup cleanup."""
import subprocess

PACKAGE = "org.example.otppager"


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def main():
    for phase in ("seed", "verify"):
        output = run("adb", "shell", "am", "instrument", "-w", "-e", "class",
                     PACKAGE + ".RetirementRestartTest", "-e", "retirementPhase", phase,
                     PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        print(output)
        if "OK (1 test)" not in output or any(s in output for s in ("FAILURES", "INSTRUMENTATION_FAILED")):
            raise RuntimeError("Retirement restart fixture failed: " + phase)
        run("adb", "shell", "am", "force-stop", PACKAGE)
    print("PASS: cold Application startup retired keys/files before vault access; other contact and reuse metadata preserved")


if __name__ == "__main__":
    main()
