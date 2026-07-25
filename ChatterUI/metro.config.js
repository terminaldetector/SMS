// Learn more https://docs.expo.io/guides/customizing-metro
const path = require('path')
const { getDefaultConfig } = require('expo/metro-config')

/** @type {import('expo/metro-config').MetroConfig} */
const config = getDefaultConfig(__dirname)

config.resolver.sourceExts.push('sql')
config.resolver.assetExts.push('gguf', 'raw')

// cui-llama.rn is a `file:` dependency pointing outside this project (../forks/cui-llama.rn-rpc,
// the HELIX Level 3 RPC fork) — node_modules/cui-llama.rn is a symlink to it. Metro only indexes
// files under `projectRoot` + `watchFolders` by default, so without this the symlinked package's
// real files are invisible to the bundler ("Unable to resolve module cui-llama.rn") even though
// Node/TypeScript resolve it fine.
const fork = path.resolve(__dirname, '../forks/cui-llama.rn-rpc')
config.watchFolders = [...(config.watchFolders ?? []), fork]

// ...but resolving through that symlink starts from the fork's REAL path, so anything the fork has
// in its own node_modules wins over ours. Building its lib/ requires installing its devDeps, which
// puts a second React Native there — and a second copy of React Native is fatal: `cui-llama.rn`
// then imports TurboModuleRegistry from an instance the running app never initialised, so
// `TurboModuleRegistry.get('RNLlama')` returns null and every native call dies with
// "Cannot read property 'install' of null". CI deletes that directory after building lib/; this
// makes the guarantee structural rather than a step someone can forget.
const forkModules = path.join(fork, 'node_modules')
const escaped = forkModules.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const existing = config.resolver.blockList
config.resolver.blockList = [
    ...(Array.isArray(existing) ? existing : existing ? [existing] : []),
    new RegExp(`^${escaped}[\\\\/].*`),
]

// With that directory out of the picture, resolution from the fork walks up its own real path and
// never reaches ours (ChatterUI/ is not an ancestor of forks/), so point its peer dependencies at
// this app's copies explicitly. These are the only non-relative imports the fork contributes to the
// bundle; `@expo/config-plugins` is used solely by its Expo plugin, which runs at build time.
config.resolver.extraNodeModules = {
    ...(config.resolver.extraNodeModules ?? {}),
    'react-native': path.resolve(__dirname, 'node_modules/react-native'),
    react: path.resolve(__dirname, 'node_modules/react'),
}

module.exports = config
