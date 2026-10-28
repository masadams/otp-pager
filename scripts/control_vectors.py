"""Reference vectors for OTPCTL01 peer-wipe control frames (docs/PEER_WIPE_PROTOCOL.md).

Written from the specification text, not from the Kotlin code, using only the Python standard
library. Fixed TEST keys only. Run: python3 scripts/control_vectors.py > test-vectors/control-v1.properties

This is the first implementation's own cross-check. The plan still asks for a second,
independent implementer to reproduce these values before the feature leaves "unreviewed".
"""
import hashlib
import hmac
import struct

MAGIC = b"OTPCTL01"


def tag(key, body):
    return hmac.new(key, body, hashlib.sha256).digest()


def wipe(key, direction, generation, request, issued):
    body = MAGIC + bytes([0x01, direction]) + generation + request + struct.pack(">q", issued) + bytes(7)
    assert len(body) == 57
    return body + tag(key, body)


def ack(key, direction, generation, outcome):
    body = MAGIC + bytes([0x02, direction]) + generation + bytes([outcome, 0])
    assert len(body) == 28
    return body + tag(key, body)


def hkdf_room(credential):
    prk = hmac.new(bytes(32), credential, hashlib.sha256).digest()
    return hmac.new(prk, b"OTP Pager control room v1" + b"\x01", hashlib.sha256).hexdigest()


def main():
    k_ctl_ab = bytes(range(32))
    k_ack_ba = bytes(range(32, 64))
    credential = bytes(range(64, 96))
    generation = bytes.fromhex("00112233445566778899aabbccddeeff")
    request = bytes.fromhex("f0e1d2c3b4a5968778695a4b3c2d1e0f")
    issued = 1767225600000  # 2026-01-01T00:00:00Z, informational only
    print("# OTPCTL01 reference vectors from scripts/control_vectors.py (standard library only). TEST keys.")
    print("kCtlAB=" + k_ctl_ab.hex())
    print("kAckBA=" + k_ack_ba.hex())
    print("credential=" + credential.hex())
    print("generation=" + generation.hex())
    print("request=" + request.hex())
    print("issued=%d" % issued)
    print("wipeAB=" + wipe(k_ctl_ab, 0x01, generation, request, issued).hex())
    for name, code in (("Received", 1), ("Wiped", 2), ("Incomplete", 3)):
        frame = ack(k_ack_ba, 0x02, generation, code)
        print("ack%s=%s" % (name, frame.hex()))
        print("ack%sSha256=%s" % (name, hashlib.sha256(frame).hexdigest()))
    print("mailbox=" + hkdf_room(credential))


if __name__ == "__main__":
    main()
