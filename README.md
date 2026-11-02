# 听书 · OpenWrt 局域网有声书

把路由器上的有声书目录变成一个网页书架。手机、平板、电脑在局域网里用浏览器打开就能听，不用装 App。

界面是一个纯前端单页应用（无依赖、无构建），打包成 OpenWrt 的 ipk，附带 LuCI 管理页面。

```
浏览器 ──HTTP──> uhttpd(独立实例) ──> /tmp/tingshu/www  静态页面
                                    └─> /cgi-ts/*       书库索引 + 进度同步
                                    └─> media -> /mnt/... 音频目录
```

## 功能

- **书架**：按文件夹自动分书，封面可以是文件夹里的图，也可以自己传、自由缩放裁切或选配色
- **播放**：断点续播、±30 秒、0.5×–3× 倍速、定时关闭（15 分钟到 90 分钟，或「播完本章」）；后台/锁屏时自动预取下一章，播完无缝续下一集
- **多端同步**：进度、合集、书名/简介/配色/封面、暂停记录，都存在路由器上，多台设备自动同步
- **暂停记录**：每次暂停（手动暂停、定时到点、开启定时）都自动记一笔，可在书籍页里查看、跳转续播、删除或清空
- **合集**：像手机桌面文件夹一样，把几本书收进一个盒子，顺序可拖动调整
- **管理**：网页里直接上传书籍（文件夹或 zip / tar.gz）、删除书籍，不用进文件管理；点击首页「听书」图标可查看版本号

## 安卓 App

网页端之外还带一个原生安卓客户端（源码在 `android/`），连的是路由器上的同一个服务。**功能与网页版一致**：书架/合集（九宫格封面）、搜索排序、上传书籍、编辑书名简介封面、删除书籍、进度备份与恢复、暂停记录、倍速/定时/后台播放；封面统一 1:1 方形，传图时可自由缩放/拖动裁切，后台通知和锁屏也显示书籍封面。进度、合集、暂停记录都和网页端双向同步。

![安卓客户端书架](android/docs/screenshots/library.png)

去 [Releases](../../releases) 下载 `tingshu-android-1.2.1.apk` 安装（Android 8.0+）。第一次打开：

1. 手机连上和路由器同一个 Wi-Fi
2. 地址已预填 `192.168.2.1:8686`（换路由器直接改，或点「扫描局域网」自动发现）
3. 连接后进入书架，点书 → 点章节开始听

> 合集、书籍资料、上传/删除都在 App 里能直接操作；合集封面是 3×3 九宫格，「调整顺序」在合集页右上角。

## 安装

去 [Releases](../../releases) 下载最新的 `luci-app-tingshu_x.y.z-r_all.ipk`，传到路由器：

```sh
opkg update
opkg install /tmp/luci-app-tingshu_1.3.0-1_all.ipk
```

装完自动启用并启动服务。打开 **服务 → 听书** 配置。

> 依赖 `uhttpd` 和 `luci-base`，正常装了 LuCI 的固件都有。

## 配置

| 项 | 说明 |
|---|---|
| 网页端口 | 默认 `8686`，浏览器访问 `http://路由器IP:8686/` |
| 书架目录 | 有声书所在路径，默认 `/mnt/audiobooks`。每个子文件夹是一本书 |
| 重新扫描书库 | 手动加了文件后点一下 |

书这样放：

```
/mnt/audiobooks/
├── 三体/
│   ├── 01 地球往事.mp3
│   ├── 02 红岸之谜.mp3
│   └── cover.jpg        ← 可选，会自动当封面
└── 明朝那些事儿/
    └── 01 洪武大帝.mp3
```

支持 mp3 / m4a / m4b / aac / ogg / opus / flac / wav / webm。

## 数据在哪

| 数据 | 位置 |
|---|---|
| 书库索引 | `/tmp/tingshu/www/library.json`（重启自动重建） |
| 听书进度 | `/etc/tingshu/progress/<hex>.json`（重装系统前记得备份） |
| 书名/封面/合集/暂停记录 | `/etc/tingshu/*.json`（同步到服务器，多端共享） |

进度导出入口：网页顶栏的下载图标 → 导出 JSON。

## 自己编译

**方式一：直接打包（不需要 SDK）**

```sh
python3 build_ipk.py        # 产物在 dist/
```

**方式二：用 OpenWrt SDK**

```sh
cp -r openwrt package/luci-app-tingshu
make package/luci-app-tingshu/compile V=s
```

**安卓 App**

需要 JDK 17 + Android SDK 34（用 Android Studio 打开 `android/` 也行）：

```sh
cd android
./gradlew assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # 进度同步协议、地址编码等单元测试
```

Release 用 debug 签名，装到手机上直接能用；要上架应用商店请换成自己的 keystore。

推送到 `main` 或打 `v*` 标签会触发 GitHub Actions 自动打包；打标签时还会自动发 Release。

## 本地预览

网页是纯静态的，直接起个静态服务器就能看（只是没有服务端书库，需要手动选本机文件夹）：

```sh
python -m http.server 8765
# 打开 http://127.0.0.1:8765
```

Windows 用户也可以双击 `启动听书.bat`。

## 目录结构

```
index.html / app.js / styles.css     网页本体
openwrt/
  Makefile                           包 Makefile（SDK 用）
  files/                             装进路由器的文件
    etc/config/tingshu               UCI 配置
    etc/init.d/tingshu               procd 服务
    usr/lib/tingshu/scan.sh          扫目录生成 library.json
    usr/lib/tingshu/api/progress     进度读写 CGI
    usr/lib/tingshu/api/manage       上传/删除/重扫 CGI
    usr/lib/tingshu/api/collections  合集同步 CGI
    usr/lib/tingshu/api/meta         书名/简介/封面同步 CGI
    usr/lib/tingshu/api/pausemarks   暂停记录同步 CGI
  htdocs/.../view/tingshu/main.js    LuCI 页面
build_ipk.py                         不依赖 SDK 的打包脚本
android/                             原生安卓客户端（Kotlin + Jetpack Compose + Media3）
  app/src/main/java/com/tingshu/app/
    data/                            HTTP 客户端、书库、进度、封面缓存、局域网扫描
    playback/                        Media3 后台播放服务
    ui/                              Compose 界面与状态
  app/src/test/                      单元测试（与服务端 CGI 的协议对齐）
```

## 已知限制

- 进度时间戳用的是各设备自己的时钟，多台设备都正常对时就没问题
- zip 是在浏览器里解的（路由器上的 unzip 会把中文名转成 GBK），需要 Chrome 80+ / Edge / Safari 16.4+
- 端口只开在局域网，WAN 侧被防火墙挡住；要外网访问建议走 VPN 或反向代理
- 安卓 APK 用 debug 签名（自用够了）；部分国产 ROM 需要手动允许「后台运行 / 自启动」，否则后台听书可能被杀

## License

MIT
