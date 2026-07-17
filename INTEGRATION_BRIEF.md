# Бриф нативной интеграции HELIX — ChatterUI (llama.cpp) и Edge (LiteRT)

Как подвести готовый протокол к двум хостам. Протокол реализован и покрыт тестами
(`helix/`, 11 selftest); интеграция = **реализовать три контракта под хост** и сверить с
`helix/spec/vectors.json`. Никакой логики протокола переписывать не нужно.

---

## 0. TL;DR — что реально строить

| Хост | Трек | Что реализовать | Сложность |
|---|---|---|---|
| **Edge / LiteRT** (Kotlin) | A (агенты) | `AgentRunner` → LiteRT `.task` (стриминг встроен) | 🟢 низкая |
| **ChatterUI / llama.cpp** (RN+C++) | B (шардинг) | `ShardRunner` → полоса слоёв **или** llama.cpp RPC | 🟠 средняя |
| оба | — | `Transport` (IP-линк) + опц. `SecurityBridge` (нативная крипта) | 🟢 низкая |

Три контракта — единственный нативный код. Всё остальное (кадр, крипта, replay,
размещение, координатор, healing, аттестация) переносимо и сверяется по векторам.

---

## 1. Три контракта (точные сигнатуры)

Взяты из референса; порт обязан им соответствовать байт-в-байт на проводе.

### 1.1 Runner
```
// Track B — llama.cpp (ChatterUI).  Референс: helix/shard.py
interface ShardRunner {
  fun load(startLayer: Int, endLayer: Int, nLayers: Int, modelId: String, modelPath: String)
  fun embed(tokenId: Int): FloatArray          // только первый шард
  fun forward(hidden: FloatArray): FloatArray   // каждый шард (полоса слоёв)
  fun sample(hidden: FloatArray): Int           // только последний
  fun detok(tokenId: Int): String               // только последний
  fun eosId(): Int
}

// Track A — LiteRT (Edge).  Референс: helix/agent/runner.py
interface AgentRunner {
  fun card(): AgentCard                          // возможности (модели/навыки/типы задач)
  // стриминг: хост толкает чанки; Python-адаптер собирает их в infer()
  fun submit(taskId: String, prompt: String, context: String)
  fun poll(): String                             // JSON: [{"task":..,"chunk":..,"done":bool}]
  fun score(prompt: String, result: String): Double
}
```
> Стриминговый `infer()` из контракта мапится на пару `submit`/`poll` (как в exo-core
> `HostLlmRunner`) — Chaquopy/JNI не отдаёт Python-итератор из нативного колбэка напрямую.

### 1.2 Transport (двигает **байты кадра**, личность внутри)
```
interface Transport {
  suspend fun send(nodeId: String, frame: ByteArray)
  suspend fun broadcast(frame: ByteArray)
  fun onFrame(handler: (ByteArray) -> Unit)      // без from_node — src в подписанном кадре
  suspend fun peers(): List<String>
}
```
Реализация: Wi-Fi Aware / Wi-Fi Direct / общий LAN / USB-tether → любой IP-линк. Обработчик
получает **только байты**; `src` достаётся кодеком (identity-в-кадре). `send(self)` —
локальная петля. Лимит кадра проверять **до** аллокации.

### 1.3 SecurityBridge (опционально, горячий путь)
```
interface SecurityBridge {                       // нативные AEAD + Ed25519
  fun seal(key: ByteArray, nonce: ByteArray, pt: ByteArray, aad: ByteArray): ByteArray
  fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray?
  fun sign(seed: ByteArray, msg: ByteArray): ByteArray
  fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean
}
```
Не обязателен: Python-референс корректен (сверен с RFC 8439/8032), просто медленнее.
Ставится под data-plane (активации на такте токена).

---

## 2. Edge / LiteRT (Kotlin) — рекомендуемый путь: Chaquopy

Проверенный паттерн (как exo-core `INTEGRATION.md`): **Python-ядро HELIX под Chaquopy**,
Kotlin-раннер отдаётся в Python через Chaquopy.

```
LiteRT .task  ──►  AgentRunnerLiteRt (Kotlin)  ──Chaquopy──►  AgentNode(runner)  (helix.agent)
                     submit/poll/score                          координатор ×4 режима
MeshService (foreground) владеет: Python.start() · Transport · AgentNode · Skill для UI
```

Шаги:
1. Применить плагин Chaquopy (Python 3.12), положить `helix/` в `python/`.
2. Реализовать `AgentRunnerLiteRt` на LiteRT LLM API (`LlmInference.generateResponseAsync` +
   progress-listener → буфер для `poll()`). Скелет — `integration/edge_litert/AgentRunnerLiteRt.kt`.
