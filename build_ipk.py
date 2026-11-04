#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 openwrt/ 目录下的文件打成一个可以 opkg install 的 .ipk。

ipk = ar/gzip 归档，里面依次是：
    debian-binary     ("2.0\\n")
    control.tar.gz    (./control  ./conffiles  ./postinst  ./prerm)
    data.tar.gz       (实际安装的文件树)

用法：
    python build_ipk.py                # 版本号取自 app.js 的 APP_VERSION
    python build_ipk.py --version 1.5.0
    python build_ipk.py --out-dir dist

产物：dist/luci-app-tingshu_<ver>-<release>_all.ipk
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import re
import sys
import tarfile
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent
PKG_SRC = BASE / "openwrt"
DIST = BASE / "dist"

NAME = "luci-app-tingshu"
RELEASE = "1"
ARCH = "all"

# 仓库根目录的网页文件 -> 包内 usr/share/tingshu/www
WEB_FILES = ("index.html", "styles.css", "app.js", "icon.svg", "manifest.webmanifest")

# 源目录 -> 安装前缀
SOURCES = (
    (PKG_SRC / "files", ""),
    (PKG_SRC / "root", ""),
    (PKG_SRC / "src" / "www", "usr/share/tingshu/www"),
    (PKG_SRC / "htdocs", "www"),
)

CONFFILES = ("/etc/config/tingshu",)

EXEC_PREFIXES = ("etc/init.d/", "etc/uci-defaults/")


def read_app_version(default: str = "0.0.0") -> str:
    """从 app.js 里读出 APP_VERSION，保证网页与 ipk 版本一致。"""
    app_js = BASE / "app.js"
    try:
        text = app_js.read_text(encoding="utf-8")
    except OSError:
        return default
    match = re.search(r"APP_VERSION\s*=\s*'([^']+)'", text)
    return match.group(1) if match else default


def is_executable(dest: str) -> bool:
    """安装后需要 +x 的文件：启动脚本、uci-defaults、书库脚本与 CGI。"""
    if dest.startswith(EXEC_PREFIXES):
        return True
    if dest.startswith("usr/lib/tingshu/"):
        return dest.endswith(".sh") or "/api/" in dest
    return False


def sync_web() -> None:
    """把仓库根目录的网页文件同步进包里，保证 ipk 里永远是最新版本。"""
    dst_dir = PKG_SRC / "src" / "www"
    dst_dir.mkdir(parents=True, exist_ok=True)
    for name in WEB_FILES:
        src = BASE / name
        if src.is_file():
            (dst_dir / name).write_bytes(src.read_bytes())


def collect_files() -> dict[str, Path]:
    """扫描所有源目录，返回 {安装路径: 本地路径}。"""
    files: dict[str, Path] = {}
    for src_dir, prefix in SOURCES:
        if not src_dir.is_dir():
            sys.exit(f"缺少目录: {src_dir}")
        for path in sorted(src_dir.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(src_dir).as_posix()
            dest = f"{prefix}/{rel}".lstrip("/") if prefix else rel
            files[dest] = path
    return files


def _tarinfo(name: str, mode: int, mtime: int, size: int = 0, is_dir: bool = False) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.size = size
    info.mode = mode
    info.mtime = mtime
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    if is_dir:
        info.type = tarfile.DIRTYPE
    return info


def make_tar_gz(build) -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.GNU_FORMAT) as tar:
        build(tar)
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as gz:
        gz.write(buf.getvalue())
    return out.getvalue()


def make_ipk(control_tgz: bytes, data_tgz: bytes) -> bytes:
    """外层：gzip(tar)，成员顺序与官方 24.10 包一致。"""
    buf = io.BytesIO()
    mtime = int(time.time())
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.GNU_FORMAT) as tar:
        for name, blob in (
            ("./debian-binary", b"2.0\n"),
            ("./data.tar.gz", data_tgz),
            ("./control.tar.gz", control_tgz),
        ):
            tar.addfile(_tarinfo(name, 0o644, mtime, len(blob)), io.BytesIO(blob))
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as gz:
        gz.write(buf.getvalue())
    return out.getvalue()


