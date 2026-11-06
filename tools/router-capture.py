#!/usr/bin/env python3
"""Interactive, host-side CDP capture for router web UIs.

The APK never captures traffic. Run this script on a computer that can reach
the routers, attach it to a visible Chromium/Chrome started with
`--remote-debugging-port=9222`, and log in by hand in the browser. The script
records every request and response (including response bodies), shows a banner
in the page, detects the login and the connected-devices page, then writes one
JSON file per router for adapter work and unit-test fixtures.

Start the browser first, for example:

    chromium --remote-debugging-port=9222 \
        --user-data-dir=/tmp/netdis-cdp http://192.168.1.1/

Then run:

    python3 tools/router-capture.py \
        --router zte=http://192.168.2.1/ \
        --router huawei=http://192.168.1.1/

No third-party Python packages are needed.
"""
import argparse
import base64
import json
import os
import re
import socket
import struct
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime

CDP = "http://127.0.0.1:9222"
OUT_DIR = "router-capture"
MAX_BODY = 2 * 1024 * 1024
ROUTERS = [
    ("zte", "http://192.168.2.1/", "中兴 ZXHN E1600"),
    ("huawei", "http://192.168.1.1/", "华为 HS8145C（中国电信网关）"),
]
CAPTURE_TIMEOUT = 420

BANNER = r"""
(function () {
  var old = document.getElementById('netdis-capture-banner');
  if (old) { old.textContent = window.__netdisMsg || old.textContent; return; }
  var d = document.createElement('div');
  d.id = 'netdis-capture-banner';
  d.textContent = window.__netdisMsg || 'NetDis 抓包中：请登录，然后打开「终端 / 客户端 / 设备列表」页面。';
  d.style.cssText = 'position:fixed;z-index:2147483647;top:0;left:0;right:0;' +
    'background:#0a7d55;color:#fff;padding:10px 16px;font:15px/1.4 sans-serif;' +
    'text-align:center;box-shadow:0 2px 10px rgba(0,0,0,.35)';
  (document.body || document.documentElement).appendChild(d);
})();
"""

STATE_JS = r"""
(function () {
  var text = (document.body && document.body.innerText) || '';
  var macs = text.match(/(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}/g) || [];
  var ips = text.match(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g) || [];
  return JSON.stringify({
    url: location.href,
    title: document.title,
    hasPassword: !!document.querySelector('input[type=password]'),
    logout: /注销|退出登录|退出|Logout|Sign out/i.test(text),
    clientWords: /客户端|终端|设备列表|在线设备|连接设备|DHCP|client|device list|station/i.test(text),
    macs: macs.slice(0, 8),
    ipCount: ips.length,
    text: text.slice(0, 3000)
  });
})()
"""


