# 一键构建 APK（GitHub Actions）

沙盒环境无法安装 Android SDK，改用 **GitHub Actions 云端编译**，约 10 分钟即可产出可直接安装的 `app-debug.apk`。

## 步骤

### 1. 创建 GitHub 仓库

```bash
cd TurboDownloader-Android
git init
git add .
git commit -m "init: Turbo Downloader Android"
gh repo create TurboDownloader-Android --public --source=. --push
# 若未装 gh CLI，去 https://github.com/new 手动创建后 push
```

### 2. 推送即自动构建

```bash
git push origin main   # 推送后 Actions 自动触发
```

或去 GitHub 网页 → **Actions** → 选 `Build APK` → **Run workflow** 手动触发。

### 3. 下载产物

- Actions 页面 → 最新 run → **Artifacts** 区 → 下载 `app-debug`
- 解压即得 `app-debug.apk`

### 4. 安装到手机

```bash
# 手机开启「开发者选项 → USB 调试」，连接电脑
adb install app-debug.apk

# 或直接把 apk 传到手机，文件管理器点击安装
```

> Debug 包**无需签名**即可安装（Android 会自动用 debug keystore）。
> 若提示「未知来源」，在手机上允许该来源即可。

## 关于签名（发布到应用商店才需要）

Debug APK 只能自己装。要上架商店需**正式签名**：

```bash
# 生成签名密钥（仅一次）
keytool -genkeypair -v -keystore release.keystore -alias turbo -keyalg RSA -keysize 2048 -validity 10000

# 在 app/build.gradle.kts 的 android 块加：
signingConfigs {
    create("release") {
        storeFile = file("../release.keystore")
        storePassword = "你的密码"
        keyAlias = "turbo"
        keyPassword = "你的密码"
    }
}
buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
```

之后用 `./gradlew assembleRelease`，产物在 `app/build/outputs/apk/release/`。

## 本地构建（可选）

若你有 Android Studio 或本地装了 SDK：

```bash
./gradlew assembleDebug        # Linux/Mac
gradlew.bat assembleDebug      # Windows
```

生成的 APK 位置同上。

## 常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| Actions 红 × `SDK not found` | runner 缺 SDK | 工作流已用 `actions/setup-java`，通常无需额外步骤；若报错加 `android-actions/setup-android@v3` |
| 下载慢 / 超时 | Gradle 依赖拉取慢 | 已有缓存配置，二次构建会快很多 |
| `minifyEnabled true` 报错 | R8 混淆反射类 | 当前 `buildTypes.debug` 未开启 minify，可放心构建 |
| 手机装不上 | 架构 / minSdk | minSdk 24（Android 7.0+），覆盖 99% 现役设备 |

## 验证清单

构建成功后建议真机验证：
- [ ] 首次运行授予通知 + 存储权限
- [ ] 添加一个 HTTP 直链，多线程下载、进度可达 100%
- [ ] 暂停 → 杀掉 App → 重开，断点续传
- [ ] 内置浏览器打开含视频的页，嗅探弹窗出现，选 m3u8 → 完整合并出 .mp4
- [ ] 通知栏点击可暂停/取消对应任务