3. `MeshService`: `Python.start(AndroidPlatform)`, собрать `AgentNode` из Python-модуля,
   передать раннер и `Transport`; foreground + Wi-Fi/Nearby permissions.
   Скелет — `integration/edge_litert/MeshService.kt`.
4. UI-тумблер «Pointer mesh» → старт/стоп сервиса; стрим `PARTIAL` → в чат.
5. Транспорт: проще всего — Python `helix.transport.wifi.WifiTransport` поверх IP (Wi-Fi
   Aware/LAN), нативный `Transport` — если нужен Nearby/BLE-контроль.

Почему Chaquopy тут ок: хост уже Kotlin, Python 3.12 доступен, стриминг решается poll-контрактом.

---

## 3. ChatterUI / llama.cpp (RN + C++) — две опции по Track B

Python-в-RN неестественен, поэтому ядро — **нативно/TS**. Раннер — важная развилка:

### Опция A (быстрый старт): llama.cpp **RPC** + HELIX как control-plane
- Использовать штатный `rpc-server` + `--rpc host:port` — llama.cpp сам режет слои и гонит
  тензоры по своему TCP.
- HELIX делает **только** discovery / placement / identity / attestation / health и
  **сообщает llama.cpp**, какие узлы:порты в кольце. L4-кольцо HELIX не используется.
- Минус: активации идут открыто по протоколу llama.cpp (без AEAD/healing/кодирования HELIX).
- Плюс: минимум кода, layer-split уже готов.

### Опция B (полное кольцо HELIX): `ShardRunner` над полосой слоёв
- Реализовать `ShardRunner`: загрузить band `[start,end)`, отдать `embed/forward/sample` с
  hidden-state I/O; KV держать локально. Требует доступа в ggml (прогнать подграф слоёв и
  вынуть скрытое состояние) — доработка нативного модуля.
- Плюс: активации едут по **кадрам HELIX** (шифрование, replay, healing/resume, int8-кодек).
- Минус: больше нативной работы (ggml-хуки, экспорт hidden state).

**Рекомендация:** начать с **Опции A** (доказать кластер), затем при необходимости перейти на
**B** ради безопасности/устойчивости data-plane.

### Ядро протокола в ChatterUI
- **TS-порт** тонкой оркестрации (координатор/сессия/контекст) + крипта из нативного модуля
  (`SecurityBridge`), сверка с `vectors.json`; llama.rn как `ShardRunner`. Либо
- **общий нативный core** (Rust/C, C ABI/JSI) — если идём на общее ядро для обоих хостов.
- Каркас интерфейсов — `integration/chatterui_llamacpp/helix.ts`.

---

## 4. Чек-лист bring-up (обязательный порядок)

1. **Соответствие:** нативная крипта/кодек проходят `helix/spec/vectors.json` (HKDF-подключи,
   запечатанный кадр, кодировки сообщений, node_id, подпись, якоря RFC 8439/8032). Без этого
   интероп не гарантирован.
2. **Один узел:** Runner отдаёт токены/ответ локально (`world_size=1` / один агент).
3. **Два устройства, один трек:** Track B — истинный шард на 2 (веха); Track A — routing на 2 +
   стрим `PARTIAL`.
4. **Безопасность:** identity (④) — подписанные кадры/голоса; attestation (③) — размещение по
   доказанной ёмкости.
5. **Устойчивость:** убить узел посреди генерации → healing (Track B resume / Track A reroute).
6. **Слияние UI:** один меш, тумблер режима B↔A.

---

## 5. Оценка усилий / рисков

| Блок | Усилие | Риск |
|---|---|---|
| Edge `AgentRunner`→LiteRT (Chaquopy) | низкое | низкий (проверенный паттерн) |
| ChatterUI Track B — Опция A (llama.cpp RPC) | низкое | средний (нет безопасности data-plane) |
| ChatterUI Track B — Опция B (`ShardRunner` над ggml) | высокое | высокий (ggml-хуки, export hidden state) |
| `Transport` (Wi-Fi Aware/USB) | среднее | средний (радио-нюансы Android) |
| `SecurityBridge` (нативная крипта) | низкое | низкий (сверка по векторам) |
| Общий Rust-core (если выбран) | высокое | средний (новый build-контур) |
| Prefill-полоса активаций (Track B) | среднее | средний (нужен int8-кодек/чанкинг) |

**Критический путь к «работает на железе»:** (1) векторы соответствия → (2) Edge `AgentRunner`
+ Track A на 2 телефонах (самый быстрый первый успех) → (3) ChatterUI Опция A → (4) добить
безопасность (③④, уже готова в ядре) → (5) при необходимости Опция B ради полного кольца.
