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
   - add `{ name: 'HELIX Mesh', path: '/screens/HelixMeshScreen', icon: 'share-alt' }` to
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

> **Build `assembleRelease`, not `assembleDebug`.** A debug RN/Expo APK does **not** embed the JS
> bundle — it loads it from a Metro dev server, so installed standalone it **hangs on the splash
> icon**. Release embeds the bundle and runs on its own; Expo's android template signs release with
> the debug key, so **no signing secrets are needed**. (`newArchEnabled` is on — see below.)

> **Level 1 needs no native module** — the HELIX Mesh screen uses only `fetch` (`helixClient.ts`).
> Add the L2 deps (`@noble/*`, `react-native-tcp-socket`, `react-native-get-random-values`) and the
> `import 'react-native-get-random-values'` at the top of `app/_layout.tsx` **only when wiring the L2
> agent** — adding new-arch native modules you don't need can break startup.

1. Copy [`ci/build-apk.yml`](ci/build-apk.yml) to `.github/workflows/build-apk.yml` in your fork and
   push the branch (it defaults to `assembleRelease`).
2. **Actions → Build APK → Run workflow**. Download the APK from the run's **Artifacts**
   (`chatterui-helix-apk`), then `adb install -r <apk>`.

The workflow assembles a release (JS-bundled, installable) APK by default; choose `assembleDebug`
only if you run a Metro dev server. Older note:
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
answers by running `cui-llama.rn` completion, over sealed HELIX frames. Transport is the **built-in
`WebSocket`** and crypto is pure JS `@noble` with the nonce from **`expo-crypto`** — so **L2 adds
NO native module** (both are already ChatterUI deps). This avoids what broke L1's startup:
`react-native-tcp-socket` has no confirmed New-Architecture support, so we don't use it. The whole
loop (agent joins over WebSocket → prompt routed to it → its model answers) is proven in-env:
`node integration/chatterui_llamacpp/js/l2_ws_smoke.mjs` (5/5), plus the codec at
`conformance_noble.mjs` (19/19).

**Dependencies:** none to add — `@noble/{ciphers,curves,hashes}` and `expo-crypto` are already in
ChatterUI's `package.json`. **Do NOT** add `react-native-tcp-socket` / `react-native-get-random-values`.

**Files** (already in `ChatterUI/lib/`): `helixCrypto.ts` (@noble codec), `helixFrame.ts`
(`FrameCodec` — nonce injectable), `helixAgent.ts` (`HelixAgentNode` over `WebSocket` +
`makeLlamaAgentRunner` + `makeExpoRandomBytes`).

**In the app:** the HELIX Mesh screen already has a **"Join as agent"** section — enter the
coordinator's `ws host:port`, tap Join. It wraps the loaded model and joins over WebSocket
(nonce via `expo-crypto`). The wiring (for reference):
```ts
import * as ExpoCrypto from 'expo-crypto'
import { Llama } from '@lib/engine/Local/LlamaLocal'
import { HelixAgentNode, makeExpoRandomBytes, makeLlamaAgentRunner } from '@lib/helixAgent'

const runner = makeLlamaAgentRunner(Llama.useLlamaModelStore.getState(),
    { agent_id: 'phone-1', skills: ['chat'], task_types: ['chat'], models: ['local'] })
const agent = new HelixAgentNode('phone-1', 'helix-agent-host-ws-demo', runner,
    { randomBytes: makeExpoRandomBytes(ExpoCrypto) })
await agent.connect('ws://<coordinator-ip>:8790')   // then it answers TASKs from the mesh
```

**Run the coordinator** on a PC (same secret) and drive it from anywhere:
```bash
cd /path/to/SMS && PYTHONPATH=. python3 -m helix.host.agent_host_ws_demo --host 0.0.0.0
# WS_PORT 8790 (point the phone's "Join as agent" here)   HTTP_PORT 8799 (POST prompts once joined)
# (Legacy raw-TCP variant, not used by the app: helix.host.agent_host_demo)
# TCP_PORT 8790  (point the phone agent here)   HTTP_PORT 8799 (POST prompts here once joined)
curl -s localhost:8799/cmd -d '{"cmd":"infer","prompt":"hello from the mesh","mode":"single","skill":"chat"}'
# -> the phone's model produces the answer
```

## What this proves / what's next
- **Proven here** (no device needed): the exact request/response path — a `fetch` client driving
  the real Python mesh over HTTP — passes `node integration/chatterui_llamacpp/js/http_smoke.mjs`
  (health / nodes / infer single·parallel·voting / super).
- **Level 1** (above) is the quickest first experiment: ChatterUI ↔ HELIX mesh over `fetch`.
- **Level 2** (the phone's model as an agent) needs **no native module at all** — built-in
  `WebSocket` + pure-JS `@noble` (19/19) + `expo-crypto` nonce. Code in `ChatterUI/lib/helix*.ts`
  and the screen's "Join as agent" section; the loop is proven by `js/l2_ws_smoke.mjs` (5/5).
- **Level 3** (sharding one big model across phones) needs the native llama.cpp `GGML_RPC` build —
  see `LEVEL3_sharding.md`. Its wires are already proven cross-language (`js/shard_smoke.mjs`,
  `js/heal_smoke.mjs`).

## Licensing
ChatterUI is **AGPL-3.0**; the combined app stays AGPL.
