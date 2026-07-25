import { copyFileSAF, getContentFd, persistContentPermission } from '@vali98/react-native-fs'
import { loadLlamaModelInfo } from 'cui-llama.rn'
import { eq, inArray, notInArray, or } from 'drizzle-orm'
import { getDocumentAsync } from 'expo-document-picker'
import { Platform } from 'react-native'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

import { db } from '@db'
import { Storage } from '@lib/enums/Storage'
import { Logger } from '@lib/state/Logger'
import { createMMKVStorage } from '@lib/storage/MMKV'
import {
    AppDirectory,
    copyFile,
    deleteFile,
    fileExists,
    fileInfo,
    listFiles,
    readableFileSize,
} from '@lib/utils/File'
import { model_data, model_mmproj_links, ModelDataType } from 'db/schema'

import { GGMLNameMap, GGMLType } from './GGML'

export type ModelData = Omit<ModelDataType, 'id' | 'create_date' | 'last_modified'>
export type ModelListQueryType = Omit<
    Awaited<ReturnType<typeof Model.getModelListQuery2>>[0],
    'mmprojLink'
> & {
    mmprojLink?: {
        model_id: number
        mmproj_id: number
    }
}
const mmprojArchs = ['clip', 'llava']

export namespace Model {
    export const getModelList = async () => {
        return listFiles(AppDirectory.ModelPath)
    }

    export const deleteModelById = async (id: number) => {
        const modelInfo = await db.query.model_data.findFirst({ where: eq(model_data.id, id) })
        if (!modelInfo) return
        // some models may be external
        if (modelInfo.file_path.startsWith(AppDirectory.ModelPath))
            await deleteModel(modelInfo.file)
        await db.delete(model_data).where(eq(model_data.id, id))
    }

    export const isMMPROJ = (arch: string) => {
        return mmprojArchs.includes(arch)
    }

    export const importModel = async () => {
        return getDocumentAsync({
            copyToCacheDirectory: false,
        }).then(async (result) => {
            if (result.canceled) return
            const file = result.assets[0]
            const name = file.name
            const newdir = `${AppDirectory.ModelPath}${name}`
            Logger.infoToast('Importing file...')
            let success = false
            console.log(file.uri, '\n', newdir)

            if (file.uri.startsWith('content://') && Platform.OS === 'android') {
                await copyFileSAF(file.uri, newdir.replace('file://', ''))
                    .then(() => {
                        success = true
                    })
                    .catch((e) => {
                        Logger.warnToast('Failed to copy')
                        Logger.warn(JSON.stringify(e))
                        success = false
                    })
            } else {
                success = await copyFile({
                    from: file.uri,
                    to: newdir,
                })
            }

            if (!success) return

            // database routine here
            if (await createModelData(name, true)) Logger.infoToast(`Model Imported Sucessfully!`)
        })
    }

    export const linkModelExternal = async () => {
        return getDocumentAsync({
            copyToCacheDirectory: false,
        }).then(async (result) => {
            if (result.canceled) return
            const file = result.assets[0]
            Logger.infoToast('Importing file...')
            if (!file) {
                Logger.errorToast('File Invalid')
                return
            }

            if (await createModelDataExternal(file.uri, file.name)) {
                persistContentPermission(file.uri)
                Logger.infoToast(`Model Imported Sucessfully!`)
            }
        })
    }

    export const getModelExists = (path: string) => {
        return fileExists(path)
    }

