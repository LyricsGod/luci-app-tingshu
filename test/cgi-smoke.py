#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CGI 冒烟测试：直接执行 openwrt/files/usr/lib/tingshu/api/* 并校验响应。

重点防住 1.5.0 的两类事故：
  1. 漏发响应头（`ts_header`）：HTTP 头没有以空行结束，客户端会一直挂到超时。
     1.5.0 的 layout/progress/meta/collections/pausemarks 五个 GET 全中招。
  2. 鉴权分支没生效：未登录必须返回 401 + JSON。

用法： python3 test/cgi-smoke.py
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIB = ROOT / "openwrt" / "files" / "usr" / "lib" / "tingshu"
API = LIB / "api"

failures: list[str] = []


def env_for(tmp: Path, auth: bool) -> dict[str, str]:
    env = dict(os.environ)
    env["TINGSHU_LIB_DIR"] = str(LIB)
    env["TINGSHU_USERS_DIR"] = str(tmp / "users")
    env["TINGSHU_SESSIONS_DIR"] = str(tmp / "sess")
    env["TINGSHU_PROGRESS_DIR"] = str(tmp / "progress")
    env["TINGSHU_META_DIR"] = str(tmp / "meta")
    env["TINGSHU_PAUSEMARKS_FILE"] = str(tmp / "users" / "admin" / "pausemarks.json")
    env["TINGSHU_COLLECTIONS_FILE"] = str(tmp / "users" / "admin" / "collections.json")
    env.pop("CONTENT_LENGTH", None)
    if auth:
        env.pop("TINGSHU_AUTH_DISABLED", None)
    else:
        env["TINGSHU_AUTH_DISABLED"] = "1"
    return env


def run(tmp: Path, script: str, *, method: str = "GET", query: str = "",
        body: bytes = b"", auth: bool = False) -> tuple[int, bytes, bytes]:
    env = env_for(tmp, auth)
    env["REQUEST_METHOD"] = method
    env["QUERY_STRING"] = query
    if body:
        env["CONTENT_LENGTH"] = str(len(body))
    proc = subprocess.run(
        ["sh", str(API / script)],
        input=body, cwd=str(ROOT), env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60,
    )
    return proc.returncode, proc.stdout, proc.stderr


def split_response(raw: bytes) -> tuple[dict[str, str], bytes]:
    """拆出响应头与响应体；返回 (头字段小写 dict, body)"""
    sep = b"\r\n\r\n"
    idx = raw.find(sep)
    if idx < 0:
        raise AssertionError(f"响应里没有找到空行分隔的头部（前 80 字节: {raw[:80]!r}）")
    head_blob = raw[:idx].decode("utf-8", "replace")
    body = raw[idx + len(sep):]
    headers: dict[str, str] = {}
    for line in head_blob.split("\r\n"):
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return headers, body


def check(name: str, tmp: Path, script: str, *, expect_json: bool = True,
          expect_status: int | None = None, **kwargs) -> bytes:
    code, out, err = run(tmp, script, **kwargs)
    if code != 0:
        failures.append(f"{name}: 退出码 {code}，stderr={err.decode('utf-8', 'replace')[:200]}")
        return b""
    try:
        headers, body = split_response(out)
    except AssertionError as exc:
        failures.append(f"{name}: {exc}")
        return b""

    ctype = headers.get("content-type", "")
    if not ctype.startswith("application/json"):
        failures.append(f"{name}: Content-Type 不是 application/json（实际 {ctype!r}）")
    if headers.get("cache-control") != "no-store":
        failures.append(f"{name}: 缺少 Cache-Control: no-store")
    if expect_status is not None:
        status_line = out.split(b"\r\n", 1)[0].decode("utf-8", "replace")
        if f"{expect_status}" not in status_line:
            failures.append(f"{name}: 期望状态 {expect_status}，实际 {status_line!r}")

    if expect_json:
        try:
            json.loads(body.decode("utf-8"))
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{name}: 响应体不是合法 JSON（{exc}）；前 80 字节 {body[:80]!r}")

    if not failures or failures[-1].split(":")[0] != name:
        print(f"ok    {name}")
    return body


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="tingshu-cgi-"))
    try:
        (tmp / "users" / "admin").mkdir(parents=True, exist_ok=True)

        # ---- 免鉴权（TINGSHU_AUTH_DISABLED=1）：五个 GET 必须带头 + JSON ----
        layout = check("GET layout", tmp, "layout")
        progress = check("GET progress", tmp, "progress")
        meta = check("GET meta", tmp, "meta")
        collections = check("GET collections", tmp, "collections")
        pausemarks = check("GET pausemarks", tmp, "pausemarks")

        # ---- 基本内容断言 ----
        if layout and "layout" not in json.loads(layout):
            failures.append("GET layout: 响应里没有 layout 字段")
        if progress and "items" not in json.loads(progress):
            failures.append("GET progress: 响应里没有 items 字段")
        if meta and "items" not in json.loads(meta):
            failures.append("GET meta: 响应里没有 items 字段")
        if collections and "data" not in json.loads(collections):
            failures.append("GET collections: 响应里没有 data 字段")
        if pausemarks and "items" not in json.loads(pausemarks):
            failures.append("GET pausemarks: 响应里没有 items 字段")

        # ---- 写回读 ----
        hex_id = "6e65743ae4b889"
        check("POST progress", tmp, "progress", method="POST",
              body=f"b={hex_id}&i=3&t=12.5&d=0,1&u=1700000000".encode())
        body = check("GET progress?b=", tmp, "progress", query=f"b={hex_id}")
        if body:
            items = json.loads(body).get("items", {})
            if hex_id not in items:
                failures.append(f"GET progress?b=: 没读到刚写入的 {hex_id}")

        check("POST layout", tmp, "layout", method="POST", body=b'{"columns":3}')
        if json.loads(check("GET layout (after)", tmp, "layout") or b"{}").get("layout", {}).get("columns") != 3:
            failures.append("GET layout: 写回读不一致（期望 columns=3）")

        # ---- 鉴权：未登录必须 401 ----
        check("GET progress 未登录", tmp, "progress", auth=True, expect_status=401)
        check("GET meta 未登录", tmp, "meta", auth=True, expect_status=401)

        # ---- 参数校验：非法 id 要 400 且是 JSON ----
        check("GET progress 非法 id", tmp, "progress", query="b=..%2Fetc", expect_status=400)

        # ---- 登录接口本身 ----
        check("GET auth status", tmp, "auth", query="action=status")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print("\n失败项:")
        for f in failures:
            print(" -", f)
        print(f"\n{len(failures)} 项失败")
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
