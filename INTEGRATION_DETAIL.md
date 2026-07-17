# Детализация интеграции HELIX — Track B (llama.cpp), Transport, тест на 2 устройствах

Технический аддендум к [`INTEGRATION_BRIEF.md`](INTEGRATION_BRIEF.md). Три части: как реально
подключить llama.cpp по Track B (обе опции), как реализовать `Transport` на Android, и
пошаговый bring-up на двух устройствах.

> Команды llama.cpp приведены для актуального master (флаги менялись — сверяйте с вашей
> версией). Ключевые факты подтверждены по официальному `tools/rpc/README.md`.

---

## 1. Track B на llama.cpp

### 1.1 Опция A — llama.cpp **RPC** исполняет, HELIX координирует (быстрый старт)

llama.cpp уже умеет распределённый инференс: `rpc-server` на воркерах + `--rpc` на главном.
**По умолчанию веса и KV распределяются пропорционально доступной памяти каждого устройства** —
это идеально ложится на HELIX (③ аттестация даёт *доказанную* память, HELIX задаёт кольцо).

Сборка (на каждом узле):
```bash
cmake -B build -DGGML_RPC=ON            # (+ -DGGML_VULKAN=ON / -DGGML_CUDA=ON под ускоритель)
cmake --build build --config Release    # даёт rpc-server и llama-cli с поддержкой RPC
```
Воркеры:
```bash
./build/bin/rpc-server -H 0.0.0.0 -p 50052 -c    # -c: локальный кэш тензоров (ускоряет загрузку)
```
Главный узел:
```bash
./build/bin/llama-cli -m model-Q4_K_M.gguf -ngl 99 \
    --rpc 192.168.49.10:50052,192.168.49.11:50052 \
    --tensor-split 0.5,0.3,0.2 -p "..."           # -ts: доли по attested-ёмкости (иначе авто по памяти)
```

**Роль HELIX (control-plane, тензоров не касается):**
1. **Discovery + attestation (③):** собрать живые узлы, получить их **доказанную** ёмкость
   (сертификаты `CapabilityCert`, а не самодекларацию).
2. **Placement:** `plan_placement(attested_caps, ...)` → упорядоченное кольцо + доли памяти.
3. **Сформировать запуск:** `--rpc <ip:port список из кольца>` и `--tensor-split <доли из
   attested-ёмкости>`; поднять `rpc-server` на воркерах.
4. **Health/healing:** heartbeat узлов; при уходе — перезапустить главный с новым `--rpc` списком
   (llama.cpp RPC сам не лечится, HELIX перезапускает поколение).

**Безопасность — главная причина, зачем тут HELIX.** У RPC llama.cpp **нет аутентификации**
(README прямо предупреждает не выставлять в недоверенную сеть). HELIX закрывает это снаружи:
- в `--rpc` список попадают **только аттестованные (③) и подписанные (④)** узлы;
- RPC-трафик идёт по **доверенному линку**, который поднял HELIX (Wi-Fi Aware/USB, §2), а не по
  открытому Wi-Fi;
- допуск в кластер гейтится идентичностью/аттестацией → чужой узел не подсунет свой rpc-server.

**Android/ChatterUI:** `rpc-server` — нативный бинарь (кросс-компиляция NDK) либо RPC,
вкомпилированный в используемый llama.rn/llama.cpp. Запускать воркер как bundled-бинарь/сервис.
Минус Опции A: активации идут по **протоколу llama.cpp**, без AEAD/healing/int8-кодека HELIX
(их даёт только Опция B).

### 1.2 Опция B — `ShardRunner` над полосой слоёв (полное кольцо HELIX)

Реализовать контракт `helix/shard.py`: узел держит band `[start,end)`, отдаёт
`embed/forward/sample` со **скрытым состоянием на границе**, KV локально. Тогда активации едут
**кадрами HELIX** → шифрование, replay-защита, healing/resume, int8-кодек.

Что нужно в рантайме:
- **ggml-путь для подграфа слоёв:** построить граф только для band'а, вход — тензор
  hidden-state (или token-ids на первом шардe), выход — hidden-state перед `lm_head`
  (на последнем — логиты → `sample`). llama.cpp «из коробки» такого хука не даёт → доработка
  ggml (собрать граф по диапазону блоков, прокинуть I/O скрытого состояния).
- **KV на band:** каждый узел хранит KV только своих слоёв, переиспользует между шагами.
- **Токенайзер** на первом/последнем шарде (`embed`/`detok`).

