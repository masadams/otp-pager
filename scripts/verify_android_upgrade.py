"""CI-only: two release APKs, one disposable signer, real install -r, no owner secrets."""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from android_signing import ALIAS, PACKAGE, build_release, certificate, generate, inspect_apk, run, sdk_tool, verify_update


def instrument(phase):
    output = run(["adb", "shell", "am", "instrument", "-w", "-e", "class", f"{PACKAGE}.UpgradeStateTest",
                  "-e", "upgradePhase", phase, f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"])
    print(output)
    if "OK (1 test)" not in output or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
        raise RuntimeError(f"Upgrade fixture {phase} did not pass")


def rejected_install(apk, reason):
    result = subprocess.run(["adb", "install", "-r", str(apk)], capture_output=True, text=True)
    if result.returncode == 0 or reason not in result.stdout + result.stderr:
        raise RuntimeError(f"Expected Android to reject installation: {reason}")
    print(f"Android correctly rejected {reason}")


def main():
    password = base64.urlsafe_b64encode(os.urandom(32)).decode()
    with tempfile.TemporaryDirectory(prefix="otp-upgrade-test-") as folder:
        root = Path(folder)
        store = root / "test.p12"; generate(store, password)
        signer = certificate(store, password)
        env = dict(os.environ, OTP_SIGNING_STORE_FILE=str(store), OTP_SIGNING_STORE_PASSWORD=password)
        # Exercise the exact production signing/verification path with disposable credentials.
        production_env = dict(OTP_SIGNING_KEYSTORE_B64=base64.b64encode(store.read_bytes()).decode(),
                              OTP_SIGNING_PASSWORD=password, OTP_SIGNING_CERT_SHA256=signer)
        prior_env = {key: os.environ.get(key) for key in production_env}
        try:
            os.environ.update(production_env)
            build_release()
        finally:
            for key, value in prior_env.items():
                if value is None: os.environ.pop(key, None)
                else: os.environ[key] = value
        # Preserve the newly built release, then install the actual previous v0.13 source
        # under the same disposable signer. No owner signing material enters CI.
        second_apk = root / "second.apk"
        shutil.copyfile("app/build/outputs/apk/release/app-release.apk", second_apk)
        baseline = root / "baseline"
        run(["git", "worktree", "add", "--detach", baseline, "382a83811721d2665bb15a3ac6526e9dd4cf74c7"])
        subprocess.run(["gradle", "--no-daemon", "-p", str(baseline), ":app:assembleRelease"], env=env, check=True)
        first_apk = root / "first.apk"
        shutil.copyfile(baseline / "app/build/outputs/apk/release/app-release.apk", first_apk)
        initial_version = inspect_apk(first_apk)["version_code"]
        subprocess.run(["gradle", "--no-daemon", "-PupgradeVerification=true", f"-PverificationVersionCode={initial_version}",
                        ":app:assembleReleaseAndroidTest"], env=env, check=True)
        run(["adb", "install", first_apk])
        run(["adb", "install", "-t", "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"])
        instrument("seed")
        info = inspect_apk(second_apk)
        # Updates only need a strictly higher versionCode; releases may skip numbers.
        assert info["version_code"] > initial_version and info["certificate_sha256"] == signer
        apks = [first_apk, second_apk]
        verify_update(apks[0], apks[1], signer)
        run(["adb", "shell", "am", "force-stop", PACKAGE])
        run(["adb", "install", "-r", apks[1]])
        # Negative tests must leave the seeded state intact too.
        rejected_install(apks[0], "INSTALL_FAILED_VERSION_DOWNGRADE")
        other = root / "wrong.p12"; generate(other, password)
        wrong = root / "wrong-signer.apk"
        sign_env = dict(os.environ, OTP_TEST_PASSWORD=password)
        run([sdk_tool("apksigner"), "sign", "--ks", other, "--ks-key-alias", ALIAS,
             "--ks-pass", "env:OTP_TEST_PASSWORD", "--out", wrong, apks[1]], env=sign_env)
        rejected_install(wrong, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        instrument("verify")
        target = Path("app/build/upgrade-verification"); target.mkdir(exist_ok=True)
        (target / "result.txt").write_text("PASS: non-debuggable release install -r preserved chat index, encrypted vaults, Keystore identity, peer pin, replay state, queued ciphertext, pad ledger and claims. Wrong signer and downgrade were rejected. New sends continued above the reserved counter. Disposable CI signer only.\n")


if __name__ == "__main__":
    main()
