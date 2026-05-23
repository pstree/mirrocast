# Touping — Android TV 投屏接收端设计稿

| 字段 | 值 |
|---|---|
| 日期 | 2026-05-23 |
| 目标平台 | Android TV（首要：Sony Bravia） |
| 目标 ABI | `arm64-v8a` + `armeabi-v7a`（不打 x86） |
| minSdk / target | 24 / 34 |
| 语言 / 框架 | Kotlin + Compose for TV + NDK (C/C++) |
| 许可证 | GPLv3（来自 uxplay/RPiPlay 传染） |
| 仓库可见性 | public |
| 状态 | 设计阶段 |

## 1. 目的与范围

构建一个安装在 Android TV（特别是 Sony Bravia）上的**投屏接收端 App**，让 iPhone / 原生安卓 / 华为手机在**手机端不安装任何 App** 的前提下，把屏幕镜像或多媒体内容投到电视上。

**核心需求**：
- iPhone / Mac → AirPlay 镜像
- 原生安卓 / 华为 → Miracast 镜像
- 任意手机的"投屏到电视"按钮 → DLNA 多媒体投流
- 配置投屏分辨率、显示比例、硬解/软解
- 调试模式实时显示分辨率、帧率、码率、网络品质、协议日志
- 通过 GitHub Actions 自动构建、签名、发布到 Releases

**非目标**：
- 不做 iOS / 安卓发送端 App（手机端零安装）
- 不做账号体系 / 云同步
- 不做 Google Cast Receiver SDK 接入（需 Google 审核 + 与 DLNA 重叠）
- 不做 USB / 本地录制、截图（已与用户确认排除）
- 不做画中画 / 多源同屏（已确认排除）
- 不上传任何遥测 / 崩溃报告

## 2. 总体架构

