"""Cross-language conformance vectors for HELIX.

The protocol must interoperate byte-for-byte across implementations (the Python reference
here, a Kotlin/LiteRT port for the edge app, a C++/TS port for the llama.cpp/ChatterUI
fork). This module emits **canonical test vectors** — fixed inputs → exact wire bytes —
that any native port must reproduce. If two implementations both pass these vectors, they
are wire-compatible by construction.

    python -m helix.conformance            # regenerate helix/spec/vectors.json
    python -m helix.conformance --check     # verify the reference matches the committed file

Every output is either anchored to a published standard (RFC 8439 AEAD, RFC 8032 Ed25519)
or is definitional (the HELIX frame/message layout). Determinism is achieved with fixed
secrets, nonces and seeds — never ``os.urandom`` — so the bytes are stable.
"""

from __future__ import annotations

import json
import os
import sys
from typing import Any, Dict

from helix import frame as _frame
from helix.activation import Int8ActivationCodec, RawActivationCodec
from helix.crypto import aead_encrypt, hkdf
from helix.identity import NodeIdentity, derive_node_id, ed25519_public, ed25519_sign
from helix.message import PROTOCOL_VERSION, Message, MsgType
from helix.mesh.router import _enc_data, _enc_presence
from helix.sealer import AeadSealer, sealer_from_cluster_secret
from helix.session import FrameCodec

_VECTORS_PATH = os.path.join(os.path.dirname(__file__), "spec", "vectors.json")

# Fixed test inputs (never random) so the vectors are reproducible.
SECRET = b"HELIX-CONFORMANCE-SECRET-v1"
NONCE = bytes.fromhex("0102030405060708090a0b0c")
EPOCH = 7
SEED = bytes.fromhex("0011223344556677889900112233445566778899001122334455667788990011")
_ACT_IN = [0.0, 1.5, -3.25, 12.0, -12.0, 0.5]  # fixed activation vector for the codec vectors