    export const verifyModelList = async () => {
        const fileList = await getModelList()

        // Cull missing models. Sequential on purpose: `forEach(async …)` does not await, so the
        // refresh below used to race deletes that were still in flight.
        if (Platform.OS === 'android') {
            // cull not required on iOS
            for (const item of await db.query.model_data.findMany()) {
                if (item.name === '' || !getModelExists(item.file_path)) {
                    Logger.warnToast(`Model Missing, its entry will be deleted: ${item.name}`)
                    await db.delete(model_data).where(eq(model_data.id, item.id))
                }
            }
        }

        // refresh as some may have been deleted
        let modelList = await db.query.model_data.findMany()

        // Retry entries whose metadata never loaded — they show up as "Model Is Invalid". Such a
        // row does nothing except block re-importing that file (`file` is UNIQUE), so if it still
        // won't parse, drop the row. The model file itself is left untouched.
        const unreadable = new Set<string>()
        for (const stale of modelList.filter(isInitialEntry)) {
            Logger.info(`Revalidating invalid model entry: ${stale.file}`)
            await db.delete(model_data).where(eq(model_data.id, stale.id))
            if (!(await setModelDataInternal(stale.file, stale.file_path, false))) {
                Logger.warnToast(`Removed unreadable model entry: ${stale.file}`)
                unreadable.add(stale.file)
            }
        }

        // refresh again as revalidation may have added or dropped rows
        modelList = await db.query.model_data.findMany()

        // create data as migration step
        for (const item of fileList) {
            if (modelList.some((model_data) => model_data.file === item) || !item) continue
            // Just proved this one can't be read — don't immediately re-add it below.
            if (unreadable.has(item)) continue
            Logger.info(`Creating Model Data for: ${item}`)
            await createModelData(item)
        }
    }

    export const createModelData = async (filename: string, deleteOnFailure: boolean = false) => {
        return setModelDataInternal(
            filename,
            `${AppDirectory.ModelPath}${filename}`,
            deleteOnFailure
        )
    }

    export const createModelDataExternal = async (
        newdir: string,
        filename: string,
        deleteOnFailure: boolean = false
    ) => {
        if (!filename) {
            Logger.errorToast('Filename invalid, Import Failed')
            return
        }
        return setModelDataInternal(filename, newdir, deleteOnFailure)
    }

    export const getModelListQuery = () => {
        return db.query.model_data.findMany()
    }

    export const getModelListQuery2 = () => {
        return db.query.model_data.findMany({
            where: notInArray(model_data.architecture, mmprojArchs),
            with: {
                mmprojLink: true,
            },
        })
    }

    export const getMMPROJListQuery = () => {
        return db.query.model_data.findMany({
            where: inArray(model_data.architecture, mmprojArchs),
            with: {
                mmprojLink: true,
            },
        })
    }

    export const getMMPROJLinks = () => {
        return db.query.model_mmproj_links.findMany()
    }

    export const createMMPROJLink = async (
        model: ModelListQueryType,
        mmproj: ModelListQueryType
    ) => {
        await db.insert(model_mmproj_links).values({ model_id: model.id, mmproj_id: mmproj.id })
    }

    export const removeMMPROJLink = async (model: ModelListQueryType) => {
        await db.delete(model_mmproj_links).where(eq(model_mmproj_links.model_id, model.id))
    }

    export const updateName = async (name: string, id: number) => {
        await db.update(model_data).set({ name: name }).where(eq(model_data.id, id))
    }

    export const isInitialEntry = (data: ModelListQueryType) => {
        const initial: ModelData = {
            file: '',
            file_path: '',
            context_length: 0,
            name: 'N/A',
            file_size: 0,
            params: 'N/A',
            quantization: '-1',
            architecture: 'N/A',
        }

        for (const key in initial) {
            if (key === 'file' || key === 'file_path') continue
            const initialV = initial[key as keyof ModelData]
            const dataV = data[key as keyof ModelListQueryType]
            if (initialV !== dataV) return false
        }
        return true
    }

    const initialModelEntry = (filename: string, file_path: string) => ({
        context_length: 0,
        file: filename,
        file_path: file_path,
        name: 'N/A',
        file_size: 0,
        params: 'N/A',
        quantization: '-1',
        architecture: 'N/A',
    })