```
┌─────────────────────────── TV App (Android TV) ───────────────────────────┐
│                                                                            │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐               │
│  │ AirPlay Server │  │ Miracast Sink  │  │ DLNA Renderer  │               │
│  │  (NDK / JNI)   │  │ (Kotlin + NDK) │  │ (JUPnP, Kotlin)│               │
│  │  uxplay 移植   │  │  WifiP2pMgr +  │  │  ExoPlayer 播放│               │
│  │                │  │  RTSP/RTP/TS   │  │                │               │
│  └────┬───────────┘  └────┬───────────┘  └────┬───────────┘               │
│       │                   │                   │                            │
│       └───────────────────┴───────────────────┘                            │
│                           │                                                │
│                ┌──────────▼─────────┐                                      │
│                │   Stream Router    │  ← 协议适配层，仲裁 active sender   │
│                └──────────┬─────────┘                                      │
│                           │                                                │
│           ┌───────────────┼────────────────┐                              │
│           ▼               ▼                ▼                              │
│   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                     │
│   │ SurfaceView  │ │ AudioTrack   │ │ Debug Overlay│                     │
│   │ MediaCodec硬 │ │              │ │ (FPS/码率/…) │                     │
│   │ FFmpeg 软备  │ │              │ │              │                     │
│   └──────────────┘ └──────────────┘ └──────────────┘                     │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │ TV UI (Compose for TV)        │ Web UI (Ktor server, 8787/TCP)       │ │
│  │  待机页 / 投屏页 / 设置 / 信任│  扫码进入，同步所有设置 + WebSocket  │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

**进程模型**：单进程。`MirrorService`（Foreground Service）托管全部协议接收端，UI Activity 死亡不影响接收。

## 3. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| App 壳 | Kotlin 2 + Jetpack Compose for TV | Leanback 已被官方淘汰 |
| 协议 native | C/C++ via Android NDK r26 | AirPlay + MPEG-TS 解析 |
| Java/Kotlin 协议 | JUPnP（DLNA）、自写 RTSP（Miracast 控制面） | |
| 视频解码 | MediaCodec（硬解）+ FFmpeg（软解后备） | FFmpeg 用 prebuilt `.so` |
| 视频渲染 | SurfaceView + EGL，调试 overlay 叠加层 | |
| HTTP/Web | Ktor server-cio + WebSocket | 比 NanoHTTPD 现代 |
| 持久化 | Jetpack DataStore Preferences | 不引 Room |
| 日志 | Timber + 文件 ring buffer (5MB 滚转) | 协议日志独立 channel |
| 构建 | Gradle 8 + AGP 8 | |
| CI | GitHub Actions on `ubuntu-latest` | |
| 签名 | Keystore via GitHub Secrets | |

## 4. 协议接收端

### 4.1 AirPlay Receiver

- **基线代码**：fork [uxplay](https://github.com/FDH2/UxPlay) 作为 git submodule 进 `third_party/uxplay`
- **裁剪**：移除 gstreamer 重依赖；视频走 native MediaCodec，音频走 AudioTrack
- **构建**：CMake 整合到 `app/src/main/cpp/airplay/`，产出 `libairplay.so`
- **JNI 接口**：
  - `startServer(deviceName, videoSurface, audioSinkCallback)`
  - `stopServer()`
  - `setPassword(pin?)`（PIN 模式开启时，复用 AirPlay 自带的密码字段，iOS 自动弹输入框）
  - 回调：`onClientConnect(fingerprint, name)`、`onClientDisconnect`、`onStreamFormat(w,h,fps,codec)`、`onBytesReceived(bytes)`
- **mDNS**：用 Android 自带 `NsdManager` 注册 `_airplay._tcp` + `_raop._tcp`，避开 native 端的 Bonjour 依赖
- **DRM/FairPlay**：依赖 `playfair`，AirPlay 1 mirror 兼容到 iOS 17（legacy 模式）
- **端到端延迟**：100–200ms 为正常

### 4.2 Miracast Sink

**高风险模块**，必须可独立失败（失败时 UI 隐藏入口 + 提示用户走 Sony 内置）。

- **发现层**：`WifiP2pManager` + `WifiP2pWfdInfo(deviceType=PRIMARY_SINK)`，注册 P2P 服务发现
- **连接层**：P2P group 建立后，sink 在 7236/TCP 监听
- **协商层（M1–M7）**：纯 Kotlin RTSP 服务器：
  - M1 OPTIONS · M2 GET_PARAMETER · M3 SET_PARAMETER (capability) · M4 SET_PARAMETER (set) · M5 SETUP · M6 PLAY · M7 TEARDOWN
- **流接收**：协商端口收 RTP，payload 为 MPEG-TS
- **解封装**：native C 实现的 MPEG-TS demuxer（约 500 行），抽出 H.264 ES + AAC ES
- **渲染**：H.264 → MediaCodec → Surface；AAC → MediaCodec audio → AudioTrack
- **WPS PIN**：开启 PIN 模式时，用 WPS-PIN 阶段做认证

### 4.3 DLNA Renderer

- **库**：[JUPnP](https://github.com/jupnp/jupnp)，实现 `AVTransport` + `RenderingControl` + `ConnectionManager`
- **播放**：拿到流 URL → ExoPlayer 播放
- **HDR 透传**：开启 ExoPlayer 的 HDR flag，元数据保留 HDR10 / Dolby Vision（视源而定）
- **支持容器**：MP4 / H.264 / HEVC / HLS / DASH

### 4.4 Stream Router & 仲裁

- 同一时刻只有一个 active sender（无 PiP，已确认排除）
- 新发送方接入时，TV 弹"已有投屏，是否切换"，遥控器 OK 切换 / Back 拒绝
- 每个协议接收端独立 lifecycle，相互不阻塞、可独立启停

## 5. UI

### 5.1 TV 端（Compose for TV）

四个屏幕，D-pad 焦点导航。

**5.1.1 StandbyScreen（待机页）**

- 中央：大字"设备名"
- 左侧：手机端 Web UI 的二维码（值 = `http://<TV-IP>:8787/`）
- 右侧：三个协议状态行（AirPlay / Miracast / DLNA + 监听端口 + ✓/✗）
- 底部提示：`按 [菜单] 进设置 · 按 [信息] 切换调试 overlay`
- 5 分钟无连接进屏保（参见 §10）

**5.1.2 MirrorScreen（投屏中）**

