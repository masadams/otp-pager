"""Exclusive fresh-epoch lifecycle matrix. Only the disposable emulator's synthetic app data is cleared."""
from verify_retirement_restart import PACKAGE, run


def main():
    scenarios = (
        ("cycle-seed", "cycle-verify", "cycle-verify2", "tamper-verify"),
        ("interrupt-seed", "interrupt-verify"),
        ("intent-seed", "intent-verify"),
    )
    for phases in scenarios:
        if "Success" not in run("adb", "shell", "pm", "clear", PACKAGE):
            raise RuntimeError("Could not clear epoch fixture")
        for phase in phases:
            output = run("adb", "shell", "am", "instrument", "-w", "-e", "class",
                         PACKAGE + ".EpochCycleRestartTest", "-e", "epochPhase", phase,
                         PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
            print(output)
            if "OK (1 test)" not in output or any(s in output for s in ("FAILURES", "INSTRUMENTATION_FAILED")):
                raise RuntimeError("Epoch lifecycle fixture failed: " + phase)
            run("adb", "shell", "am", "force-stop", PACKAGE)
    print("PASS: two full wipe/fresh-epoch cycles refusing all earlier pads and pairings, interrupted activation, "
          "interrupted epoch wipe, tampered manifest frozen without destruction")


if __name__ == "__main__":
    main()
