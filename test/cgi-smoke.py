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
        body: bytes = b"", auth: bool = False,
        extra_env: dict[str, str] | None = None) -> tuple[int, bytes, bytes]:
    env = env_for(tmp, auth)
    if extra_env:
        env.update(extra_env)
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


def check_raw(name: str, tmp: Path, script: str, **kwargs) -> tuple[dict[str, str], bytes]:
    """校验二进制（非 JSON）响应：头必须完整，body 原样返回。"""
    code, out, err = run(tmp, script, **kwargs)
    if code != 0:
        failures.append(f"{name}: 退出码 {code}，stderr={err.decode('utf-8', 'replace')[:200]}")
        return {}, b""
    try:
        headers, body = split_response(out)
    except AssertionError as exc:
        failures.append(f"{name}: {exc}")
        return {}, b""
    if headers.get("cache-control") != "no-store":
        failures.append(f"{name}: 缺少 Cache-Control: no-store")
    if not failures or failures[-1].split(":")[0] != name:
        print(f"ok    {name}")
    return headers, body


def test_backup(tmp: Path) -> None:
    """备份接口：5 种导出模式、管理员限制、恢复往返、路径白名单。"""
    import io
    import tarfile

    root = tmp / "backuproot"
    data, www, books, cfg = root / "data", root / "www", root / "audiobooks", root / "config"
    for d in (data / "users" / "alice" / "progress", data / "users" / "bob",
              data / "meta", www, books, cfg):
        d.mkdir(parents=True, exist_ok=True)
    (data / "users" / "alice" / "account").write_text("role=user\n")
    (data / "users" / "bob" / "account").write_text("role=admin\n")
    (data / "users" / "alice" / "progress" / "a.json").write_text('{"i":1}')
    (data / "meta" / "b.json").write_text('{"title":"三体"}')
    (www / "library.json").write_text('{"books":[]}')
    (books / "book1.mp3").write_bytes(b"ID3fake")
    (cfg / "tingshu").write_text("config tingshu 'main'\n")

    # CGI 用 `sh` 跑：Windows 上要把 C:\... 转成 Git Bash 认识的 /c/...，
    # 否则 DATA_REL 会拼出 /C:\...，tar -C / 找不到文件。
    def msys(p: Path) -> str:
        s = str(p).replace("\\", "/")
        if len(s) > 1 and s[1] == ":":
            s = "/" + s[0].lower() + s[2:]
        return s

    data_posix, www_posix = msys(data), msys(www)
    books_posix, cfg_posix = msys(books), msys(cfg / "tingshu")
    extra = {
        "TINGSHU_DATA_DIR": data_posix,
        "TINGSHU_WWW_DIR": www_posix,
        "TINGSHU_BOOKS_DIR": books_posix,
        "TINGSHU_CONFIG_FILE": cfg_posix,
    }
    data_rel, www_rel = data_posix.lstrip("/"), www_posix.lstrip("/")
    books_rel, cfg_rel = books_posix.lstrip("/"), cfg_posix.lstrip("/")

    def members(body: bytes) -> list[str]:
        with tarfile.open(fileobj=io.BytesIO(body), mode="r:gz") as tf:
            return tf.getnames()

    info = check("GET backup info", tmp, "backup", query="action=info", extra_env=extra)
    if info:
        obj = json.loads(info)
        if set(obj.get("users", [])) != {"alice", "bob"}:
            failures.append(f"backup info: 用户列表不对 {obj.get('users')}")
        if obj.get("sizes", {}).get("media", 0) <= 0:
            failures.append("backup info: 媒体库体积没算出来")
        if "freeKb" not in obj:
            failures.append("backup info: 缺少 freeKb")

    cases = [
        ("users", [f"{data_rel}/users/alice/account", f"{data_rel}/users/bob/account"], None),
        ("user", [f"{data_rel}/users/alice/account"], "alice"),
        ("meta", [f"{data_rel}/meta/b.json", f"{www_rel}/library.json"], None),
        ("media", [f"{books_rel}/book1.mp3"], None),
        ("full", [f"{data_rel}/users/alice/account", f"{cfg_rel}", f"{books_rel}/book1.mp3"], None),
    ]
    exported: dict[str, bytes] = {}
    for mode, expected, user in cases:
        q = f"action=export&mode={mode}" + (f"&u={user}" if user else "")
        headers, body = check_raw(f"GET backup export {mode}", tmp, "backup",
                                  query=q, extra_env=extra)
        if not body or not body.startswith(b"\x1f\x8b"):
            failures.append(f"backup export {mode}: 不是 gzip 流（响应 {body[:200]!r}）")
            continue
        if not headers.get("content-type", "").startswith("application/gzip"):
            failures.append(f"backup export {mode}: Content-Type 不对")
        names = members(body)
        for want in expected:
            if want not in names:
                failures.append(f"backup export {mode}: 包里缺少 {want}（{names[:6]}）")
        if mode == "user" and any("users/bob" in n for n in names):
            failures.append("backup export user: 误打包了别的用户")
        exported[mode] = body

    check("GET backup 非法 mode", tmp, "backup",
          query="action=export&mode=../../etc", extra_env=extra, expect_status=400)
    check("GET backup 缺 mode", tmp, "backup",
          query="action=export", extra_env=extra, expect_status=400)
    check("GET backup 非法用户名", tmp, "backup",
          query="action=export&mode=user&u=..%2Fetc", extra_env=extra, expect_status=400)
    check("GET backup 未登录", tmp, "backup",
          query="action=info", extra_env=extra, auth=True, expect_status=401)

    # 恢复往返：先删掉一个文件，再用导出的包写回来
    target = data / "users" / "alice" / "account"
    target.unlink()
    payload = exported.get("users", b"")
    if payload:
        check("POST backup import 往返", tmp, "backup", method="POST",
              query="action=import&mode=users", body=payload, extra_env=extra)
        if not target.exists() or "role=user" not in target.read_text():
            failures.append("backup import: 恢复后文件没回来")

    # 恶意包：包含不在白名单里的绝对路径镜像，必须拒绝
    bad = io.BytesIO()
    with tarfile.open(fileobj=bad, mode="w:gz") as tf:
        blob = b"evil"
        ti = tarfile.TarInfo("etc/passwd")
        ti.size = len(blob)
        tf.addfile(ti, io.BytesIO(blob))
    check("POST backup 拒绝越界路径", tmp, "backup", method="POST",
          query="action=import&mode=users", body=bad.getvalue(),
          extra_env=extra, expect_status=400)
    check("POST backup 拒绝坏包", tmp, "backup", method="POST",
          query="action=import&mode=users", body=b"not a tar.gz",
          extra_env=extra, expect_status=400)


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

        # ---- 备份 / 恢复 ----
        test_backup(tmp)
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