- 全屏 SurfaceView 显示画面
- 右上角调试 overlay（默认隐藏，[INFO] 键切换）：分辨率 / 帧率 / codec / 码率 / 延迟 / 网络品质迷你折线

**5.1.3 SettingsScreen**

`设备名称 / 启动行为 / 视频 / 音频 / 安全 / 调试 / 关于`

**5.1.4 TrustedDevicesScreen**

列表：设备名 · 协议 · 上次连接时间 · `[移除]` 按钮

### 5.2 手机 Web UI（Ktor，8787）

单页响应式，暗色主题。功能：
- 设备状态 + 当前投屏信息 + `[中断投屏]`
- 设置（与 TV UI 同源 DataStore，双向同步）
- 信任设备列表（增删）
- 实时调试（WebSocket 推送）：网络品质 SVG 折线、近 200 条协议日志
- 系统：版本号 / 检查更新 / 下载日志 / 重启服务

**鉴权**：默认无密码（家庭网络），PIN 模式开启时 Web UI 同样要求 PIN。

## 6. 调试系统

### 6.1 指标矩阵

| 指标 | 来源 | overlay | Web |
|---|---|---|---|
| 分辨率 / 帧率 | MediaCodec output format | ✓ | ✓ |
| 视频码率 | 累积字节 / 时间窗 | ✓ | ✓ |
| 音频码率 / 采样率 | AudioTrack format | – | ✓ |
| 端到端延迟 | RTP timestamp - 渲染时刻 | ✓ | ✓ |
| 丢包率 / 抖动 | RTP 序号 gap + 间隔方差 | – | ✓ |
| 解码器名称 | MediaCodec 实例名 | ✓ | ✓ |

### 6.2 协议日志（人类可读）

```
[AirPlay] 12:43:01.234  ← GET /info  HTTP/1.1
[AirPlay] 12:43:01.245  → 200 OK (deviceID=00:11:22:..., features=0x..)
[AirPlay] 12:43:01.890  FairPlay handshake ok (4 rtt)
[AirPlay] 12:43:02.012  mirror stream start: 1920×1080 @ 60fps H.264 baseline
[Miracast] 12:44:55.300  ← M3 GET_PARAMETER (capabilities)
[Miracast] 12:44:55.302  → M3 200 OK  wfd_video_formats=00 00 03 10 ...
```

### 6.3 网络诊断向导

诊断"手机找不到 TV"的步骤页：
1. TV 自身 WiFi/有线 IP
2. mDNS 端口 5353 可达性
3. AP 隔离检测（同子网下能否访问网关）
4. ARP 活跃客户端数
5. "请在手机 ping `<TV-IP>`"，自动检测来包

### 6.4 强制 codec / 解码器

设置页可输入 MediaCodec 名称（如 `OMX.qcom.video.decoder.avc`）强制指定，排查花屏 / 卡顿。

### 6.5 延迟测量（拍手对齐法）

调试 overlay 中 `[测延迟]` 按钮 → TV 全屏白闪 3 次（500ms 间隔），手机相机拍下后对照源端时戳。

## 7. 安全模型

**威胁**：同 WiFi 邻居/路人误投。

| 层 | 行为 | 默认 |
|---|---|---|
| PIN 码 | 6 位随机码，手机端必须输入 | 关 |
| 一次性确认 | 新设备投上来时 TV 弹确认框 | 开 |
| 信任白名单 | 一次性确认通过 → 自动加入 | 跟随上一项 |

**设备指纹**：
- AirPlay：device-id (MAC) + Client-Name
- Miracast：P2P MAC + friendly_name
- DLNA：UDN (UUID) + Friendly Name

指纹只在本地 DataStore 中存储，不离开 TV。

## 8. 启动与生命周期

- **MirrorService**（Foreground Service，`START_STICKY`）托管所有协议接收端
- **开机自启**：`RECEIVE_BOOT_COMPLETED` 接收器 → 拉起 Service
- **常驻通知**：`"投屏接收中 · AirPlay/Miracast/DLNA 就绪"`，点击拉回 Activity
- **省电**：无连接时只跑 mDNS + P2P 发现（CPU ~0%），有连接时唤起解码 pipeline
- **WakeLock**：仅 active 投屏时持 `PARTIAL_WAKE_LOCK`
- **CEC 唤醒**：投屏开始时尝试 HDMI-CEC One Touch Play 唤醒 TV（若 Sony 允许第三方 App 调 CEC API；否则静默跳过）

