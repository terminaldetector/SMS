"""Activation-bandwidth measurement (Track B data plane, HELIX ①).

In a layer-shard ring only **activations** cross device boundaries, and during prefill they
dominate bandwidth (``d_model`` per token per inter-node hop). This measures the **real sealed-frame
wire size** of one activation under each codec (``helix/activation.py``) and projects the ring
bandwidth, so the int8 win — and the remaining base64 tax — are quantified, not guessed.

    python -m helix.host.bandwidth

Numbers are exact: every row seals an actual ``ACTIVATION`` frame with the real ``FrameCodec``.
Activation values are round-tripped through float32 first, so the raw-JSON cost reflects real
fp32-origin activations (long shortest-round-trip reprs), not tidy literals.
"""

from __future__ import annotations

import struct
from typing import List

from helix.activation import Int8ActivationCodec, RawActivationCodec
from helix.message import Message, MsgType
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec

# Representative decoder hidden sizes (d_model) by model class.
DIMS = [
    (2048, "~1-3B (d_model 2048)"),
    (4096, "~7-8B (d_model 4096)"),
    (5120, "~13B (d_model 5120)"),
    (8192, "~65-70B (d_model 8192)"),
]

_SECRET = b"helix-bandwidth-measure"


def _activation(dim: int) -> List[float]:
    """A realistic activation: pseudo-random, but rounded through float32 like a real engine."""
    vals: List[float] = []
    x = 0.123456789
    for i in range(dim):
        x = (1103515245 * x + 12345) % 1.0 if x else 0.31  # cheap deterministic churn
        v = (x - 0.5) * 6.0  # ~[-3, 3]
        v = struct.unpack("f", struct.pack("f", v))[0]  # fp32 round-trip (real-activation-like)
        vals.append(v)
    return vals


def _frame_bytes(codec_payload: dict, dim: int) -> int:
    fc = FrameCodec("shardN", sealer_from_cluster_secret(_SECRET))
    wire = fc.seal(MsgType.ACTIVATION.value, {"step": 7, "act": codec_payload}, tid="g1")
    return len(wire)


def _fmt(n: int) -> str:
    return "{:,}".format(n)


def _mbit_s(bytes_per_hop: int, hops: int, tok_s: float) -> float:
    return bytes_per_hop * hops * tok_s * 8 / 1e6


def main() -> None:
    raw, q8 = RawActivationCodec(), Int8ActivationCodec()
    print("HELIX activation bandwidth — real sealed ACTIVATION frames (per token, per hop)\n")
    print("  {:<26} {:>12} {:>12} {:>10}   {:>12} {:>10}".format(
        "model / d_model", "raw JSON", "int8 b64", "int8/raw", "fp32 bin*", "int8 bin*"))
    print("  " + "-" * 88)

    rows = []
    for dim, label in DIMS:
        vec = _activation(dim)
        raw_frame = _frame_bytes(raw.encode(vec), dim)
        q8_frame = _frame_bytes(q8.encode(vec), dim)
        fp32_bin = 4 * dim   # theoretical: fp32 tensor in a binary body
        int8_bin = dim       # theoretical: int8 tensor in a binary body (no base64)
        rows.append((dim, label, raw_frame, q8_frame, fp32_bin, int8_bin))
        print("  {:<26} {:>10}B {:>10}B {:>9.1%}   {:>10}B {:>9}B".format(
            label, _fmt(raw_frame), _fmt(q8_frame), q8_frame / raw_frame, _fmt(fp32_bin), _fmt(int8_bin)))

    print("\n  * fp32 bin / int8 bin = theoretical binary body (no JSON/base64) — the follow-up path.")
    print("    int8 b64 carries a ~33% base64 tax over int8 bin; both already dwarf raw JSON.\n")

    # Ring bandwidth projection: K nodes -> K-1 inter-shard hops per token.
    K, TOK_S = 4, 20.0
    print("  Ring projection: {} nodes ({} inter-shard hops), {:.0f} tok/s decode\n".format(K, K - 1, TOK_S))
    print("  {:<26} {:>16} {:>16}".format("model / d_model", "raw JSON", "int8 b64"))
    print("  " + "-" * 60)
    for dim, label, raw_frame, q8_frame, _fp, _i8 in rows:
        print("  {:<26} {:>13.2f} Mb/s {:>13.2f} Mb/s".format(
            label, _mbit_s(raw_frame, K - 1, TOK_S), _mbit_s(q8_frame, K - 1, TOK_S)))
    print("\n  Prefill multiplies per-token cost by seq_len (e.g. x512) — one bursty pass.")
    print("  Wi-Fi (real ~100-300 Mb/s) and USB-OTG (~high) comfortably carry int8; raw JSON")
    print("  strains Wi-Fi at large d_model. int8 is the practical default; binary body is next.\n")

    # sanity: int8 frame is well under half the raw frame at every size (doubles as a self-check).
    for dim, label, raw_frame, q8_frame, _fp, _i8 in rows:
        assert q8_frame < raw_frame // 2, (label, q8_frame, raw_frame)
    print("ALL PASSED (int8 < raw/2 at every d_model)")


if __name__ == "__main__":
    main()
