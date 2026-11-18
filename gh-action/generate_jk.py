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
     "public-vpn", IP starting with "219.100.37."), keeps JP/KR with
     VPN Gate ping <= 50 ms and speed >= 20 Mbps, then verifies each
     of the top 80 candidates in two stages: (a) a real TCP
     connect, and (b) a geolocation check of the server IP
     (ip-api.com) — the published country label is the IP's real
     location, and any node whose real location is not JP/KR (or
     disagrees with the VPN Gate label) is dropped. Publishes only
     the nodes that pass (max 40 home nodes, JP first). Each stage
     falls back to the previous stage's list if too few pass, so
     the subscription never goes stale.
     (An OpenVPN handshake probe was tried 2026-11-18 and removed:
     VPN Gate's SoftEther servers accept the TCP connection but
     never answer a bare P_CONTROL_HARD_RESET_CLIENT_V2 packet —
     two runner runs returned 0 passes and zero response bytes —
     so the probe cannot distinguish live from dead SoftEther
     nodes. TCP + geo is the verification; the phone's url-test
     group remains the final check.)
     Long-term stability: nodes that were already published in
     consecutive runs are preferred (tracked in
     gh-action/node_history.json, committed by the workflow), so
     the subscription keeps the nodes that keep surviving instead
     of churning to brand-new ones every hour.
  3. Writes jk_home.yaml (front = preferred-domain VLESS nodes) and
     jk_home_cmcc.yaml (front = preferred-domain VLESS nodes + 2 direct
     pages.dev nodes, China Mobile version — the url-test front group
     picks the fastest front on the phone's own mobile connection) into the current working directory.

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
import json
import os
import re
import socket
import sys
import time
import urllib.request
import urllib.error
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone, timedelta

import yaml

FRONT_SUB_URL = (
    "https://cfnew-aeo.pages.dev/351c9981-04b6-4103-aa4b-864aa9c91469"
    "/sub?target=clash"
)
# China Mobile optimized Cloudflare edge IPs (public lists, refreshed
# by their maintainers). The uouin API the video's worker code calls
# for these is dead (its old key returns 密钥错误 in all variants
# tried 2026-11-18), so the generator fetches these public lists
# instead. These IPs are the first hop for CM data — a fast front
# here lowers the whole home-broadband chain's latency.
CMCC_IP_URLS = [
    "https://addressesapi.090227.xyz/cmcc",
    "https://090227.pages.dev/bestcf?isp=cmcc&ips=50",
]
MAX_CMCC_IPS = 12
# Hong Kong landing IPs (public BestCF lists). China Mobile routes
# to Hong Kong fastest, so HK front IPs are the best first hop for
# CM data. Verified 2026-11-18: every IP in these lists answers
# the Pages terminal HTML when queried with SNI cfnew-aeo.pages.dev
# (they pass the TLS stream through to the Cloudflare edge), so
# they work as front servers exactly like the CMCC IPs.
HK_IP_URLS = [
    "https://bestcf.pages.dev/tiancheng/hk.txt",
    "https://bestcf.pages.dev/tiancheng2/hk.txt",
]
MAX_HK_IPS = 10
DEFAULT_UUID = "351c9981-04b6-4103-aa4b-864aa9c91469"
VPNGATE_URLS = [
    "https://www.vpngate.net/api/iphone/",
    "http://www.vpngate.net/api/iphone/",
]
MAX_HOME_NODES = 40
MIN_HOME_NODES = 10
# Live verification (the video's detection idea, run from the Actions
# runner instead of a Cloudflare Worker): VPN Gate's ping/speed are
# self-reported and go stale fast, which is why the phone's speed test
# kept hitting dead nodes. So before publishing, open a real TCP
# connection to each candidate home node and keep only the ones that
# answer. A successful TCP handshake proves the node is up and its
# port is listening; it does not prove a full OpenVPN login works,
# so the phone's url-test group is still the last check. Measured
# from the runner, not from the phone, so connect time is only a
# liveness signal, never a phone-latency promise.
VERIFY_TIMEOUT_S = 5.0
VERIFY_WORKERS = 32
VERIFY_CANDIDATES = 80
# Stability tracking (long-term stability for the subscription):
# volunteer home nodes die within hours, so prefer nodes that were
# already published in consecutive runs — a node that survived the
# last few hourly generations is far less likely to die in the next
# hour than a brand-new one. The history lives in node_history.json
# next to this script (committed by the workflow, so it persists
# across runs). A node counts as "returning" if it was published
# within the last 3 hours; entries older than 48 hours are pruned.
HISTORY_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "node_history.json"
)
HISTORY_WINDOW_H = 3
HISTORY_PRUNE_H = 48
# Geolocation check (ip-api.com batch, free tier, 100 IPs/request):
# VPN Gate's country is self-reported and can be stale, which is
# why a "JP" node sometimes exits in Hong Kong. The published
# label is the IP's real location from the geo database.
GEO_API_URL = "http://ip-api.com/batch"
GEO_TIMEOUT_S = 20
FRONT_GROUP = "⚡ CF前置"
LOW_LAT = "🚀 低延迟直连"
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