## 9. 持久化

**两个 DataStore Preferences 文件**：

```
settings.preferences_pb
  device_name         : String   = "我的客厅电视"
  auto_start_on_boot  : Bool     = true
  resolution_pref     : Enum     = AUTO | 1080P | 720P | 4K
  aspect_ratio_pref   : Enum     = FIT | FILL | STRETCH | ORIGINAL
  decoder_pref        : Enum     = HW_FIRST | HW_ONLY | SW_ONLY
  forced_codec        : String?  = null
  pin_enabled         : Bool     = false
  pin_code_hash       : String?  = null   (PIN 仅存哈希)
  prompt_unknown      : Bool     = true
  airplay_enabled     : Bool     = true
  miracast_enabled    : Bool     = true
  dlna_enabled        : Bool     = true
  web_ui_port         : Int      = 8787
  debug_overlay       : Bool     = false
  log_level           : Enum     = INFO | DEBUG | TRACE
  language            : Enum     = SYSTEM | ZH | EN

trusted_devices.preferences_pb
  trusted = JSON list of { fingerprint, name, protocol, last_seen_iso, added_iso }
```

## 10. 额外功能（已确认纳入）

- **OTA 更新检查**：启动时 + 每周一次访问 `api.github.com/repos/<owner>/touping/releases/latest`，有新版红点提示；用户点击下载 + `PackageInstaller` 安装
- **屏保**：5 分钟无连接，待机页降亮度，设备名 / 二维码缓慢飘动，防 OLED 烧屏
- **多语言**：中文 + 英文，TV UI + Web UI 都做
- **崩溃日志**：本地落地 `crash-<ts>.txt`，Web UI 可下载，不上传任何远端
- **HDR / Dolby Vision 透传**：MediaCodec / ExoPlayer 打开 HDR flag
- **音频路由开关**：TV 扬声器 / ARC / 蓝牙 切换（Web UI 暴露开关）
- **弱网自适应**：丢包率 > 阈值时主动请求源端降码率
- **延迟测量**（§6.5）
- **遥控器快捷键**：
  - `[INFO]` 切换调试 overlay
  - `[菜单]` 进设置
  - `[BACK]` 中断当前投屏
  - 长按 `[OK]` 切换调试日志等级
- **手机旋转同步（横竖屏自适应）**：手机发送端横竖屏切换时，TV 渲染层（`SurfaceView` 容器 + `MatrixTransform`）实时调整画面 transform 与黑边填充，避免画面被压扁/被裁。AirPlay 协议自带 orientation 字段；Miracast 用 SPS/PPS 解析得到的源分辨率推断；DLNA 由 ExoPlayer 自动处理。

## 11. 仓库结构

```
touping/
├── app/
│   ├── src/main/
│   │   ├── kotlin/io/github/tffinder/touping/
│   │   │   ├── service/          # MirrorService, BootReceiver
│   │   │   ├── receiver/         # AirPlayBridge, MiracastSink, DlnaRenderer
│   │   │   ├── ui/               # Compose screens
│   │   │   ├── web/              # Ktor server + WebSocket
│   │   │   ├── settings/         # DataStore
│   │   │   └── debug/            # Metrics, logging, overlay
│   │   ├── cpp/
│   │   │   ├── airplay/          # uxplay 移植 + JNI
│   │   │   ├── miracast/         # MPEG-TS demuxer
│   │   │   └── CMakeLists.txt
│   │   └── res/
│   │       ├── drawable/         # TV launcher banner (320×180)
│   │       └── values{,-en}/     # 多语言
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── third_party/
│   ├── uxplay/                   # git submodule
│   └── ffmpeg-prebuilt/          # prebuilt .so (arm64-v8a, armeabi-v7a)
├── docs/
│   └── superpowers/specs/
│       └── 2026-05-23-touping-design.md   # 本文件
├── .github/workflows/
│   ├── ci.yml                    # PR: lint + ktlint + unit test
│   └── release.yml               # tag: build signed APK + GH Release
├── gradle/wrapper/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── LICENSE                       # GPLv3
├── README.md
└── .gitignore
```