def build_control_tgz(version: str, release: str, installed_kb: int) -> bytes:
    control = (
        f"Package: {NAME}\n"
        f"Version: {version}-{release}\n"
        "Depends: uhttpd, luci-base\n"
        f"Source: package/{NAME}\n"
        f"SourceName: {NAME}\n"
        "License: MIT\n"
        "Section: luci\n"
        "Priority: optional\n"
        f"Architecture: {ARCH}\n"
        f"Installed-Size: {installed_kb}\n"
        "Maintainer: tingshu\n"
        "Description: 听书 - 局域网有声书书架\n"
        " 把路由器上的有声书目录做成网页书架，支持断点记忆、倍速、定时关闭、合集。\n"
        " 手机/电脑在局域网里用浏览器打开即可，包含 LuCI 管理页面。\n"
    ).encode("utf-8")
    conffiles = ("\n".join(CONFFILES) + "\n").encode("utf-8")

    def build(tar: tarfile.TarFile) -> None:
        mtime = int(time.time())
        tar.addfile(_tarinfo(".", 0o755, mtime, is_dir=True))
        tar.addfile(_tarinfo("./control", 0o644, mtime, len(control)), io.BytesIO(control))
        tar.addfile(_tarinfo("./conffiles", 0o644, mtime, len(conffiles)), io.BytesIO(conffiles))
        # 安装/卸载钩子：启用并启动服务，卸载时停掉
        for hook in ("postinst", "prerm"):
            path = PKG_SRC / hook
            if path.is_file():
                blob = path.read_bytes()
                tar.addfile(_tarinfo(f"./{hook}", 0o755, mtime, len(blob)), io.BytesIO(blob))

    return make_tar_gz(build)


def build_data_tgz(files: dict[str, Path]) -> tuple[bytes, int, int]:
    """返回 (data.tar.gz, 未压缩字节数, 需要修 CRLF 的文件数)。"""
    mtime = int(time.time())
    total = 0
    crlf_fixed = 0
    dirs: set[str] = set()
    for dest in files:
        parts = dest.split("/")[:-1]
        for i in range(1, len(parts) + 1):
            dirs.add("/".join(parts[:i]))

    def build(tar: tarfile.TarFile) -> None:
        nonlocal total, crlf_fixed
        for d in sorted(dirs):
            tar.addfile(_tarinfo(f"./{d}", 0o755, mtime, is_dir=True))
        for dest in sorted(files):
            data = files[dest].read_bytes()
            # 从 Windows 复制过来时可能带 CRLF，shebang 会被 "\r" 破坏
            if b"\r\n" in data and b"\x00" not in data:
                print(f"  ! 修正 CRLF 换行: {dest}")
                data = data.replace(b"\r\n", b"\n")
                crlf_fixed += 1
            total += len(data)
            mode = 0o755 if is_executable(dest) else 0o644
            tar.addfile(_tarinfo(f"./{dest}", mode, mtime, len(data)), io.BytesIO(data))

    return make_tar_gz(build), total, crlf_fixed


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="把 openwrt/ 打成 luci-app-tingshu 的 ipk")
    parser.add_argument("--version", default=None, help="包版本号，默认读 app.js 里的 APP_VERSION")
    parser.add_argument("--release", default=RELEASE, help=f"包修订号，默认 {RELEASE}")
    parser.add_argument("--out-dir", default=str(DIST), help="输出目录，默认 dist/")
    parser.add_argument("--quiet", action="store_true", help="只打印结果，不列出每个文件")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    version = args.version or read_app_version()
    out_dir = Path(args.out_dir)

    sync_web()
    files = collect_files()
    if not files:
        sys.exit("没有可打包的文件")

    installed_kb = max(1, round((sum(p.stat().st_size for p in files.values()) + 4096) / 1024))
    control_tgz = build_control_tgz(version, args.release, installed_kb)
    data_tgz, total, crlf_fixed = build_data_tgz(files)
    ipk = make_ipk(control_tgz, data_tgz)

    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{NAME}_{version}-{args.release}_{ARCH}.ipk"
    out.write_bytes(ipk)

    print(f"打包完成: {out}")
    print(f"  版本 {version}-{args.release}，文件数 {len(files)}，"
          f"未压缩 {total / 1024:.1f} KB，ipk {len(ipk) / 1024:.1f} KB")
    if crlf_fixed:
        print(f"  修正 CRLF 文件 {crlf_fixed} 个")
    print(f"  sha256 {hashlib.sha256(ipk).hexdigest()}")

    if not args.quiet:
        for dest in sorted(files):
            mode = "0755" if is_executable(dest) else "0644"
            print(f"   /{dest:<52} {files[dest].stat().st_size:>6} B  {mode}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
