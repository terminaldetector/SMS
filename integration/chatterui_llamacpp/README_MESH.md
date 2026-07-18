# ChatterUI × HELIX — mesh mod + APK for first experiments

Adds a **HELIX Mesh** screen to ChatterUI (Level 1): the app becomes a UI over a HELIX node —
connect to a running mesh, see its agents, and run a prompt across it (single / parallel / voting,
or a fused SuperAgent). Pure `fetch`, **no new native module**.

> **The APK cannot be built in the HELIX cloud dev environment** — the Android SDK/NDK aren't
> installed there and the agent proxy blocks Google's download/Maven hosts (`dl.google.com` → 403).
> Build on your own machine with the Android SDK, using `build-apk.sh` below.

## Prerequisites (your build machine)
- **Node.js 20+**, **JDK 17 or 21**.
- **Android SDK** (via Android Studio): platform 34/35, build-tools, and the **NDK**
  (`cui-llama.rn` compiles native llama.cpp — the NDK is required). Export
  `ANDROID_SDK_ROOT=/path/to/Android/sdk`.
- Linux or macOS, ~10 GB free disk for the first build.

## Steps

1. **Get ChatterUI** (the sources you already have) and make a branch:
   ```bash
   cd ChatterUI && git checkout -b helix-mesh-mod
   ```
2. **Apply the mod** — copy two files + add one drawer entry. Full detail in
   [`app_mod/apply.md`](app_mod/apply.md):
   - `app_mod/lib/helixClient.ts` → `lib/helixClient.ts`
   - `app_mod/screens/HelixMeshScreen/index.tsx` → `app/screens/HelixMeshScreen/index.tsx`
   - add `{ name: 'HELIX Mesh', path: '/screens/HelixMeshScreen', icon: 'sharealt' }` to
     `getPaths(...)` in `app/components/views/SettingsDrawer/RouteList.tsx`.
3. **Build the APK** (from the ChatterUI root):
   ```bash
   bash /path/to/SMS/integration/chatterui_llamacpp/build-apk.sh          # installable debug APK
   # or --release for assembleRelease (needs your signing config)
   ```
   Output: `android/app/build/outputs/apk/…/…apk`. Install: `adb install -r <apk>`.
   *(Alternative: ChatterUI's own flow — rename `eas.json.example`→`eas.json`, set
   `ANDROID_SDK_ROOT`, `npm install && npx eas build --platform android --local`.)*

### Build in CI (recommended — no local Android SDK needed)

GitHub-hosted runners have the Android SDK and full internet, and `cui-llama.rn` ships prebuilt
native libs, so CI builds the APK cleanly (it does not compile llama.cpp).

1. On your ChatterUI fork's `helix-mesh-mod` branch, add the L2 deps so `npm ci` installs them:
   ```bash
   npm i @noble/ciphers @noble/curves @noble/hashes react-native-tcp-socket react-native-get-random-values
   git commit -am "deps: HELIX mesh (@noble + tcp-socket + get-random-values)"
   ```
2. Copy [`ci/build-apk.yml`](ci/build-apk.yml) to `.github/workflows/build-apk.yml` in your fork and
   push the branch.
3. **Actions → Build APK → Run workflow** (or it runs on push to `helix-mesh-mod`). Download the APK
   from the run's **Artifacts** (`chatterui-helix-apk`), then `adb install -r <apk>`.

The workflow assembles a debug (installable) APK by default; choose `assembleRelease` from the
Run-workflow inputs if you have signing configured.

## Run a first experiment

1. **Start a HELIX node** on a PC or a Python-capable phone on the **same Wi-Fi**, bound so the
   phone can reach it:
   ```bash
   cd /path/to/SMS && PYTHONPATH=. python3 -m helix.host.http_control --host 0.0.0.0 --port 8799
   ```
   Note that machine's LAN IP (e.g. `192.168.1.10`). *(This demo node spins up a small mesh:
   a coordinator + two echo agents `up`/`lo`, so you get real multi-node responses.)*
