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

## Notes
- No new dependency: the screen uses only `fetch`, `react-native-mmkv` (already a ChatterUI dep),
  and existing `@components`.
- ChatterUI is **AGPL-3.0** — the combined app stays AGPL.
- This is Level 1 (control-plane). Level 2 (the phone's model as a mesh *agent*) and Level 3
  (sharding) need the native `SecurityBridge` / RPC and are a later step.
