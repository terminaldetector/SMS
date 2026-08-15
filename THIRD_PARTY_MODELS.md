# Third-party model compatibility log

This is an **empty scaffold**, not populated data — no real-device testing has happened in the
session that created this file. Google's own official models (Gemma family) are tested and
supported through the app's built-in allowlist (`data/ModelAllowlist.kt`); this file is for
tracking what's actually been verified in this fork for **non-Google, third-party** LiteRT/
LiteRT-LM model conversions imported via "Import model" — which converter produced the file, which
device/accelerator it was tried on, and what happened. Fill in a row each time a third-party model
is actually tried on real hardware; don't guess or backfill from assumption.

See `ACCELERATION_AND_STABILITY_ROADMAP.md`'s Part 2 for the background on why third-party models
are more crash-prone than the official pipeline, and what hardening exists so far
(`common/Utils.kt`'s `validateModelFileOrNull`, `ui/llmchat/LlmChatModelHelper.kt`'s isolated
engine-creation try/catch).

| Model / converter | Format | Device tested | Accelerator | Result | Notes / issue link | Date |
|---|---|---|---|---|---|---|
| _(none yet)_ | | | | | | |
