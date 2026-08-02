# LiteRT (Google AI Edge) → TriangleUI: what was merged, and what is not done

Merged from `cursor/gedge-core-distillation-20b0` into the TriangleUI/HELIX branch, which is the
step [`EDGE_INVENTORY.md`](EDGE_INVENTORY.md) names last ("Next assimilation PR should open on that
branch").

## What came across

Only `Android/src/edge/` and `Android/src/edge-distilled/` — 97 files, ~960 KB. That is the
distilled library and nothing else.

**Deliberately left behind:** the Gallery app itself, its Compose screens, the `skills/` tree and
its several hundred MP3s. Those are ~60 000 lines and tens of megabytes that would ride into an APK
this repo builds on every push, in exchange for nothing TriangleUI uses. `EDGE_INVENTORY.md`
already lists them under "Intentionally NOT distilled"; this merge honours that.

## What is now true

`Android/src/edge/llm/.../engine/InferenceEngine.kt` gives exactly the seam
`ChatterUI/lib/helixEngines.ts` was written against:

- `EngineKind.LITERT` / `EngineKind.GGUF`
- `InferenceEngine` — `load` / `unload` / `generate` / `stop`
- `EngineRegistry` — `activate(kind)` unloads the previous engine and swaps without a restart
- `AgentRunnerLiteRt` — HELIX Track A `submit` / `poll` / `score` / `cardJson`, i.e. a LiteRT device
  can be a Pointer agent

## What is NOT done, and why the app still says LiteRT is unavailable

**JavaScript cannot call any of this.** TriangleUI is React Native; the Kotlin above is reachable
only through a native module, the way `modules/wifi-hotspot` and `modules/bitchat-ble` are. Nothing
of that kind exists for the edge modules yet.

`helixEngines.ts` therefore keeps `litert.available = false`. That is not pessimism — a registry
that claims an engine it cannot reach converts a missing bridge into a crash somewhere unrelated,
and the whole point of declaring the seam early was to avoid exactly that.

### The remaining work, in order

1. **Gradle** — Expo prebuild generates `ChatterUI/android/`, so `settings.gradle` needs the
   `:edge:*` includes and the app module needs to depend on `:edge:distilled`. Prebuild regenerates
   that directory, so this belongs in a config plugin, not in a hand-edited file that vanishes.
2. **Expo module** — `ChatterUI/modules/edge-litert/`, exposing `activate(kind)`, `load(request)`,
   `generate(prompt)` with a token event, and `unload()`. Small surface on purpose: it is the seam
   `InferenceEngine` already defines, not a second API over it.
3. **Flip the flag** — `ENGINES.litert.available` becomes true only when the module is present, by
   the same lazy `require` pattern the other native modules use, so an APK built without it degrades
   to a clear message rather than a crash.
4. **Hybrid** — `AgentRunnerLiteRt` into the existing mesh transport, so a shell device joins as a
   Pointer agent. `HYBRID_TARGET` in `helixEngines.ts` records the 3-core / 3-shell shape this aims
   at.

### Two conflicts to expect at step 1

Both are already flagged in `EDGE_INVENTORY.md` and both bite specifically in an RN host:

- **`litertlm-android` vs `com.google.ai.edge.litert:litert`** — adding both ships `libLiteRt.so`
  twice and the build fails at packaging. Keep only the former.
- **Compose icons in `:edge:tools-mobile`** — ChatterUI has no Compose. Strip the icons from
  `Actions`, or keep that module out of the RN packaging entirely.

Neither is a reason to delay the merge: the sources are inert until step 1 runs.
