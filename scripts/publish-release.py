"""Publish previously tested bytes. Run only in the trusted main-branch release workflow."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path, optional=False):
    result = subprocess.run(["gh", "api", path], text=True, capture_output=True)
    if result.returncode:
        if optional and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"GitHub request failed: {path}: {result.stderr.strip()}")
    return json.loads(result.stdout)


def image_digest(image):
    output = command("docker", "buildx", "imagetools", "inspect", image)
    match = re.search(r"^Digest:\s+(sha256:[a-f0-9]{64})\s*$", output, re.MULTILINE)
    if not match:
        raise RuntimeError("Registry did not return a manifest digest")
    return match.group(1)


def main():
    repo = os.environ["RELEASE_REPOSITORY"]
    config = json.loads(Path("release/manifest.json").read_text())
    version, commit = config["version"], config["commit"]
    assert re.fullmatch(r"\d+\.\d+\.\d+", version)
    assert re.fullmatch(r"[a-f0-9]{40}", commit)
    assert re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo)
    assert config["artifact"] == "otp-pager-debug-apk-api-31"
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"], check=True)
    for kind, workflow in [("android_run", ".github/workflows/android.yml"), ("relay_run", ".github/workflows/relay.yml")]:
        run_id = config[kind]
        assert isinstance(run_id, int) and run_id > 0
        run = api(f"repos/{repo}/actions/runs/{run_id}")
        assert run["conclusion"] == "success" and run["head_sha"] == commit
        assert run["head_repository"]["full_name"] == repo
        assert run["path"] == workflow
    source = api(f"repos/{repo}/contents/app/build.gradle.kts?ref={commit}")
    build = base64.b64decode(source["content"]).decode()
    assert re.search(r'versionName\s*=\s*"' + re.escape(version) + '"', build)
    tag = f"v{version}"
    existing_tag = api(f"repos/{repo}/git/ref/tags/{tag}", optional=True)
    if existing_tag:
        assert existing_tag["object"]["type"] == "commit" and existing_tag["object"]["sha"] == commit, "Release tag already identifies different source"

    image = f"ghcr.io/{repo.lower()}-relay"
    source_image = f"{image}:sha-{commit}"
    source_digest = image_digest(source_image)
    index = json.loads(command("docker", "buildx", "imagetools", "inspect", "--raw", source_image))
    platforms = {(m.get("platform", {}).get("os"), m.get("platform", {}).get("architecture")) for m in index["manifests"]}
    assert {("linux", "amd64"), ("linux", "arm64")} <= platforms
    release_image = f"{image}:{version}"
    probe = subprocess.run(["docker", "buildx", "imagetools", "inspect", release_image], text=True, capture_output=True)
    if probe.returncode == 0:
        assert image_digest(release_image) == source_digest, "Versioned image already points at different content"
    else:
        # Only an absent manifest permits creating the version tag; auth/network errors fail closed.
        assert any(value in probe.stderr.lower() for value in ["not found", "manifest unknown"]), probe.stderr
        subprocess.run(["docker", "buildx", "imagetools", "create", "--tag", release_image, f"{image}@{source_digest}"], check=True)
    assert image_digest(release_image) == source_digest

    with tempfile.TemporaryDirectory() as folder:
        target = Path(folder)
        subprocess.run(["gh", "run", "download", str(config["android_run"]), "--repo", repo, "--name", config["artifact"], "--dir", folder], check=True)
        apk = target / f"otp-pager-{version}-debug.apk"
        (target / "app-debug.apk").rename(apk)
        assert apk.stat().st_size > 100000
        assets = [apk]
        for src, dest in [("deploy/portainer-stack.yml", "portainer-stack.yml"), ("docs/SECURITY_REVIEW.md", "SECURITY_REVIEW.md")]:
            asset = target / dest
            asset.write_bytes(Path(src).read_bytes())
            assets.append(asset)
        reference = target / "relay-image.txt"
        reference.write_text(f"{release_image}\n{image}@{source_digest}\n")
        assets.append(reference)
        checksums = target / "SHA256SUMS"
        checksums.write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in assets))
        assets.append(checksums)
        notes = Path(f"release/{version}.md")
        assert notes.is_file()
        release = api(f"repos/{repo}/releases/tags/{tag}", optional=True)
        if release:
            names = {a["name"] for a in release["assets"]}
            missing = [str(p) for p in assets if p.name not in names]
            if missing:
                subprocess.run(["gh", "release", "upload", tag, "--repo", repo, *missing], check=True)
        else:
            subprocess.run(["gh", "release", "create", tag, "--repo", repo, "--target", commit,
                            "--title", f"OTP Pager {version} — first field release", "--prerelease", "--latest=false",
                            "--notes-file", str(notes), *map(str, assets)], check=True)
    print(f"Published {tag}: {release_image}, digest {source_digest}")


if __name__ == "__main__":
    main()