**Практичная альтернатива для B — ONNX Runtime Mobile:** оффлайн разрезать граф по границам
слоёв (per-shard суб-модели с hidden-state I/O и `past_key_values` только своих слоёв), грузить
`shard_i.onnx` в ORT. Чище для hidden-state I/O, чем хирургия по ggml (см. исходный роадмап
Track B). Формат: экспорт из HF/PyTorch → квантизация per-shard.

**Когда брать B:** нужна конфиденциальность/устойчивость **data-plane** (активации по проводу),
а не только control-plane. Иначе A.

### 1.3 Итог по Track B

| | Опция A (RPC) | Опция B (ShardRunner) |
|---|---|---|
| Layer-split | готов в llama.cpp | свой (ggml/ONNX) |
| Активации | протокол llama.cpp (открыто) | кадры HELIX (AEAD/replay/heal/int8) |
| Healing | перезапуск поколения | resume с чекпойнта (L5) |
| Код | мало | много |
| Старт | **сейчас** | после A |

---

## 2. Transport на Android (под контракт `Transport`)

Контракт: `send(nodeId, frame)` / `broadcast(frame)` / `onFrame(bytes)` / `peers()`. Обработчик
получает **только байты** — `src` внутри подписанного кадра, транспорт личность не резолвит.
Три несущих, **все сводятся к IP** (переиспользуется length-prefixed TCP как в
`helix/transport/wifi.py`):

### 2.1 Wi-Fi Aware (NAN) — основной P2P дата-путь (без точки доступа)
`WifiAwareManager` (API 26+):
1. `attach()` → сессия.
2. **Discovery:** `publish()` / `subscribe()` со service name (напр. `"helix-mesh"`); в
   служебном payload — `node_id`. Найденные пиры → наполняют `peers()` и карту `node_id↔handle`.
3. **Data path:** `WifiAwareNetworkSpecifier` + `ConnectivityManager.requestNetwork()` →
   **IPv6 link-local** адрес + порт → обычные TCP-сокеты по этой сети.
4. **Маппинг:** discovery → `peers()`; сокеты → `send`/`broadcast` (length-prefix + `MAX_FRAME`
   до аллокации); входящие байты → `onFrame(bytes)`; `send(self)` → локальная петля.

### 2.2 NSD / mDNS (тот же Wi-Fi/AP) — проще, но нужен общий L2
`NsdManager`: `registerService()` (DNS-SD, тип `_helix._tcp`) + `discoverServices()` →
`resolveService()` → `node_id→(ip,port)` → TCP length-prefixed. Хороший fallback, когда все на
одном роутере/hotspot.

### 2.3 USB-OTG — байт-поток PC↔клиент (основной путь) + звезда/кольцо
USB как **надёжный байт-поток** (AOA / usb-serial) → `StreamTransport`
(`helix/transport/stream.py`), а не только tether→IP. Несколько потоков собираются в **звезду**
(PC-hub) или **кольцо** через `MeshRouter` (`helix/mesh/router.py`). Каркасы:
`integration/usb_otg/` (PC — `pc_usb.py`; клиент — `UsbStreamTransport.kt`, то же length-prefix
кадрирование). Альтернатива — USB-tether → `usb0` IP → существующий Wi-Fi/IP-транспорт без
изменений (для телефон↔Linux-SBC).

### 2.4 BitChat / BLE — вход клиента в чат (Track A + control)
BT-клиенты входят через **bitchat-core** (BLE-mesh + Noise) и общаются с **сервером**; серверы
между собой — по Wi-Fi/USB. Это `MeshRouter` на сервере с двумя downstream
(`BitChatTransport` + `WifiTransport`/`StreamTransport`). Message-ориентирован (без
length-prefix). Директория `node_id↔peerID` — через presence-рассылку; bitchat сам делает BLE
multi-hop. **Только Track A/control**, не тяжёлые активации. Каркас: `integration/bitchat_ble/`.

### 2.5 Роутинг (звезда/кольцо/мост)
`MeshRouter` пересылает кадры между несколькими транспортами по внешнему routing-конверту
(cleartext `dst` над зашифрованным кадром; TTL+дедуп). Спека — `HELIX_WIRE_SPEC.md §5.1`,
векторы — `vectors.json:routing_envelope`.

### 2.6 Общее для всех несущих
- **Кадрирование потока:** 4-байтовый length-prefix + проверка `> MAX_FRAME` **до** `readFully`
  (`helix/transport/framing.py`) — иначе DoS. BitChat — целые сообщения, без префикса.
