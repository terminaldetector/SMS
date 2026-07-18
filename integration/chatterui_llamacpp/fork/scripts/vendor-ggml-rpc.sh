#!/usr/bin/env bash
# Vendor ggml-rpc into a cui-llama.rn fork, matching its pinned llama.cpp (b9309).
#
#   bash vendor-ggml-rpc.sh /path/to/your/cui-llama.rn
#
# cui-llama.rn 1.11.14 pins llama.cpp b9309 and prefixes ggml symbols ggml_ -> lm_ggml_ /
# GGML_ -> LM_GGML_ (macros). This fetches the matching ggml-rpc source and applies that rename,
# so the already-present (gated) registration in cpp/ggml-backend-reg.cpp links.
#
# NOTE: verify against your fork's own sync tooling if it has one — symbol renaming is the most
# likely source of build breakage. Not run/verified in the HELIX dev env (no NDK).
set -euo pipefail

FORK="${1:?usage: vendor-ggml-rpc.sh /path/to/cui-llama.rn}"
TAG="b9309"
RAW="https://raw.githubusercontent.com/ggml-org/llama.cpp/${TAG}"

tmp="$(mktemp -d)"
echo "Fetching ggml-rpc from llama.cpp ${TAG}..."
curl -fsSL "${RAW}/ggml/src/ggml-rpc/ggml-rpc.cpp" -o "${tmp}/ggml-rpc.cpp"
curl -fsSL "${RAW}/ggml/include/ggml-rpc.h"        -o "${tmp}/ggml-rpc.h"

echo "Applying lm_ prefix rename (symbols only; include filenames left as-is)..."
for f in ggml-rpc.cpp ggml-rpc.h; do
  # Rename ggml symbols but NOT #include "ggml*.h" lines (the fork keeps those filenames).
  sed -E \
    -e '/^[[:space:]]*#[[:space:]]*include/! s/\bGGML_/LM_GGML_/g' \
    -e '/^[[:space:]]*#[[:space:]]*include/! s/\bggml_/lm_ggml_/g' \
    "${tmp}/${f}" > "${FORK}/cpp/${f}"
  echo "  wrote ${FORK}/cpp/${f}"
done

echo "Done. Now: apply the patches in ../patches/, do the two manual steps in ../README.md,"
echo "set RNLLAMA_BUILD_FROM_SOURCE=ON, and build."
rm -rf "${tmp}"
