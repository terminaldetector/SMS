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

## What this proves / what's next
- **Proven here** (no device needed): the exact request/response path — a `fetch` client driving
  the real Python mesh over HTTP — passes `node integration/chatterui_llamacpp/js/http_smoke.mjs`
  (health / nodes / infer single·parallel·voting / super).
- **Level 1** is the first experiment: ChatterUI ↔ HELIX mesh. **Level 2** (the phone's own GGUF
  model joining the mesh as an *agent*) and **Level 3** (sharding one big model across phones) need
  the native `SecurityBridge` / llama.cpp RPC — see `LEVEL3_sharding.md`. Their wires are already
  proven cross-language (`js/agent_smoke.mjs`, `js/shard_smoke.mjs`, `js/heal_smoke.mjs`).

## Licensing
ChatterUI is **AGPL-3.0**; the combined app stays AGPL.