    // Errors here arrive from three layers (JS, expo modules, native llama.cpp) and `String(e)`
    // drops the detail on some of them, so pull out whatever each actually carries.
    const stringifyError = (e: unknown) => {
        if (!(e instanceof Error)) return String(e)
        const parts = [e.message]
        // Expo/JSI errors often carry the real reason on `cause` rather than in the message.
        if (e.cause) parts.push(`cause: ${e.cause}`)
        if (e.stack) parts.push(e.stack)
        return parts.join('\n')
    }

    // expo-sqlite reports native failures as "Call to function 'NativeStatement.runSync' has been
    // rejected. → Caused by: <the actual reason>" — and a toast truncates away the half that
    // matters. Surface the cause, and name the case users actually hit (re-importing a model).
    const describeError = (e: unknown) => {
        const raw = e instanceof Error ? e.message : String(e)
        const cause = raw.split(/→\s*Caused by:\s*/).pop()?.trim() || raw
        if (/UNIQUE constraint failed/i.test(cause)) return 'this model is already in the list'
        return cause
    }

    // Other on-device runtimes' formats. llama.cpp reads GGUF only, so these can never load —
    // say that plainly instead of letting the GGUF parser fail with something cryptic.
    const foreignModelFormats = ['.litertlm', '.task', '.tflite', '.onnx', '.safetensors', '.pte']

    const foreignFormatOf = (filename: string) =>
        foreignModelFormats.find((ext) => filename.toLowerCase().endsWith(ext))

    const setModelDataInternal = async (
        filename: string,
        file_path: string,
        deleteOnFailure: boolean
    ) => {
        let insertedId: number | undefined
        try {
            const foreign = foreignFormatOf(filename)
            if (foreign) {
                Logger.errorToast(`${foreign} is not a GGUF model — ChatterUI runs GGUF files only`)
                return false
            }

            // `file` and `file_path` are UNIQUE. Check up front so a duplicate import reports
            // itself plainly instead of failing later as a raw native constraint violation.
            const duplicate = await db.query.model_data.findFirst({
                where: or(eq(model_data.file, filename), eq(model_data.file_path, file_path)),
            })
            if (duplicate) {
                Logger.errorToast(`Already in the model list: ${filename}`)
                return false
            }

            const [{ id }] = await db
                .insert(model_data)
                .values(initialModelEntry(filename, file_path))
                .returning({ id: model_data.id })
            insertedId = id

            // This will load GGUF KV-pairs
            // refer to https://github.com/ggml-org/ggml/blob/master/docs/gguf.md#standardized-key-value-pairs
            let loadable_path = file_path
            if (loadable_path.includes('content://'))
                loadable_path = (await getContentFd(loadable_path)) ?? loadable_path

            const modelInfo: any = await loadLlamaModelInfo(loadable_path)
            // File size is cosmetic (it's only ever displayed). Statting can fail on its own —
            // notably for a `content://` path, which is what `file_path` still holds for an
            // externally linked model — and that must not sink an import whose GGUF already read
            // fine above.
            let fileSize = 0
            try {
                const fileResult = fileInfo(file_path)
                if (fileResult.exists) {
                    fileSize = fileResult.size ?? 0
                }
            } catch (sizeError) {
                Logger.warn(`Could not stat "${filename}" for its size: ${sizeError}`)
            }
            const modelType = modelInfo?.['general.architecture']
            const modelDataEntry = {
                context_length: modelInfo?.[modelType + '.context_length'] ?? 0,
                file: filename,
                file_path: file_path,
                name: modelInfo?.['general.name'] ?? 'N/A',
                file_size: fileSize,
                params: modelInfo?.['general.size_label'] ?? filename ?? 'N/A',
                quantization: modelInfo?.['general.file_type'] ?? '-1',
                architecture: modelType ?? 'N/A',
            }
            Logger.info(`New Model Data:\n${modelDataText(modelDataEntry)}`)
            await db.update(model_data).set(modelDataEntry).where(eq(model_data.id, id))
            return true
        } catch (e) {
            // A toast truncates, and the interesting part of these errors is at the end — so the
            // untruncated original also goes to the log, where it can be read and copied.
            Logger.error(`Import failed for "${filename}" (${file_path}): ${stringifyError(e)}`)
            // Reason first: the toast truncates, and a long filename would otherwise crowd out the
            // only part that says what went wrong.
            Logger.errorToast(`${describeError(e)} — ${filename}`)
            // Never leave the half-written 'N/A' row behind. It renders as a dead "Model Is
            // Invalid" card, and because `file` is UNIQUE it also blocks re-importing the same
            // model — the failure would then masquerade as a constraint error on every retry.
            if (insertedId !== undefined) {
                try {
                    await db.delete(model_data).where(eq(model_data.id, insertedId))
                } catch (cleanupError) {
                    Logger.warn(`Failed to roll back model entry: ${cleanupError}`)
                }
            }
            if (deleteOnFailure) deleteFile(file_path)
            return false
        }
    }

