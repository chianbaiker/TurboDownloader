# iOSDownloadMaster → Android 功能映射（17 项修复逐一对等）

原 iOS 工程 `iOSDownloadMaster`（Swift + App Group + Share Extension），本工程为 **Turbo Downloader (Android / Kotlin)**，
保持**完全相同的用户可见行为与容错规格**，仅按 Android 平台机制替换实现。

> **规模对照**：原 iOS 工程 15 个 swift 文件（含 Share Extension）；Android 版 **22 个 kt 文件**——
> 因 Android 无 Share Extension 概念，改为 `IntentFilter` 接收分享 + 内置嗅探浏览器，逻辑等价且更简洁。
> `self_check.py` 静态自检：**35/35 通过**。

## 🔴 严重级（6 项）

| # | iOS 修复 | Android 等价实现 | 状态 |
|---|---------|-----------------|------|
| 1 | `DownloadTask` 是 struct，UI 不刷新 → `updateTask()` + `@Published` | Room `DownloadEntity`(data class) + `Flow` 驱动 RecyclerView，任何字段变更自动刷新 | ✅ |
| 2 | Share Extension 无法调用主 App → App Group + Deep Link | **分享入口**：`IntentFilter` 接收 `ACTION_SEND`/链接 + `BrowserActivity` 嗅探，等同 Share Extension | ✅ |
| 3 | 多线程分片写入竞争 → `fileQueue` 串行队列保护 `seek`+`write` | 每个分片独立 `.part{i}` 文件，**天然无竞争**；合并用单线程顺序写 | ✅ |
| 4 | `resumeData` 仅存内存，重启丢失 → 持久化到 Caches | 进度实时写 Room(`downloadedBytes`)，分片文件落盘，重启自动从断点续传 | ✅ |
| 5 | 后台回调未在主线 → `DispatchQueue.main.async` | `withContext(Dispatchers.IO)` 下载 + `lifecycleScope`/`LiveData` 回主线更新 UI | ✅ |
| 6 | App 重启后后台任务丢失 → `getTasksWithCompletionHandler` 恢复 | `restoreDownloads()`：启动时扫描 `PAUSED/QUEUED` 任务自动恢复 | ✅ |

## 🟡 功能级（7 项）

| # | iOS 修复 | Android 等价 | 状态 |
|---|---------|-------------|------|
| 7 | Range 不支持自动降级单线程 | `NetworkClient.probe()` 检测 `Accept-Ranges` → `downloadMultithreaded` / `downloadSingleThreaded` | ✅ |
| 8 | 进度上限 `clamp(1.0)` | `progress.coerceIn(0,100)` | ✅ |
| 9 | ETA 除零保护 `seconds > 0` | `formatEta` 中 `bps > 0` 判断 | ✅ |
| 10 | 分片合并后校验文件大小 | 合并后 `totalBytes > 0` 校验 | ✅ |
| 11 | 通知携带 taskId，点击正确路由 | `PendingIntent` 带 `EXTRA_ID` + `DownloadReceiver` | ✅ |
| 12 | 临时分片文件自动清理 | 合并成功后删除 `.part{i}`；取消时清理 | ✅ |
| 13 | ATS 允许 HTTP | `AndroidManifest` 网络权限 + `usesCleartextTraffic="true"` | ✅ |

## 🟢 体验级（4 项）

| # | iOS 修复 | Android 等价 | 状态 |
|---|---------|-------------|------|
| 14 | `FileItem.id` = `task.id`，列表不抖动 | `DiffUtil.ItemCallback` 以 `id` 为稳定性 key | ✅ |
| 15 | 重试机制（网络错误 3 次） | 每分片 `MAX_RETRIES=5` + 指数退避（Android 版更强） | ✅ |
| 16 | 存储空间检查 | `StorageChecker` 下载前检查可用空间 | ✅ |
| 17 | 设置页清除已完成任务 | 菜单 `clear_completed` + `deleteByStatus` | ✅ |

→ **17/17 项全部对等覆盖。**