def tcp_check(node, timeout=VERIFY_TIMEOUT_S):
    """Open a real TCP connection to one home node and measure it.

    Returns (node, connect_ms) when the node answers, else None.
    A connect proves the host:port is listening right now. DNS
    failure, refusal, or timeout all count as dead.
    """
    start = time.monotonic()
    try:
        sock = socket.create_connection(
            (node["server"], node["port"]), timeout=timeout
        )
    except (OSError, ValueError):
        return None
    try:
        sock.close()
    except OSError:
        pass
    return node, int((time.monotonic() - start) * 1000)


def verify_home_nodes(candidates, timeout=VERIFY_TIMEOUT_S, workers=VERIFY_WORKERS):
    """Test every candidate concurrently. Returns
    (verified, checked, passed) where verified is the list of
    (node, connect_ms) for nodes that answered, in the same order
    the candidates were given (so the existing ping/speed order is
    kept and the TCP check is purely a liveness filter)."""
    verified = []
    checked = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(tcp_check, node, timeout): node for node in candidates
        }
        for future in as_completed(futures):
            checked += 1
            try:
                result = future.result()
            except Exception:
                result = None
            if result is not None:
                verified.append(result)
    # Keep the candidates' original order (ping/speed, JP first).
    order = {id(node): i for i, node in enumerate(candidates)}
    verified.sort(key=lambda item: order[id(item[0])])
    return verified, checked, len(verified)



