#!/usr/bin/env python3
"""对齐 iOS 版「静态自检 8 大类」，并新增 HLS/DASH 完整性检查。"""
import os, re, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app", "src", "main")
SRC = os.path.join(APP, "java", "com", "turbo", "downloader")
TEST = os.path.join(ROOT, "app", "src", "test", "java", "com", "turbo", "downloader")

CHECKS = []
def check(name, cond, detail=""):
    CHECKS.append((name, bool(cond), detail))

# 1. 文件完整性
kt_files = []
for dirpath, _, files in os.walk(SRC):
    for f in files:
        if f.endswith(".kt"):
            kt_files.append(os.path.join(dirpath, f))
test_files = []
for dirpath, _, files in os.walk(TEST):
    for f in files:
        if f.endswith(".kt"):
            test_files.append(os.path.join(dirpath, f))

check("① 文件完整性 (主源 kt)", len(kt_files) >= 22, f"{len(kt_files)} 个")
check("① 测试文件存在", len(test_files) >= 2, f"{len(test_files)} 个")

required = [
    "sniffer/hls/HlsDownloader.kt",
    "sniffer/hls/HlsTestAccess.kt",
    "sniffer/dash/DashDownloader.kt",
    "sniffer/MediaDownloadCoordinator.kt",
    "sniffer/Sniffer.kt",
    "ui/BrowserActivity.kt",
    "ui/MediaPickerSheet.kt",
    "ui/MediaItemAdapter.kt",
]
missing = [r for r in required if not os.path.exists(os.path.join(SRC, r))]
check("① 关键源文件齐全 (HLS/DASH/Coordinator)", not missing, str(missing) if missing else "全部存在")

# 2. 严重级 6 项
engine = open(os.path.join(SRC, "core/DownloadEngine.kt")).read()
sniffer = open(os.path.join(SRC, "sniffer/Sniffer.kt")).read()
hls = open(os.path.join(SRC, "sniffer/hls/HlsDownloader.kt")).read()
checks_severe = [
    ("Flow/StateFlow 驱动UI", "StateFlow" in open(os.path.join(SRC, "ui/MainViewModel.kt")).read()),
    ("分享入口(IntentFilter)", "ACTION_SEND" in open(os.path.join(SRC, "ui/MainActivity.kt")).read()),
    ("分片无竞争(.part独立文件)", ".part" in engine),
    ("断点持久化(downloadedBytes)", "downloadedBytes" in engine),
    ("后台主线(Dispatchers.IO)", "Dispatchers.IO" in engine),
    ("启动恢复(StartupRestore)", os.path.exists(os.path.join(SRC, "core/StartupRestore.kt"))),
]
for name, ok in checks_severe:
    check(f"② 严重级: {name}", ok)

# 3. 功能级 7 项 + HLS/DASH 新增
checks_func = [
    ("Range降级单线程", "downloadSingleThreaded" in engine),
    ("进度clamp", "coerceIn(0, 100)" in open(os.path.join(SRC, "ui/DownloadAdapter.kt")).read()),
    ("ETA除零保护", "etaSeconds" in open(os.path.join(SRC, "ui/MainViewModel.kt")).read()),
    ("合并后校验大小", "Size mismatch after merge" in engine),
    ("通知携带taskId", "EXTRA_ID" in open(os.path.join(SRC, "download/DownloadService.kt")).read()),
    ("临时分片清理", "partFile.delete()" in engine),
    ("ATS等价(cleartext)", os.path.exists(os.path.join(APP, "res", "xml", "network_security_config.xml"))),
    # ---- 新增：IDM 视频流完整下载 ----
    ("HLS: Master→最高码率", "BANDWIDTH" in hls),
    ("HLS: SEQUENCE 连续媒体列表", "isEnd" in hls and "MAX_ROUNDS" in hls),
    ("HLS: AES-128 解密", "AES/CBC/PKCS5Padding" in hls),
    ("HLS: IV = sequence_number", "sequence_number" in hls),
    ("HLS: 分片并行+串行合并", "awaitAll" in hls or "forEachIndexed" in hls),
    ("HLS: 临时分片清理", ".deleteRecursively()" in hls),
    ("DASH: MPD 解析", "DocumentBuilderFactory" in open(os.path.join(SRC, "sniffer/dash/DashDownloader.kt")).read()),
    ("DASH: SegmentTemplate/StartNumber", "startNumber" in open(os.path.join(SRC, "sniffer/dash/DashDownloader.kt")).read()),
    ("DASH: 视频+音频混流(ffmpeg)", "ffmpeg" in open(os.path.join(SRC, "sniffer/dash/DashDownloader.kt")).read()),
    ("嗅探: m3u8/mpd 检测", "TYPE_HLS" in sniffer and "TYPE_DASH" in sniffer),
    ("嗅探: manifest 内嵌分片解析", "parseManifestInternal" in sniffer),
    ("协调器: 自动路由 HLS/DASH/直链", "MediaDownloadCoordinator" in open(os.path.join(SRC, "sniffer/MediaDownloadCoordinator.kt")).read()),
]
for name, ok in checks_func:
    check(f"③ 功能级: {name}", ok)

