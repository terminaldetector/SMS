# Silica Cluster — what is worth taking, and what is not

Studied at `terminaldetector/Silica-Cluster-Decentralized-Mobile-AI` (AGPLv3, ShintoChakkiath).
Same problem as ours — llama.cpp RPC clustering across Android phones — solved differently enough
to be genuinely informative. This lists what applies to TriangleUI, in priority order, with an
honest note on what it would cost.

**Licence first:** Silica is AGPLv3. Copying its code into TriangleUI would impose AGPL on the
result. Everything below is therefore about *what it learned*, not its source — the findings are
facts about Android and llama.cpp, which are not copyrightable. Any actual code must be written
independently. If AGPL is acceptable for TriangleUI that changes, but it is a decision to make
deliberately, not by pasting.

---

## 1. The architectural difference, and why it matters to our blocker

Silica does **not** call llama.cpp through JNI in-process. It ships the upstream binaries as
`libllama.so` (llama-server) and `librpc.so` (rpc-server) inside `nativeLibDir`, marks them
executable, and runs them as **separate OS processes** via `ProcessBuilder`.

That is the opposite of our design (`startRpcServer` through the cui-llama.rn JSI fork), and it
buys three things we currently do not have:

- **The worker can be stopped.** Ours cannot: `startRpcServer` has no stop API, the accept loop
  never returns, and it serves for the life of the process. Silica kills the PID
  (`killall -9 librpc.so`) before each start.
- **A crash in the engine does not take the app with it.** A SIGABRT in a child process is a
  failed job; in ours it is the app dying.
- **Upstream binaries, no fork to maintain.** We maintain `forks/cui-llama.rn-rpc` precisely
  because the JNI path needed `-DLM_GGML_USE_RPC` wired in by hand.

The cost is equally real: two processes to supervise, model paths and stdout to marshal, no JSI
streaming, and Android's rules about executing from `nativeLibDir` (which work, but constrain
packaging). **This is not a change to make casually.** It is the fallback worth having in mind if
the JNI RPC path cannot be made to connect — which is exactly where we are stuck.

## 2. Things to take now (small, and independently valuable)

### Validate every worker before putting it in `--rpc`
Silica pings each node with a plain socket connect (1.5s) and *drops* unreachable ones from the
`--rpc` list rather than passing them to llama.cpp. We arrived at the same thing independently in
`lib/helixReach.ts` — worth recording as convergent evidence that it is the right shape, not a
workaround.

### A separate, always-up telemetry port
Silica runs telemetry on **8082** and says why, in a comment worth quoting:

> Telemetry port 8082 boots instantly upon START LISTENING. 50052 (librpc) is delayed until the
> model loads.

Two ports, two meanings: "this phone is here" and "this phone's engine is ready". We conflate them
— a phone is discoverable only once its rpc-server is up, so "not found" and "not ready yet" look
identical. Our mesh WebSocket (8790) is already the always-up channel, so we have the ingredients;
what we lack is the distinction being *made*.

### Pick a free RPC port instead of a fixed one
Silica scans 50052–50100 for the first bindable port and publishes the winner in its telemetry. Ours
is one configurable port, and because our server cannot be stopped, a second attempt in the same
process has no way out if that port is held. Publishing the chosen port (we already carry `rpc` in
`AGENT_ANNOUNCE`) makes this nearly free.

### Batch subnet scans
If we ever add LAN auto-discovery: Silica sweeps /24 in **chunks of 45** and says why — more
concurrent sockets than that and Android returns "No buffer space available". That is the kind of
detail only found by hitting it.

## 3. The one I am deliberately NOT applying blind

Silica passes `--no-mmap` unconditionally on the master, with this reasoning:

> Enforce no-mmap globally on Android to prevent SIGABRT 134 chunk fault assertions. Android's
> virtual memory mapper often denies contiguous 3GB+ allocations.

We set `use_mmap: true` always (`lib/engine/Local/LlamaLocal.ts`), and deliberately: with mmap on,
tensors bound for a remote worker stream from the file instead of being materialised on the host
first — which is the whole point of not needing the model to fit locally.

So this is a genuine conflict, not an oversight on either side, and **we have not observed the crash
Silica guards against.** Adopting their fix would trade a benefit we designed for against a problem
we have not seen. The honest thing is to leave it, and to try `use_mmap: false` as a *first
experiment* if a sharded load ever dies with SIGABRT — at which point this note is the reason why.

## 4. Compute-aware placement — the gap we already know about

`HardwareManager.measureSyntheticTops()` runs a short integer benchmark across all cores and reports
GOPS, cached per session. Silica then splits layers by a master/worker percentage the user can move.

This is the gap flagged in `OVERVIEW.md`: **our planner allocates by memory only.** A phone with free
RAM and a slow CPU currently gets a large band and becomes the pipeline bottleneck for every token.
A benchmark like this — a few hundred milliseconds, once — would give `planLocalShard` a second
weight. Their comment is worth keeping too: they removed "fake multipliers for NPU assumptions" and
report measured CPU throughput only.

Worth doing, and independent of everything above.

## 5. Not applicable

- **Their UI, chat, RSS, web search, API gateway, Cloudflare tunnel** — TriangleUI has its own, and
  ChatterUI's are better developed.
- **Their model downloader** (fixed Llama 3.2 1B/3B from a hardcoded list) — ours already transfers
  arbitrary GGUFs phone-to-phone.
- **No encryption on the wire.** HELIX seals every frame (ChaCha20-Poly1305, HKDF from a cluster
  secret); Silica's telemetry and control are plain HTTP on the LAN. This is one place we are ahead
  and should stay.
