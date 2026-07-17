// HELIX activation codec — JS reference (helix/activation.py), for the ChatterUI *Level 3* /
// Option B ring: how a hidden-state tensor crosses a HELIX frame.
//
//   RawActivationCodec  — exact fp32 list (correctness; used by the ring proof).
//   Int8ActivationCodec — per-tensor symmetric int8 (HELIX ①, ~4x smaller). Pinned by
//                         helix/spec/vectors.json (activation_codec) and ./conformance.mjs.
//
// The int8 quantizer mirrors Python's round-half-to-even so the bytes match exactly.

// round half to even (Python's round()), so JS and Python quantize identically.
function rintEven(x) {
  const f = Math.floor(x);
  const d = x - f;
  if (d < 0.5) return f;
  if (d > 0.5) return f + 1;
  return f % 2 === 0 ? f : f + 1;
}

export class RawActivationCodec {
  encode(vec) { return { raw: vec.map((x) => x) }; }
  decode(payload) { return payload.raw.map((x) => x); }
}

export class Int8ActivationCodec {
  encode(vec) {
    let m = 0;
    for (const x of vec) m = Math.max(m, Math.abs(x));
    const scale = m > 0 ? m / 127 : 1.0;
    const q = Buffer.alloc(vec.length);
    for (let i = 0; i < vec.length; i++) {
      const c = Math.max(-127, Math.min(127, rintEven(vec[i] / scale)));
      q[i] = (c + 128) & 0xff;
    }
    return { q: q.toString("base64"), s: scale, n: vec.length };
  }

  decode(payload) {
    const raw = Buffer.from(payload.q, "base64");
    const scale = payload.s;
    return Array.from(raw, (b) => (b - 128) * scale);
  }
}