    const modelDataText = (data: ModelData) => {
        const quantValue = parseInt(data.quantization) as GGMLType
        const quantType = GGMLNameMap[quantValue]
        return `Context length: ${data.context_length ?? 'N/A'}\nFile: ${data.file}\nName: ${data.name ?? 'N/A'}\nSize: ${(data.file_size && readableFileSize(data.file_size)) ?? 'N/A'}\nParams: ${data.params ?? 'N/A'}\nQuantization: ${quantType ?? 'N/A'}\nArchitecture: ${data.architecture ?? 'N/A'}`
    }

    const modelExists = async (modelName: string) => {
        return (await getModelList()).includes(modelName)
    }

    const deleteModel = async (name: string) => {
        if (!(await modelExists(name))) return
        return deleteFile(`${AppDirectory.ModelPath}${name}`)
    }
}

type KvVerifyResult = {
    match: boolean
    matchLength: number
    inputLength: number
    cachedLength: number
}

type KVStateProps = {
    kvCacheLoaded: boolean
    kvCacheTokens: number[]
    setKvCacheLoaded: (b: boolean) => void
    setKvCacheTokens: (na: number[]) => void
    verifyKVCache: (na: number[]) => KvVerifyResult
}

export namespace KV {
    export const useKVStore = create<KVStateProps>()(
        persist(
            (set, get) => ({
                kvCacheLoaded: false,
                kvCacheTokens: [],
                setKvCacheLoaded: (b: boolean) => {
                    set({ kvCacheLoaded: b })
                },
                setKvCacheTokens: (tokens: number[]) => {
                    set({ kvCacheTokens: tokens })
                },
                verifyKVCache: (tokens: number[]) => {
                    const cachedTokens = get().kvCacheTokens
                    let matched = 0
                    const [a, b] =
                        cachedTokens.length <= tokens.length
                            ? [cachedTokens, tokens]
                            : [tokens, cachedTokens]
                    a.forEach((v, i) => {
                        if (v === b[i]) matched++
                    })
                    return {
                        match: matched === a.length,
                        cachedLength: cachedTokens.length,
                        inputLength: tokens.length,
                        matchLength: matched,
                    }
                },
            }),
            {
                name: Storage.KV,
                partialize: (state) => ({
                    kvCacheTokens: state.kvCacheTokens,
                }),
                storage: createMMKVStorage(),
                version: 1,
            }
        )
    )

    export const sessionFile = `${AppDirectory.SessionPath}llama-session.bin`

    export const getKVSize = async () => {
        const data = fileInfo(sessionFile)
        return data.size ?? 0
    }

    export const deleteKV = async () => {
        deleteFile(sessionFile)
    }

    export const kvInfo = async () => {
        const data = fileInfo(sessionFile)
        if (!data.exists) {
            Logger.warn('No KV Cache found')
            return
        }
        Logger.info(`Size of KV cache: ${Math.floor(data.size ?? 0 * 0.000001)} MB`)
    }
}
