# Phase 0 — 项目骨架 + CI/CD + Hello TV (v0.0.1)

| 字段 | 值 |
|---|---|
| 对应 spec | `docs/superpowers/specs/2026-05-23-mirrocast-design.md` §14 阶段 0 |
| 目标产物 | 一个能在 Sony Bravia 上启动并显示 "Hello TV" 待机页的签名 APK，GH Release 已发布 |
| 工期估算 | 1–2 天 |
| 风险 | 低 |

本计划只覆盖 **Phase 0**。Phase 1–6 到时候各自再写细化计划文档。

## 任务清单（按依赖顺序）

每项都设计成可以**独立验证**。完成一项后做一次 git commit。

### T1. 创建远程仓库

- 用 `gh repo create tffinder/mirrocast --public --license gpl-3.0 --description "Android TV screen mirroring receiver — AirPlay / Miracast / DLNA"`
- 加 remote：`git remote add origin https://github.com/tffinder/mirrocast.git`
- 推现有 2 个 commit（设计稿）：`git push -u origin main`

**验证**：浏览器打开 https://github.com/tffinder/mirrocast，能看到 README（auto-generated）+ docs/。
**风险**：低。`gh` 已认证，scope 足够。
**回滚**：`gh repo delete tffinder/mirrocast --confirm`（不会动本地）。

### T2. 写顶层 `.gitignore` + `README.md` + `LICENSE`

- `.gitignore`：覆盖 Android Studio / Gradle / NDK / 本地 keystore / build/ 输出
- `README.md`：项目简介 + 功能列表 + 截图占位 + 构建说明 + 许可证声明
- `LICENSE`：GPLv3 全文（GitHub 自动生成时已带，但 T1 用 `--license gpl-3.0` 会自动放好；本步只是 verify）

**验证**：`git status` 干净。
**Commit**：`chore: add .gitignore and bootstrap README`

### T3. 初始化 Gradle 项目

执行：
- `gradle init --type basic --dsl kotlin --project-name mirrocast`（或手工写 wrapper）
- 写 `settings.gradle.kts`：包含 `:app` 模块
- 写顶层 `build.gradle.kts`：声明 plugin 仓库（google + mavenCentral），AGP 8.5+、Kotlin 2.0+ 版本
- 写 `gradle.properties`：开启 AndroidX、Jetifier 关闭、Kotlin code style official、JVM heap 4GB

**验证**：`./gradlew --version` 输出预期版本。
**Commit**：`build: scaffold Gradle 8 + Kotlin 2 + AGP 8`

### T4. 创建 `app` 模块（最小 Compose for TV）

- `app/build.gradle.kts`：
  - `applicationId = "io.github.tffinder.mirrocast"`
  - `minSdk 24` / `targetSdk 34` / `compileSdk 34`
  - `versionName` from `git describe --tags --always`
  - `versionCode` from `git rev-list --count HEAD`
  - ABI splits：`arm64-v8a` + `armeabi-v7a`，开 `universalApk true`
  - 依赖：`androidx.tv:tv-foundation`、`androidx.tv:tv-material`、`androidx.activity:activity-compose`、`androidx.lifecycle:lifecycle-runtime-ktx`
- `AndroidManifest.xml`：
  - `<uses-feature android:name="android.software.leanback" android:required="false"/>`（Sony 是真 TV，但保持安卓手机兼容用 `false` 仍可装）
  - `<application android:banner="@drawable/tv_banner" .../>`（TV launcher 必须有 banner，否则 launcher 不显示图标）
  - `MainActivity` 带 `<category android:name="android.intent.category.LEANBACK_LAUNCHER"/>` + `MAIN/LAUNCHER`（同时支持手机和 TV launcher）
- `MainActivity.kt`：Compose 显示 `Text("Hello TV — Mirrocast v$versionName")` 居中，深色背景
- `res/drawable/tv_banner.png`：320×180 占位图（一个深色矩形 + 白色文字 "Mirrocast"）

**验证**：`./gradlew :app:assembleDebug` 成功，APK 大小合理（< 10MB）。
**Commit**：`feat(app): minimal Compose-for-TV hello screen with launcher banner`

### T5. 配 NDK 桩

即使 Phase 0 还没接协议，提前把 NDK 编译链路打通，避免 Phase 1 时再加这一坨拖延：

- `app/src/main/cpp/CMakeLists.txt`：空的项目，仅 declare `add_library(mirrocast_native SHARED stub.cpp)`
- `app/src/main/cpp/stub.cpp`：一个 `extern "C" JNICALL Java_io_github_tffinder_mirrocast_NativeBridge_version(...) → returns "0.0.1-stub"`
- `app/src/main/kotlin/.../NativeBridge.kt`：load `mirrocast_native` + `external fun version(): String`
- `app/build.gradle.kts`：开 `externalNativeBuild { cmake { ... } }`，`ndkVersion "26.2.11394342"`

**验证**：`./gradlew :app:assembleDebug` 仍成功；APK 内 `lib/arm64-v8a/libmirrocast_native.so` 存在；MainActivity 显示 `NativeBridge.version()`。
**Commit**：`build(ndk): bootstrap NDK r26 + JNI stub library`

