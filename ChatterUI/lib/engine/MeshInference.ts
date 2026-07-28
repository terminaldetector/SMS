// Answers an ordinary chat from the mesh instead of from this phone (Pointer / Track A).
//
// The prompt is built by exactly the same path the local engine uses — same character, persona,
// instruct template and context window — so a mesh answer is not a different kind of conversation,
// only a different machine producing it. What changes is the last step: instead of running the
// local model, the built prompt goes to the coordinator, which asks a joined phone's whole model.
//
// Sharder deliberately has nothing here. Its split model IS this phone's context, so the ordinary
// local path already runs across the mesh; routing it through here as well would be wrong.

import { Chats, useInference } from '@lib/state/Chat'
import { Logger } from '@lib/state/Logger'
import { meshSession } from '@lib/helixSession'

import { buildLocalPayload } from './LocalInference'

const stopGenerating = () => {
    Chats.useChatState.getState().stopGenerating()
}

export const meshInference = async () => {
    try {
        const coord = meshSession.coord
        if (!coord) {
            Logger.errorToast('Not hosting a mesh — start hosting in HELIX Mesh, or switch off Pointer')
            return stopGenerating()
        }
        if (coord.agents().length === 0) {
            Logger.errorToast('No phone has joined the mesh to answer')
            return stopGenerating()
        }

        const payload = await buildLocalPayload()
        if (!payload || !payload.prompt) {
            Logger.errorToast('Could not build the prompt')
            return stopGenerating()
        }

        // The coordinator has no cancel of its own — the request is already with the other phone —
        // so aborting stops us waiting for it rather than pretending to recall it.
        let abandoned = false
        useInference.getState().setAbort(async () => {
            abandoned = true
        })

        const answer = await coord.infer(payload.prompt, meshSession.answerMode)
        if (abandoned) return stopGenerating()

        // No token stream: an agent returns its answer whole, so the buffer is filled once rather
        // than grown. Timings are the local engine's shape and mean nothing for a remote answer,
        // so they are left alone instead of being invented.
        Chats.useChatState.getState().setBuffer({ data: answer })
        stopGenerating()
    } catch (e) {
        Logger.errorToast(`Mesh answer failed: ${e instanceof Error ? e.message : String(e)}`)
        stopGenerating()
    }
}
