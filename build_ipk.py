#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 openwrt/ 目录下的文件打成一个可以 opkg install 的 .ipk

ipk = ar 归档，里面依次是：
    debian-binary     ("2.0\n")
    control.tar.gz    (./control  ./conffiles)
    data.tar.gz       (实际安装的文件树)

用法： python build_ipk.py
产物： dist/luci-app-tingshu_<ver>_all.ipk
"""

import io
import os
import sys
import time
import gzip
import shutil
import hashlib
import tarfile

NAME = 'luci-app-tingshu'
VERSION = '1.4.2'
RELEASE = '1'
ARCH = 'all'

BASE = os.path.dirname(os.path.abspath(__file__))
PKG_SRC = os.path.join(BASE, 'openwrt')
DIST = os.path.join(BASE, 'dist')

EXEC_FILES = {
    'etc/init.d/tingshu',
    'etc/uci-defaults/90-luci-app-tingshu',
    'usr/lib/tingshu/scan.sh',
    'usr/lib/tingshu/api/progress',
    'usr/lib/tingshu/api/manage',
    'usr/lib/tingshu/api/collections',
    'usr/lib/tingshu/api/meta',
    'usr/lib/tingshu/api/pausemarks',
    'usr/lib/tingshu/api/auth',
    'usr/lib/tingshu/api/users',
    'usr/lib/tingshu/api/layout',
}

CONFFILES = ['/etc/config/tingshu']

# 源目录 -> 安装前缀
SOURCES = [
    (os.path.join(PKG_SRC, 'files'), ''),
    (os.path.join(PKG_SRC, 'root'), ''),
    (os.path.join(PKG_SRC, 'src', 'www'), 'usr/share/tingshu/www'),
    (os.path.join(PKG_SRC, 'htdocs'), 'www'),
]


WEB_FILES = ('index.html', 'styles.css', 'app.js', 'icon.svg', 'manifest.webmanifest')


def sync_web():
    """把仓库根目录的网页文件同步进包里，保证 ipk 里永远是最新版本"""
    dst_dir = os.path.join(PKG_SRC, 'src', 'www')
    os.makedirs(dst_dir, exist_ok=True)
    for name in WEB_FILES:
        src = os.path.join(BASE, name)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dst_dir, name))
    # 版本号由 init 脚本在启动时动态注入（每次启动 timestamp 变化，强制浏览器刷新）


def collect():
    """返回 {相对安装路径: 本地绝对路径}"""
    files = {}
    for src, prefix in SOURCES:
        if not os.path.isdir(src):
            sys.exit('缺少目录: %s' % src)
        for root, _dirs, names in os.walk(src):
            for n in names:
                full = os.path.join(root, n)
                rel = os.path.relpath(full, src).replace('\\', '/')
                dest = (prefix + '/' + rel).lstrip('/') if prefix else rel
                files[dest] = full
    return files


def add_bytes(tar, name, data, mode, mtime):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = mtime
    info.uid = info.gid = 0
    info.uname = info.gname = 'root'
    tar.addfile(info, io.BytesIO(data))


def add_dir(tar, name, mode, mtime):
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE
    info.mode = mode
    info.mtime = mtime
    info.uid = info.gid = 0
    info.uname = info.gname = 'root'
    tar.addfile(info)


def gz(data):
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode='wb', mtime=0) as f:
        f.write(data)
    return buf.getvalue()


def make_tar_gz(build):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w', format=tarfile.GNU_FORMAT) as tar:
        build(tar)
    return gz(buf.getvalue())


def make_ipk(control_tgz, data_tgz):
    """外层：gzip(tar)，成员顺序与官方 24.10 包一致"""
    buf = io.BytesIO()
    mtime = int(time.time())
    with tarfile.open(fileobj=buf, mode='w', format=tarfile.GNU_FORMAT) as tar:
        add_bytes(tar, './debian-binary', b'2.0\n', 0o644, mtime)
        add_bytes(tar, './data.tar.gz', data_tgz, 0o644, mtime)
        add_bytes(tar, './control.tar.gz', control_tgz, 0o644, mtime)
    return gz(buf.getvalue())


def main():
    sync_web()
    files = collect()

    total = sum(os.path.getsize(p) for p in files.values())
    installed_kb = max(1, round((total + 4096) / 1024))

    control = (
        'Package: %s\n'
        'Version: %s-%s\n'
        'Depends: uhttpd, luci-base\n'
        'Source: package/%s\n'
        'SourceName: %s\n'
        'License: MIT\n'
        'Section: luci\n'
        'Priority: optional\n'
        'Architecture: %s\n'
        'Installed-Size: %d\n'
        'Maintainer: tingshu\n'
        'Description: 听书 - 局域网有声书书架\n'
        ' 把路由器上的有声书目录做成网页书架，支持断点记忆、倍速、定时关闭、合集。\n'
        ' 手机/电脑在局域网里用浏览器打开即可，包含 LuCI 管理页面。\n'
        % (NAME, VERSION, RELEASE, NAME, NAME, ARCH, installed_kb)
    ).encode('utf-8')

    conffiles = ('\n'.join(CONFFILES) + '\n').encode('utf-8')

    def build_control(tar):
        mtime = int(time.time())
        add_dir(tar, '.', 0o755, mtime)
        add_bytes(tar, './control', control, 0o644, mtime)
        add_bytes(tar, './conffiles', conffiles, 0o644, mtime)
        # 安装/卸载钩子：启用并启动服务，卸载时停掉
        for hook in ('postinst', 'prerm'):
            path = os.path.join(PKG_SRC, hook)
            if os.path.exists(path):
                with open(path, 'rb') as fh:
                    add_bytes(tar, './' + hook, fh.read(), 0o755, mtime)

    def build_data(tar):
        mtime = int(time.time())
        dirs = set()
        for dest in sorted(files):
            parts = dest.split('/')[:-1]
            for i in range(1, len(parts) + 1):
                dirs.add('/'.join(parts[:i]))
        for d in sorted(dirs):
            add_dir(tar, './' + d, 0o755, mtime)
        for dest in sorted(files):
            mode = 0o755 if dest in EXEC_FILES else 0o644
            with open(files[dest], 'rb') as fh:
                data = fh.read()
            # 从 Windows 复制过来时可能带 CRLF，shebang 会被 "\r" 破坏
            if b'\r\n' in data and b'\x00' not in data:
                print('  ! 修正 CRLF 换行: %s' % dest)
                data = data.replace(b'\r\n', b'\n')
            add_bytes(tar, './' + dest, data, mode, mtime)

    control_tgz = make_tar_gz(build_control)
    data_tgz = make_tar_gz(build_data)

    ipk = make_ipk(control_tgz, data_tgz)

    os.makedirs(DIST, exist_ok=True)
    out = os.path.join(DIST, '%s_%s-%s_%s.ipk' % (NAME, VERSION, RELEASE, ARCH))
    with open(out, 'wb') as fh:
        fh.write(ipk)

    print('打包完成: %s' % out)
    print('  文件数 %d，未压缩 %.1f KB，ipk %.1f KB' % (
        len(files), total / 1024.0, len(ipk) / 1024.0))
    print('  sha256 %s' % hashlib.sha256(ipk).hexdigest())
    for dest in sorted(files):
        print('   /%-52s %6d B  %s' % (
            dest, os.path.getsize(files[dest]),
            '0755' if dest in EXEC_FILES else '0644'))


if __name__ == '__main__':
    main()
