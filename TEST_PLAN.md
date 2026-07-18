# HELIX — план тестирования (от готового референса к железу)

Единый чек-лист прогона. Протокол/крипта/кольцо/безопасность/самолечение **уже доказаны в среде**
(см. Фазу 0). Дальше — на устройствах/в форках. Детали сценариев — в `INTEGRATION_DETAIL.md §3`,
`integration/chatterui_llamacpp/LEVEL3_sharding.md`, `integration/chatterui_llamacpp/README.md`.

Порядок: **не переходить на следующую фазу, пока предыдущая не зелёная.** Каждый пункт — с явным
критерием «прошло».

---

## Фаза 0 — Гейт в среде (уже зелёный; перепрогнать перед выкладкой)

- [ ] Python-референс: `for m in helix.selftest helix.transport.selftest helix.transport.stream_selftest helix.mesh.router_selftest helix.control_selftest helix.pipeline_selftest helix.orchestrator_selftest helix.agent.selftest helix.agent.context_selftest helix.agent.secure_selftest helix.identity_selftest helix.attest_selftest helix.super.selftest helix.rpc_cluster; do PYTHONPATH=. python3 -m $m; done` → каждый `ALL PASSED`.
- [ ] Конформанс + компиляция: `PYTHONPATH=. python3 -m helix.conformance --check` и `python3 -m compileall -q helix`.
- [ ] JS-доказательства (6): `for s in conformance control_smoke agent_smoke rpc_smoke shard_smoke heal_smoke; do node integration/chatterui_llamacpp/js/$s.mjs; done` → все `ALL PASSED`.
- **Критерий:** всё зелёное. Не сошлось — дальше не идти.

## Фаза 1 — Конформанс нативной крипты (гейт интеропа)

- [ ] Нативный/TS `SecurityBridge` (ChaCha20-Poly1305 + Ed25519 + HKDF) воспроизводит
  `helix/spec/vectors.json` **байт-в-байт** — те же 20 проверок, что `js/conformance.mjs`
  (hkdf, aead RFC8439, message-кодировки вкл. FEED, sealed-frame, ed25519 RFC8032, node_id,
  signed-claim, routing, int8-`activation_codec`).
- **Критерий:** все векторы совпали. Не сошлось → интероп не гарантирован, дальше не идти.

## Фаза 2 — ChatterUI L1 (client): UI над HELIX-узлом

- [ ] На ПК/Python-телефоне поднять `python -m helix.host.control_demo`.
- [ ] ChatterUI `control_client.ts` (RN TCP) коннектится, гоняет `ping/status/nodes/context/
  infer(single|parallel|voting)/super`. Ошибочная команда не рвёт соединение.
- **Критерий:** те же ответы, что `js/control_smoke.mjs` (11/11), но с реального телефона.

## Фаза 3 — Два телефона, Track A (agent / edge)

- [ ] Оба: `MeshService` с общим cluster secret (QR/ввод один раз); discovery видит оба
  (`get_cluster_nodes`).
- [ ] 4 режима (`single`/`parallel`/`voting`/`pipeline`), стрим `PARTIAL` в UI — маппинг
  `makeLlamaAgentRunner` (`ctx.completion`→`AgentRunner`), проверено в среде `js/agent_smoke.mjs`.
- [ ] **Healing:** выключить Wi-Fi на одном посреди задачи → координатор перемаршрутизирует, ответ
  завершается, `heals≥1` (в среде — `js/heal_smoke.mjs`).
- **Критерий:** 4 режима + healing на двух физических устройствах. Детали — `INTEGRATION_DETAIL §3.2`.

## Фаза 4 — Edge/LiteRT (нативный раннер)

- [ ] Chaquopy: упаковать `helix/` в app; реальный `AgentRunnerLiteRt` на `LlmInference` (`.task`).
- [ ] `world_size=1` стрим на одном телефоне, затем Фаза 3 на двух с LiteRT-моделями.
- **Критерий:** реальная LiteRT-модель отвечает как Track-A агент; каркасы — `integration/edge_litert/`.

## Фаза 5 — ChatterUI L3A (Track B, llama.cpp RPC)

- [ ] Оба: собрать cui-llama.rn с `-DGGML_RPC=ON`; воркер — `rpc-server -p 50052` (адрес идёт в
  `ANNOUNCE.rpc`).
- [ ] HELIX: discovery → **аттестация ③** обоих → `rpcPlan()` даёт `--rpc`/`--tensor-split`/`main`
  (в среде — `js/rpc_smoke.mjs`, 7/7). В `--rpc` попадают только аттестованные ③ и подписанные ④.
- [ ] Главный: `completion` с `--rpc <воркер:50052> --tensor-split <доли> -ngl 99`.
- **Веха:** 16B Q4, не влезающая в один телефон, генерирует на двух; оба реально держат полосу
  (память/лог RPC). Детали — `LEVEL3_sharding.md`, `INTEGRATION_DETAIL §3.3`.
- ⚠️ RPC без auth — **никогда** не выставлять `rpc-server` в открытую сеть (только доверенный
  LAN/WireGuard, только аттестованные узлы).

## Фаза 6 — ChatterUI L3B (полное HELIX-кольцо) — позже

- [ ] Нативный ggml-`ShardRunner` за швом `embed/forward/sample/detok`; активации по HELIX-фреймам
  (int8-кодек), кольцо/healing/resume уже готовы (в среде — `js/shard_smoke.mjs` + `js/heal_smoke.mjs`).
- **Критерий:** кольцо на 2+ устройствах над Wi-Fi/USB-OTG; убить узел → resume с контрольной точки.

## Кросс-секущее — безопасность и полоса

- [ ] **④ идентичность:** голоса/контекст подписаны; подделка «от имени другого узла» отвергается
  (`secure_selftest`).
- [ ] **③ аттестация:** размещение только по доказанной памяти; солгавший о памяти узел не получает
  полосу.
- [ ] **Полоса активаций (B):** сверить с `python -m helix.host.bandwidth`. Ориентир (на хоп/токен):
  int8 ≈ 7% от raw JSON; d_model 5120 ≈ 7 KB/хоп; кольцо 4 узла @20 tok/s ≈ 3.3 Mb/s (int8) —
  влезает в Wi-Fi/USB. Если полоса — узкое место: включить int8-кодек, затем бинарный `act`-body.

## Что мерить (цели) — `INTEGRATION_DETAIL §3.5`

| Метрика | Цель |
|---|---|
| Задержка первого токена (seed→первый `PARTIAL`/`SHARD_TOKEN`) | приемлемо для чата |
| Токенов/с (decode) | не хуже деградации от сети |
| Полоса префилла (B) | int8 держит Wi-Fi; иначе чанкинг/бинарный body |
| Память/узел (RSS) | band+KV+рантайм ≲ 2.5–3 ГБ |
| Надёжность (убить узел) | Track A reroute / Track B resume, `heals≥1` |

## Грабли — `INTEGRATION_DETAIL §3.6`

Wi-Fi Aware не на всех чипсетах (fallback NSD/hotspot) · RPC без auth не в открытую сеть ·
`--tensor-split` по **аттестованной** памяти (иначе OOM) · префилл 16B по BLE нежизнеспособен
(нужен Wi-Fi Aware/USB) · **раздельные** устройства (один IP на двух процессах ломает адресацию).
