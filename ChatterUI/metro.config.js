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
config.watchFolders = [
    ...(config.watchFolders ?? []),
    path.resolve(__dirname, '../forks/cui-llama.rn-rpc'),
]

module.exports = config