class WS:
    """Tiny RFC6455 client, enough for CDP."""

    def __init__(self, url):
        _, rest = url.split("://", 1)
        hostport, path = rest.split("/", 1)
        path = "/" + path
        host, port = hostport.split(":")
        self.s = socket.create_connection((host, int(port)), timeout=30)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\nHost: {hostport}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.s.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.s.recv(4096)
        self.buf = buf.split(b"\r\n\r\n", 1)[1]
        self.id = 0
        self.events = []

    def _recv(self, n):
        while len(self.buf) < n:
            chunk = self.s.recv(65536)
            if not chunk:
                raise EOFError
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send_frame(self, opcode, payload):
        hdr = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            hdr.append(0x80 | n)
        elif n < 65536:
            hdr.append(0x80 | 126)
            hdr += struct.pack(">H", n)
        else:
            hdr.append(0x80 | 127)
            hdr += struct.pack(">Q", n)
        mask = os.urandom(4)
        hdr += mask
        self.s.sendall(bytes(hdr) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send(self, obj):
        self._send_frame(0x1, json.dumps(obj).encode())
        self.id += 1
        return self.id

    def frame(self):
        b0, b1 = self._recv(2)
        opcode = b0 & 0x0F
        ln = b1 & 0x7F
        if ln == 126:
            ln = struct.unpack(">H", self._recv(2))[0]
        elif ln == 127:
            ln = struct.unpack(">Q", self._recv(8))[0]
        mask = b1 & 0x80
        maskkey = self._recv(4) if mask else None
        data = self._recv(ln) if ln else b""
        if maskkey:
            data = bytes(b ^ maskkey[i % 4] for i, b in enumerate(data))
        if opcode == 0x9:  # ping
            self._send_frame(0xA, data)
            return None
        if opcode == 0x8:  # close
            raise EOFError
        if opcode == 0x1:
            return json.loads(data)
        return None

    def call(self, method, params=None, timeout=30):
        mid = self.id + 1
        self.send({"id": mid, "method": method, "params": params or {}})
        end = time.time() + timeout
        while time.time() < end:
            m = self.frame()
            if m and m.get("id") == mid:
                if "error" in m:
                    raise RuntimeError(f"{method}: {m['error']}")
                return m.get("result", {})
            if m:
                self.events.append(m)
        raise TimeoutError(method)


def http_json(path, method="GET"):
    req = urllib.request.Request(CDP + path, method=method)
    return json.load(urllib.request.urlopen(req, timeout=10))


def new_tab(url):
    return http_json("/json/new?" + urllib.parse.quote(url, safe=":/?&=%"), method="PUT")


def close_tab(target_id):
    try:
        http_json("/json/close/" + target_id)
    except Exception:
        pass


def js(ws, expression, timeout=20):
    result = ws.call(
        "Runtime.evaluate",
        {"expression": expression, "returnByValue": True, "awaitPromise": False},
        timeout=timeout,
    )
    if "exceptionDetails" in result:
        return None
    return result.get("result", {}).get("value")


def set_banner(ws, message):
    js(ws, "window.__netdisMsg = " + json.dumps(message) + ";")
    js(ws, BANNER)


def read_state(ws):
    raw = js(ws, STATE_JS)
    try:
        return json.loads(raw)
    except Exception:
        return {}


class Capture:
    def __init__(self, ws):
        self.ws = ws
        self.cursor = 0
        self.requests = {}
        self.bodies = {}
        ws.call("Network.enable", {
            "maxTotalBufferSize": 64 * 1024 * 1024,
            "maxResourceBufferSize": 8 * 1024 * 1024,
        })

    def drain(self):
        new = self.ws.events[self.cursor:]
        self.cursor = len(self.ws.events)
        for event in new:
            method = event.get("method")
            params = event.get("params", {})
            if method == "Network.requestWillBeSent":
                rid = params.get("requestId")
                req = params.get("request", {})
                self.requests[rid] = {
                    "id": rid,
                    "url": req.get("url"),
                    "method": req.get("method"),
                    "requestHeaders": req.get("headers"),
                    "postData": req.get("postData"),
                    "resourceType": params.get("type"),
                    "startedAt": params.get("timestamp"),
                }
            elif method == "Network.responseReceived":
                rid = params.get("requestId")
                resp = params.get("response", {})
                entry = self.requests.setdefault(rid, {"id": rid})
                entry.update({
                    "url": resp.get("url"),
                    "status": resp.get("status"),
                    "mimeType": resp.get("mimeType"),
                    "responseHeaders": resp.get("headers"),
                    "resourceType": params.get("type"),
                })
            elif method == "Network.loadingFinished":
                rid = params.get("requestId")
                entry = self.requests.get(rid)
                if entry is None or rid in self.bodies:
                    continue
                if entry.get("mimeType", "").startswith(("image/", "font/", "audio/", "video/", "text/event-stream")):
                    self.bodies[rid] = None
                    continue
                try:
                    body = self.ws.call(
                        "Network.getResponseBody", {"requestId": rid}, timeout=15
                    )
                    data = body.get("body", "")
                    if body.get("base64Encoded"):
                        try:
                            data = base64.b64decode(data).decode("utf-8", "replace")
                        except Exception:
                            data = "<binary>"
                    if len(data) > MAX_BODY:
                        data = data[:MAX_BODY] + "\n...<truncated>"
                    self.bodies[rid] = data
                except Exception as exc:
                    self.bodies[rid] = None
                    entry["bodyError"] = str(exc)

    def finish(self):
        self.drain()
        # LoadingFinished may have arrived before a response event; try once more
        # for anything that still has no body and is not a static asset.
        for rid, entry in list(self.requests.items()):
            if rid in self.bodies:
                continue
            mime = entry.get("mimeType", "")
            if mime.startswith(("image/", "font/", "audio/", "video/", "text/event-stream")):
                continue
            try:
                body = self.ws.call("Network.getResponseBody", {"requestId": rid}, timeout=10)
                data = body.get("body", "")
                if body.get("base64Encoded"):
                    data = base64.b64decode(data).decode("utf-8", "replace")
                self.bodies[rid] = data[:MAX_BODY]
            except Exception:
                self.bodies[rid] = None
        out = []
        for rid, entry in self.requests.items():
            item = dict(entry)
            item["responseBody"] = self.bodies.get(rid)
            out.append(item)
        out.sort(key=lambda e: e.get("startedAt") or 0)
        return out


def dump_page(ws, cap, directory, meta):
    os.makedirs(directory, exist_ok=True)
    html = js(ws, "document.documentElement.outerHTML", timeout=30) or ""
    text = js(ws, "(document.body && document.body.innerText) || ''", timeout=20) or ""
    links_raw = js(
        ws,
        "JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(function(a){"
        "return {text:(a.innerText||a.textContent||'').trim().slice(0,120), href:a.href};}))",
    ) or "[]"
    storage = js(
        ws,
        "JSON.stringify({local:Object.assign({},localStorage),session:Object.assign({},sessionStorage)})",
    ) or "{}"
    performance = js(
        ws,
        "JSON.stringify(performance.getEntriesByType('resource').map(function(e){"
        "return {name:e.name, type:e.initiatorType, size:e.transferSize};}))",
    ) or "[]"
    try:
        cookies = ws.call("Network.getAllCookies").get("cookies", [])
    except Exception:
        cookies = []
    try:
        shot = ws.call("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": True})
        open(os.path.join(directory, "page.png"), "wb").write(base64.b64decode(shot["data"]))
    except Exception as exc:
        meta["screenshotError"] = str(exc)

    data = dict(meta)
    data.update({
        "url": js(ws, "location.href"),
        "title": js(ws, "document.title"),
        "cookies": cookies,
        "storage": json.loads(storage),
        "links": json.loads(links_raw),
        "performance": json.loads(performance),
        "html": html,
        "innerText": text,
        "exchanges": cap.finish(),
        "capturedAt": datetime.now().isoformat(timespec="seconds"),
    })
    path = os.path.join(directory, "capture.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(directory, "page.html"), "w", encoding="utf-8") as fh:
        fh.write(html)
    with open(os.path.join(directory, "summary.txt"), "w", encoding="utf-8") as fh:
        fh.write("URL: %s\nTITLE: %s\n\n" % (data.get("url"), data.get("title")))
        for entry in data["exchanges"]:
            fh.write("%-6s %-4s %-9s %s\n" % (
                entry.get("method", ""),
                entry.get("status", ""),
                entry.get("resourceType", ""),
                entry.get("url", ""),
            ))
    return path


def looks_logged_in(state):
    if not state:
        return False
    url = (state.get("url") or "").lower()
    if ";stok=" in url or "/admin/" in url:
        return True
    return not state.get("hasPassword") and bool(state.get("logout"))


def looks_like_client_page(state):
    if not state:
        return False
    text = (state.get("text") or "")
    macs = state.get("macs") or []
    ips = state.get("ipCount") or 0
    return (
        len(macs) >= 2
        or (ips >= 4 and bool(re.search(r"客户端|终端|设备列表|在线设备|连接设备|DHCP|client|device|station", text, re.I)))
    )


def capture_one(name, url, label):
    print("\n" + "=" * 70, flush=True)
    print(f"[{name}] {label}", flush=True)
    print(f"[{name}] 打开 {url}", flush=True)
    target = new_tab("about:blank")
    target_id = target["id"]
    ws = WS(target["webSocketDebuggerUrl"])
    try:
        ws.call("Page.enable")
        ws.call("Runtime.enable")
        ws.call("Log.enable")
        cap = Capture(ws)
        ws.call("Page.navigate", {"url": url})

        started = time.time()
        login_at = None
        client_at = None
        last_report = 0
        logged_in = False
        client_ready = False

        while time.time() - started < CAPTURE_TIMEOUT:
            cap.drain()
            state = read_state(ws)
            if not state:
                time.sleep(1.5)
                continue

            if not logged_in and looks_logged_in(state):
                logged_in = True
                login_at = time.time()
                print(f"[{name}] ✅ 已检测到登录成功：{state.get('url')}", flush=True)
                print(f"[{name}] 请在浏览器里打开「终端 / 客户端 / 设备列表」页面。", flush=True)
                set_banner(ws, "登录成功。请打开「终端 / 客户端 / 设备列表」页面，脚本会自动保存。")
            elif not logged_in:
                msg = f"NetDis 抓包中（{label}）：请登录。登录后请打开「终端 / 客户端 / 设备列表」页面。"
                if time.time() - last_report > 8:
                    set_banner(ws, msg)
                    last_report = time.time()
                    print(f"[{name}] 等待登录… 当前 {state.get('url')}", flush=True)

            if logged_in and not client_ready and looks_like_client_page(state):
                client_ready = True
                client_at = time.time()
                print(f"[{name}] ✅ 已检测到设备/客户端页面，8 秒后保存。", flush=True)
                set_banner(ws, "已检测到设备列表，正在保存抓包…")
                time.sleep(8)
                cap.drain()
                state = read_state(ws)
                break

            if logged_in and time.time() - login_at > 90 and not client_ready:
                print(f"[{name}] 登录后 90 秒仍未识别设备页，保存当前页面。", flush=True)
                break

            time.sleep(1.5)

        meta = {
            "router": name,
            "label": label,
            "startUrl": url,
            "loggedIn": logged_in,
            "clientPageDetected": client_ready,
            "loginDetectedAt": datetime.fromtimestamp(login_at).isoformat(timespec="seconds") if login_at else None,
            "clientPageDetectedAt": datetime.fromtimestamp(client_at).isoformat(timespec="seconds") if client_at else None,
        }
        directory = os.path.join(OUT_DIR, name)
        path = dump_page(ws, cap, directory, meta)
        print(f"[{name}] 已保存 {path}", flush=True)
        print(f"[{name}] 页面 HTML: {directory}/page.html，截图: {directory}/page.png", flush=True)
        return path
    finally:
        close_tab(target_id)


def parse_router(value):
    """`name=url` or just `url`, with an optional `name=label@url` label."""
    name, sep, rest = value.partition("=")
    if not sep:
        name, rest = "router", value
    if name == "" or rest == "":
        raise argparse.ArgumentTypeError("--router expects name=url")
    label, sep, url = rest.partition("@")
    if not sep:
        label, url = name, rest
    if not url.startswith(("http://", "https://")):
        raise argparse.ArgumentTypeError("router URL must start with http:// or https://")
    return name, url, label


def main():
    global CDP, OUT_DIR, ROUTERS, CAPTURE_TIMEOUT
    parser = argparse.ArgumentParser(
        description="Capture router web UI traffic through an existing Chromium CDP session.",
    )
    parser.add_argument(
        "--cdp",
        default=CDP,
        help="Chrome DevTools Protocol endpoint (default: %(default)s)",
    )
    parser.add_argument(
        "--out",
        default=OUT_DIR,
        help="output directory (default: %(default)s)",
    )
    parser.add_argument(
        "--router",
        action="append",
        type=parse_router,
        metavar="NAME=LABEL@URL",
        help="router to capture; repeat for multi-level setups",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=CAPTURE_TIMEOUT,
        help="seconds per router before saving whatever is open (default: %(default)s)",
    )
    args = parser.parse_args()

    CDP = args.cdp.rstrip("/")
    OUT_DIR = args.out
    CAPTURE_TIMEOUT = max(30, args.timeout)
    if args.router:
        ROUTERS = args.router

    os.makedirs(OUT_DIR, exist_ok=True)
    print("CDP 抓包开始。Chromium 窗口已经打开。", flush=True)
    print("请按顺序在每个路由器页面里手动登录；每次登录后打开设备/客户端列表。", flush=True)
    for name, url, label in ROUTERS:
        try:
            capture_one(name, url, label)
        except Exception as exc:
            print(f"[{name}] ❌ 抓取失败：{exc}", flush=True)
            import traceback
            traceback.print_exc()
    print("\n全部抓包结束。文件在", OUT_DIR, flush=True)


if __name__ == "__main__":
    main()
