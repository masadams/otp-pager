"""Exclusive empty-storage staging matrix. Only the disposable emulator's synthetic app data is cleared."""
from verify_retirement_restart import PACKAGE, run


def main():
    scenarios = (
        ("prepared-seed", "prepared-verify", "interrupted-verify", "rollback-verify"),
        ("proof-change",),
        ("orphan-seed", "orphan-verify"),
        ("tamper-seed", "tamper-verify"),
        ("variants-seed", "variants-verify"),
        ("partial-seed", "partial-verify"),
        ("unknown-seed", "unknown-verify"),
    )
    for phases in scenarios:
        if "Success" not in run("adb", "shell", "pm", "clear", PACKAGE):
            raise RuntimeError("Could not clear staging fixture")
        for phase in phases:
            output = run("adb", "shell", "am", "instrument", "-w", "-e", "class",
                         PACKAGE + ".EnrollmentStageRestartTest", "-e", "enrollmentPhase", phase,
                         PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
            print(output)
            if "OK (1 test)" not in output or any(s in output for s in ("FAILURES", "INSTRUMENTATION_FAILED")):
                raise RuntimeError("Enrollment staging fixture failed: " + phase)
            run("adb", "shell", "am", "force-stop", PACKAGE)
    print("PASS: confirmation-bound empty staging, prepared restart, abandonment/new generation, interrupted and restored attempts, orphan/tamper/variant refusal; no activation")


if __name__ == "__main__":
    main()
