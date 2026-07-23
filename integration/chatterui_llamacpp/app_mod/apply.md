# Applying the HELIX Mesh mod to ChatterUI

Level 1 mesh mod: adds a **HELIX Mesh** screen (ChatterUI as a UI over a HELIX node). Pure TS/RN
over `fetch` — **no new native module**, so it drops into a normal ChatterUI build.

Do this on a **separate branch** of your ChatterUI fork:

```bash
cd ChatterUI                       # your ChatterUI checkout
git checkout -b helix-mesh-mod
```

## 1. Copy two files (drop-in)

From this repo's `integration/chatterui_llamacpp/app_mod/` into your ChatterUI tree:

| From (this repo) | To (ChatterUI) |
|---|---|
| `app_mod/lib/helixClient.ts` | `lib/helixClient.ts` |
| `app_mod/screens/HelixMeshScreen/index.tsx` | `app/screens/HelixMeshScreen/index.tsx` |

```bash
MOD=/path/to/SMS/integration/chatterui_llamacpp/app_mod
cp "$MOD/lib/helixClient.ts" lib/helixClient.ts
mkdir -p app/screens/HelixMeshScreen
cp "$MOD/screens/HelixMeshScreen/index.tsx" app/screens/HelixMeshScreen/index.tsx
```

expo-router auto-registers `app/screens/HelixMeshScreen/` as the route
`/screens/HelixMeshScreen` — no `_layout.tsx` change needed. The `@lib` / `@components` aliases
already resolve (`tsconfig.json`).

## 2. Add the drawer entry (one edit)

In `app/components/views/SettingsDrawer/RouteList.tsx`, add one item to the `getPaths(...)` array
(e.g. right before `About`):

```ts
    {
        name: 'HELIX Mesh',
        path: '/screens/HelixMeshScreen',
        icon: 'sharealt',
    },
```

That's the whole integration. Commit:

```bash
git add lib/helixClient.ts app/screens/HelixMeshScreen app/components/views/SettingsDrawer/RouteList.tsx
git commit -m "Add HELIX Mesh screen (Level 1 mesh mod)"
```

## 3. Build & run

See `../build-apk.sh` and `../README_MESH.md`. In short: run a HELIX node
(`python -m helix.host.http_control --host 0.0.0.0`) on a PC/phone on the same Wi-Fi, build the
APK, open **HELIX Mesh**, enter the node's `LAN-IP:8799`, Connect, then Run.

## Level 2 (optional, next step): the phone's model as a mesh agent

L1 above needs zero new deps. **Level 2** makes the phone a Track-A agent (its GGUF model answers
mesh tasks) — still **no native module at all** (pure-JS `@noble` + the built-in `WebSocket`; the
frame nonce comes from `expo-crypto`, already a ChatterUI dep). Extra files:
`app_mod/lib/helixCrypto.ts`, `helixFrame.ts`, `helixAgent.ts` → `lib/`. Extra deps:
```bash
npm i @noble/ciphers @noble/curves @noble/hashes   # all pure JS; no native, no polyfill import
```
**Do NOT** add `react-native-tcp-socket` / `react-native-get-random-values` for L2 — adding
new-architecture native modules you don't need can break startup. Full wiring + a coordinator to
test against (`helix.host.agent_host_ws_demo`) are in `../README_MESH.md` (Level 2). The screen's
**"Join as agent"** section is the entry point.

## Device-to-device (no PC): this phone hosts the coordinator

Lets **two ChatterUI phones mesh with no PC**: one phone hosts the coordinator, the other joins with
"Join as agent". Only the **host** role needs a native server socket (`react-native-tcp-socket`); the
agent phone stays native-free (built-in WebSocket). Extra files (on top of L2):
`app_mod/lib/helixWsServer.ts`, `helixCoordinator.ts` → `lib/`. Extra deps:
```bash
npm i react-native-tcp-socket expo-network   # host role only; loaded lazily (never at startup)
```
`react-native-tcp-socket` is required **lazily** (only when the host taps *Start hosting*) and
`expo-network` (for showing the host's LAN IP) the same way, so neither touches app startup.

> **Gate first (Phase 0):** `react-native-tcp-socket`'s New-Architecture support is unverified.
> After adding it, rebuild the release APK and confirm the app still **starts** (splash passes) before
> relying on the host feature. If startup breaks, the native dep is the cause — remove it and use the
> PC coordinator path instead.

**Test on 2 phones (same Wi-Fi):** phone A → **Device-to-device → Start hosting** (note its
`IP:8790`); phone B → load a GGUF model → **Join as agent** → `A_IP:8790`; on A type a prompt →
**Run on mesh** → phone B's model answers. Proven cross-language in-env by
`node integration/chatterui_llamacpp/js/p2p_ws_smoke.mjs`.

## Notes
- L1 needs no new dependency: the screen uses only `fetch`, `react-native-mmkv` (already a
  ChatterUI dep), and existing `@components`.
- ChatterUI is **AGPL-3.0** — the combined app stays AGPL.
- This is Level 1 (control-plane). Level 2 (the phone's model as a mesh *agent*) and Level 3
  (sharding) need the native `SecurityBridge` / RPC and are a later step.
