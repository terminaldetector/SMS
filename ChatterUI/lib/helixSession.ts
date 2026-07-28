// The live mesh session, owned by the app rather than by whichever screen is on top.
//
// It started life as module-level variables inside HelixMeshScreen, which was already right about
// one thing — a session must survive navigating away — but wrong about where it belongs: the
// inference layer needs to reach the coordinator too, and an engine cannot sensibly import a
// screen. This is that same state, in a place both can use.

import { HelixAgentNode } from './helixAgent'
import { HelixCoordinator } from './helixCoordinator'

export type MeshMode = 'hybrid' | 'pointer' | 'sharder'

/** How a mesh with several agents settles on one answer. */
export type MeshAnswerMode = 'single' | 'voting'

interface MeshSession {
    coord: HelixCoordinator | null
    agent: HelixAgentNode | null
    agentOnline: boolean
    agentJoinedHotspot: boolean
    hotspotActive: boolean
    hostIp: string
    hostIpTransport: string
    hostQrExtra: string
    /** Mirrors the persisted setting, so non-React code can read it without touching MMKV. */
    mode: MeshMode
    answerMode: MeshAnswerMode
}

export const meshSession: MeshSession = {
    coord: null,
    agent: null,
    agentOnline: false,
    agentJoinedHotspot: false,
    hotspotActive: false,
    hostIp: '',
    hostIpTransport: '',
    hostQrExtra: '',
    mode: 'hybrid',
    answerMode: 'single',
}

/**
 * Whether an ordinary chat should be answered by the mesh rather than by this phone.
 *
 * Only Pointer: that is the mode where another phone holds a whole model and answers as an agent.
 * Sharder needs nothing here — its split model IS this phone's context, so the normal local path
 * already runs across the mesh without knowing it.
 */
export function meshCanAnswer(): boolean {
    return (
        meshSession.mode === 'pointer' &&
        !!meshSession.coord &&
        meshSession.coord.agents().length > 0
    )
}
