# HELIX — протокол распределённого инференса для локальных мобильных ИИ-меш-систем

Один протокол объединяет несколько устройств в единый ИИ-кластер поверх общего защищённого
меша. Два трека на одном субстрате + экспериментальный суперагент-режим.

> **Статус (честно):** это **протестированный референс протокола + контракты интеграции**, а
> **не** готовая фича в приложении. Чистый Python-контур (L1–L5, оба трека, безопасность,
> суперагент) полностью работает и покрыт тестами. **На реальных устройствах не запускался** —
> интеграция в Edge/ChatterUI — это инженерия в форках приложений (Chaquopy, нативные раннеры,
> радио, тест на 2+ телефонах), которую в этой среде не собрать/не проверить.

## Что это

```
                  общий субстрат: L2 (AEAD/HKDF/replay/identity) + L1 (transport/router/endpoint)
   Track B (llama, мономеш)              Track A (edge-агенты, поинтер)
   аренда → кольцо → сессии              registry → координатор ×4 → контекст (CRDT)
   тензоры по проводу                    задачи/результаты/диалог по проводу
                          SuperAgent: один промт на обе ветки (иерархия / ансамбль)
                          Scheduler: движок мощностей (кто в ring, кто в агенты) + миграция
```

- **Track A (Pointer / edge):** несколько **целых** LiteRT-моделей = агенты, координируются
  (single / parallel / voting / pipeline), общий контекст, healing переназначением.
- **Track B (llama / мономеш):** одна модель **шардируется** по слоям (llama.cpp/GGUF), healing
  с resume, int8-кодек активаций.
- **SuperAgent:** один промт наводится на обе ветки и сливается (иерархия «большая модель +
  мелкие помощники» / ансамбль-синхронизация); планировщик делит устройства по возможностям.
- **Безопасность:** ① int8-транспорт · ② самолечение · ③ аттестация ёмкости · ④ Ed25519-идентичность
  (подписанные голоса/контекст). Аудит: `AUDIT_*.md`.

## Готовность к Edge (LiteRT)

Edge/Track A — **ближе всего к готовности** (LiteRT чисто ложится на `AgentRunner`; Chaquopy
реален для Kotlin).

| Готово (протестировано) | Осталось (инженерия в форке Gallery) |
|---|---|
| Протокол L1–L5, оба трека, безопасность ①②③④ | Применить **Chaquopy**, упаковать `helix/` в app |
| Контракт `AgentRunner` + `PollingAgentRunner` + `edge_bootstrap` | Реальный `AgentRunnerLiteRt` на `LlmInference` (`.task`) |
| Каркасы Kotlin (`AgentRunnerLiteRt.kt`, `MeshService.kt`) | Нативный транспорт (Wi-Fi Aware/NSD) — сейчас каркас |
| Конформанс-векторы (`helix/spec/vectors.json`) | Прогнать нативную крипту через векторы |
| Control-server + PowerShell (`super`/`infer`) | **Тест на 2+ физических устройствах** |
| 16 selftest-модулей зелёные, `compileall` чистый | Замер крипты/полосы на устройстве |

**Вывод:** как *протокол + швы* — готов к внедрению. Как *работающая Edge-фича* — нет: нужен
app-side слой (Chaquopy + реальный LiteRT-раннер + радио + тест на железе). Разрыв — «интеграция
и тест на устройствах», не «дизайн протокола».

## Готовность к ChatterUI (React Native + cui-llama.rn)

ChatterUI — RN + llama.cpp (нативный C++), Python туда не воткнуть → HELIX на стороне ChatterUI
**нативный/TS**. Три уровня внедрения (`integration/chatterui_llamacpp/README.md`):

| Уровень | ChatterUI становится | Усилие | Статус |
|---|---|---|---|
| **1. Client** | UI над HELIX-узлом (TCP → `control_server`) | 🟢 низкое | **доказан end-to-end** — `js/control_smoke.mjs` гоняет реальный меш (11/11); RN-клиент `control_client.ts` |
| **2. Agent** | его модель = **Track A** агент (`completion`→`AgentRunner`) | 🟡 среднее | маппинг готов; **провод HELIX уже воспроизведён в JS** (`integration/chatterui_llamacpp/js/` сверяет `vectors.json`, 16/16) — осталось RN-транспорт + agent-worker |
| **3. Sharding** | вклад слоёв в **Track B** | 🔴 высокое | **нативные** правки cui-llama.rn (llama.cpp RPC / ShardRunner) |

**Ключевое:** самый простой и ценный путь для ChatterUI — **Уровень 2 (его целая GGUF-модель как
агент)**, а не шардинг. llama.rn отдаёт только whole-model completion — шардинг (Уровень 3) это
форк нативного модуля. **Лицензия:** ChatterUI — AGPL-3.0; интеграция делает приложение AGPL.

**Вывод:** Уровень 1 доказан end-to-end (JS-клиент гоняет реальный меш, 11/11); у Уровня 2
**самое рискованное снято** — провод HELIX
воспроизведён в JS (`integration/chatterui_llamacpp/js/`, `vectors.json` 16/16), осталось
RN-транспорт + agent-worker поверх готового кодека; Уровень 3 — только дизайн (нативная работа).

## Карта репозитория

**Код (референс, Python, тестируемый):** `helix/`
- L2 кадр/крипта: `crypto` (RFC 8439/8032), `sealer`, `frame`, `message`, `session`
- L1 транспорт: `transport/{base,memory,wifi,stream,framing}`, `mesh/router`, `endpoint`
- L3 control: `placement`, `roster`, `control` · L4 data: `shard`, `activation`, `pipeline`
- L5: `orchestrator` · Track A: `agent/*` · безопасность: `identity`, `attest`
- суперагент: `super/*` · PC: `host/control_server` · интероп: `conformance`, `spec/vectors.json`

**Интеграция (каркасы):** `integration/`
- `edge_litert/` (Kotlin: LiteRT-раннер + MeshService), `chatterui_llamacpp/` (TS-контракты),
  `usb_otg/`, `bitchat_ble/`, `powershell/Helix.psm1`

**Документы:**
- `AUDIT_*.md` — аудит безопасности · `ROADMAP_mobile_ai_mesh.md` — план (Track A/B)
- `HELIX_WIRE_SPEC.md` — спека провода · `POINTER_protocol.md` — дизайн агентного трека
- `INTEGRATION_BRIEF.md` / `INTEGRATION_DETAIL.md` — как встраивать в хосты
- `helix/README.md` — детали пакета

## Запуск тестов

```bash
cd /path/to/repo
for m in helix.selftest helix.transport.selftest helix.transport.stream_selftest \
         helix.mesh.router_selftest helix.host.control_server helix.control_selftest \
         helix.pipeline_selftest helix.orchestrator_selftest helix.agent.selftest \
         helix.agent.context_selftest helix.agent.secure_selftest helix.identity_selftest \
         helix.attest_selftest helix.super.selftest; do PYTHONPATH=. python3 -m $m; done
PYTHONPATH=. python3 -m helix.conformance --check
python3 -m compileall -q helix
```

## Дальше к железу (порядок)

1. Конформанс-гейт для нативной крипты (если она нативная).
2. **Edge (Track A):** Chaquopy + `AgentRunnerLiteRt` на `.task` + `WifiTransport` (LAN/Wi-Fi
   Aware) + `MeshService` → тест на 2 телефонах (4 режима + стрим + healing).
3. **ChatterUI (Track B):** llama.cpp RPC (Опция A) → полное кольцо (Опция B).
4. Включить ④/③ (готовы), затем SuperAgent 3+3 на железе.