- **Идентичность:** транспорт **не** сопоставляет адрес↔узел для аутентификации; `src` берётся из
  кадра кодеком. Адрес-карта нужна только для `send`/роутинга.

## 2bis. PowerShell-интерфейс на PC
PC гоняет Python-стек + `helix/host/control_server.py` (JSON-lines на `127.0.0.1`); модуль
`integration/powershell/Helix.psm1` даёт cmdlet'ы `Connect-Helix`, `Get-HelixNodes`,
`Invoke-HelixInfer -Prompt -Mode -Skill`, `Add-HelixContext`.

### 2.5 Разрешения (Android)
`NEARBY_WIFI_DEVICES` (API 33+, `neverForLocation`) или `ACCESS_FINE_LOCATION` (старее),
`CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE`; foreground-сервис
`connectedDevice|dataSync`; для USB — tethering API/ручное включение.

---

## 3. Пошаговый bring-up на 2 устройствах

### 3.0 Гейт (обязательно первым) — соответствие проводу
Нативная крипта/кодек **проходят** `helix/spec/vectors.json`:
```bash
python -m helix.conformance           # эталон
# затем: нативный SecurityBridge воспроизводит HKDF-подключи, запечатанный кадр,
# кодировки сообщений, node_id, подпись + якоря RFC 8439/8032 — байт-в-байт.
```
Не сошлось → интероп не гарантирован, дальше не идти.

### 3.1 Один узел (каждый трек отдельно)
- **Track A:** `AgentRunner`→LiteRT отдаёт стрим на одном телефоне (`world_size=1`).
- **Track B:** `llama-cli -m model.gguf -ngl 99` (без `--rpc`) на одном — модель, влезающая в один.

### 3.2 Два устройства — Track A (edge/LiteRT)
1. Оба поднимают `MeshService` (общий cluster secret, введён/QR один раз).
2. Проверить discovery: `get_cluster_nodes` видит оба.
3. Прогнать 4 режима: `single` (роутинг), `parallel`+`voting` (веер+решение), `pipeline`
   (2 стадии). Наблюдать стрим `PARTIAL` в UI.
4. **Healing:** выключить Wi-Fi на одном посреди задачи → координатор перемаршрутизирует, ответ
   завершается (`heals≥1`).

### 3.3 Два устройства — Track B (ChatterUI/llama.cpp, Опция A)
1. На обоих собрать с `-DGGML_RPC=ON`; на «воркере» — `rpc-server -p 50052`.
2. HELIX: discovery → **аттестация (③)** обоих → `plan_placement` → сформировать `--rpc
   ip:50052` + `--tensor-split` по доказанной памяти.
3. Запустить главный: `llama-cli -m 16b-Q4_K_M.gguf -ngl 99 --rpc <воркер:50052> -ts <доли>`.
4. **Веха:** 16B Q4, не влезающая в один телефон, генерирует на двух. Замерить, что оба узла
   реально держат свою полосу (память/лог RPC).

### 3.4 Безопасность (после того как «просто работает»)
- **④:** включить идентичности → голоса/контекст подписаны; подделка «от имени другого узла»
  отвергается (см. `secure_selftest`).
- **③:** размещение только по аттестованной ёмкости; узел, солгавший о памяти, не получает полосу.

### 3.5 Что мерить и критерии успеха
| Метрика | Как | Цель |
|---|---|---|
| Задержка первого токена | таймстемп seed→первый `PARTIAL`/`SHARD_TOKEN` | приемлемо для чата |
| Токенов/с (decode) | счётчик за окно | не хуже деградации от сети |
| Полоса префилла (B) | байты активаций/префилл | узкое место → int8-кодек/чанкинг |
| Память/узел | RSS приложения | band+KV+рантайм ≲ 2.5–3 ГБ |
| Надёжность | убить узел → результат | Track A reroute / Track B перезапуск |

### 3.6 Типичные грабли
- Wi-Fi Aware не на всех чипсетах → fallback на NSD/hotspot.
- llama.cpp RPC **без auth** → никогда не выставлять `rpc-server` в открытую сеть; только по
  HELIX-линку и только аттестованным узлам.
- Разошёлся `--tensor-split` vs реальная память → OOM: доверять **аттестованной** ёмкости, не
  заявленной.
- Префилл 16B по BLE — нежизнеспособно; для B нужен Wi-Fi Aware/USB.
- Один физический хост, два процесса/эмулятора → одинаковый IP ломает адресацию: тестировать на
  **раздельных** устройствах.
