"""Runnable self-test for the HELIX protocol core.

    python -m helix.selftest

Pure CPython, no third-party deps. Proves the properties the AUDIT2 findings
demanded of a real protocol: verified crypto, confidentiality, tamper/replay/
oversize rejection, key isolation, and authenticated sender identity.
"""

from __future__ import annotations

from helix import frame as _frame
from helix.crypto import aead_decrypt, aead_encrypt, hkdf
from helix.message import MsgType
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec, ReplayGuard

SECRET = b"helix-selftest-cluster-secret"
OTHER = b"a-different-cluster-secret"


def test_rfc8439_vector() -> None:
    """AEAD must match the official RFC 8439 §2.8.2 test vector exactly."""
    key = bytes(range(0x80, 0xA0))
    nonce = bytes.fromhex("070000004041424344454647")
    aad = bytes.fromhex("50515253c0c1c2c3c4c5c6c7")
    pt = (b"Ladies and Gentlemen of the class of '99: If I could offer you "
          b"only one tip for the future, sunscreen would be it.")
    want = ("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6"
            "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36"
            "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc"
            "3ff4def08e4b7a9de576d26586cec64b6116"
            "1ae10b594f09e26a7e902ecbd0600691")
    got = aead_encrypt(key, nonce, pt, aad)
    assert got.hex() == want, got.hex()
    assert aead_decrypt(key, nonce, got, aad) == pt
    print("  crypto: RFC 8439 AEAD vector matches; round-trip OK")


def test_hkdf_separation() -> None:
    a = hkdf(SECRET, info=b"one", length=32)
    b = hkdf(SECRET, info=b"two", length=32)
    assert a != b and len(a) == len(b) == 32
    assert hkdf(SECRET, info=b"one", length=32) == a  # deterministic
    print("  hkdf: deterministic and info-separated")


def _codec(node_id: str, secret: bytes = SECRET, mode: str = "aead", **kw) -> FrameCodec:
    return FrameCodec(node_id, sealer_from_cluster_secret(secret, mode=mode), **kw)


def test_roundtrip_and_identity() -> None:
    tx = _codec("nodeA")
    rx = _codec("nodeB")
    wire = tx.seal(MsgType.PROMPT.value, {"text": "hello world"}, tid="t1")
    msg = rx.open(wire)
    assert msg is not None and msg.type == MsgType.PROMPT.value
    assert msg.body["text"] == "hello world" and msg.tid == "t1"
    # Sender identity comes from the authenticated frame, not the transport.
    assert msg.src == "nodeA"
    print("  frame: AEAD round-trip; src='{}' from the sealed body".format(msg.src))


def test_confidentiality() -> None:
    tx = _codec("nodeA")
    rx = _codec("nodeB")
    secretish = "TOP-SECRET-PROMPT-9917"
    wire = tx.seal(MsgType.PROMPT.value, {"text": secretish})
    assert secretish.encode() not in wire, "plaintext leaked into AEAD frame!"
    assert rx.open(wire).body["text"] == secretish
    # HMAC sealer authenticates but does NOT hide the body:
    htx = _codec("nodeA", mode="hmac")
    hwire = htx.seal(MsgType.ANNOUNCE.value, {"text": secretish})
    assert secretish.encode() in hwire, "HMAC sealer is auth-only, body should be visible"
    print("  confidentiality: AEAD hides body; HMAC sealer is (correctly) auth-only")


def test_tamper_and_wrong_key() -> None:
    tx = _codec("nodeA")
    rx = _codec("nodeB")
    wire = tx.seal(MsgType.PROMPT.value, {"text": "data"})
    bad = bytearray(wire); bad[-1] ^= 1                 # flip a ciphertext/tag byte
    assert rx.open(bytes(bad)) is None
    bad2 = bytearray(wire); bad2[_frame.HEADER_LEN - 1] ^= 1  # flip header (AAD)
    assert rx.open(bytes(bad2)) is None
    outsider = _codec("nodeC", secret=OTHER)
    assert outsider.open(wire) is None                  # different cluster secret
    print("  integrity: body-tamper, header-tamper and wrong-secret all rejected")


def test_replay_and_reorder() -> None:
    g = ReplayGuard(window=8)
    assert g.accept("s", 1) and g.accept("s", 2)
    assert not g.accept("s", 2)          # duplicate
    assert g.accept("s", 5)              # jump forward
    assert g.accept("s", 3)              # in-window reorder
    assert not g.accept("s", 3)          # duplicate of reordered
    assert not g.accept("s", 5 - 8)      # older than window (seq -3 -> too old anyway)
    assert g.accept("t", 1)              # independent sender space
    # End-to-end: replaying a whole sealed frame is rejected.
    tx = _codec("nodeA")
    rx = _codec("nodeB")
    wire = tx.seal(MsgType.PROMPT_TOKEN.value, {"text": "x"})
    assert rx.open(wire) is not None
    assert rx.open(wire) is None, "verbatim replay must be dropped"
    print("  anti-replay: dup/old rejected, bounded reorder OK, frame replay dropped")


def test_size_limit() -> None:
    tx = FrameCodec("nodeA", sealer_from_cluster_secret(SECRET), max_frame=256)
    rx = FrameCodec("nodeB", sealer_from_cluster_secret(SECRET), max_frame=256)
    small = tx.seal(MsgType.HEARTBEAT.value, {"n": 1})
    assert rx.open(small) is not None
    oversized = b"HLX1" + b"\x00" * 4096          # magic present, but > max_frame
    assert rx.open(oversized) is None, "oversized frame must be rejected pre-alloc"
    try:
        tx.seal(MsgType.ACTIVATION.value, {"blob": "A" * 4096})
        raise AssertionError("sealing an oversized frame should raise")
    except ValueError:
        pass
    print("  size: MAX_FRAME enforced on open (pre-alloc) and on seal")


def test_epoch_isolation() -> None:
    tx = _codec("nodeA", epoch=1)
    rx_same = _codec("nodeB", epoch=1)
    rx_diff = _codec("nodeB", epoch=2)
    wire = tx.seal(MsgType.PROMPT.value, {"text": "hi"})
    assert rx_same.open(wire) is not None
    assert rx_diff.open(wire) is None, "frame from another key epoch must be rejected"
    print("  epoch: frames are bound to their key generation")


def test_type_disambiguation() -> None:
    # The old single TOKEN is split so the two sub-protocols can't collide.
    assert MsgType.PROMPT_TOKEN.value != MsgType.SHARD_TOKEN.value
    assert {"PROMPT_TOKEN", "SHARD_TOKEN", "ACTIVATION", "FEED"} <= {t.value for t in MsgType}
    print("  types: PROMPT_TOKEN vs SHARD_TOKEN disambiguated; versioned messages")


def main() -> None:
    print("HELIX protocol-core self-test")
    print("[1] crypto (RFC 8439 vector)"); test_rfc8439_vector()
    print("[2] hkdf key separation"); test_hkdf_separation()
    print("[3] frame round-trip + authenticated identity"); test_roundtrip_and_identity()
    print("[4] confidentiality"); test_confidentiality()
    print("[5] integrity / tamper / wrong key"); test_tamper_and_wrong_key()
    print("[6] anti-replay + reorder"); test_replay_and_reorder()
    print("[7] frame size limit"); test_size_limit()
    print("[8] epoch isolation"); test_epoch_isolation()
    print("[9] message-type disambiguation"); test_type_disambiguation()
    print("ALL PASSED")


if __name__ == "__main__":
    main()
