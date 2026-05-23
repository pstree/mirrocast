# Mirrocast

Android TV 投屏接收端 · AirPlay + Miracast + DLNA · 手机端零安装

为 Sony Bravia (Android TV) 自用而生，参考 AirScreen / LetsView，但**无广告、无遥测、开源**。

## 现状

仍在 **Phase 0**（骨架阶段，v0.0.x）。下表为分阶段交付路线，详见 [设计稿](docs/superpowers/specs/2026-05-23-mirrocast-design.md)。

| 阶段 | 内容 | 版本 |
|---|---|---|
| 0 | 项目骨架 + CI + Hello TV | v0.0.1 |
| 1 | AirPlay MVP（iPhone 镜像） | v0.1.0 |
| 2 | DLNA 多媒体投屏 | v0.2.0 |
| 3 | Web UI + 设置 + DataStore | v0.3.0 |
| 4 | Miracast Sink（安卓/华为） | v0.4.0 |
| 5 | 安全 + 调试系统全套 | v0.5.0 |
| 6 | OTA / HDR / CEC / 多语言 | v1.0.0 |

## 功能（计划）

- **三协议接收端**：AirPlay（iPhone/Mac）+ Miracast（原生 Android / 华为）+ DLNA（多媒体投屏）
- **手机端零安装**：所有镜像协议依赖手机系统能力
- **可配置**：分辨率 / 比例 / 硬解/软解 / 强制 codec
- **调试模式**：实时分辨率/帧率/码率/延迟/网络品质曲线 + 协议层可读日志 + 网络诊断向导
- **手机 Web UI**：扫码进入，与 TV 端设置双向同步
- **安全**：PIN 码 / 一次性确认 / 信任设备白名单 三层独立开关
- **后台驻留**：开机自启，Foreground Service 常驻
- **HDR / Dolby Vision 透传**、屏保、多语言、OTA 自检更新

## 安装

到 [Releases](https://github.com/tffinder/mirrocast/releases) 下载对应 ABI 的 APK：

- `mirrocast-vX.Y.Z-arm64.apk` — 现代 Android TV（Sony Bravia 2018+）
- `mirrocast-vX.Y.Z-armv7.apk` — 老款 32 位 TV
- `mirrocast-vX.Y.Z-universal.apk` — 不确定时用

sideload 到 Android TV：`adb install mirrocast-*.apk` 或用 Send Files to TV 类工具。

## 构建

```bash
git clone --recursive https://github.com/tffinder/mirrocast.git
cd mirrocast
./gradlew assembleDebug          # 产物在 app/build/outputs/apk/debug/
```

需要 JDK 21、Android SDK、NDK r26+。

## 目标平台

- **首要**：Sony Bravia Android TV（Android 7+）
- **ABI**：`arm64-v8a` + `armeabi-v7a`（不出 x86）

## 许可证

GPLv3。本项目集成了 GPLv3 许可的开源接收端（uxplay 等），按传染条款整体以 GPLv3 发布。

## 不做

- 手机端 App（要求零安装）
- 账号体系 / 云同步 / 任何遥测
- 投屏录制 / 截图
- 画中画 / 多源同屏
- 商店分发