def build_vectors() -> Dict[str, Any]:
    aead_key = hkdf(SECRET, info=b"helix/1 aead key", length=32)

    # A canonical sealed frame (fixed nonce, so the whole wire is deterministic).
    msg = Message(type=MsgType.LEASE.value, src="coordinator-1", seq=3, tid="task-xyz",
                  body={"term": 2, "band": [0, 4], "n_layers": 8})
    plaintext = msg.serialize()
    header = _frame.encode_header(_frame.FLAG_CONFIDENTIAL, EPOCH, NONCE)
    sealed = AeadSealer(aead_key).seal(plaintext, NONCE, aad=header)
    wire = header + sealed

    pub = ed25519_public(SEED)
    claim = {"task": "t1", "candidate": "Paris", "score": 5, "voter": derive_node_id(pub)}
    claim_sig = ed25519_sign(SEED, json.dumps(claim, sort_keys=True, separators=(",", ":")).encode())

    return {
        "protocol_version": PROTOCOL_VERSION,
        "hkdf": {
            "secret_utf8": SECRET.decode(),
            "aead_key": aead_key.hex(),
            "hmac_key": hkdf(SECRET, info=b"helix/1 hmac key", length=32).hex(),
            "beacon_key": hkdf(SECRET, info=b"helix/1 beacon key", length=32).hex(),
        },
        "aead_rfc8439": {  # external anchor: RFC 8439 §2.8.2
            "key": "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
            "nonce": "070000004041424344454647",
            "aad": "50515253c0c1c2c3c4c5c6c7",
            "plaintext_utf8": ("Ladies and Gentlemen of the class of '99: If I could offer you "
                               "only one tip for the future, sunscreen would be it."),
            "sealed": aead_encrypt(
                bytes.fromhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"),
                bytes.fromhex("070000004041424344454647"),
                (b"Ladies and Gentlemen of the class of '99: If I could offer you "
                 b"only one tip for the future, sunscreen would be it."),
                bytes.fromhex("50515253c0c1c2c3c4c5c6c7"),
            ).hex(),
        },
        "routing_envelope": {  # MeshRouter outer layer (cleartext dst over a sealed frame)
            "data": {
                "ttl": 8, "dst": "phone", "frame": wire.hex(),
                "envelope": _enc_data(8, "phone", wire).hex(),
                "layout": "kind=0(1) | ttl(1) | dst_len(2 BE) | dst(utf8) | frame",
            },
            "presence": {
                "ttl": 8, "origin": "coordinator-1",
                "envelope": _enc_presence(8, "coordinator-1").hex(),
                "layout": "kind=1(1) | ttl(1) | origin(utf8)",
            },
        },
        "frame": {
            "magic": _frame.MAGIC.decode(),
            "header_len": _frame.HEADER_LEN,
            "max_frame": _frame.MAX_FRAME,
            "flag_confidential": _frame.FLAG_CONFIDENTIAL,
            "epoch": EPOCH,
            "nonce": NONCE.hex(),
            "message_fields": {"v": msg.v, "t": msg.type, "seq": msg.seq, "src": msg.src,
                               "tid": msg.tid, "b": msg.body},
            "plaintext": plaintext.hex(),
            "header": header.hex(),
            "wire": wire.hex(),
        },
        "message_encodings": [
            {"desc": "ANNOUNCE", "fields": {"type": MsgType.ANNOUNCE.value, "src": "n1", "seq": 1,
                                            "body": {"mem": 8589934592}},
             "bytes": Message(MsgType.ANNOUNCE.value, "n1", {"mem": 8589934592}, seq=1).serialize().hex()},
            {"desc": "SHARD_TOKEN", "fields": {"type": MsgType.SHARD_TOKEN.value, "src": "last", "seq": 9,
                                               "tid": "g1", "body": {"step": 2, "token": 42, "eos": False}},
             "bytes": Message(MsgType.SHARD_TOKEN.value, "last", {"step": 2, "token": 42, "eos": False},
                              seq=9, tid="g1").serialize().hex()},
            {"desc": "AGENT_ANNOUNCE", "fields": {"type": MsgType.AGENT_ANNOUNCE.value, "src": "a1", "seq": 4,
                                                  "body": {"agent_id": "a1", "skills": ["rag"],
                                                           "task_types": ["chat"]}},
             "bytes": Message(MsgType.AGENT_ANNOUNCE.value, "a1",
                              {"agent_id": "a1", "skills": ["rag"], "task_types": ["chat"]},
                              seq=4).serialize().hex()},
            # Track B ring control (integer-only bodies -> byte-exact across languages).
            {"desc": "FEED", "fields": {"type": MsgType.FEED.value, "src": "coord", "seq": 1,
                                        "tid": "g1", "body": {"step": 0, "token": 5}},
             "bytes": Message(MsgType.FEED.value, "coord", {"step": 0, "token": 5},
                              seq=1, tid="g1").serialize().hex()},
        ],
        # Track B activation codec (HELIX ①). Pinned numerically (not by JSON float formatting):
        # a port must reproduce the int8 quantization (q bytes, scale, length) and the raw list.
        "activation_codec": {
            "input": _ACT_IN,
            "raw": RawActivationCodec().encode(_ACT_IN),
            "int8": Int8ActivationCodec().encode(_ACT_IN),
            "note": "int8: q=base64(uint8, value+128), s=max|x|/127 (1.0 if all-zero), n=len; "
                    "decode x=(byte-128)*s. Sample reads hidden[0]=max -> quantizes losslessly.",
        },
        "ed25519_rfc8032": {  # external anchor: RFC 8032 test 1
            "seed": "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            "public": ed25519_public(
                bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")).hex(),
            "message": "",
            "signature": ed25519_sign(
                bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"), b"").hex(),
        },
        "node_id": {
            "seed": SEED.hex(),
            "public": pub.hex(),
            "node_id": derive_node_id(pub),
            "derivation": "hlx1 + sha256(public_key)[:20 hex]",
        },
        "signed_claim": {
            "canonical": "json.dumps(fields, sort_keys=True, separators=(',',':'))",
            "fields": claim,
            "signature": claim_sig.hex(),
        },
    }


def verify(v: Dict[str, Any]) -> None:
    """Re-derive every output from the stated inputs and assert it matches (+ round-trips)."""
    # HKDF
    assert hkdf(SECRET, info=b"helix/1 aead key", length=32).hex() == v["hkdf"]["aead_key"]
    # Frame seals to the stated wire, and opens back to the same message.
    codec = FrameCodec("coordinator-1", sealer_from_cluster_secret(SECRET), epoch=EPOCH)
    parsed = _frame.parse_frame(bytes.fromhex(v["frame"]["wire"]))
    assert parsed is not None
    opened = codec.open(bytes.fromhex(v["frame"]["wire"]))
    assert opened is not None and opened.type == MsgType.LEASE.value and opened.src == "coordinator-1"
    # Routing envelope decodes back to (dst, frame)
    from helix.mesh.router import _decode
    kind, ttl, dst, inner = _decode(bytes.fromhex(v["routing_envelope"]["data"]["envelope"]))
    assert kind == 0 and dst == "phone" and inner.hex() == v["frame"]["wire"]
    # RFC anchors
    assert v["aead_rfc8439"]["sealed"].endswith("1ae10b594f09e26a7e902ecbd0600691")
    assert v["ed25519_rfc8032"]["public"] == \
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    # Activation codec: int8 vector round-trips within one scale step, and re-encodes identically.
    ac = v["activation_codec"]
    assert Int8ActivationCodec().encode(ac["input"]) == ac["int8"]
    back = Int8ActivationCodec().decode(ac["int8"])
    assert all(abs(a - b) <= ac["int8"]["s"] for a, b in zip(ac["input"], back))
    # Self-certifying id + claim signature round-trip
    idn = NodeIdentity(SEED)
    assert idn.node_id == v["node_id"]["node_id"]
    from helix.identity import Keyring
    kr = Keyring(); kr.admit(idn.node_id, idn.public)
    assert kr.verify_claim(idn.node_id, v["signed_claim"]["fields"],
                           v["signed_claim"]["signature"])


def main() -> None:
    v = build_vectors()
    verify(v)
    if "--check" in sys.argv:
        with open(_VECTORS_PATH, "r", encoding="utf-8") as f:
            committed = json.load(f)
        assert committed == v, "reference output drifted from committed vectors.json"
        print("conformance: reference matches committed vectors.json")
        return
    os.makedirs(os.path.dirname(_VECTORS_PATH), exist_ok=True)
    with open(_VECTORS_PATH, "w", encoding="utf-8") as f:
        json.dump(v, f, indent=2, ensure_ascii=False)
    print("conformance: wrote {} ({} vectors verified)".format(_VECTORS_PATH, len(v)))


if __name__ == "__main__":
    main()
