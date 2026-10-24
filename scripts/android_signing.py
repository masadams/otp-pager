"""Owner-run key setup and fail-closed Android release tooling. Never prints key/password bytes."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

REPO = "masadams/otp-pager"
ALIAS = "otp-pager"
PACKAGE = "org.example.otppager"


def run(args, *, env=None, data=None, binary=False):
    args = list(map(str, args))
    if os.name == "nt" and Path(args[0]).name == "apksigner.bat":
        # Avoid command-shell interpretation of APK paths on Windows.
        args = ["java", "-jar", str(Path(args[0]).parent / "lib" / "apksigner.jar"), *args[1:]]
    result = subprocess.run(args, input=data, capture_output=True, text=not binary, env=env)
    if result.returncode:
        # Tool diagnostics may contain supplied configuration. Never echo captured output here.
        raise RuntimeError(f"{Path(str(args[0])).name} failed (exit {result.returncode})")
    return result.stdout


def private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Signing directory must not be a symbolic link")
    if os.name == "nt":
        owner = os.environ.get("USERDOMAIN", "") + "\\" + os.environ["USERNAME"]
        run(["icacls", path, "/inheritance:r", "/grant:r", f"{owner}:(OI)(CI)F"])
    else:
        path.chmod(0o700)


def write_private(path, data):
    # Exclusive creation prevents accidentally replacing the only copy of a signing key.
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def certificate(store, password):
    env = dict(os.environ, OTP_KEYTOOL_PASSWORD=password)
    der = run(["keytool", "-exportcert", "-keystore", store, "-storetype", "PKCS12", "-alias", ALIAS,
               "-storepass:env", "OTP_KEYTOOL_PASSWORD"], env=env, binary=True)
    return hashlib.sha256(der).hexdigest()


def generate(store, password):
    if store.exists():
        raise ValueError("Refusing to replace an existing signing key")
    env = dict(os.environ, OTP_KEYTOOL_PASSWORD=password)
    run(["keytool", "-genkeypair", "-noprompt", "-keystore", store, "-storetype", "PKCS12", "-alias", ALIAS,
         "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "12000",
         "-dname", "CN=OTP Pager Release", "-storepass:env", "OTP_KEYTOOL_PASSWORD",
         "-keypass:env", "OTP_KEYTOOL_PASSWORD"], env=env)
    if os.name != "nt":
        store.chmod(0o600)


def fingerprint(value):
    value = value.strip().lower().replace(":", "")
    if not re.fullmatch(r"[a-f0-9]{64}", value):
        raise ValueError("A pinned SHA-256 signing certificate fingerprint is required")
    return value


def backup_note(store, password):
    value = {"format": "otp-pager-signer-v1", "pkcs12_base64": base64.b64encode(store.read_bytes()).decode(),
             "password": password, "certificate_sha256": certificate(store, password)}
    note = json.dumps(value, separators=(",", ":"))
    if len(note) > 6000:
        raise ValueError("Key backup exceeds the compact note bound; keep the original files instead")
    return note


def restore_backup(source, directory):
    if source.stat().st_size > 6000:
        raise ValueError("Signing backup is too large")
    value = json.loads(source.read_text())
    if (not isinstance(value, dict) or value.get("format") != "otp-pager-signer-v1"
            or not isinstance(value.get("password"), str) or len(value["password"]) < 32
            or not isinstance(value.get("pkcs12_base64"), str) or not isinstance(value.get("certificate_sha256"), str)):
        raise ValueError("Invalid signing backup")
    data = base64.b64decode(value["pkcs12_base64"], validate=True)
    expected = fingerprint(value["certificate_sha256"])
    # Check before creating the destination; never replace a saved owner key.
    with tempfile.TemporaryDirectory(prefix="otp-restore-check-") as folder:
        temporary = Path(folder) / "release.p12"; write_private(temporary, data)
        if certificate(temporary, value["password"]) != expected:
            raise ValueError("Backup certificate does not match the key")
    if directory.exists() and any(directory.iterdir()):
        raise ValueError("Restore requires an empty private directory; existing material is never overwritten")
    private_directory(directory)
    write_private(directory / "release.p12", data)
    write_private(directory / "password.txt", value["password"].encode())
    write_private(directory / "bitwarden-backup.txt", source.read_bytes())
    (directory / "certificate-sha256.txt").write_text(expected + "\n")
    print(f"Original signer restored to {directory}. Run setup with this directory to verify the repository pin before uploading.")


def configure(directory, repo=REPO, build=True):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository")
    for tool in ("keytool", "gh"):
        if not shutil.which(tool):
            raise RuntimeError(f"Install {tool} before setup; see docs/ANDROID_UPDATES.md")
    try:
        run(["gh", "auth", "status"])
    except RuntimeError as error:
        raise RuntimeError("Sign in with gh auth login, then retry setup with the same signing folder") from error
    # Inspect remote pins BEFORE creating or changing key material.
    values = json.loads(run(["gh", "variable", "list", "--repo", repo, "--json", "name,value"]))
    pinned = next((v["value"] for v in values if v["name"] == "ANDROID_SIGNING_CERT_SHA256"), None)
    names = {s["name"] for s in json.loads(run(["gh", "secret", "list", "--repo", repo, "--json", "name"]))}
    store = directory / "release.p12"
    password_file = directory / "password.txt"
    if pinned or names & {"ANDROID_SIGNING_KEYSTORE", "ANDROID_SIGNING_PASSWORD"}:
        if not store.is_file() or not password_file.is_file() or not pinned:
            raise ValueError("Signing is already configured or partially configured. Restore the ORIGINAL backup; do not generate a replacement key.")
    private_directory(directory)
    if not password_file.exists():
        if store.exists():
            raise ValueError("Keystore exists without its password. Restore the original backup.")
        write_private(password_file, base64.urlsafe_b64encode(os.urandom(32)))
    password = password_file.read_text().strip()
    if len(password) < 32:
        raise ValueError("Invalid saved signing password")
    if not store.exists():
        generate(store, password)
    digest = certificate(store, password)
    if pinned and fingerprint(pinned) != digest:
        raise ValueError("Local key differs from the pinned release signer. Nothing uploaded.")
    note_path = directory / "bitwarden-backup.txt"
    note = backup_note(store, password)
    if note_path.exists():
        if note_path.read_text() != note:
            raise ValueError("Existing backup differs; keep the originals and inspect the private backup folder")
    else:
        write_private(note_path, note.encode())
    (directory / "certificate-sha256.txt").write_text(digest + "\n")
    # Pin before upload: a partial failure can be resumed only with this exact local key.
    run(["gh", "variable", "set", "ANDROID_SIGNING_CERT_SHA256", "--repo", repo, "--body", digest])
    run(["gh", "secret", "set", "ANDROID_SIGNING_KEYSTORE", "--repo", repo], data=base64.b64encode(store.read_bytes()).decode())
    run(["gh", "secret", "set", "ANDROID_SIGNING_PASSWORD", "--repo", repo], data=password)
    print(f"Release signer configured for {repo}. Certificate SHA-256: {digest}")
    print(f"Keep an offline backup of this entire private folder: {directory}")
    if build:
        run(["gh", "workflow", "run", "signed-android.yml", "--repo", repo, "--ref", "main"])
        print(f"Signed build requested: https://github.com/{repo}/actions/workflows/signed-android.yml")


def sdk_tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    suffix = (".bat" if name == "apksigner" else ".exe") if os.name == "nt" else ""
    path = sdk / "build-tools" / "35.0.0" / (name + suffix)
    if not path.is_file():
        raise RuntimeError("Android build-tools 35.0.0 are required")
    return path


def inspect_apk(apk):
    signatures = run([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", apk])
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", signatures, re.M)
    if len(signers) != 1 or "Verified using v2 scheme (APK Signature Scheme v2): true" not in signatures:
        raise ValueError("A single verified v2 APK signer is required")
    badging = run([sdk_tool("aapt"), "dump", "badging", apk])
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match or match[1] != PACKAGE or "application-debuggable" in badging:
        raise ValueError("Wrong package or debuggable APK; refusing release/update")
    return {"package": match[1], "version_code": int(match[2]), "version_name": match[3],
            "certificate_sha256": fingerprint(signers[0]), "apk_sha256": hashlib.sha256(Path(apk).read_bytes()).hexdigest()}


def verify_update(previous, candidate, expected):
    old, new = inspect_apk(previous), inspect_apk(candidate)
    if new["certificate_sha256"] != fingerprint(expected) or old["certificate_sha256"] != new["certificate_sha256"]:
        raise ValueError("Signer mismatch: Android cannot preserve pairing through this update. Do not uninstall or restore an operational pad.")
    if new["version_code"] <= old["version_code"]:
        raise ValueError("Update versionCode must strictly increase")
    return new


def build_release():
    # Only explicitly supplied, pinned credentials can sign; never fall back to debug keys.
    expected = fingerprint(os.environ.get("OTP_SIGNING_CERT_SHA256", ""))
    password = os.environ.get("OTP_SIGNING_PASSWORD", "")
    if len(password) < 32:
        raise ValueError("Release signing password is missing")
    encoded = os.environ.get("OTP_SIGNING_KEYSTORE_B64", "")
    if not encoded or len(encoded) > 60000:
        raise ValueError("Release keystore is missing or too large")
    data = base64.b64decode(encoded, validate=True)
    with tempfile.TemporaryDirectory(prefix="otp-signing-") as folder:
        store = Path(folder) / "release.p12"
        write_private(store, data)
        if certificate(store, password) != expected:
            raise ValueError("Keystore does not match the pinned certificate")
        env = dict(os.environ, OTP_SIGNING_STORE_FILE=str(store), OTP_SIGNING_STORE_PASSWORD=password)
        # Base64 input has no purpose in Gradle's environment.
        env.pop("OTP_SIGNING_KEYSTORE_B64", None)
        subprocess.run(["gradle", "--no-daemon", "-PrequireReleaseSigning=true", ":app:lintRelease", ":app:assembleRelease"], env=env, check=True)
        source = Path("app/build/outputs/apk/release/app-release.apk")
        info = inspect_apk(source)
        if info["certificate_sha256"] != expected:
            raise ValueError("Built APK signer does not match the pinned certificate")
        target = Path("app/build/field-release"); target.mkdir(parents=True, exist_ok=False)
        apk = target / f"otp-pager-{info['version_name']}.apk"
        shutil.copyfile(source, apk)
        info["source_commit"] = run(["git", "rev-parse", "HEAD"]).strip()
        (target / "signing.json").write_text(json.dumps(info, indent=2) + "\n")
        (target / "SHA256SUMS").write_text(f"{info['apk_sha256']}  {apk.name}\n")
        print(f"Verified signed APK: {apk}; certificate SHA-256: {expected}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    setup = sub.add_parser("setup")
    default = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".local" / "share")) / "OTPPager" / "signing"
    setup.add_argument("--directory", type=Path, default=default)
    setup.add_argument("--repo", default=REPO)
    setup.add_argument("--no-build", action="store_true")
    restore = sub.add_parser("restore")
    restore.add_argument("backup", type=Path)
    restore.add_argument("--directory", type=Path, default=default)
    sub.add_parser("build")
    compare = sub.add_parser("check-update")
    compare.add_argument("previous", type=Path); compare.add_argument("candidate", type=Path)
    compare.add_argument("--certificate", required=True)
    args = parser.parse_args()
    if args.action == "setup":
        configure(args.directory, args.repo, not args.no_build)
    elif args.action == "restore":
        restore_backup(args.backup, args.directory)
    elif args.action == "build":
        build_release()
    else:
        print(json.dumps(verify_update(args.previous, args.candidate, args.certificate), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
