# Turbo Downloader — Android (Kotlin)

IDM 风格的 Android 下载管理器（原 iOS 版 `iOSDownloadMaster` 的逐功能对等移植，17 项修复 + HLS/DASH 视频流完整下载）。

## 🚀 构建 APK（推荐：GitHub Actions 云端出包）

沙盒环境无法安装 Android SDK，已在 `.github/workflows/build-apk.yml` 配好 CI，**push 即自动编译**，约 10 分钟产出可直接安装的 `app-debug.apk`。

完整图文步骤见 **[BUILD_APK.md](./BUILD_APK.md)**，核心三步：

```bash
git init && git add . && git commit -m "init"
gh repo create TurboDownloader-Android --public --source=. --push
# 推送后到 GitHub → Actions → 下载 Artifacts → app-debug.apk
```

> Debug APK 无需签名即可安装到手机。要上架商店再做正式签名（BUILD_APK.md 有说明）。

---

## 本地构建（可选）

### 1. 准备 Gradle Wrapper（首次仅需一次）

工程内已含 `gradle/wrapper/gradle-wrapper.properties`，但 `gradlew` 脚本与 `gradle-wrapper.jar`
需要在本地生成（沙盒无法在线下载 Gradle 发行版）。任选其一：

```bash
# 方式 A：本机已装 Gradle 8.x
gradle wrapper --gradle-version 8.7

# 方式 B：直接用系统 Gradle 打开
gradle assembleDebug
```

### 2. Android Studio 打开

- 打开本目录 → Sync Project
- minSdk 24 / targetSdk 34 / compileSdk 34
- ⌘B（或 Build → Make）编译

### 3. 首次运行授予权限

- **通知**（Android 13+）
- **存储**（Android 12 及以下会请求 WRITE_EXTERNAL_STORAGE）

## 核心能力

| 模块 | 说明 |
|------|------|
| `core/DownloadEngine` | 多线程 Range 分片下载、断点续传、重试、空间检查 |
| `download/DownloadService` | 前台服务 + 通知（携带 taskId 路由） |
| `data/` (Room) | `Flow` 驱动 UI，重启恢复 |
| `sniffer/Sniffer` | 网页嗅探：video/audio/m3u8/mpd + manifest 内嵌分片解析 |
| **`sniffer/hls/HlsDownloader`** | **m3u8 完整下载：Master→最高码率、SEQUENCE 直播轮询、AES-128 + IV 解密、分片合并** |
| **`sniffer/dash/DashDownloader`** | **MPD 完整下载：SegmentTemplate/StartNumber、视频+音频 ffmpeg 混流** |
| **`sniffer/MediaDownloadCoordinator`** | **嗅探→自动路由 HLS/DASH/直链，产出单个 .mp4** |
| `ui/BrowserActivity` | 内置浏览器 → 嗅探 → 底部弹窗选资源 → 一键下载 |

## HLS 完整下载验证（JVM 可跑）

`app/src/test/.../hls/HlsUnitTest.kt` 覆盖：
- ✅ Master Playlist → 选最高码率 Variant
- ✅ Media Playlist 分片解析 + SEQUENCE 增量
- ✅ `#EXT-X-KEY` AES-128 + IV 解析
- ✅ **AES-128-CBC 解密可逆性**（端序符合 RFC 8216 大端）
- ✅ 分片串行合并顺序

本地跑测试：`./gradlew testDebugUnitTest`

## 静态自检

对齐 iOS 版「静态自检 8 大类」，已扩展至 9 类（含 HLS/DASH + 单元测试覆盖）：

```bash
python3 self_check.py
# 通过: 53/53
```

## iOS → Android 17 项修复映射

详见 `FEATURE_MAP.md`。

## 需你本地调整

1. **包名**：`com.turbo.downloader` → 改为你自己的
2. **签名**：Build → Generate Signed Bundle
3. **HLS/DASH**：部分站点有 token 防盗链，需在 `Sniffer` 补 Referer/Header
4. **ffmpeg 混流**：DASH 音视频分轨需设备装 ffmpeg；若无，产物为双轨 `.m4v`+`.m4a`

## 许可

仅供学习研究，下载内容请遵守版权法规。
