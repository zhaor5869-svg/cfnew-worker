#!/usr/bin/env python3
"""Regenerate the CFnew v3.1 home-broadband (家宽) Clash Meta files.

Standalone: stdlib + PyYAML only.

What it does (mirrors the logic in _worker.js: 解析家宽清单 /
获取家宽节点 / 生成家宽链式值):
  1. Fetches the normal Clash subscription from the live Pages site and
     takes the TLS-443 VLESS nodes as the "front" (dialer-proxy) nodes.
  2. Fetches the public VPN Gate list (https://www.vpngate.net/api/iphone/,
     HTTP fallback — the source TLS is old and some runtimes cannot
     handshake it), parses the CSV, keeps only TCP OpenVPN configs,
     excludes the datacenter entries (hostname starting with
     "public-vpn", IP starting with "219.100.37."), sorts by speed,
     JP/KR first, max 30 home nodes.
  3. Writes jk_home.yaml (front = preferred-domain VLESS nodes) and
     jk_home_cmcc.yaml (front = 2 direct pages.dev nodes, China Mobile
     version) into the current working directory.

Placement in the GitHub repo (zhaor5869-svg/cfnew-worker):
  - this file  -> gh-action/generate_jk.py
  - workflow   -> .github/workflows/update-jk.yml  (runs
    `python3 gh-action/generate_jk.py` from the repo root, so both
    output files land at the repo root and can be subscribed to as
    raw.githubusercontent.com/zhaor5869-svg/cfnew-worker/main/jk_home.yaml
    and .../jk_home_cmcc.yaml)

Exits non-zero if fewer than 10 home nodes parse or if a generated
file fails yaml.safe_load validation. Never invents node IPs or
certificates — every server address and certificate comes from the
live VPN Gate response or the live Pages subscription.
"""

import base64
import binascii
import re
import sys
import urllib.request
import urllib.error
from collections import Counter
from datetime import datetime, timezone, timedelta

import yaml

FRONT_SUB_URL = (
    "https://cfnew-aeo.pages.dev/351c9981-04b6-4103-aa4b-864aa9c91469"
    "/sub?target=clash"
)
DEFAULT_UUID = "351c9981-04b6-4103-aa4b-864aa9c91469"
VPNGATE_URLS = [
    "https://www.vpngate.net/api/iphone/",
    "http://www.vpngate.net/api/iphone/",
]
MAX_HOME_NODES = 30
MIN_HOME_NODES = 10
FRONT_GROUP = "⚡ CF前置"
HOME_AUTO = "🏠 家宽自动"
HOME_PICK = "🏠 家宽节点"
MAIN_PICK = "🚀 节点选择"
DATACENTER_HOST_PREFIX = "public-vpn"
DATACENTER_IP_PREFIX = "219.100.37."
CONFIG_HEAD_LEN = 6000

DIRECT_FRONT = [
    ("直连-移动-01", "cfnew-aeo.pages.dev"),
    ("直连-移动-02", "cfnew-bq1.pages.dev"),
]


