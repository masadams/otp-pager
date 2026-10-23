import base64
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import android_signing as signing


class SigningTests(unittest.TestCase):
    def test_real_pkcs12_survives_reload_and_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder) / 'private'; signing.private_directory(directory)
            password = base64.urlsafe_b64encode(os.urandom(32)).decode()
            store = directory / 'release.p12'
            signing.generate(store, password)
            before = store.read_bytes()
            self.assertEqual(signing.certificate(store, password), signing.certificate(store, password))
            self.assertRegex(signing.certificate(store, password), r'^[a-f0-9]{64}$')
            with self.assertRaises(ValueError): signing.generate(store, password)
            self.assertEqual(before, store.read_bytes())
            with self.assertRaises(RuntimeError): signing.certificate(store, 'wrong password')
            if os.name != 'nt':
                self.assertEqual(0o600, store.stat().st_mode & 0o777)
                self.assertEqual(0o700, directory.stat().st_mode & 0o777)

    def test_missing_or_bad_pin_fails_before_gradle(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(subprocess, 'run') as child:
            with self.assertRaises(ValueError): signing.build_release()
            child.assert_not_called()
        for value in ['', 'bad', '0' * 63, 'x' * 64]:
            with self.assertRaises(ValueError): signing.fingerprint(value)
        self.assertEqual('a' * 64, signing.fingerprint(':'.join(['AA'] * 32)))

    def test_apk_policy_rejects_debug_wrong_package_multiple_signers_and_unsigned(self):
        signature = 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
        badging = "package: name='org.example.otppager' versionCode='8' versionName='0.8.0'\n"
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'; apk.write_bytes(b'fixture')
            with patch.object(signing, 'sdk_tool', side_effect=lambda name: name):
                with patch.object(signing, 'run', side_effect=[signature, badging]):
                    self.assertEqual(8, signing.inspect_apk(apk)['version_code'])
                for sig, badge in [(signature, badging + 'application-debuggable\n'), (signature, badging.replace('org.example.otppager', 'wrong.package')),
                                   (signature + 'Signer #2 certificate SHA-256 digest: ' + 'b' * 64, badging), ('unverified', badging)]:
                    with patch.object(signing, 'run', side_effect=[sig, badge]), self.assertRaises(ValueError): signing.inspect_apk(apk)

    def test_update_requires_same_pinned_signer_and_increasing_version(self):
        old = {'certificate_sha256': 'a' * 64, 'version_code': 8}
        new = {'certificate_sha256': 'a' * 64, 'version_code': 9}
        with patch.object(signing, 'inspect_apk', side_effect=[old, new]):
            self.assertEqual(new, signing.verify_update('old', 'new', 'a' * 64))
        for changed, pin in [(dict(new, version_code=8), 'a' * 64), (dict(new, version_code=7), 'a' * 64),
                             (dict(new, certificate_sha256='b' * 64), 'a' * 64), (new, 'b' * 64)]:
            with patch.object(signing, 'inspect_apk', side_effect=[old, changed]), self.assertRaises(ValueError): signing.verify_update('old', 'new', pin)

    def test_setup_refuses_missing_original_or_mismatched_key_before_secret_upload(self):
        for missing in [True, False]:
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                if not missing:
                    (root / 'release.p12').write_bytes(b'existing key')
                    (root / 'password.txt').write_text('p' * 43)
                calls = []
                def fake(args, **kwargs):
                    calls.append((args, kwargs))
                    if args[1:3] == ['variable', 'list']: return json.dumps([{'name': 'ANDROID_SIGNING_CERT_SHA256', 'value': 'a' * 64}])
                    if args[1:3] == ['secret', 'list']: return json.dumps([{'name': 'ANDROID_SIGNING_KEYSTORE'}])
                    return ''
                with patch.object(signing.shutil, 'which', return_value='tool'), patch.object(signing, 'run', side_effect=fake), patch.object(signing, 'certificate', return_value='b' * 64):
                    with self.assertRaises(ValueError): signing.configure(root, build=False)
                self.assertFalse(any(args[1:3] in [['secret', 'set'], ['variable', 'set']] for args, _ in calls))

    def test_resumable_setup_uses_stdin_and_never_logs_secret_values(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); store = root / 'release.p12'
            store.write_bytes(b'PRIVATE_KEY_FIXTURE'); password = 'z' * 43
            (root / 'password.txt').write_text(password)
            calls = []
            def fake(args, **kwargs):
                calls.append((args, kwargs))
                if args[1:3] == ['variable', 'list']: return '[]'
                if args[1:3] == ['secret', 'list']: return '[]'
                return ''
            output = io.StringIO()
            with patch.object(signing.shutil, 'which', return_value='tool'), patch.object(signing, 'run', side_effect=fake), patch.object(signing, 'certificate', return_value='a' * 64), contextlib.redirect_stdout(output):
                signing.configure(root, build=False)
            self.assertNotIn(password, output.getvalue()); self.assertNotIn('PRIVATE_KEY_FIXTURE', output.getvalue())
            uploads = [(args, kw) for args, kw in calls if args[1:3] == ['secret', 'set']]
            self.assertEqual(2, len(uploads))
            self.assertEqual(password, uploads[1][1]['data'])
            self.assertNotIn(password, str([args for args, _ in calls]))
            self.assertEqual(b'PRIVATE_KEY_FIXTURE', store.read_bytes())


if __name__ == '__main__': unittest.main()
