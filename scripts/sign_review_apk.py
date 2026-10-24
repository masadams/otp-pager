"""Privately sign a verified own-repository CI review build with the authorized temporary key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

from android_signing import ALIAS, certificate, fingerprint, inspect_apk, restore_backup, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--expected-source', required=True)
    parser.add_argument('--expected-tree', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.build.resolve()
    build = json.loads((root / 'build.json').read_text())
    if (build.get('purpose') != 'unsigned-development-build' or build.get('source_commit') != args.expected_source
            or build.get('source_tree') != args.expected_tree):
        raise ValueError('Review build provenance differs from the verified CI source')
    # The caller must verify the successful own-repository run/head/tree via GitHub before use.
    for name, digest in build['files'].items():
        path = root / name
        if path.is_symlink() or not path.resolve().is_relative_to(root) or not path.is_file():
            raise ValueError('Review archive contains an unsafe path')
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Review archive checksum mismatch')
    required = ['otp-pager-unsigned.apk', 'sdk/build-tools/35.0.0/aapt', 'sdk/build-tools/35.0.0/lib/apksigner.jar']
    if not all(name in build['files'] for name in required):
        raise ValueError('Incomplete unsigned review build')
    pin = json.loads(Path('release/development-signer.json').read_text())
    if pin['purpose'] != 'temporary-development-only':
        raise ValueError('Temporary signing is not configured')
    expected = fingerprint(pin['certificate_sha256'])
    tools = root / 'sdk' / 'build-tools' / '35.0.0'
    aapt = tools / 'aapt'; aapt.chmod(0o755)
    jar = tools / 'lib' / 'apksigner.jar'
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='otp-private-review-') as folder:
        private = Path(folder) / 'signer'
        restore_backup(args.backup, private)
        password = (private / 'password.txt').read_text()
        if certificate(private / 'release.p12', password) != expected:
            raise ValueError('Wrong temporary signer; refusing to replace the update identity')
        # Public SDK tools only. Do not use arbitrary tools from unreviewed PRs.
        env = dict(os.environ, OTP_PRIVATE_SIGNING_PASSWORD=password)
        env['LD_LIBRARY_PATH'] = str(tools / 'lib64') + (':' + env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
        badging = run([aapt, 'dump', 'badging', root / 'otp-pager-unsigned.apk'], env=env)
        import re
        version = re.search(r"versionName='(\d+\.\d+\.\d+)'", badging)
        if not version:
            raise ValueError('Review versionName is invalid')
        apk = args.output / f'otp-pager-{version[1]}-test.apk'
        if apk.exists():
            raise ValueError('Refusing to overwrite an existing signed test APK')
        run(['java', '-jar', jar, 'sign', '--ks', private / 'release.p12', '--ks-key-alias', ALIAS,
             '--ks-pass', 'env:OTP_PRIVATE_SIGNING_PASSWORD', '--key-pass', 'env:OTP_PRIVATE_SIGNING_PASSWORD',
             '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--out', apk,
             root / 'otp-pager-unsigned.apk'], env=env)
        # Reuse the same strict package/debuggable/signature policy as owner releases.
        # The public SDK shell launcher is reconstructed locally; only its verified JAR runs.
        launcher = tools / 'apksigner'
        launcher.write_text('#!/usr/bin/env python3\nimport os,sys\nos.execvp("java",["java","-jar",os.path.join(os.path.dirname(__file__),"lib","apksigner.jar"),*sys.argv[1:]])\n')
        launcher.chmod(0o755)
        previous_sdk = os.environ.get('ANDROID_HOME'); previous_ld = os.environ.get('LD_LIBRARY_PATH')
        try:
            os.environ['ANDROID_HOME'] = str(root / 'sdk')
            os.environ['LD_LIBRARY_PATH'] = env['LD_LIBRARY_PATH']
            info = inspect_apk(apk)
        except Exception:
            apk.unlink(missing_ok=True)
            raise
        finally:
            for name, value in [('ANDROID_HOME', previous_sdk), ('LD_LIBRARY_PATH', previous_ld)]:
                if value is None: os.environ.pop(name, None)
                else: os.environ[name] = value
        if info['certificate_sha256'] != expected:
            apk.unlink(); raise ValueError('Signed review APK has the wrong signer')
        info.update(purpose='temporary-development', source_commit=args.expected_source, source_tree=args.expected_tree)
        (args.output / 'signing.json').write_text(json.dumps(info, indent=2) + '\n')
        print(json.dumps({'apk': str(apk), **info}, indent=2))


if __name__ == '__main__': main()