def geo_lookup(ips, timeout=GEO_TIMEOUT_S):
    """Look up the real country of each server IP via ip-api.com
    batch (one request for up to 100 IPs). Returns {ip: countryCode}
    or None if the lookup fails (caller then keeps the VPN Gate
    labels instead of breaking the pipeline)."""
    try:
        body = json.dumps(
            [{"query": ip, "fields": "query,countryCode"} for ip in ips]
        ).encode()
        req = urllib.request.Request(
            GEO_API_URL,
            data=body,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            results = json.loads(resp.read().decode("utf-8"))
        out = {}
        for item in results:
            if item.get("countryCode"):
                out[item["query"]] = item["countryCode"].upper()
        return out
    except (urllib.error.URLError, OSError, ValueError, KeyError) as exc:
        print(f"geo lookup failed: {exc}", flush=True)
        return None


def geo_filter(nodes):
    """Drop nodes whose server IP does not really geolocate to the
    label's country (VPN Gate's country is self-reported and can be
    stale — a "JP" node that really exits in Hong Kong is dropped
    here), and drop anything that is not JP/KR. Returns
    (kept, dropped) where kept is the filtered node list. If the
    geo service is unreachable, returns the input unchanged."""
    # Resolve any hostname servers to IPs for the lookup.
    ip_by_server = {}
    for node in nodes:
        srv = node["server"]
        if re.match(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$", srv):
            ip_by_server[srv] = srv
        else:
            try:
                ip_by_server[srv] = socket.gethostbyname(srv)
            except OSError:
                ip_by_server[srv] = None
    lookup_ips = sorted({ip for ip in ip_by_server.values() if ip})
    geo = geo_lookup(lookup_ips) if lookup_ips else {}
    if geo is None:
        print("geo check skipped (service unreachable)", flush=True)
        return list(nodes), []
    kept, dropped = [], []
    for node in nodes:
        ip = ip_by_server.get(node["server"])
        real = geo.get(ip) if ip else None
        if real is None:
            # No geo data for this IP: keep it (do not punish the
            # node for a lookup gap) but log it.
            print(f"geo: no data for {node['server']}, keeping", flush=True)
            kept.append(node)
        elif real == node["country"] and real in ("JP", "KR"):
            kept.append(node)
        else:
            dropped.append((node, real))
            print(
                f"geo: dropping {node['server']} "
                f"(label {node['country']}, real {real})",
                flush=True,
            )
    return kept, dropped


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


def cmcc_ip_front_node(name, ip, sni_host):
    """A China Mobile optimized Cloudflare edge IP as a front node:
    connect to the IP directly, TLS SNI + WS Host stay the Pages
    domain so the certificate checks out."""
    return {
        "name": name,
        "type": "vless",
        "server": ip,
        "port": 443,
        "uuid": DEFAULT_UUID,
        "udp": True,
        "tls": True,
        "client-fingerprint": "chrome",
        "servername": sni_host,
        "skip-cert-verify": False,
        "network": "ws",
        "ws-opts": {"path": "/?ed=2048", "headers": {"Host": sni_host}},
    }


def fetch_cmcc_ips():
    """Fetch China Mobile optimized Cloudflare IPs from public lists.
    Returns a deduplicated list of IPv4 strings (may be empty)."""
    found = []
    for url in CMCC_IP_URLS:
        try:
            text = fetch_text(url, timeout=20)
        except (urllib.error.URLError, RuntimeError, OSError) as exc:
            print(f"CMCC IP list fetch failed ({url}): {exc}", flush=True)
            continue
        for ip in re.findall(r"(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})", text):
            if ip not in found:
                found.append(ip)
        if len(found) >= MAX_CMCC_IPS:
            break
    return found[:MAX_CMCC_IPS]


def fetch_hk_ips():
    """Fetch Hong Kong landing IPs from public BestCF lists.
    Returns a deduplicated list of IPv4 strings (may be empty)."""
    found = []
    for url in HK_IP_URLS:
        try:
            text = fetch_text(url, timeout=20)
        except (urllib.error.URLError, RuntimeError, OSError) as exc:
            print(f"HK IP list fetch failed ({url}): {exc}", flush=True)
            continue
        for ip in re.findall(r"(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})", text):
            if ip not in found:
                found.append(ip)
        if len(found) >= MAX_HK_IPS:
            break
    return found[:MAX_HK_IPS]


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
    # Direct low-latency group: the same front VLESS nodes, used
    # WITHOUT the home-broadband chain. One hop instead of two, so
    # latency on China Mobile data is far lower — this is the fast
    # path; the home chain stays for when a residential IP is needed.
    body.append(f'  - name: {quote(LOW_LAT)}')
    body.append("    type: url-test")
    body.append("    url: https://www.gstatic.com/generate_204")
    body.append("    interval: 300")
    body.append("    tolerance: 50")
    body.append("    proxies:")
    body.append(group_lines(front_names))
    body.append(f'  - name: {quote(HOME_AUTO)}')
    body.append("    type: url-test")
    body.append("    url: https://www.gstatic.com/generate_204")
    body.append("    interval: 300")
    body.append("    tolerance: 30")
    body.append("    proxies:")
    body.append(group_lines(home_names))
    body.append(f'  - name: {quote(HOME_PICK)}')
    body.append("    type: select")
    body.append("    proxies:")
    body.append(group_lines(home_by_country))
    body.append(f'  - name: {quote(MAIN_PICK)}')
    body.append("    type: select")
    body.append("    proxies:")
    # Home chain FIRST: this subscription is for home broadband, so
    # the default route must be the home chain, not the direct CF
    # front. (Before 2026-11-18 the direct group was first, so a
    # fresh load silently routed everything through Cloudflare's
    # edge — Hong Kong / US datacenter IPs — even though the user
    # had picked a "JP home" node. The direct group stays available
    # as a manual fallback.)
    body.append(f'      - {quote(HOME_AUTO)}')
    body.append(f'      - {quote(HOME_PICK)}')
    body.append(f'      - {quote(LOW_LAT)}')
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


def load_history():
    """Load node stability history: {ip: {"runs": int, "last": iso}}.

    Missing or corrupt file means a fresh start (all nodes new).
    """
    try:
        with open(HISTORY_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except (OSError, ValueError):
        pass
    return {}


def save_history(history):
    """Persist node stability history (best effort; never fail the run)."""
    try:
        with open(HISTORY_PATH, "w", encoding="utf-8") as f:
            json.dump(history, f, ensure_ascii=False, indent=1, sort_keys=True)
    except OSError as exc:
        print(f"WARNING: could not save node history: {exc}", flush=True)


def stable_runs_for(history, ip, now):
    """Consecutive-run count for an IP, or 0 if it was not published
    recently (within HISTORY_WINDOW_H hours)."""
    prev = history.get(ip)
    if not isinstance(prev, dict):
        return 0
    try:
        last = datetime.fromisoformat(str(prev.get("last", "")))
    except ValueError:
        return 0
    if last.tzinfo is None:
        last = last.replace(tzinfo=timezone.utc)
    if now - last <= timedelta(hours=HISTORY_WINDOW_H):
        try:
            return int(prev.get("runs", 0))
        except (TypeError, ValueError):
            return 0
    return 0


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

    # Stability first: keep up to 40 nodes. Volunteer home nodes die
    # within hours, so a bigger pool means more live backups when
    # nodes drop. Every node must still pass a quality bar
    # (JP/KR, VPN Gate ping <= 50 ms, speed >= 20 Mbps), and the
    # 🏠 家宽自动 url-test group measures all of them on the phone
    # and only uses the fastest alive one — extra nodes are spares,
    # they never slow anything down. Sort by ping ascending, then
    # speed descending; JP first.
    fast = [
        n
        for n in nodes
        if n["country"] in ("JP", "KR")
        and n["ping"] <= 50
        and n["speed"] >= 20_000_000
    ]
    if len(fast) < MIN_HOME_NODES:
        fast = [
            n
            for n in nodes
            if n["country"] in ("JP", "KR")
            and n["ping"] <= 80
            and n["speed"] >= 10_000_000
        ]
    pool = fast if len(fast) >= MIN_HOME_NODES else nodes
    # Prefer nodes that were already published in consecutive runs
    # (stable), then lowest ping, then highest speed. Every pool node
    # already passes the ping/speed bar, so stability is the
    # tiebreaker that makes the subscription hold up over hours
    # instead of churning to brand-new nodes each run.
    history = load_history()
    for n in pool:
        n["_stable_runs"] = stable_runs_for(history, n["server"], now_utc)
    pool = sorted(
        pool, key=lambda n: (-n["_stable_runs"], n["ping"], -n["speed"])
    )
    ordered = [n for n in pool if n["country"] == "JP"]
    ordered += [n for n in pool if n["country"] == "KR"]
    ordered += [n for n in pool if n["country"] not in ("JP", "KR")]
    print(
        f"stability filter: {len(fast)} nodes pass ping<=50 & speed>=20M "
        f"(pool {len(pool)})",
        flush=True,
    )

    # Live verification, two stages — VPN Gate's ping/speed are
    # self-reported and stale, so test the top candidates for real:
    # (a) a real TCP connect, and (b) a geolocation check of
    # the server IP (the published label is the IP's real location).
    # Each stage falls back to the previous stage's list if too few
    # pass, so the subscription never goes stale.
    candidates = ordered[:VERIFY_CANDIDATES]
    verified, checked, passed = verify_home_nodes(candidates)
    if verified:
        latencies = sorted(ms for _, ms in verified)
        print(
            f"live check: {passed}/{checked} nodes answered TCP "
            f"(connect {latencies[0]}-{latencies[-1]} ms, "
            f"median {latencies[len(latencies) // 2]} ms)",
            flush=True,
        )
    else:
        print("live check: 0 nodes answered, using unverified list", flush=True)
    if passed >= MIN_HOME_NODES:
        stage_nodes = [node for node, _ in verified]
        verify_note = f"实测连通 {passed}/{checked} 个节点（TCP 握手，{stamp}）"
    else:
        stage_nodes = ordered
        verify_note = (
            f"实测仅连通 {passed}/{checked} 个节点，已回退到未验证清单（{stamp}）"
        )
        print(
            "WARNING: too few verified nodes, "
            f"falling back to unverified top {MAX_HOME_NODES}",
            flush=True,
        )

    # Stage (b): geolocation check — drop nodes whose server IP
    # does not really sit in the labelled country.
    geo_kept, geo_dropped = geo_filter(stage_nodes)
    if len(geo_kept) >= MIN_HOME_NODES:
        if geo_dropped:
            print(
                f"geo check: dropped {len(geo_dropped)} mislabelled "
                f"nodes, {len(geo_kept)} remain",
                flush=True,
            )
            verify_note += f"，已剔除 {len(geo_dropped)} 个国家不符的节点"
        ordered = geo_kept[:MAX_HOME_NODES]
    else:
        print(
            "WARNING: geo check left too few nodes, "
            "keeping the pre-geo list",
            flush=True,
        )
        ordered = stage_nodes[:MAX_HOME_NODES]
    print(f"picking {len(ordered)} home nodes", flush=True)

    # Record which nodes were published, so the next run can prefer
    # the ones that keep surviving. A node counts as continuing only
    # if it was published within the last HISTORY_WINDOW_H hours.
    # Prune entries not seen in HISTORY_PRUNE_H hours.
    now_iso = now_utc.isoformat()
    returning = 0
    for n in ordered:
        prev_runs = n.get("_stable_runs", 0)
        if prev_runs:
            returning += 1
        history[n["server"]] = {"runs": prev_runs + 1, "last": now_iso}
    cutoff = now_utc - timedelta(hours=HISTORY_PRUNE_H)
    for ip in list(history):
        try:
            last = datetime.fromisoformat(str(history[ip].get("last", "")))
            if last.tzinfo is None:
                last = last.replace(tzinfo=timezone.utc)
        except ValueError:
            last = None
        if last is None or last < cutoff:
            del history[ip]
    save_history(history)
    max_runs = max((n.get("_stable_runs", 0) for n in ordered), default=0)
    print(
        f"stability: {returning}/{len(ordered)} home nodes were published "
        f"in a recent run (max consecutive runs: {max_runs})",
        flush=True,
    )
    # Strip the internal stability key before building the document.
    for n in ordered:
        n.pop("_stable_runs", None)

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
        "本文件由 GitHub Actions 自动生成，未走 Cloudflare Worker/Pages；"
        "家宽节点来自公开 VPN Gate 清单；"
        + verify_note,
    )
    with open("jk_home.yaml", "w", encoding="utf-8") as f:
        f.write(doc)

    # China Mobile version: the front matters most on CM data. The
    # phone -> Cloudflare hop is the first hop of the whole chain, and
    # CM's routing to Cloudflare's anycast edge is often poor, while
    # some preferred-domain IPs route CM -> HK/JP/SG much faster.
    # So the CM front is, in order: CM-optimized edge IPs (fastest
    # first hop for CM), the 21 preferred-domain nodes, and the 2
    # direct pages.dev nodes. The ⚡ CF前置 url-test group measures
    # them all on the phone's own CM connection and always uses the
    # fastest one.
    cmcc_ips = fetch_cmcc_ips()
    print(f"CMCC optimized front IPs: {len(cmcc_ips)}", flush=True)
    hk_ips = fetch_hk_ips()
    print(f"HK front IPs: {len(hk_ips)}", flush=True)
    sni_host = "cfnew-aeo.pages.dev"
    cmcc_front = [
        cmcc_ip_front_node(f"移动优选-{i + 1:02d}", ip, sni_host)
        for i, ip in enumerate(cmcc_ips)
    ]
    cmcc_front += [
        cmcc_ip_front_node(f"香港优选-{i + 1:02d}", ip, sni_host)
        for i, ip in enumerate(hk_ips)
    ]
    cmcc_front += list(front)
    cmcc_front += [direct_front_node(name, host) for name, host in DIRECT_FRONT]
    doc_cmcc = build_document(
        cmcc_front,
        home_nodes,
        cert,
        stamp,
        "移动版：前置为移动优选 IP + 香港优选 IP + 21 个优选域名 + 2 个直连 pages.dev，测速组在移动流量下自动选最快的前置；"
        "家宽节点来自公开 VPN Gate 清单；"
        + verify_note,
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
