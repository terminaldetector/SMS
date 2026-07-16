"""Runnable self-test for HELIX ④ per-node identity.

    python -m helix.identity_selftest

Verifies the Ed25519 implementation against the official RFC 8032 test-1 vector, then
demonstrates the security upgrade it delivers: self-certifying node ids and non-forgeable,
correctly-attributed claims (votes / context turns) that an insider who knows the group
secret still cannot forge as another node.
"""

from __future__ import annotations

from helix.identity import (
    Keyring, NodeIdentity, derive_node_id, ed25519_public, ed25519_sign, ed25519_verify,
    id_binds_key,
)

# RFC 8032 §7.1 Test 1 (authoritative vector).
_SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
_PUB = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
_SIG = ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33"
        "bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")


def test_rfc8032_vector() -> None:
    assert ed25519_public(_SEED).hex() == _PUB
    assert ed25519_sign(_SEED, b"").hex() == _SIG
    assert ed25519_verify(bytes.fromhex(_PUB), b"", bytes.fromhex(_SIG))
    print("  ed25519: RFC 8032 test-1 public key and signature match")


def test_sign_verify() -> None:
    idn = NodeIdentity()
    m = b"attribution matters"
    sig = idn.sign(m)
    assert ed25519_verify(idn.public, m, sig)
    assert not ed25519_verify(idn.public, m + b"!", sig)          # tamper
    other = NodeIdentity()
    assert not ed25519_verify(other.public, m, sig)               # wrong key
    print("  sign/verify: round-trip, tamper and wrong-key all handled")


def test_self_certifying_ids() -> None:
    idn = NodeIdentity()
    assert idn.node_id == derive_node_id(idn.public) and id_binds_key(idn.node_id, idn.public)
    kr = Keyring()
    assert kr.admit(idn.node_id, idn.public)
    # An id that does not bind to the presented key is rejected (no claiming others' ids).
    assert not kr.admit("hlx1deadbeefdeadbeefdead", idn.public)
    # Trust-on-first-use: a node's key cannot be swapped under its id.
    imposter = NodeIdentity()
    assert not kr.admit(idn.node_id, imposter.public)
    print("  ids: self-certifying (id == f(pubkey)); binding + TOFU enforced")


def test_unforgeable_claims() -> None:
    """An insider with the group secret cannot forge a vote/turn as another node."""
    alice, mallory = NodeIdentity(), NodeIdentity()
    kr = Keyring()
    kr.admit(alice.node_id, alice.public)
    kr.admit(mallory.node_id, mallory.public)

    vote = {"task": "t1", "candidate": "Paris", "score": 5, "voter": alice.node_id}
    sig = alice.sign_claim(vote)
    assert kr.verify_claim(alice.node_id, vote, sig)                      # honest vote verifies

    # Mallory forges a vote attributed to Alice and signs it with her own key.
    forged = {"task": "t1", "candidate": "WRONG", "score": 99, "voter": alice.node_id}
    forged_sig = mallory.sign_claim(forged)
    assert not kr.verify_claim(alice.node_id, forged, forged_sig)         # rejected: not Alice's key

    # Tampering with a signed field is rejected.
    tampered = dict(vote); tampered["score"] = 99
    assert not kr.verify_claim(alice.node_id, tampered, sig)
    print("  claims: honest vote attributed; insider forge & field-tamper rejected")
    print("  (note: identity stops *spoofing*; full Sybil resistance also needs cluster"
          " admission / attestation — HELIX ③)")


def main() -> None:
    print("HELIX (4) per-node identity self-test")
    print("[1] ed25519 RFC 8032 vector"); test_rfc8032_vector()
    print("[2] sign / verify"); test_sign_verify()
    print("[3] self-certifying ids + keyring"); test_self_certifying_ids()
    print("[4] unforgeable attributed claims"); test_unforgeable_claims()
    print("ALL PASSED")


if __name__ == "__main__":
    main()
