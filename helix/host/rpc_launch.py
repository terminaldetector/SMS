"""RPC launcher — turn a HELIX plan into runnable llama.cpp sharding commands (Track B / Option A).

This is the shortest path to **real multi-device sharding today**, without forking cui-llama.rn:
HELIX does discovery / memory-weighted placement / attestation and produces the topology
(:mod:`helix.rpc_cluster`); llama.cpp's own ``GGML_RPC`` binaries do the tensor split. This module
converts a plan (the ``rpc_plan`` control response) into the exact commands each device runs:

* every **worker** runs ``rpc-server -H 0.0.0.0 -p <port>`` (its band is offloaded to it);
* the **main/driver** runs ``llama-cli -m <model> -ngl 99 --rpc <workers> --tensor-split <ratios>``.

    # print commands for a running HELIX node (its coordinator must be a ControlNode):
    python -m helix.host.rpc_launch --host 127.0.0.1 --port 8765 \
        --model-id big-16b --n-layers 80 --model-bytes 16000000000 --model-path /sd/big.gguf

Build the binaries once per device with ``-DGGML_RPC=ON`` (llama.cpp). The tensor hops then ride
llama.cpp's TCP RPC (not HELIX frames) — run over a trusted LAN / WireGuard (see LEVEL3_sharding.md).
"""

from __future__ import annotations

import argparse
import asyncio
import json
from typing import Any, Dict, List


def _fmt_split(split: List[float]) -> str:
    # llama.cpp --tensor-split: comma-separated proportions across [main-local, worker0, worker1, ...]
    return ",".join("{:.4f}".format(x) for x in split)


def build_commands(
    plan: Dict[str, Any],
    model_path: str,
    *,
    prompt: str = "",
    server: bool = False,
    n_predict: int = 128,
) -> Dict[str, Any]:
    """Build the llama.cpp command line for each node from a ``rpc_plan`` response.

    Returns ``{"main": {...}, "workers": [{...}]}`` where each entry has ``node`` and ``cmd`` (argv).
    """
    endpoints = plan["endpoints"]
    workers = [e for e in endpoints if e["role"] == "worker"]
    main = next(e for e in endpoints if e["role"] == "main")

    worker_cmds = []
    for e in workers:
        port = e["addr"].rsplit(":", 1)[-1]
        worker_cmds.append({"node": e["node"], "addr": e["addr"],
                            "cmd": ["rpc-server", "-H", "0.0.0.0", "-p", port]})

    binary = "llama-server" if server else "llama-cli"
    cmd = [binary, "-m", model_path, "-ngl", "99"]
    if plan.get("rpc_arg"):
        cmd += ["--rpc", plan["rpc_arg"]]
    if plan.get("tensor_split"):
        cmd += ["--tensor-split", _fmt_split(plan["tensor_split"])]
    if not server:
        cmd += ["-n", str(n_predict)]
        if prompt:
            cmd += ["-p", prompt]

    return {"main": {"node": main["node"], "addr": main["addr"], "cmd": cmd}, "workers": worker_cmds}


def render(commands: Dict[str, Any]) -> str:
    lines = ["# HELIX -> llama.cpp RPC sharding. Run each on its device (build with -DGGML_RPC=ON)."]
    for w in commands["workers"]:
        lines.append("# worker {} ({})".format(w["node"], w["addr"]))
        lines.append(" ".join(w["cmd"]))
    m = commands["main"]
    lines.append("# main/driver {} ({})".format(m["node"], m["addr"]))
    lines.append(" ".join(_quote(a) for a in m["cmd"]))
    return "\n".join(lines)


def _quote(a: str) -> str:
    return '"{}"'.format(a) if " " in a else a


async def _fetch_plan(host: str, port: int, req: Dict[str, Any]) -> Dict[str, Any]:
    reader, writer = await asyncio.open_connection(host, port)
    writer.write((json.dumps(req) + "\n").encode())
    await writer.drain()
    line = await reader.readline()
    writer.close()
    return json.loads(line)


def _selftest() -> None:
    from helix.placement import Capacity
    from helix.rpc_cluster import plan_rpc_cluster

    GB = 2 ** 30
    members = {"A": Capacity(8 * GB), "B": Capacity(4 * GB), "C": Capacity(4 * GB)}
    addrs = {"A": "10.0.0.1:50052", "B": "10.0.0.2:50052", "C": "10.0.0.3:50052"}
    plan = plan_rpc_cluster(members, addrs, "big-16b", 6, 3 * GB, order=["A", "B", "C"]).to_dict()

    cmds = build_commands(plan, "/sd/big.gguf", prompt="hello", n_predict=64)
    # two workers, each an rpc-server on its own port
    assert [w["node"] for w in cmds["workers"]] == ["B", "C"], cmds
    assert cmds["workers"][0]["cmd"] == ["rpc-server", "-H", "0.0.0.0", "-p", "50052"], cmds["workers"][0]
    # main: llama-cli with --rpc (workers) + --tensor-split spanning the ring
    main = cmds["main"]["cmd"]
    assert main[0] == "llama-cli" and "/sd/big.gguf" in main, main
    assert main[main.index("--rpc") + 1] == "10.0.0.2:50052,10.0.0.3:50052", main
    assert main[main.index("--tensor-split") + 1] == "0.5000,0.3333,0.1667", main
    assert main[main.index("-p") + 1] == "hello", main
    print("  build: 2 rpc-server workers + llama-cli --rpc/--tensor-split from the HELIX plan")

    # server variant omits prompt, uses llama-server
    srv = build_commands(plan, "/sd/big.gguf", server=True)["main"]["cmd"]
    assert srv[0] == "llama-server" and "-p" not in srv, srv
    print("  build: --server variant -> llama-server")
    print("  rendered:\n" + "\n".join("    " + ln for ln in render(cmds).splitlines()))
    print("ALL PASSED")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=False, help="HELIX control-server TCP port")
    ap.add_argument("--model-id")
    ap.add_argument("--n-layers", type=int)
    ap.add_argument("--model-bytes", type=int)
    ap.add_argument("--model-path", help="on-device path to the GGUF")
    ap.add_argument("--prompt", default="")
    ap.add_argument("--server", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest or args.port is None:
        _selftest()
        return

    if not all([args.model_id, args.n_layers, args.model_bytes, args.model_path]):
        raise SystemExit("need --model-id --n-layers --model-bytes --model-path to fetch a plan")
    resp = asyncio.run(_fetch_plan(args.host, args.port, {
        "cmd": "rpc_plan", "model_id": args.model_id, "n_layers": args.n_layers,
        "model_bytes": args.model_bytes,
    }))
    if not resp.get("ok"):
        raise SystemExit("rpc_plan failed: {}".format(resp.get("error")))
    print(render(build_commands(resp, args.model_path, prompt=args.prompt, server=args.server)))


if __name__ == "__main__":
    main()
