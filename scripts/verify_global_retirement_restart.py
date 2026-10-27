"""CI-only destructive test on the disposable emulator, after all ordinary app tests."""
from verify_retirement_restart import PACKAGE, run


def verify():
    run("adb", "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
    run("adb", "install", "-r", "-t", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    for phase in ("seed", "verify", "restored", "malformed"):
        output = run("adb", "shell", "am", "instrument", "-w", "-e", "class",
                     PACKAGE + ".GlobalRetirementRestartTest", "-e", "globalRetirementPhase", phase,
                     PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        print(output)
        if "OK (1 test)" not in output or any(s in output for s in ("FAILURES", "INSTRUMENTATION_FAILED")):
            raise RuntimeError("Global retirement fixture failed: " + phase)
        run("adb", "shell", "am", "force-stop", PACKAGE)
    print("PASS: global cold-start/partial cleanup, corrupt-index/key-only orphans, blocked-worker isolation, restored-state sweep malformed-journal freeze and verified recovery UI")


if __name__ == "__main__":
    try:
        verify()
    finally:
        # These exclusive screens are captured after connected tests' ordinary artifact pull.
        import subprocess
        subprocess.run(["adb", "pull", "/sdcard/Download/otp-pager-preview", "app/build/global-recovery-screenshots"], check=False)
