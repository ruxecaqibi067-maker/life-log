# T15 验收证据索引

批次：2026-10-23（Asia/Shanghai）。实现基线 `592e668`，直接前置 `9770bac`。本报告区分主机、Android 模拟器与荣耀真机，中央 ACCEPTANCE/STATUS 状态仍由主管维护。

## 数据与构建

- 规模集：599 条饮食、200 条完整作息、1 条未结束作息、200 场训练，共 1,000 条；400 个动作、400 个力量组、9 个项目（含未使用且隐藏的自定义项目）、200 张程序生成的 120×80 JPEG，深色主题。
- 图片用于引用完整性与规模数量检查，不能替代大图/高分辨率压力和真实相机测试。单独的大图解码边界由 PhotoNormalizer/PhotoDevice 测试覆盖。
- QA 包名 `com.personal.lifelog.qa`，版本 `0.1.0-qa`/1，`debuggable=false`；继承 release R8 和资源压缩，使用本机 debug 测试证书。版本/哈希与最终本机产物索引见 handoff。
- 模拟器为已有的 Medium Phone、Android 17 / API 37、x86_64。没有连接荣耀 200 Pro，MagicOS、荣耀设备存储与字体配置未知。
- 私有照片、备份、设备序列号均未写入本报告；本地 ZIP/XML/PNG 均只含合成验收数据并被 Git 忽略。

## 证据映射

下表的「通过」仅适用于明确标注的执行层。所有荣耀手工用例仍为未执行，不能将下列自动化映射直接改成真机验收通过。

| 验收 ID | 已执行证据 / 覆盖层 | 荣耀待验证内容 |
| --- | --- | --- |
| F01、F04、F05、F08 | MealEditor/MealDetail/MealFlow、Repository 主机回归 | 手机保存、编辑、删除与重开 |
| F02、F03、F06、F07、F09、F10 | PhotoStorage/Normalizer/Intents 主机测试及 PhotoDevice Android 测试 | 真实相机/图库取消、URI 撤销、原图删除、缓存清理 |
| S01、S03–S10 | RecordTime、Validation、SleepFlow/SleepUi、Repository 主机测试；规模集含跨日和未结束状态 | 手机日期/时区操作、文案与操作路径 |
| S02 | QA 模拟器强停后未结束作息仍显示；release 重启补测结果见 T16 | 手机划掉、强停、实际重启 |
| W01–W08、W10–W12 | WorkoutFlow/Ui、StrengthSetsFlow、ExerciseLibraryData/ViewModel 主机测试；设备 UI 套件复测见 handoff | 荣耀输入法、逐组多动作、项目管理及日期回顾 |
| W09 | 规模备份/恢复对比包含未使用且隐藏的自定义项目 | 手机从设置导出并恢复 |
| U01、U02 | TimelineRepository、Home/ReviewViewModel、TimelineUi 主机测试；规模集全日期查询总数恰为 1,000 | 真机日期滚动与编辑后的归属 |
| U03、U08 | 主 Manifest/合并候选 Manifest 检查；应用不带业务网络等无关权限 | 飞行模式全流程及系统权限交互 |
| U04–U06 | 主机组合 UI 回归；模拟器可启动并显示深色主题 | 大字体全页面、手势/键盘遮挡、旋转草稿与单手区域 |
| U07 | 主机规模集查询与回滚通过；优化 QA 成功恢复/再导出全部规模集 | 荣耀列表滚动、详情错图/冻结与性能 |
| B01–B05 | BackupExport/Import、RestoreEngine、BackupRestoreController/Ui 主机测试；真实 SAF QA 往返成功 | 荣耀各文件提供者、预览取消、回滚包再次恢复 |
| B06 | 主机异常矩阵与持久库重开矩阵；真实 killProcess 九边界复测见 handoff | 荣耀进程终止/存储故障；不能模拟真正断电 |
| B07–B10 | BackupImport 测试的坏 ZIP、坏哈希、未知版本、重复 ID、恶意路径、流式上限参数化检查 | 手机错误反馈与可重试交互 |
| B11、B12 | BackupExport、PhotoStorage、RestoreEngine 故障与取消回归 | 真机磁盘写满、SAF 拒绝删除、照片保存中断 |
| B13 | 自动备份排除配置静态检查 | 荣耀厂商换机/备份行为，不能承诺控制全部厂商迁移 |

## 本地日志与操作复现

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleQaRelease :app:lintQaRelease --console=plain --max-workers=2
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain --max-workers=2
.\docs\testing\restore-process-matrix.ps1
python scripts\verify_backup.py artifacts\t15\scale-fixture.zip artifacts\t15\qa-export.zip
```

- `artifacts/t15/verified-check.log`：最终依赖修复后的主机与候选构建日志。
- `app/build/test-results/testDebugUnitTest/`：XML，含 ScaleBackupTest。
- `app/build/reports/lint-results-debug.txt`、`lint-results-qaRelease.txt`：静态检查。
- `artifacts/t15/emulator-instrumentation.log`：初次失败；保留复现而非隐藏。
- `artifacts/t15/emulator-instrumentation-fixed.log` / connected 复测日志：修复后结果，以 handoff 中的最终结果为准。
- `artifacts/t13/device-process/*-interrupt.log`、`*-recover.log`：每个真实进程终止点与重开验证；批次汇总在 `artifacts/t15/`。
- `artifacts/t15/qa-preview.xml/png`、`qa-result.xml/png`、`qa-export-complete.xml`、`qa-populated-home.xml/png`、`qa-reopen.xml/png`：真实系统文件选择器、预览、恢复、导出和强停重开截图。
- `artifacts/t15/qa-roundtrip.json`：QA 从规模 ZIP 恢复，再经系统 SAF 导出；数据 JSON 完整语义、200 张照片 SHA-256 和数量完全一致；未忽略任何设置字段。

设备操作：向模拟器 Download 放入合成 ZIP；QA 设置 → 从备份恢复 → 系统 Downloads 选 ZIP → 核对预览 → 覆盖并恢复 → 等待恢复完成 → 导出备份 → 系统保存 → 等待成功再拉取 ZIP 比较。强停后重开，未结束作息仍在。最终候选依赖更新后的重复冒烟结果另记 handoff。

## 缺陷、性能与门槛

1. Compose 间接引入 Espresso 3.5.0，在 API 37 报 InputManager.getInstance 缺失；显式使用 3.7.0。[上游已确认](https://github.com/android/android-test/issues/2507)。
2. 目标 APK 的旧 serialization-core 与 Room 测试 serializer 不兼容，出现 AbstractMethodError；目标 APK 显式对齐 core 1.9.0，未引入业务 JSON serializer。[上游兼容性讨论](https://github.com/Kotlin/kotlinx.serialization/issues/2968)。
3. 进程矩阵的测试目录使用 Android 路径别名，被 BackupFiles 的规范路径校验拒绝；修正测试夹具为 canonicalFile，保留生产路径限制。

荣耀冷启动、文字/点选保存、照片保存的 10 次原始数据和 P90 均未测量。模拟器的 `am start -W TotalTime` 仅到 Activity 绘制，既不是荣耀结果，也不证明主按钮可响应，因此不用于判定 2 秒/300 毫秒/2 秒目标。

结论：主机/模拟器可提供候选证据，但 T15 的荣耀真机与性能门槛未满足。用户要求的 T16 签名和升级准备可继续；正式日常使用放行和一周试用验收均不能在此报告中提前宣布通过。
