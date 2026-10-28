#!/usr/bin/env python3
"""Candidate codec fixtures, not independent cryptographic review or a live protocol."""
import pathlib
import struct
import sys


def vectors():
    prefix = b'OTPSTR01'
    generation = bytes(range(16))
    device = bytes(range(32))
    authority = bytes(range(32, 64))
    nonce = bytes(range(64, 96))
    share = bytes(range(96, 128))
    result = {}
    for kind, name in enumerate(('enroll', 'unlock', 'grant', 'revoke', 'revoked', 'enrolled'), 1):
        issued, expires = (0, 0) if kind in (4, 5) else (1700000000000, 1700000060000)
        frame = struct.pack('>8sBc16s32s32sq32sqq32s', prefix, kind, b'A', generation,
                            device, authority, 7, nonce, issued, expires,
                            share if kind in (1, 3) else bytes(32))
        assert len(frame) == 178
        result[name] = frame.hex()
    return '# Candidate OTPSTR01 codec vectors; Python-generated, not independently reviewed.\n' + ''.join(
        f'{key}={value}\n' for key, value in result.items())


if __name__ == '__main__':
    path = pathlib.Path(__file__).resolve().parents[1] / 'test-vectors' / 'strict-chat-v1.properties'
    if sys.argv[1:] == ['--check']:
        assert path.read_text() == vectors(), 'Strict-chat vectors differ'
        print('Strict-chat codec vectors: PASS')
    elif not sys.argv[1:]:
        path.write_text(vectors())
    else:
        raise SystemExit('usage: strict_chat_vectors.py [--check]')