## 12. CI/CD（GitHub Actions）

### 12.1 `release.yml`

触发条件：推送 `v*.*.*` 形式 tag。

关键步骤：
1. `actions/checkout` (`submodules: recursive`)
2. `setup-java` JDK 21
3. `setup-android` + `sdkmanager 'ndk;26.2.11394342'`
4. 解码 keystore from `secrets.KEYSTORE_B64`
5. `./gradlew assembleRelease`（splits ABI: arm64-v8a + armeabi-v7a + universal）
6. 收集产物 → `touping-<tag>-arm64.apk` / `touping-<tag>-armv7.apk` / `touping-<tag>-universal.apk`
7. 生成 SHA256SUMS.txt
8. `gh api ...releases/generate-notes` 生成 changelog
9. `softprops/action-gh-release` 发布

**Secrets**：`KEYSTORE_B64` / `KEYSTORE_PWD` / `KEY_ALIAS` / `KEY_PWD`

### 12.2 `ci.yml`

PR 触发，`./gradlew check ktlintCheck assembleDebug`，不上传产物。

### 12.3 版本号

`build.gradle.kts` 从 git 提取：
- `versionName` = `git describe --tags --always`
- `versionCode` = `git rev-list --count HEAD`

## 13. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| Miracast sink 在 Sony 上注册失败 | 高 | 模块化，失败时隐藏入口 + 提示用户走 Sony 内置屏幕镜像 |
| AirPlay 2 加密对抗 | 中 | 当前 uxplay 走 AirPlay 1 mirror，iOS 17 仍能 legacy 投上。AirPlay 2 完整兼容是后期工作 |
| NDK 编译 uxplay 第三方依赖（gstreamer / openssl） | 中 | 移除 gstreamer，视频走 MediaCodec；openssl 用 NDK 自带或 prebuilt |
| Sony 系统层限制（CEC / 第三方 Foreground Service） | 中 | 各功能独立开关，单点失败不影响整体 |
| GPLv3 法律 | 低 | public repo + 自用，符合协议 |
| FairPlay 解密合规 | 低 | 不分发到 Play Store，不二次贩售 |

## 14. 分阶段交付

| 阶段 | 内容 | 工期估算 | 输出版本 |
|---|---|---|---|
| 0 | 项目骨架、gh 仓库、Gradle/NDK/CI 跑通、Hello TV APK | 1–2 天 | v0.0.1 |
| 1 | AirPlay MVP（uxplay 移植 + iPhone 镜像通） | 1–2 周 | v0.1.0 |
| 2 | DLNA Renderer（JUPnP + ExoPlayer） | 3–5 天 | v0.2.0 |
| 3 | Web UI + 设置 + DataStore + Compose for TV 设置页 | 1 周 | v0.3.0 |
| 4 | Miracast sink 攻坚（高风险，可能降级） | 2–3 周 | v0.4.0 |
| 5 | 安全模型 + 调试系统全套 + 网络诊断 | 1 周 | v0.5.0 |
| 6 | 打磨：OTA、HDR、CEC、屏保、多语言、遥控器快捷键 | 持续 | v1.0.0 |

## 15. 范围明确排除

- 画中画 / 多源同屏
- 投屏录制（MP4 / 音频）/ 一键截图
- Google Cast Receiver SDK 接入（需注册 + 与 DLNA 重叠）
- 手机端 App 安装路径（用户明确要求零安装）
- 账号体系 / 云同步设置
- 任何远端遥测、崩溃上报、用量统计

## 16. 待用户审阅项

- 包名 `io.github.tffinder.touping`（GitHub 账号已确认为 `tffinder`）
- GitHub repo 名建议 `touping`（用户可改）
- 签名 keystore 由用户本地生成后 base64 塞 Secret，本设计稿不处理生成流程，落到实现计划阶段
- Sony Bravia 的具体型号未提供，按 Android 7+ 通用假设；如果是 Android 11+，可启用更多 API（如 `WifiP2pWfdInfo(deviceType=PRIMARY_SINK)` 的新字段）
