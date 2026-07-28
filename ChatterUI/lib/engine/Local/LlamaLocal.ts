import { closeFd, getContentFd } from '@vali98/react-native-fs'
import {
    CompletionParams,
    ContextParams,
    initLlama,
    LlamaContext,
    RNLLAMA_MTMD_DEFAULT_MEDIA_MARKER,
} from 'cui-llama.rn'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

import { Storage } from '@lib/enums/Storage'
import { AppDirectory, fileExists, readableFileSize, writeBase64File } from '@lib/utils/File'
import { ModelDataType } from 'db/schema'

import { checkGGMLDeprecated } from './GGML'
import { KV, Model } from './Model'
import { AppSettings } from '../../constants/GlobalValues'
import { Logger } from '../../state/Logger'
import { createMMKVStorage, mmkv } from '../../storage/MMKV'

export type CompletionTimings = {
    predicted_per_token_ms: number
    predicted_per_second: number | null
    predicted_ms: number
    predicted_n: number

    prompt_per_token_ms: number
    prompt_per_second: number | null
    prompt_ms: number
    prompt_n: number
}

export type CompletionOutput = {
    text: string
    timings: CompletionTimings
}

// Topology for a distributed (sharded) load — comes from HELIX's planner, see lib/helixPlacement.ts.
export type ShardParams = {
    rpc_servers: string[] // worker "host:port" rpc-servers, ring order
    tensor_split: number[] // ratios across [this phone, worker0, worker1, …]
    n_layers?: number // the model's block count, so every layer is eligible for placement
}

export type LlamaState = {
    context: LlamaContext | undefined
    /** True when `context` was loaded split across the mesh rather than wholly on this phone. */
    sharded: boolean
    model?: ModelDataType
    mmproj?: ModelDataType
    loadProgress: number
    chatCount: number
    promptCache?: string
    // `shard` (HELIX Level 3) loads the model distributed across other phones' llama.cpp
    // rpc-servers instead of wholly on this one. It goes through the normal load path on purpose:
    // the sharded context then IS the app's context, so chats use it like any other model.
    load: (model: ModelDataType, shard?: ShardParams) => Promise<void>
    loadMmproj: (model: ModelDataType) => Promise<void>
    setLoadProgress: (progress: number) => void
    unload: () => Promise<void>
    unloadMmproj: () => Promise<void>
    saveKV: (prompt: string | undefined, media_paths?: string[]) => Promise<void>
    loadKV: () => Promise<boolean>
    completion: (
        params: CompletionParams,
        callback: (text: string) => void,
        completed: (text: string, timngs: CompletionTimings) => void
    ) => Promise<void>
    stopCompletion: () => Promise<void>
    tokenLength: (text: string, mediaPaths?: string[]) => Promise<number>
    tokenize: (text: string, media_paths?: string[]) => Promise<{ tokens: number[] } | undefined>
}

export type LlamaConfig = {
    context_length: number
    threads: number
    gpu_layers: number
    batch: number
    ctx_shift: boolean
    devices: string[]
}

export type EngineDataProps = {
    config: LlamaConfig
    lastModel?: ModelDataType
    lastMmproj?: ModelDataType
    setConfiguration: (config: LlamaConfig) => void
    setLastModelLoaded: (model: ModelDataType | undefined) => void
    setLastMmprojLoaded: (model: ModelDataType | undefined) => void
    maybeClearLastLoaded: (mode: ModelDataType) => void
}

const sessionFile = `${AppDirectory.SessionPath}llama-session.bin`

const defaultConfig = {
    context_length: 4096,
    threads: 4,
    gpu_layers: 0,
    batch: 512,
    ctx_shift: true,
    devices: [],
}

export namespace Llama {
    export const useLlamaPreferencesStore = create<EngineDataProps>()(
        persist(
            (set, get) => ({
                config: defaultConfig,
                setConfiguration: (config: LlamaConfig) => {
                    set({ config: config })
                },
                setLastModelLoaded: (model: ModelDataType | undefined) => {
                    if (get().lastModel?.id === model?.id) return
                    set({ lastModel: model, lastMmproj: undefined })
                },
                setLastMmprojLoaded: (mmproj: ModelDataType | undefined) => {
                    set({ lastMmproj: mmproj })
                },
                maybeClearLastLoaded: (data) => {
                    if (data.id === get().lastModel?.id) {
                        set({ lastModel: undefined, lastMmproj: undefined })
                    } else if (data.id === get().lastMmproj?.id) {
                        set({ lastMmproj: undefined })
                    }
                },
            }),
            {
                name: Storage.EngineData,
                partialize: (state) => ({
                    config: state.config,
                    lastModel: state.lastModel,
                    lastMmproj: state.lastMmproj,
                }),
                storage: createMMKVStorage(),
                version: 3,
                migrate: (persistedState: any, version) => {
                    if (version === 1) {
                        persistedState.config.ctx_shift = true
                        Logger.info('Migrated to v2 EngineData')
                    }
                    if (version === 2) {
                        persistedState.config.devices = []
                        Logger.info('Migrated to v3 EngineData')
                    }
                },
            }
        )
    )