### T6. 生成签名 keystore + 配 GitHub Secrets

- 本地一条命令：`keytool -genkeypair -v -keystore mirrocast-release.keystore -alias mirrocast -keyalg RSA -keysize 4096 -validity 36500 -storepass "<pw>" -keypass "<pw>" -dname "CN=Mirrocast, OU=Self, O=tffinder, L=., S=., C=CN"`
- 不要 commit keystore 文件，本地保存到非 repo 路径（如 `~/.keystores/mirrocast-release.keystore`）
- base64 编码：`base64 -w0 mirrocast-release.keystore > kb.txt`（Windows 用 `certutil -encode`）
- 用 `gh secret set` 四件套：
  - `gh secret set KEYSTORE_B64 < kb.txt`
  - `gh secret set KEYSTORE_PWD --body "<pw>"`
  - `gh secret set KEY_ALIAS --body "mirrocast"`
  - `gh secret set KEY_PWD --body "<pw>"`
- `app/build.gradle.kts` 加 `signingConfigs.release { ... }`，读环境变量 `KEYSTORE_PATH` / `KEYSTORE_PWD` / `KEY_ALIAS` / `KEY_PWD`

**验证**：`gh secret list` 看到 4 个 Secret。本地 `KEYSTORE_PATH=... ./gradlew :app:assembleRelease` 产出能 install 的签名 APK。
**Commit**：`build(signing): wire release signing via env vars`
**安全**：keystore 文件 + 密码不进 git，本地丢失 → 重做。GH Secrets 加密存储。

### T7. CI workflow（PR 触发）

文件：`.github/workflows/ci.yml`

```yaml
name: CI
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { submodules: recursive, fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: android-actions/setup-android@v3
      - run: sdkmanager 'ndk;26.2.11394342'
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: debug-apks
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 7
```

**验证**：在 GitHub Actions 标签下能看到 workflow run 全绿，artifact 可下载并装到模拟器。
**Commit**：`ci: PR debug build with NDK r26 + artifact upload`

### T8. Release workflow（tag 触发）

文件：`.github/workflows/release.yml`

按 spec §12.1 完整版（含 keystore 解码、ABI 分别打包、SHA256、自动 changelog、softprops/action-gh-release）。

**验证**：
1. 本地 `git tag v0.0.1 && git push --tags`
2. 在 Actions 标签下看 release workflow 跑完
3. 浏览器看 `releases/tag/v0.0.1`，三个 APK + SHA256SUMS.txt + CHANGELOG 全部存在
4. 下载 `mirrocast-v0.0.1-arm64.apk` → adb install 到 Sony → 启动看到 "Hello TV — Mirrocast v0.0.1"

**Commit**：`ci: release pipeline — signed multi-ABI APKs to GH Release`
**风险**：中。GitHub Actions YAML 容易写错；secret 名拼错会静默失败。回滚：删 tag + 删 release（保留为草稿）。

### T9. 端到端冒烟（手动）

走完 T1–T8 后做一次"假装从零开始"的验证：

1. **新克隆**：找另一个目录 `git clone https://github.com/tffinder/mirrocast mirrocast-fresh && cd mirrocast-fresh && ./gradlew assembleDebug` 跑通
2. **Sony 实机**：v0.0.1 APK 装上去 → 在 Sony 主屏看到 Mirrocast banner → 点开看到 Hello TV
3. **CI 闭环**：随便改个文案推 PR，CI 跑绿、artifact 能下载

**Commit**：无（仅验证）。如果发现问题 → 开补丁 commit 修。

---

## 退出标准

Phase 0 完成当且仅当：

- [x] 远程仓库 `tffinder/mirrocast` 存在、public、GPLv3
- [ ] 本地 `./gradlew assembleDebug` & `assembleRelease` 都成功
- [ ] Sony Bravia 上 v0.0.1 APK 能装能启动能显示 Hello TV
- [ ] CI workflow PR 全绿
- [ ] Release workflow 在 v0.0.1 tag 上跑通，三个 APK + 校验和 + changelog 齐全
- [ ] `NativeBridge.version()` 返回 stub 字符串（证明 NDK 链路通）

进入 Phase 1（AirPlay MVP）之前，会写一份 `2026-XX-XX-phase-1-airplay-plan.md` 细化任务。

## 关键交互点（需要用户介入的地方）

| 步骤 | 用户必须做什么 |
|---|---|
| T1 | 确认 `tffinder/mirrocast` 这个 repo 名 + public 可见性（spec §16 已确认） |
| T6 | 在 keystore 密码上做决定（建议 ≥16 字符；丢了 = 重做） |
| T9.2 | 用 USB / ADB over WiFi 把 APK 装到自己的 Sony 上做实机测试 |

## 不属于 Phase 0 的事

- 任何协议接收端代码（→ Phase 1+）
- Web UI / 设置页 / 调试 overlay（→ Phase 3 / 5）
- 多语言资源（→ Phase 6）
- OTA 检查代码（→ Phase 6）
- proguard / R8 优化（→ Phase 6 打磨阶段，否则可能折腾很久）
