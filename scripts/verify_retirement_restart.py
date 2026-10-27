"""CI-only: finish instrumentation, kill the app, verify Application startup cleanup."""
import subprocess

PACKAGE = "org.example.otppager"


def run(*args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        print(result.stdout, flush=True)
        raise RuntimeError("Retirement host command failed: " + " ".join(args))
    return result.stdout


def main():
    # Connected tests may uninstall their APKs. Establish the exact fixture pair explicitly.
    run("adb", "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
    run("adb", "install", "-r", "-t", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
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
