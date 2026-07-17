# HELIX — спецификация провода и нативная интеграция

Язык-нейтральный контракт протокола. Python-пакет `helix/` — **референс и оракул
соответствия**; приложения (ChatterUI на llama.cpp, edge на LiteRT) реализуют этот
контракт **нативно**. Совместимость гарантируется байтовой сверкой с
[`helix/spec/vectors.json`](helix/spec/vectors.json) (генерируется и проверяется
`python -m helix.conformance`).

Принцип: **общий провод + общее переносимое ядро + тонкие адаптеры под хост.** Ничего
Python-специфичного на проводе нет.

---

## 1. Слой кадра (L1/L2)

Каждый кадр:

```
magic(4) | flags(1) | epoch(4, BE) | nonce(12) | sealed_body(...)
└──────────────── header (21 байт) ────────────────┘
```

| Поле | Значение |
|---|---|
| `magic` | ASCII `HLX1` (`48 4c 58 31`). Не тот magic → drop. |
| `flags` | бит 0 = confidential (AEAD) vs auth-only (HMAC). |
| `epoch` | uint32 BE — поколение группового ключа (rekey/replay-граница). |
| `nonce` | 12 байт, уникальный на ключ. |
| `sealed_body` | вывод sealer'а над plaintext'ом сообщения, **с заголовком как AAD**. |

- `MAX_FRAME = 16 MiB` — проверяется **до** аллокации на приёме.
- Заголовок целиком идёт в AAD → `flags/epoch/nonce` неизменяемы в полёте.

### Sealer (конфиденциальность + аутентичность)
- **AEAD** (`flags bit0=1`): ChaCha20-Poly1305 (RFC 8439). `sealed = ciphertext || tag(16)`.
- **HMAC** (auth-only, control-plane): HMAC-SHA256; `sealed = plaintext || tag(32)`.
- Ключи из секрета кластера через **HKDF-SHA256** (RFC 5869) с метками:
  `helix/1 aead key`, `helix/1 hmac key`, `helix/1 beacon key`. Векторы — в `vectors.json`.

Якоря корректности: AEAD сверен с RFC 8439 §2.8.2; Ed25519 — с RFC 8032 test-1 (оба в
`vectors.json`).

---

## 2. Сообщение (sealed plaintext)

Каноническая сериализация — **компактный JSON** `json.dumps(obj, separators=(",",":"))`
с полями в порядке `v,t,seq,src[,tid][,b]` (пустые `tid`/`b` опускаются):

```json
{"v":1,"t":"LEASE","seq":3,"src":"coordinator-1","tid":"task-xyz","b":{...}}
```

| Поле | Смысл |
|---|---|
| `v` | версия протокола (1). |
| `t` | тип (см. §3). |
| `seq` | per-sender монотонный номер (порядок/дедуп/replay, скользящее окно 64). |
| `src` | **аутентифицированная** личность отправителя (не берётся из транспорта). |
| `tid` | id задачи/сессии (опц.). |
| `b` | тело по типу. |

> Бинарный кодек тела — будущая оптимизация; контракт кадра при этом не меняется.

---

## 3. Типы сообщений (под-протоколы на одном меше)

| Группа | Типы |
|---|---|
| Control | `ANNOUNCE`, `LEASE`, `LEASE_ACK`, `HEARTBEAT` |
| Track B (llama-шардинг) | `PROMPT`, `PROMPT_TOKEN`, `DONE`, `ACTIVATION`, `FEED`, `SHARD_TOKEN` |
| Track A (edge-агенты) | `AGENT_ANNOUNCE`, `STATUS`, `TASK`, `PARTIAL`, `RESULT`, `VOTE`, `DECIDE`, `CONTEXT_SYNC`, `CONTEXT_BLOB`, `CONTEXT_PULL` |

Оба трека делят L1/L2, discovery, членство, живучесть, healing. Тип + `v` разводят
под-протоколы (закрыта старая перегрузка `TOKEN`).

---

## 4. Личность (HELIX ④)

- Узел = Ed25519-ключ. `node_id = "hlx1" + sha256(public_key)[:20 hex]` — **самосертифицирующийся**
  (id проверяется локально без CA).
- Атрибутивные утверждения (`VOTE`, запись `CONTEXT_SYNC`, `AGENT_ANNOUNCE`) подписываются
  ключом; каноническая форма для подписи — `json.dumps(fields, sort_keys=True, separators=(",",":"))`.
- Групповой секрет = *членство*; подпись = *конкретный узел*. Полная Sybil-стойкость
  дополнительно требует допуска/аттестации (③).