    export const useLlamaModelStore = create<LlamaState>()((set, get) => ({
        context: undefined,
        sharded: false,
        loadProgress: 0,
        chatCount: 0,
        promptCache: undefined,
        load: async (model: ModelDataType, shard?: ShardParams) => {
            const config = useLlamaPreferencesStore.getState().config

            if (get()?.model?.id === model.id) {
                return Logger.errorToast('Model Already Loaded!')
            }

            if (checkGGMLDeprecated(parseInt(model.quantization))) {
                return Logger.errorToast('Quantization No Longer Supported!')
            }

            if (!(await Model.getModelExists(model.file_path))) {
                Logger.errorToast('Model Does Not Exist!')
                Model.verifyModelList()
                return
            }

            if (get().context !== undefined) {
                await get().unload()
            }

            let model_path = model.file_path
            if (model.file_path.includes('content://')) {
                model_path = (await getContentFd(model_path)) ?? model_path
            }

            const params: ContextParams = {
                model: model_path,
                n_ctx: config.context_length,
                n_threads: config.threads,
                n_batch: config.batch,
                ctx_shift: config.ctx_shift,
                // llama.cpp registers each rpc-server as an OFFLOAD device, and n_gpu_layers is
                // what decides how many layers are eligible to leave the local CPU. At the app's
                // usual 0, none are — so tensor_split has nothing to divide and this phone loads
                // and runs the whole model itself, which is the opposite of sharding. Every layer
                // has to be placeable for the split to mean anything; 99 is llama.cpp's own
                // "all of them" idiom and is clamped to the real block count.
                n_gpu_layers: shard ? (shard.n_layers && shard.n_layers > 0 ? shard.n_layers : 99) : config.gpu_layers,
                // mlock pins the whole file in this phone's physical RAM. That is exactly the
                // memory a shard is meant not to need, so it stays off when sharding; mmap remains
                // on either way, letting the tensors bound for a worker stream from the file
                // instead of being materialised here first.
                use_mlock: !shard,
                use_mmap: true,
                devices: config.devices,
                // Present only for a sharded load: llama.cpp registers each rpc-server as a remote
                // device and splits the layers by these ratios.
                ...(shard ? { rpc_servers: shard.rpc_servers, tensor_split: shard.tensor_split } : {}),
            }

            Logger.info(
                `\n------ MODEL LOAD -----\n Model Name: ${model.name}\nStarting with parameters: \nContext Length: ${params.n_ctx}\nThreads: ${params.n_threads}\nBatch Size: ${params.n_batch}\nGPU Layers: ${params.n_gpu_layers}` +
                    (shard
                        ? `\nSharded across: ${shard.rpc_servers.join(', ')}\nTensor split: ${shard.tensor_split
                              .map((x) => x.toFixed(3))
                              .join(', ')}`
                        : '')
            )

            const progressCallback = (progress: number) => {
                if (progress % 5 === 0) get().setLoadProgress(progress)
            }

            const llamaContext = await initLlama(params, progressCallback).catch((error) => {
                Logger.errorToast(`Could Not Load Model: ${error} `)
                if (model.file_path.includes('content://')) {
                    closeFd(model_path)
                }
            })

            if (!llamaContext) return

            set({
                context: llamaContext,
                sharded: !!shard,
                model: model,
                chatCount: 1,
            })

            // updated EngineData
            useLlamaPreferencesStore.getState().setLastModelLoaded(model)
            KV.useKVStore.getState().setKvCacheLoaded(false)
        },
        loadMmproj: async (model: ModelDataType) => {
            const context = get().context
            if (!context) return

            let model_path = model.file_path
            if (model.file_path.includes('content://')) {
                model_path = (await getContentFd(model_path)) ?? model_path
            }

            Logger.info('Loading MMPROJ')
            await context.initMultimodal({ path: model_path, use_gpu: true }).catch((e) => {
                if (model.file_path.includes('content://')) {
                    closeFd(model_path)
                }

                Logger.errorToast('Failed to load MMPROJ: ' + e)
            })
            if (await context.isMultimodalEnabled()) {
                const capabilities = await context.getMultimodalSupport()
                Logger.info(
                    `MMPROJ Loaded:\n- Vision: ${capabilities.vision}\n- Audio: ${capabilities.audio}`
                )
            }

            set({
                mmproj: model,
            })

            useLlamaPreferencesStore.getState().setLastMmprojLoaded(model)
        },
        setLoadProgress: (progress: number) => {
            set({ loadProgress: progress })
        },
        unload: async () => {
            if (get().mmproj) {
                await get().context?.releaseMultimodal()
            }

            await get().context?.release()
            set({
                context: undefined,
                sharded: false,
                model: undefined,
                mmproj: undefined,
            })
            Logger.info('Model Unloaded')
        },
        unloadMmproj: async () => {
            if (!get().mmproj) return
            await get()
                .context?.releaseMultimodal()
                .catch((e) => {
                    Logger.errorToast('Failed to unload MMPROJ: ' + e)
                })
            set({
                mmproj: undefined,
            })
        },
        completion: async (
            params: CompletionParams,
            callback = (text: string) => {},
            completed = (text: string) => {}
        ) => {
            const llamaContext = get().context
            if (llamaContext === undefined) {
                Logger.errorToast('No Model Loaded')
                return
            }

            return llamaContext
                .completion(params, (data) => {
                    callback(data.token)
                })
                .then(async ({ text, timings }: CompletionOutput) => {
                    completed(text, timings)
                    Logger.info(
                        `\n---- Start Chat ${get().chatCount} ----\n${textTimings(timings)}\n---- End Chat ${get().chatCount} ----\n`
                    )
                    set({ chatCount: get().chatCount + 1 })
                    if (mmkv.getBoolean(AppSettings.SaveLocalKV)) {
                        await get().saveKV(params.prompt, params.media_paths ?? [])
                    }
                })
        },
        stopCompletion: async () => {
            await get().context?.stopCompletion()
        },
        saveKV: async (prompt, media_paths) => {
            const llamaContext = get().context
            if (!llamaContext) {
                Logger.errorToast('No Model Loaded')
                return
            }

            if (prompt) {
                const tokens = (await get().tokenize(prompt, media_paths ?? []))?.tokens
                KV.useKVStore.getState().setKvCacheTokens(tokens ?? [])
            }

            if (!fileExists(sessionFile)) {
                Logger.warn('Session file does not exist, creating...')
                await writeBase64File(sessionFile, '')
            }

            const now = performance.now()
            const data = await llamaContext.saveSession(sessionFile.replace('file://', ''))
            Logger.info(
                data === -1
                    ? 'Failed to save KV cache'
                    : `Saved KV in ${Math.floor(performance.now() - now)}ms with ${data} tokens`
            )
            Logger.info(`Current KV Size is: ${readableFileSize(await KV.getKVSize())}`)
        },
        loadKV: async () => {
            let result = false
            const llamaContext = get().context
            if (!llamaContext) {
                Logger.errorToast('No Model Loaded')
                return false
            }
            if (!fileExists(sessionFile)) {
                Logger.warn('No Cache found')
                return false
            }
            await llamaContext
                .loadSession(sessionFile.replace('file://', ''))
                .then(() => {
                    Logger.info('Session loaded from KV cache')
                    result = true
                })
                .catch(() => {
                    Logger.error('Session loaded could not load from KV cache')
                })
            return result
        },
        tokenLength: async (text: string, mediaPaths: string[] = []) => {
            const finalPaths = get().mmproj ? mediaPaths : []
            if (!get().mmproj && mediaPaths.length > 0) {
                Logger.warnToast('Media was added without MMPROJ model')
            }
            const result = await get().context?.tokenize(
                text + finalPaths.map(() => RNLLAMA_MTMD_DEFAULT_MEDIA_MARKER).join(),
                {
                    media_paths: finalPaths.map((item) => item.replace('file://', '')),
                }
            )
            if (!result) return 0
            return result.tokens.length
        },
        tokenize: async (text: string, media_paths: string[] = []) => {
            const params = get().mmproj ? { media_paths } : {}
            return await get().context?.tokenize(text, params)
        },
    }))

    const textTimings = (timings: CompletionTimings) => {
        return (
            `\n[Prompt Timings]` +
            (timings.prompt_n > 0
                ? `\nPrompt Per Token: ${timings.prompt_per_token_ms.toFixed(2)} ms/token` +
                  `\nPrompt Per Second: ${timings.prompt_per_second?.toFixed(2) ?? 0} tokens/s` +
                  `\nPrompt Time: ${(timings.prompt_ms / 1000).toFixed(2)}s` +
                  `\nPrompt Tokens: ${timings.prompt_n} tokens`
                : '\nNo Tokens Processed') +
            `\n\n[Predicted Timings]` +
            (timings.predicted_n > 0
                ? `\nPredicted Per Token: ${timings.predicted_per_token_ms.toFixed(2)} ms/token` +
                  `\nPredicted Per Second: ${timings.predicted_per_second?.toFixed(2) ?? 0} tokens/s` +
                  `\nPrediction Time: ${(timings.predicted_ms / 1000).toFixed(2)}s` +
                  `\nPredicted Tokens: ${timings.predicted_n} tokens\n`
                : '\nNo Tokens Generated')
        )
    }
}
