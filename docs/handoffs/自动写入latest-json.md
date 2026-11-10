# 自动写入 latest.json 交接

## 范围与来源

2026-11-10（Asia/Shanghai），分支为 `feat/automatic-latest-json`，前置 main 为 `cf14590`。实现和本交接随任务提交，最终 HEAD 见交付答复。

仓库的 `BackupJson.VERSION` 已为 7，同餐多照片关联使用 `mealPhotos`。本功能直接复用现有编码器和 v7 字段，省略整个 `media`，保留全部 photoId 引用，并添加顶层 `generatedAtUtcMs`、`appVersion`。这与需求文字中“当前 6”的描述有差异，采用仓库实际备份版本以满足同构要求。

## 实际行为与入口

- `App.onCreate` 完成恢复检查后启动应用级 `LatestJsonExporter`，监听 Room 全部 13 张表，使用 600 毫秒防抖。数据库快照沿用事务和 WriteCoordinator。照片文件缺失也能生成 JSON。
- 冷启动比较现有文件与当前快照，补齐缺失、旧版、损坏及进程结束前遗漏的内容。数据库任一表变更都会触发发布，包括数据文件省略的媒体元数据表和内部恢复状态表。
- API 29 及以上使用 MediaStore.Downloads，RELATIVE_PATH 为 `Download/lifelog`，复用同一条目，写入时设 IS_PENDING，完整写入、关闭并同步后发布。可恢复的失败尝试恢复上一份内容，遗留 pending 条目由下次尝试修复。路径冲突产生的编号文件会被清理并报告失败。
- API 28 使用限定 maxSdkVersion 28 的 WRITE_EXTERNAL_STORAGE 运行时权限。同目录临时文件 flush/fsync 后 rename 覆盖目标。
- 设置页提供默认开启的「自动写入 latest.json」。开关是设备本地 SharedPreferences 偏好，独立于 ZIP 中的 AppSettings。关闭操作与发布互斥，完成后停止后续文件写入，重新开启会核对当前数据。手动导出的成功时间仍只由原有 ZIP 导出记录。
- 写入器使用应用级 SupervisorJob 和 IO 调度。权限、空间、Provider 及文件异常转换为设置页错误状态，Activity 关闭允许已排队的写入继续完成。

## 验证

本次运行使用 JDK 17，隔离 applicationId 为 `com.personal.lifelog.latestjsontest`。构建和设备证据均保存在忽略目录 `artifacts/`。

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| assembleDebug、assembleDebugAndroidTest、定向主机测试和 lintDebug | 成功；首轮 17 项包含原有 ZIP 导出 5 项、多照片备份 3 项、设置 2 项、自动导出 7 项 | `artifacts/latest-json-final-check.log` |
| 自动导出新增内部表监听检查后的 8 项 | 全部通过，覆盖真实 Room 增删改、防抖、当前文件跳过、版本/损坏修复、缺失照片文件、持久关闭、并发关闭和失败重试 | `artifacts/latest-json-device/exporter-tests.xml` |
| MediaStore 协议 5 项分别在 Robolectric API 29/34 运行 | 10 项全部通过，覆盖同 URI 覆盖、UTF-8、pending 恢复、失败回退、首次失败清理、编号冲突清理 | `artifacts/latest-json-protocol-final.log`、`artifacts/latest-json-device/destination-tests.xml` |
| Android 10/API 29 隔离模拟器 | 综合场景通过，9.831 秒 | `artifacts/latest-json-device/android10-instrumentation.log` |
| Android 14/API 34 隔离模拟器 | 综合场景通过，9.121 秒 | `artifacts/latest-json-device/android14-instrumentation.log` |
| 两个系统的 adb ls、pull、jq | 固定路径正确；meals 为 2，与应用快照一致；JSON 可解析；media、relativePath、generationId 键检查通过 | `artifacts/latest-json-device/android10-latest.json`、`android14-latest.json` 及同目录 path 证据 |
| 关闭后 force-stop 并真实冷启动 | 两个系统文件 SHA-256 均保持一致 | `artifacts/latest-json-device/android10-disabled-check.txt`、`android14-disabled-check.txt` |
| 设置页实际点击与截图 | API 29 开启/关闭都写入对应偏好，页面检查完成 | `artifacts/latest-json-device/android10-toggle-check.txt`、`android10-settings.png` |
| 最终 assembleDebug 与 lintDebug | 成功，Lint 为 0 errors / 23 warnings，警告来自现有依赖版本提示和既有代码 | `artifacts/latest-json-build-final.log`、`app/build/reports/lint-results-debug.txt` |

综合设备场景通过真实应用容器写入和删除饮食记录，每次以 3 秒为上限核对 JSON 数组长度，并验证自动写入关闭期间手动 ZIP 导出可用、ZIP 仍含 manifest.json/data.json/media 数组、Activity 关闭后写入完成。原有主机 ZIP 检查还覆盖照片、SHA-256 和导出失败路径。

测试夹具的 JUnit 返回类型、Provider authority 和写入故障注入时机曾导致主机检查失败，均已修正后复核。Android 10 的 crash buffer 含模拟器首次启动时 sdksetup 的时区异常，应用包的崩溃/ANR 检查通过。

## 后续验证范围

API 28 权限弹窗、临时文件替换仍待 Android 9 设备验证。Android 10/14 的上述结果来自隔离模拟器，厂商真机与长期大数据量使用仍待验证。本轮构建的隔离 APK 位于 Gradle 输出目录。