- **Версия/расширения:** версия уже в `v`/`HLX1`/`epoch`; `AgentCard` несёт опциональное
  `ext[]` (теги HELIX-расширений) — **опускается, если пусто** (провод/векторы не меняются).

## 5. Транспорт

Абстракция: `send(node_id, frame)` / `broadcast(frame)` / `on_frame(frame_bytes)` /
`peers()`. Обработчик получает **только байты кадра** — личность внутри. Отправка себе
закольцовывается локально. Несущие: length-prefixed поток (`uint32 BE длина || frame`, лимит
до аллокации) для TCP/Wi-Fi/**USB (AOA/serial)**; message-ориентированный **BitChat/BLE**
(целые сообщения, без length-prefix). BLE — под Track A/control, не под тяжёлые активации.

## 5.1 Роутинг (звезда / кольцо / мост) — внешний конверт

Для релея между несколькими транспортами (USB-звезда, мост BitChat↔wifi) — внешний
**routing-конверт** вокруг запечатанного кадра (`dst` — cleartext-метка, payload зашифрован):

- **data:** `kind=0(1) | ttl(1) | dst_len(2 BE) | dst(utf8) | frame`  (`dst="*"` = broadcast)
- **presence:** `kind=1(1) | ttl(1) | origin(utf8)`  (флудится, строит директорию `node_id→downstream`)

Релей форвардит по директории, иначе флудит; TTL + дедуп по `sha256(frame)[:16]` гасят петли.
Векторы — в `vectors.json` (`routing_envelope`).

---

## 6. Нативная интеграция (одновременно ChatterUI и edge)

### Что общее, что per-host

```
┌──────────────── общее переносимое ЯДРО (кандидат: Rust/C, FFI) ────────────────┐
│  frame · sealer(ChaCha20-Poly1305/HMAC) · HKDF · Ed25519 · replay · message codec │
│  (сверяется с vectors.json; тот же контракт, что helix/ на Python)                 │
└───────────────────────────────────────────────────────────────────────────────────┘
        │ JNI / uniffi (Kotlin)                 │ C ABI / JSI (React Native)
        ▼                                        ▼
   edge / LiteRT (Kotlin)                   ChatterUI / llama.cpp (C++/TS)
   AgentRunner  ← LiteRT .task              ShardRunner  ← llama.cpp RPC (GGUF)
   Transport    ← Wi-Fi Aware/USB           Transport    ← Wi-Fi/USB (тот же провод)
```

### Адаптеры (то, что «добавить или обновить»)
Три контракта — единственное, что пишется под хост; всё остальное переносимо:

| Контракт | ChatterUI (llama.cpp) | Edge (LiteRT) |
|---|---|---|
| **Runner** | `ShardRunner` → полоса слоёв через llama.cpp RPC (GGUF) | `AgentRunner` → целая модель `.task` (стриминг встроен) |
| **Transport** | IP-линк (Wi-Fi/USB) — двигает байты кадра | то же |
| **SecurityBridge** | нативные AEAD/Ed25519 на горячий путь (опц.) | то же |

> Оба трека — на одном проводе. В UI это тумблер режима: «мощный ИИ через шардинг» (Track B,
> llama) ↔ «мультиагенты» (Track A, edge). Слияние интерфейсов = один меш, два раннера.

### Развилка ядра (нужно решение)
Как переносимое ядро живёт нативно в **обоих** хостах:

- **A. Общее ядро на Rust/C + биндинги (рекомендуется).** Framing/крипта/replay/codec — один
  раз, FFI в Kotlin (JNI/uniffi) и в React Native (C ABI/JSI). Максимум переиспользования,
  один аудит. Минус: новый build-контур.
- **B. Реимплементация под хост по спеке + `vectors.json`.** Kotlin-порт и TS/C++-порт
  независимо, сверяются векторами. Проще стартовать, дублирование + двойной аудит.
- **C. Python-via-Chaquopy там, где можно (edge/Kotlin), нативно в ChatterUI.** Быстро для
  edge, но два разных ядра и Python в RN неестественен — наименее «нативно».

`helix/` (Python) остаётся референсом/оракулом при любом варианте.

---

## 7. Соответствие

`python -m helix.conformance` пишет `helix/spec/vectors.json`; `--check` сверяет референс с
ним. Нативный порт **обязан** воспроизвести эти байты: HKDF-подключи, запечатанный кадр
(фикс. nonce), кодировки сообщений, node_id из ключа, подпись утверждения, плюс якоря
RFC 8439 / RFC 8032. Две реализации, прошедшие векторы, интероперабельны по построению.
