package expo.modules.edgelitert

import android.content.Context
import android.os.Build
import com.saturnmask.edge.distilled.engine.EngineGenerateRequest
import com.saturnmask.edge.distilled.engine.EngineKind
import com.saturnmask.edge.distilled.engine.EngineLoadRequest
import com.saturnmask.edge.distilled.engine.EngineRegistry
import com.saturnmask.edge.distilled.engine.LiteRTInferenceEngine
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * TriangleUI's second inference engine: LiteRT-LM (Google AI Edge), alongside llama.cpp/GGUF.
 *
 * The JavaScript surface is deliberately the one `InferenceEngine` already defines — activate,
 * load, generate, stop, unload — and not a second API layered over it. Anything richer would be a
 * translation to keep in sync with a Kotlin interface that already says the same thing.
 *
 * Hot-swap lives in `EngineRegistry`: `activate(kind)` unloads whatever was resident before taking
 * over, so switching engines does not need the process restarted. Only LITERT is registered here.
 * GGUF stays with cui-llama.rn on the JS side, because that is where its context, its sharding and
 * its RPC ring already live; giving this module a second, competing handle on the same llama.cpp
 * context would be a way to load a model twice.
 *
 * NOT YET VERIFIED ON A DEVICE. It is written the way every other native module in this app was
 * written — against the sources and the docs — and every one of those needed a pass against real
 * phone logs afterwards. Compiling is not the proof; the first real run is.
 */
class EdgeLiteRtModule : Module() {

    private val registry = EngineRegistry().apply { register(LiteRTInferenceEngine()) }

    // The engine's own generate() is callback-based and its load() is suspending, so the module
    // owns one scope rather than borrowing whichever thread a JS call arrived on. SupervisorJob:
    // one failed load must not cancel the scope every later call depends on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val context: Context
        get() = requireNotNull(appContext.reactContext) { "no react context" }

    override fun definition() = ModuleDefinition {
        Name("EdgeLiteRt")

        // One event, carrying partial text as it is produced. `done` is on the event rather than a
        // separate event because a stream that ends is one fact, and two events would let a
        // listener see the end before the last chunk.
        Events("onToken", "onError")

        /**
         * True when this build contains the engine AND this phone can run it.
         *
         * LiteRT-LM needs API 31. The module's manifest overrides that so the app stays
         * installable below it; this is where an older phone is told the truth, in time for the
         * engine registry not to offer LiteRT at all — rather than at the first generate(), as a
         * crash from a native library that was never going to load.
         */
        Function("isAvailable") { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }

        AsyncFunction("activate") { kind: String, promise: Promise ->
            scope.launch {
                try {
                    registry.activate(parseKind(kind))
                    promise.resolve(true)
                } catch (e: Throwable) {
                    promise.reject("ERR_ACTIVATE", e.message ?: "could not activate $kind", e)
                }
            }
        }

        AsyncFunction("load") { modelPath: String, modelId: String, supportImage: Boolean, supportAudio: Boolean, promise: Promise ->
            scope.launch {
                try {
                    registry.loadActive(
                        context,
                        EngineLoadRequest(
                            modelPath = modelPath,
                            modelId = modelId,
                            supportImage = supportImage,
                            supportAudio = supportAudio,
                        ),
                    )
                    promise.resolve(true)
                } catch (e: Throwable) {
                    promise.reject("ERR_LOAD", e.message ?: "could not load $modelId", e)
                }
            }
        }

        /**
         * Streams by event and resolves with the whole answer.
         *
         * Both, because they answer different needs: the events are what a chat renders as it
         * arrives, and the resolved string is what a caller stores without having to reassemble
         * chunks it may have missed while a screen was unmounted.
         */
        AsyncFunction("generate") { prompt: String, promise: Promise ->
            val engine = registry.active()
            if (engine == null) {
                promise.reject("ERR_NO_ENGINE", "no active engine — call activate() first", null)
                return@AsyncFunction
            }
            if (!engine.isLoaded) {
                promise.reject("ERR_NOT_LOADED", "no model is loaded — call load() first", null)
                return@AsyncFunction
            }

            val whole = StringBuilder()
            var settled = false
            engine.generate(
                EngineGenerateRequest(prompt = prompt),
                onPartial = { text, done ->
                    whole.append(text)
                    sendEvent("onToken", mapOf("text" to text, "done" to done))
                    // The promise settles once, on the first terminal signal. A second `done` from
                    // an engine that repeats itself must not crash the bridge.
                    if (done && !settled) {
                        settled = true
                        promise.resolve(whole.toString())
                    }
                },
                onError = { message ->
                    sendEvent("onError", mapOf("message" to message))
                    if (!settled) {
                        settled = true
                        promise.reject("ERR_GENERATE", message, null)
                    }
                },
                coroutineScope = scope,
            )
        }

        Function("stop") { registry.active()?.stop() }

        AsyncFunction("unload") { promise: Promise ->
            scope.launch {
                try {
                    registry.unloadActive()
                    promise.resolve(true)
                } catch (e: Throwable) {
                    promise.reject("ERR_UNLOAD", e.message ?: "could not unload", e)
                }
            }
        }

        Function("activeKind") { registry.active()?.kind?.name?.lowercase() }

        Function("isLoaded") { registry.active()?.isLoaded == true }
    }

    private fun parseKind(kind: String): EngineKind =
        when (kind.lowercase()) {
            "litert" -> EngineKind.LITERT
            "gguf" ->
                // Refused rather than accepted-and-ignored: GGUF is owned by cui-llama.rn on the JS
                // side, and an activate("gguf") that quietly succeeded here would leave two layers
                // each believing they hold the model.
                throw IllegalArgumentException(
                    "GGUF is not registered in this module — it is driven by cui-llama.rn on the JS side"
                )
            else -> throw IllegalArgumentException("unknown engine kind: $kind")
        }
}
