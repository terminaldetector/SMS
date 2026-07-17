"""Self-test for HELIX ③ capability attestation.

    python -m helix.attest_selftest

Proves the proof binds to the claimed size, the ④-signed certificate is secure, a
memory-liar is denied a cert, and placement consumes only attested capacities.
"""

from __future__ import annotations

from helix.attest import (
    CapabilityCert, Challenge, Prover, Proof, Verifier, attested_capacities, memory_walk,
)
from helix.identity import Keyring, NodeIdentity
from helix.placement import Capacity, InsufficientCapacity, plan_placement

MB = 1024 * 1024
GB = 1024 ** 3


def test_proof_binding() -> None:
    ch = Challenge(chal="aa" * 16, claimed_bytes=256 * 1024, steps=32)
    p = Prover().prove(ch)
    # A proof is exactly reproducible from the challenge...
    assert p.acc == memory_walk(bytes.fromhex(ch.chal), ch.n_words, ch.steps).hex()
    # ...and binds to the size: the same walk at a smaller buffer differs.
    smaller = memory_walk(bytes.fromhex(ch.chal), ch.n_words // 4, ch.steps).hex()
    assert smaller != p.acc
    # ...and to the challenge: a different nonce differs.
    other = memory_walk(bytes.fromhex("bb" * 16), ch.n_words, ch.steps).hex()
    assert other != p.acc
    print("  proof: reproducible; bound to both claimed size and challenge nonce")


def test_honest_flow_and_cert() -> None:
    issuer = NodeIdentity()
    kr = Keyring(); kr.admit(issuer.node_id, issuer.public)
    v = Verifier(issuer)
    subject = NodeIdentity().node_id
    ch = v.challenge(256 * 1024, steps=32)
    proof = Prover().prove(ch)
    cert = v.certify(subject, ch, proof)
    assert cert is not None and cert.verify(kr)
    assert cert.subject == subject and cert.capacity_bytes == 256 * 1024
    print("  honest: challenge -> proof -> ④-signed cert, verifies under issuer key")


def test_cert_security() -> None:
    issuer = NodeIdentity()
    kr = Keyring(); kr.admit(issuer.node_id, issuer.public)
    cert = CapabilityCert("nodeX", 8 * GB, expires=1e18).sign(issuer)
    assert cert.verify(kr)
    # tamper capacity -> signature no longer matches
    tampered = CapabilityCert.from_body(cert.to_body()); tampered.capacity_bytes = 64 * GB
    assert not tampered.verify(kr)
    # issued by an unknown/forged issuer -> not in keyring
    forger = NodeIdentity()
    fake = CapabilityCert("nodeX", 64 * GB, expires=1e18).sign(forger)
    assert not fake.verify(kr)
    # expired -> rejected
    expired = CapabilityCert("nodeX", 8 * GB, expires=0.0).sign(issuer)
    assert not expired.verify(kr, now=1.0)
    print("  cert: tamper, forged issuer and expiry all rejected")


def test_memory_liar_denied() -> None:
    issuer = NodeIdentity()
    v = Verifier(issuer)
    liar = "liar-node"
    # Verifier challenges for 4 MB; the liar can only build a 256 KB buffer, so it computes
    # the walk over a smaller buffer -> wrong digest -> no certificate.
    ch = v.challenge(4 * MB, steps=32)
    liar_proof = Proof(memory_walk(bytes.fromhex(ch.chal), (256 * 1024) // 32, ch.steps).hex(), 0.01)
    assert v.certify(liar, ch, liar_proof) is None
    print("  liar: claim 4 MB but prove 256 KB -> denied (no cert for capacity it lacks)")


def test_placement_uses_attested() -> None:
    issuer = NodeIdentity()
    kr = Keyring(); kr.admit(issuer.node_id, issuer.public)
    v = Verifier(issuer)
    # Two honest nodes attest; a liar gets no cert.
    certs = {}
    for name, size in [("A", 512 * 1024), ("B", 256 * 1024)]:
        ch = v.challenge(size, steps=16)
        certs[name] = v.certify(name, ch, Prover().prove(ch))
    caps_bytes = attested_capacities(certs, kr)
    assert set(caps_bytes) == {"A", "B"}, caps_bytes
    # Placement runs over ATTESTED capacities only (liar "L" is absent, cannot grab layers).
    members = {n: Capacity(b) for n, b in caps_bytes.items()}
    placement = plan_placement(members, "m", 6, 128 * 1024)
    assert set(placement.ring) == {"A", "B"} and "L" not in placement.ring
    # A's attested band is proportional to its proven (not claimed) capacity: 512:256 -> 4:2
    assert placement.bands["A"].n == 4 and placement.bands["B"].n == 2, placement.bands
    try:
        plan_placement({}, "m", 6, 128 * 1024)  # nobody attested -> no viable placement
        raise AssertionError("expected InsufficientCapacity")
    except InsufficientCapacity:
        pass
    print("  placement: only attested nodes get bands, sized by proven capacity")


def main() -> None:
    print("HELIX ③ capability-attestation self-test")
    print("[1] proof binding"); test_proof_binding()
    print("[2] honest flow + cert"); test_honest_flow_and_cert()
    print("[3] cert security"); test_cert_security()
    print("[4] memory-liar denied"); test_memory_liar_denied()
    print("[5] placement uses attested capacity"); test_placement_uses_attested()
    print("ALL PASSED")


if __name__ == "__main__":
    main()
