#!/usr/bin/env python3
"""Test whether the CF front (VLESS proxy on Pages) can actually
reach the home-broadband nodes.

Runs on the GitHub Actions runner (direct internet). It connects
to the front via VLESS-over-WebSocket and asks it to open a TCP
connection to a target, then reports whether the front connected.

Targets:
  1. example.com:80 — proves the front's VLESS proxy works at all.
  2. The first 3 openvpn nodes from the live jk_home_cmcc.yaml —
     proves the front can reach the home nodes (the middle leg of
     the dialer-proxy chain: phone -> CF front -> home node).

A VLESS response header (2 bytes, first byte 0x00) means the front
successfully opened the TCP connection to the target. A timeout or
close means the front could not reach it (e.g. Cloudflare's
sockets API blocking the port/IP).

This only tests the TCP leg, not the OpenVPN handshake (SoftEther
does not answer a bare OpenVPN reset, so that cannot be probed
this way). Exit code is 0 if the general test passes; the home
node results are reported per node.
"""

import socket
import struct
import sys
import time
import urllib.request
import uuid

import websocket
import yaml

FRONT = "cfnew-aeo.pages.dev"
UUID = uuid.UUID("351c9981-04b6-4103-aa4b-864aa9c91469")
SUB_URL = (
    "https://raw.githubusercontent.com/zhaor5869-svg/cfnew-worker"
    "/main/jk_home_cmcc.yaml"
)
WS_TIMEOUT_S = 15
RECV_TIMEOUT_S = 10


def vless_request(target_host, target_port, is_domain=False):
    hdr = b"\x00" + UUID.bytes + b"\x00" + b"\x01" + struct.pack(
        ">H", target_port
    )
    if is_domain:
        hb = target_host.encode()
        hdr += b"\x02" + bytes([len(hb)]) + hb
    else:
        hdr += b"\x01" + socket.inet_aton(target_host)
    return hdr


def test_target(target_host, target_port, is_domain, label):
    print(f"--- {label}: front -> {target_host}:{target_port} ---", flush=True)
    try:
        ws = websocket.create_connection(
            f"wss://{FRONT}/?ed=2048",
            header={"User-Agent": "Mozilla/5.0"},
            timeout=WS_TIMEOUT_S,
        )
    except Exception as exc:
        print(f"  WS connect FAILED: {type(exc).__name__}: {exc}", flush=True)
        return False
    try:
        ws.send(
            vless_request(target_host, target_port, is_domain),
            opcode=websocket.ABNF.OPCODE_BINARY,
        )
        ws.settimeout(RECV_TIMEOUT_S)
        try:
            resp = ws.recv()
        except Exception as exc:
            print(
                f"  no VLESS response (front could not connect): "
                f"{type(exc).__name__}: {exc}",
                flush=True,
            )
            return False
        if isinstance(resp, str):
            resp = resp.encode()
        print(
            f"  VLESS response header: {resp[:2].hex()} "
            f"(len {len(resp)} bytes)",
            flush=True,
        )
        if len(resp) >= 2 and resp[0] == 0x00:
            print(f"  => front CONNECTED to {target_host}:{target_port}", flush=True)
            return True
        print("  => unexpected response, front did NOT connect cleanly", flush=True)
        return False
    finally:
        try:
            ws.close()
        except Exception:
            pass


def main():
    print(f"front: {FRONT} (default UUID)", flush=True)
    general = test_target("example.com", 80, True, "general front test")
    print(f"general front test: {'PASS' if general else 'FAIL'}", flush=True)
    time.sleep(1)

    # Fetch the live subscription and test the first 3 home nodes.
    try:
        req = urllib.request.Request(
            SUB_URL, headers={"User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = yaml.safe_load(resp.read().decode("utf-8"))
    except Exception as exc:
        print(f"ERROR: could not fetch live subscription: {exc}", flush=True)
        return 1
    home = [p for p in (data or {}).get("proxies", []) if p.get("type") == "openvpn"][:3]
    if not home:
        print("ERROR: no openvpn nodes in live subscription", flush=True)
        return 1
    results = []
    for node in home:
        ok = test_target(node["server"], node["port"], False, f"home node {node['name']}")
        results.append((node["name"], ok))
        time.sleep(1)
    print("home node results:", flush=True)
    for name, ok in results:
        print(f"  {name}: {'CONNECTED' if ok else 'FAILED'}", flush=True)
    passed = sum(1 for _, ok in results if ok)
    print(f"home nodes reachable via front: {passed}/{len(results)}", flush=True)
    return 0 if general else 1


if __name__ == "__main__":
    sys.exit(main())