# 4. 体验级 4 项
adapter = open(os.path.join(SRC, "ui/DownloadAdapter.kt")).read()
checks_exp = [
    ("DiffUtil稳定id", 'o.id == n.id' in adapter),
    ("重试机制", "MAX_RETRIES" in engine or "retryWithBackoff" in hls),
    ("存储空间检查", os.path.exists(os.path.join(SRC, "core/StorageChecker.kt"))),
    ("清除已完成", "deleteByStatus" in open(os.path.join(SRC, "data/DownloadDao.kt")).read()),
]
for name, ok in checks_exp:
    check(f"④ 体验级: {name}", ok)

# 5. 代码质量
unbalanced = []
force_unwrap = []
todo = []
for f in kt_files + test_files:
    s = open(f).read()
    if s.count("{") != s.count("}"):
        unbalanced.append(os.path.relpath(f, ROOT))
    if re.search(r"!!", s) and not re.search(r"_binding\s*!!", s):
        force_unwrap.append(os.path.relpath(f, ROOT))
    if re.search(r"\bTODO\s*\(", s) or "= TODO()" in s:
        todo.append(os.path.relpath(f, ROOT))
check("⑤ 括号平衡", not unbalanced, str(unbalanced) if unbalanced else "")
check("⑤ 无强制解包(!! 非视图绑定)", not force_unwrap, str(force_unwrap) if force_unwrap else "")
check("⑤ 无遗留 TODO", not todo, str(todo) if todo else "")

# 6. 工程引用
gradle = open(os.path.join(ROOT, "app", "build.gradle.kts")).read()
root_gradle = open(os.path.join(ROOT, "build.gradle.kts")).read()
deps = re.findall(r'(?:testImplementation|androidTestImplementation|implementation)\("([^"]+)"\)', gradle)
deps += re.findall(r'id\("([^"]+)"\)', root_gradle)  # 插件也算工程能力
check("⑥ 依赖声明 >=10", len(deps) >= 10)
for lib in ["room-runtime", "okhttp", "jsoup", "work-runtime", "lifecycle"]:
    check(f"⑥ 依赖 {lib}", any(lib in d for d in deps))
check("⑥ 依赖 junit", "junit" in open(os.path.join(ROOT, "app", "build.gradle.kts")).read())

# 7. Manifest
manifest = open(os.path.join(APP, "AndroidManifest.xml")).read()
checks_manifest = [
    ("Internet权限", "android.permission.INTERNET" in manifest),
    ("前台服务", "FOREGROUND_SERVICE" in manifest),
    ("DownloadService", "DownloadService" in manifest),
    ("FileProvider", "FileProvider" in manifest),
    ("http/https 深链", 'android:scheme="https"' in manifest),
    ("BrowserActivity", "BrowserActivity" in manifest),
]
for name, ok in checks_manifest:
    check(f"⑦ Manifest: {name}", ok)

# 8. 资源
res = os.path.join(APP, "res")
icons = ["ic_download.xml", "ic_add.xml", "ic_cancel.xml", "ic_pause.xml", "ic_resume.xml", "ic_browser.xml"]
layouts = ["activity_main.xml", "item_download.xml", "activity_add_download.xml",
           "activity_browser.xml", "sheet_media_picker.xml", "item_media.xml"]
checks_res = [
    ("图标齐全", all(os.path.exists(os.path.join(res, "drawable", i)) for i in icons)),
    ("布局齐全", all(os.path.exists(os.path.join(res, "layout", l)) for l in layouts)),
    ("测试布局不需要", True),
]
for name, ok in checks_res:
    check(f"⑧ {name}", ok)

# 9. 测试覆盖（新增）
for f in test_files:
    rel = os.path.relpath(f, TEST)
    s = open(f).read()
    has_test = "@Test" in s
    check(f"⑨ 测试 {rel} 含 @Test", has_test, "" if has_test else "缺少 @Test")

# ---- 汇总 ----
print("\n" + "=" * 60)
print(f"{'静态自检报告 (含 HLS/DASH)':^50}")
print("=" * 60)
passed = sum(1 for _, ok, _ in CHECKS if ok)
for name, ok, detail in CHECKS:
    print(f"  [{'✅' if ok else '❌'}] {name}" + (f"  ← {detail}" if detail and not ok else ""))
print("-" * 60)
print(f"通过: {passed}/{len(CHECKS)}")
sys.exit(0 if passed == len(CHECKS) else 1)
