package com.saturnmask.edge.distilled.host

import com.saturnmask.edge.distilled.engine.EngineKind
import com.saturnmask.edge.distilled.engine.InferenceEngine

/**
 * Marker documentation for the Chatter Triangle GGUF bridge.
 *
 * Implement [InferenceEngine] with `kind == [EngineKind.GGUF]` in the host (cui-llama.rn /
 * `Llama.useLlamaModelStore`) and register it on [com.saturnmask.edge.distilled.engine.EngineRegistry]
 * alongside [com.saturnmask.edge.distilled.engine.LiteRTInferenceEngine].
 *
 * This module deliberately does **not** depend on React Native or llama.cpp — the host owns that.
 */
@Suppress("unused")
object GgufInferenceEnginePort {
  val expectedKind: EngineKind = EngineKind.GGUF
}