def fetch_text(url, timeout=30):
    req = urllib.request.Request(
        url, headers={"User-Agent": "Mozilla/5.0", "Accept": "text/plain"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status != 200:
            raise RuntimeError(f"{url} returned HTTP {resp.status}")
        return resp.read().decode("utf-8", errors="replace")


def b64decode_padded(data):
    data = re.sub(r"\s", "", data)
    data += "=" * (-len(data) % 4)
    return base64.b64decode(data)


def decode_ovpn_config(config64, full=False):
    """Mirror _worker.js 解家宽配置: decode only the first 6000 chars
    unless the full config is needed (remote not found / cert blocks)."""
    clean = re.sub(r"\s", "", config64)
    if full or len(clean) <= CONFIG_HEAD_LEN:
        return b64decode_padded(clean).decode("utf-8", errors="replace")
    return b64decode_padded(clean[:CONFIG_HEAD_LEN]).decode(
        "utf-8", errors="replace"
    )


def directive(text, name):
    m = re.search(rf"^[ \t]*{re.escape(name)}[ \t]+(.+?)[ \t]*$", text, re.M)
    return m.group(1).strip() if m else ""


def inline_block(text, tag):
    m = re.search(rf"<{tag}>([\s\S]*?)</{tag}>", text)
    return m.group(1).strip() if m else ""


def parse_vpngate(text):
    """Mirror _worker.js 解析家宽清单. Returns (nodes, cert)."""
    candidates = []
    for raw_line in text.split("\n"):
        line = raw_line.strip()
        if not line or line.startswith("*") or line.startswith("#"):
            continue
        fields = line.split(",")
        if len(fields) < 15:
            continue
        # Only residential broadband: drop the operator's own datacenter
        # servers (they fill up and fail handshakes easily).
        if (fields[0] or "").startswith(DATACENTER_HOST_PREFIX):
            continue
        if (fields[1] or "").startswith(DATACENTER_IP_PREFIX):
            continue
        config64 = fields[-1]
        if not config64 or len(config64) < 100:
            continue
        try:
            speed = int(fields[4])
        except (ValueError, TypeError):
            speed = 0
        try:
            ping = int(fields[3])
        except (ValueError, TypeError):
            ping = 9999
        candidates.append(
            {
                "country": (fields[6] or "").upper() or "XX",
                "speed": speed,
                "ping": ping,
                "config64": config64,
            }
        )
    # Speed descending (fewest sessions is NOT better — those servers
    # are usually unreachable, per the worker's comment).
    candidates.sort(key=lambda c: c["speed"], reverse=True)

    nodes = []
    cert = None
    decode_failures = 0
    for item in candidates:
        try:
            config = decode_ovpn_config(item["config64"])
            if not directive(config, "remote"):
                config = decode_ovpn_config(item["config64"], full=True)
        except (binascii.Error, ValueError, UnicodeDecodeError):
            decode_failures += 1
            continue
        # The front can only carry TCP; drop UDP nodes.
        if (directive(config, "proto") or "tcp").lower() != "tcp":
            continue
        remote = directive(config, "remote").split()
        if not remote or not remote[0]:
            continue
        if cert is None:
            # The certificate is shared by the whole list; take the
            # first complete one.
            try:
                full = decode_ovpn_config(item["config64"], full=True)
                this_cert = {
                    "ca": inline_block(full, "ca"),
                    "cert": inline_block(full, "cert"),
                    "key": inline_block(full, "key"),
                }
                if this_cert["ca"] and this_cert["cert"] and this_cert["key"]:
                    cert = this_cert
            except (binascii.Error, ValueError, UnicodeDecodeError):
                decode_failures += 1
                continue
            if cert is None:
                continue
        try:
            port = int(remote[1]) if len(remote) > 1 else 443
        except ValueError:
            port = 443
        nodes.append(
            {
                "country": item["country"],
                "ping": item["ping"],
                "speed": item["speed"],
                "server": remote[0],
                "port": port,
                "cipher": directive(config, "cipher") or "AES-128-CBC",
                "auth": directive(config, "auth") or "SHA1",
            }
        )
    return nodes, cert, decode_failures


def fetch_front_nodes():
    """Take the TLS-443 VLESS nodes from the live Pages Clash feed."""
    text = fetch_text(FRONT_SUB_URL, timeout=30)
    data = yaml.safe_load(text)
    proxies = (data or {}).get("proxies") or []
    front = [
        p
        for p in proxies
        if p.get("type") == "vless" and p.get("port") == 443 and p.get("tls")
    ]
    if not front:
        raise RuntimeError("no TLS-443 VLESS front nodes in Pages subscription")
    return front


def direct_front_node(name, host):
    return {
        "name": name,
        "type": "vless",
        "server": host,
        "port": 443,
        "uuid": DEFAULT_UUID,
        "udp": True,
        "tls": True,
        "client-fingerprint": "chrome",
        "servername": host,
        "skip-cert-verify": False,
        "network": "ws",
        "ws-opts": {"path": "/?ed=2048", "headers": {"Host": host}},
    }


def quote(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def render_front_node(node):
    """Render one VLESS front node, mirroring _worker.js 构建值节点行."""
    lines = [
        f'  - name: {quote(node["name"])}',
        f'    type: {node["type"]}',
        f'    server: {quote(node["server"])}',
        f'    port: {node["port"]}',
        f'    uuid: {node["uuid"]}',
        "    udp: true",
        f'    tls: {"true" if node.get("tls") else "false"}',
    ]
    if node.get("flow"):
        lines.append(f'    flow: {quote(node["flow"])}')
    lines.append(
        f'    client-fingerprint: {quote(node.get("client-fingerprint") or "chrome")}'
    )
    if node.get("tls"):
        sni = node.get("servername") or node.get("sni") or node["server"]
        lines.append(f"    servername: {quote(sni)}")
        lines.append("    skip-cert-verify: false")
    if node.get("network") == "ws":
        ws = node.get("ws-opts") or {}
        path = ws.get("path", "/?ed=2048")
        host = (ws.get("headers") or {}).get("Host") or node["server"]
        lines += [
            "    network: ws",
            "    ws-opts:",
            f"      path: {quote(path)}",
            "      headers:",
            f"        Host: {quote(host)}",
        ]
    return "\n".join(lines)


def indent_cert(text, prefix="      "):
    return "\n".join(
        prefix + line.strip() for line in text.split("\n") if line.strip()
    )


def render_home_node(node, index, cert):
    lines = [
        f'  - name: {quote(node["name"])}',
        "    type: openvpn",
        f"    server: {node['server']}",
        f"    port: {node['port']}",
        "    proto: tcp",
        "    username: vpn",
        "    password: vpn",
        f"    cipher: {node['cipher']}",
        f"    auth: {node['auth']}",
        "    udp: false",
        "    handshake-timeout: 30",
        "    remote-dns-resolve: true",
        "    dns: [ 8.8.8.8, 1.1.1.1 ]",
        f'    dialer-proxy: {quote(FRONT_GROUP)}',
    ]
    if index == 0:
        lines.append("    ca: &jkca |-")
        lines.append(indent_cert(cert["ca"]))
        lines.append("    cert: &jkcert |-")
        lines.append(indent_cert(cert["cert"]))
        lines.append("    key: &jkkey |-")
        lines.append(indent_cert(cert["key"]))
    else:
        lines += ["    ca: *jkca", "    cert: *jkcert", "    key: *jkkey"]
    return "\n".join(lines)


def build_document(front_nodes, home_nodes, cert, generated_stamp, extra_note):
    header = [
        "# cfnew 家宽订阅：CF 节点带路，落地是住宅宽带",
        "# 内核要 1.19.25 以上，老内核不认这类节点",
        "# 节点是网友共享的，掉线很正常，家宽自动会自己往下换",
        f"# 生成时间 {generated_stamp}，节点有效期以 VPN Gate 清单为准",
    ]
    if extra_note:
        header.append(f"# {extra_note}")
    header += [
        "mixed-port: 7890",
        "allow-lan: false",
        "mode: rule",
        "log-level: info",
        "ipv6: false",
        "unified-delay: true",
        "tcp-concurrent: true",
        "external-controller: 127.0.0.1:9090",
        "dns:",
        "  enable: true",
        "  ipv6: false",
        "  enhanced-mode: fake-ip",
        "  fake-ip-range: 198.18.0.1/16",
        "  nameserver:",
        "    - https://223.5.5.5/dns-query",
        "    - https://1.1.1.1/dns-query",
        "",
        "proxies:",
    ]
    body = list(header)
    for node in front_nodes:
        body.append(render_front_node(node))
    for i, node in enumerate(home_nodes):
        body.append(render_home_node(node, i, cert))

    front_names = [n["name"] for n in front_nodes]
    home_names = [n["name"] for n in home_nodes]
    home_by_country = sorted(home_names, key=lambda n: n.split("-")[0])

    def group_lines(names):
        return "\n".join(f'      - {quote(n)}' for n in names)

    body.append("")
    body.append("proxy-groups:")
    body.append(f'  - name: {quote(FRONT_GROUP)}')
    body.append("    type: url-test")
    body.append("    url: https://www.gstatic.com/generate_204")
    body.append("    interval: 300")
    body.append("    tolerance: 50")
    body.append("    proxies:")
    body.append(group_lines(front_names))
    body.append(f'  - name: {quote(HOME_AUTO)}')
    body.append("    type: url-test")
    body.append("    url: https://www.gstatic.com/generate_204")
    body.append("    interval: 600")
    body.append("    tolerance: 50")
    body.append("    proxies:")
    body.append(group_lines(home_names))
    body.append(f'  - name: {quote(HOME_PICK)}')
    body.append("    type: select")
    body.append("    proxies:")
    body.append(group_lines(home_by_country))
    body.append(f'  - name: {quote(MAIN_PICK)}')
    body.append("    type: select")
    body.append("    proxies:")
    body.append(f'      - {quote(HOME_AUTO)}')
    body.append(f'      - {quote(HOME_PICK)}')
    body.append(f'      - {quote(FRONT_GROUP)}')
    body.append('      - DIRECT')
    body.append("")
    body.append("rules:")
    body.append("  - GEOIP,LAN,DIRECT,no-resolve")
    body.append("  - GEOIP,CN,DIRECT,no-resolve")
    body.append(f"  - MATCH,{MAIN_PICK}")
    return "\n".join(body) + "\n"


def validate(path, expect_vless, expect_openvpn):
    with open(path, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    proxies = data.get("proxies") or []
    vless = [p for p in proxies if p.get("type") == "vless"]
    home = [p for p in proxies if p.get("type") == "openvpn"]
    names = [p.get("name") for p in proxies]
    assert len(vless) == expect_vless, (
        f"{path}: expected {expect_vless} vless, got {len(vless)}"
    )
    assert len(home) == expect_openvpn, (
        f"{path}: expected {expect_openvpn} openvpn, got {len(home)}"
    )
    assert len(names) == len(set(names)), f"{path}: duplicate proxy names"
    missing = [p["name"] for p in home if not p.get("dialer-proxy")]
    assert not missing, f"{path}: openvpn nodes without dialer-proxy: {missing}"
    return vless, home


def main():
    now_utc = datetime.now(timezone.utc)
    now_cst = now_utc + timedelta(hours=8)
    stamp = now_cst.strftime("%Y-%m-%d %H:%M CST")

    print(f"[{stamp}] fetching front nodes from Pages ...", flush=True)
    front = fetch_front_nodes()
    print(f"front nodes: {len(front)} TLS-443 VLESS", flush=True)

    print("[...] fetching VPN Gate list ...", flush=True)
    text = None
    last_error = None
    for url in VPNGATE_URLS:
        try:
            text = fetch_text(url, timeout=30)
            if text and ("*vpn_servers" in text or "OpenVPN_ConfigData_Base64" in text):
                print(f"VPN Gate fetched from {url} ({len(text)} bytes)", flush=True)
                break
            last_error = RuntimeError(f"{url}: unexpected content")
            text = None
        except (urllib.error.URLError, RuntimeError, OSError) as exc:
            last_error = exc
            print(f"VPN Gate fetch failed ({url}): {exc}", flush=True)
    if text is None:
        raise SystemExit(f"ERROR: could not fetch VPN Gate list: {last_error}")

    nodes, cert, decode_failures = parse_vpngate(text)
    print(
        f"VPN Gate parsed: {len(nodes)} usable TCP nodes, "
        f"decode failures: {decode_failures}",
        flush=True,
    )
    if cert is None or len(nodes) < MIN_HOME_NODES:
        raise SystemExit(
            f"ERROR: only {len(nodes)} home nodes parsed "
            f"(need >= {MIN_HOME_NODES}) or no shared certificate found"
        )

    # Low-latency first: only low-ping, high-speed nodes. VPN Gate's
    # Ping is the server's own reported value, not the phone's latency,
    # but it is a good quality signal — Japan nodes report ~12 ms
    # median vs ~31 ms for Korea, so JP dominates the top of the list.
    # Sort by ping ascending, then speed descending; JP first.
    fast = [
        n
        for n in nodes
        if n["ping"] <= 30 and n["speed"] >= 50_000_000
    ]
    if len(fast) < MIN_HOME_NODES:
        fast = [
            n
            for n in nodes
            if n["ping"] <= 50 and n["speed"] >= 20_000_000
        ]
    pool = fast if len(fast) >= MIN_HOME_NODES else nodes
    pool = sorted(pool, key=lambda n: (n["ping"], -n["speed"]))
    ordered = [n for n in pool if n["country"] == "JP"]
    ordered += [n for n in pool if n["country"] == "KR"]
    ordered += [n for n in pool if n["country"] not in ("JP", "KR")]
    ordered = ordered[:MAX_HOME_NODES]
    print(
        f"latency filter: {len(fast)} nodes pass ping<=30 & speed>=50M "
        f"(pool {len(pool)}), picking {len(ordered)}",
        flush=True,
    )

    counts = Counter(n["country"] for n in ordered)
    per_country = Counter()
    home_nodes = []
    for node in ordered:
        per_country[node["country"]] += 1
        home_nodes.append(
            {
                **node,
                "name": f"🏠 {node['country']}-家宽-{per_country[node['country']]:02d}",
            }
        )
    print(f"home nodes: {len(home_nodes)}, by country: {dict(counts)}", flush=True)

    doc = build_document(
        front,
        home_nodes,
        cert,
        stamp,
        "本文件由 GitHub Actions 每 2 小时自动生成，未走 Cloudflare Worker/Pages；"
        "家宽节点来自公开 VPN Gate 清单",
    )
    with open("jk_home.yaml", "w", encoding="utf-8") as f:
        f.write(doc)

    cmcc_front = [direct_front_node(name, host) for name, host in DIRECT_FRONT]
    doc_cmcc = build_document(
        cmcc_front,
        home_nodes,
        cert,
        stamp,
        "移动版：前置不走优选域名，直连 cfnew-aeo.pages.dev / cfnew-bq1.pages.dev；"
        "家宽节点来自公开 VPN Gate 清单",
    )
    with open("jk_home_cmcc.yaml", "w", encoding="utf-8") as f:
        f.write(doc_cmcc)

    validate("jk_home.yaml", len(front), len(home_nodes))
    validate("jk_home_cmcc.yaml", len(cmcc_front), len(home_nodes))
    print(
        f"OK: jk_home.yaml ({len(front)} vless + {len(home_nodes)} openvpn), "
        f"jk_home_cmcc.yaml ({len(cmcc_front)} vless + {len(home_nodes)} openvpn)",
        flush=True,
    )
    print(f"home countries: {dict(counts)}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
