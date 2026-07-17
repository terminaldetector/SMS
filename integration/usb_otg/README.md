# USB-OTG — PC ↔ smartphones (star / ring)

USB as a reliable **byte stream** (AOA / usb-serial), carried by
[`StreamTransport`](../../helix/transport/stream.py). Multiple streams become a **star** (PC
hub) or **ring** via a [`MeshRouter`](../../helix/mesh/router.py).

```
        smartphone A ──USB──┐
        smartphone B ──USB──┤►  [ PC hub: MeshRouter{ StreamTransport×N (+ WifiTransport) } ]  ── PowerShell ── ControlServer
        smartphone C ──USB──┘
```

## Files
- `pc_usb.py` — PC side (runs Python HELIX directly): open a byte stream per phone →
  `StreamTransport` → `MeshRouter` (star). Channels: pyserial-asyncio (CDC), pyusb (AOA), or
  `adb forward` + TCP (dev). Attach a node + `ControlServer` for PowerShell.
- `UsbStreamTransport.kt` — Android client: native transport over the AOA/usb-serial byte
  stream, **same length-prefix framing** as `helix/transport/framing.py` (uint32 BE length,
  checked before allocation), bridging decoded frames up to the HELIX codec.

## Roles & topology
- **PC** = coordinator + big Track-B node (llama.cpp) on the wired backbone; hub relaying
  leaf↔leaf and USB↔WiFi.
- **Star:** each phone = one `StreamTransport` downstream on the PC's `MeshRouter`.
- **Ring:** each node has two neighbour `StreamTransport`s; the router floods with TTL+dedup.
- **Multiplex:** the PC runs one `StreamTransport` per USB port; the `MeshRouter` routes between
  them by the presence-learned directory.

## Bring-up
1. Conformance gate (`python -m helix.conformance`) if using a native codec.
2. `python -m helix.transport.stream_selftest` — verify the stream path locally.
3. Wire real channels in `pc_usb.py`; connect phones; `Get-HelixNodes` from PowerShell should
   list them; `Invoke-HelixInfer` runs across the USB star.
