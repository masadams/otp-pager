"""Package an UNSIGNED development APK and public SDK verification tools. No signing key."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

from android_signing import run, sdk_tool


def main():
    source = Path('app/build/outputs/apk/release/app-release-unsigned.apk')
    if not source.is_file():
        raise RuntimeError('Expected an unsigned release APK, never a debug or test-version APK')
    root = Path('app/build/unsigned-review')
    root.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(source, root / 'otp-pager-unsigned.apk')
    tools = root / 'sdk' / 'build-tools' / '35.0.0'
    (tools / 'lib').mkdir(parents=True)
    sdk_tools = sdk_tool('aapt').parent
    shutil.copyfile(sdk_tools / 'aapt', tools / 'aapt')
    shutil.copyfile(sdk_tools / 'lib' / 'apksigner.jar', tools / 'lib' / 'apksigner.jar')
    if (sdk_tools / 'lib64').is_dir():
        shutil.copytree(sdk_tools / 'lib64', tools / 'lib64')
    source_commit = os.environ['REVIEW_SOURCE_COMMIT']
    if not re.fullmatch('[a-f0-9]{40}', source_commit):
        raise ValueError('Invalid source commit')
    info = {'purpose': 'unsigned-development-build', 'source_commit': source_commit,
            'checkout_commit': run(['git', 'rev-parse', 'HEAD']).strip(),
            'source_tree': run(['git', 'rev-parse', 'HEAD^{tree}']).strip(),
            'files': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in root.rglob('*') if p.is_file()}}
    (root / 'build.json').write_text(json.dumps(info, indent=2) + '\n')


if __name__ == '__main__': main()