2. On the phone, open **Settings drawer → HELIX Mesh**.
3. Enter `192.168.1.10:8799` → **Connect**. You should see the mesh nodes (`coord`, `up`, `lo`).
4. Type a prompt, pick a mode, **Run** — the answer comes from the HELIX mesh (not the phone's
   local model). Try **SuperAgent** for the fused Track A + Track B response.

## Level 2 — the phone's model as a mesh agent

Level 2 makes ChatterUI's **own GGUF model** a Track-A agent: a coordinator sends it tasks and it
answers by running `cui-llama.rn` completion, over sealed HELIX frames. **No native crypto module**
— all HELIX crypto is pure JS via `@noble` (proven wire-compatible:
`node integration/chatterui_llamacpp/js/conformance_noble.mjs`, 19/19). The whole loop
(agent joins → prompt routed to it → its model answers) is proven in-env:
`node integration/chatterui_llamacpp/js/l2_host_smoke.mjs` (5/5).

**Dependencies to add** to ChatterUI:
```bash
npm i @noble/ciphers @noble/curves @noble/hashes react-native-tcp-socket react-native-get-random-values
```
Import the RNG polyfill once at app entry (top of `app/_layout.tsx`):
```ts
import 'react-native-get-random-values'
```

**Files** (from `app_mod/lib/`, alongside `helixClient.ts`):
`helixCrypto.ts` (@noble codec), `helixFrame.ts` (`FrameCodec` + stream framing),
`helixAgent.ts` (`HelixAgentNode` + `makeLlamaAgentRunner`).

**Wire it up** (e.g. a button in the HELIX Mesh screen): build a TCP `connect` for
`react-native-tcp-socket`, wrap the loaded model, and join the mesh —
```ts
import TcpSocket from 'react-native-tcp-socket'
import { Llama } from '@lib/engine/Local/LlamaLocal'
import { HelixAgentNode, makeLlamaAgentRunner } from '@lib/helixAgent'

const connect = (host: string, port: number) => new Promise((res, rej) => {
    const s = TcpSocket.createConnection({ host, port }, () => res(s as any)); s.on('error', rej)
})
const runner = makeLlamaAgentRunner(Llama.useLlamaModelStore.getState(),
    { agent_id: 'phone-1', skills: ['chat'], task_types: ['chat'], models: ['local'] })
const agent = new HelixAgentNode('phone-1', 'helix-agent-host-demo', runner)
await agent.connect(connect, '<coordinator-ip>', 8790)   // then it answers TASKs from the mesh
```

**Run the coordinator** on a PC (same secret) and drive it from anywhere:
```bash
cd /path/to/SMS && PYTHONPATH=. python3 -m helix.host.agent_host_demo --host 0.0.0.0
# TCP_PORT 8790  (point the phone agent here)   HTTP_PORT 8799 (POST prompts here once joined)
curl -s localhost:8799/cmd -d '{"cmd":"infer","prompt":"hello from the mesh","mode":"single","skill":"chat"}'
# -> the phone's model produces the answer
```

## What this proves / what's next
- **Proven here** (no device needed): the exact request/response path — a `fetch` client driving
  the real Python mesh over HTTP — passes `node integration/chatterui_llamacpp/js/http_smoke.mjs`
  (health / nodes / infer single·parallel·voting / super).
- **Level 1** (above) is the quickest first experiment: ChatterUI ↔ HELIX mesh over `fetch`.
- **Level 2** (the phone's model as an agent) needs **no native crypto** — pure-JS `@noble`
  (proven, 19/19) + `react-native-tcp-socket`. Code is in `app_mod/lib/helix{Crypto,Frame,Agent}.ts`;
  the loop is proven by `js/l2_host_smoke.mjs`. Wiring above.
- **Level 3** (sharding one big model across phones) needs the native llama.cpp `GGML_RPC` build —
  see `LEVEL3_sharding.md`. Its wires are already proven cross-language (`js/shard_smoke.mjs`,
  `js/heal_smoke.mjs`).

## Licensing
ChatterUI is **AGPL-3.0**; the combined app stays AGPL.
