"""Exclusive lifecycle fault/restart matrix on disposable CI emulator; clears app fixtures only."""
from verify_retirement_restart import PACKAGE, run


def main():
    scenarios = (
        ("sealed-seed", "sealed-verify", "restored", "key-only"),
        ("submit-seed", "submit-verify"),
        ("failure-seed", "failure-verify"),
        ("intent-seed", "intent-verify"),
        ("scope-seed", "scope-verify"),
        ("key-seed", "key-verify"),
    )
    for phases in scenarios:
        # Prior global fixture deliberately leaves the app frozen. No production reset API is added.
        output = run("adb", "shell", "pm", "clear", PACKAGE)
        if "Success" not in output:
            raise RuntimeError("Could not clear disposable lifecycle fixture")
        for phase in phases:
            output = run("adb", "shell", "am", "instrument", "-w", "-e", "class",
                         PACKAGE + ".LifecycleEvidenceRestartTest", "-e", "lifecyclePhase", phase,
                         PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
            print(output)
            if "OK (1 test)" not in output or any(s in output for s in ("FAILURES", "INSTRUMENTATION_FAILED")):
                raise RuntimeError("Lifecycle evidence fixture failed: " + phase)
            run("adb", "shell", "am", "force-stop", PACKAGE)
    print("PASS: authenticated pre-cleanup capture, sealed restart, real submit, failed/interrupted capture, old-guard refusal, key-only freeze and scope-growth cleanup")


if __name__ == "__main__":
    main()
