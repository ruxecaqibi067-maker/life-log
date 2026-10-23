# 作息补录/编辑易用性修复交接

日期：2026-10-23（Asia/Shanghai）；分支 `fix/sleep-wheel-picker`；状态：实现及主机/模拟器检查交付待主管整合，荣耀实机待验证。

## 基线与范围

- 原 T06 worktree `C:\Users\user1\Desktop\workspace\NEW TRY\.worktrees\life-log-t06` 已先将原 `task/t06-sleep` 从 `bdebc6e` 快进到当前 main `ec270b6`，再从该主线提交建立独立修复分支。main 保持不动。
- 尝试更新远程引用时 GitHub 连接在300秒后超时；本机 main 与已有 origin/main 均为 `ec270b6`。本包不声称已联网确认远端无更新，不推送、不合入 main。
- 只改作息显示、选择器、相关测试及版本默认值/本交接；不改中央 STATUS、Room schema、备份格式、权限或依赖。

## 最终行为

- 移除就寝/起床的两组时区与UTC偏移输入、格式说明、ISO手输入口。显示如「2026年10月31日 23:50」，24小时制，不显示T、秒或毫秒。
- 点击时间卡打开原生 Android NumberPicker 滚轮：年月日一行、时分一行，有确认和取消。键盘输入关闭，支持上下滑动及数值无障碍调整。换月/年按真实月份天数限制日期，包括闰年。
- 选择器有独立可恢复草稿；取消、返回或点遮罩不改变表单。确认后才更改对应端点。表单保留已起床开关、跨夜、午睡、多段、结束晚于开始/重叠校验、长区间确认、保存和确认删除。
- 读取现有注入 Clock 的手机当前时间、`ZoneId.systemDefault()` 提供器。新建与明确修改的端点使用打开选择器时的系统时区快照，无硬编码 UTC/GMT/北京时间。
- 旧记录先用完整原时间点转换到当前手机时区供编辑器显示；未修改端点仍保存原 epoch milliseconds、原时区及偏移。只打开保存，或打开选择器确认同一个分钟/同一次夏令时发生，不会截掉秒/毫秒或重写历史归档。选择不同分钟才按所选分钟落到秒/毫秒为零的新时间点；另一端完全保留。
- 历史摘要沿用记录原时区显示其中文当地时间；现有归档规则不变。编辑器中显示当前手机当地时间仅为视图转换，不迁移数据库。
- 夏令时不存在的时间禁用确认并提示重新选择；重复时间显示「第一次出现 / 第二次出现」供明确选择，不暴露偏移字段或静默猜测。
- 日期卡支持文本换行、表单滚动；选择器内容可滚动，取消/确认置于固定底部区域。普通及大字体操作已实际执行。

## 兼容性与文件

- `SleepForm` 保持原7字段 SavedState 存储与 ISO 内部表示，新增分钟视图和选择投影。`SleepEditorViewModel` 只增加系统时区访问。Repository、DAO、实体、导入/导出校验及格式均不变。
- `SleepDateTimePicker.kt` 为滚轮与月份天数规则；`SleepScreen.kt` 替换技术表单并统一作息摘要中文格式；`sleep.xml` 改为用户文案。
- `app/build.gradle.kts` 和 `scripts/Build-Release.ps1` 默认版本从 `0.1.1 / 2` 递增到 `0.1.2 / 3`。包名 `com.personal.lifelog` 与已有长期签名配置保持。
- 测试更新既有作息/时间线编辑用例，新增模型边界与 Android 原生滚轮设备用例；没有重写原先校验预期以绕过失败。

## 实际检查

本地证据位于本 worktree 的 `artifacts/sleep-wheel/`；截图均为模拟器实际屏幕和虚构夹具，无生成图/拼图，不入 Git。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain --max-workers=2` | 成功；217项测试，0失败/错误（原212项+5项模型边界）；Lint 0错误/13警告 | `host-check.log`、`host-summary.json`、JUnit XML |
| 专项 Android 测试 | Android 17/API37/1080×2400模拟器，4项通过，0失败/跳过；实际原生滚轮手势、跨夜、旧毫秒精度不变、未结束、大字体及 SavedInstanceState 恢复 | `device-final.log`、connected测试XML |
| 临时设备包隔离 | 本地忽略的 Gradle init 脚本仅将 debug 测试包后缀设为 `.sleepfix`；正式 release 配置不受影响，模拟器原有正式版未被调试包替换/卸载 | `isolated-device.init.gradle`、设备日志 |
| 月末/闰年/系统时区/旧数据往返 | 1月31日换2月为28/29日；上海以外的 Kolkata/Auckland/纽约时区；旧时区转换后确认同分钟仍原样保存两端毫秒；重复/缺口时间 | `SleepWheelModelTest`、`SleepPickerDeviceTest` |
| 大字体/重建 | 2倍 Compose 字体：选择器改到2月28日后恢复 SavedInstanceState，所选值仍在；取消不写回；保存可滚动触达；旧 SavedStateHandle 草稿回归通过 | `editor-large-font.png`、`wheel-large-font.png`、设备测试 |
| schema与备份格式 | 无差异；既有备份导出/导入/恢复回归包含在217项中 | `git diff main -- app/schemas`、主机测试 |
| 长期签名 release 构建 | 源码提交后执行并在下方补记 | `artifacts/t16/v0.1.2-3/` |

13项debug Lint警告含现有10项版本提示、2项存储空间API建议，以及新增的 `ConfigurationScreenWidthHeight`（滚轮对话框高度限制）。没有关闭规则或增设 baseline；原ISO短横线警告随技术输入资源移除。

### 实际截图

- `artifacts/sleep-wheel/screenshots/editor.png`：就寝/起床中文日期卡、已起床开关、保存及删除。
- `screenshots/wheel.png`：正常字体、5个原生滚轮、24小时制、确认与取消。
- `screenshots/editor-large-font.png`、`screenshots/wheel-large-font.png`：两倍字体下的表单与选择操作。
- `screenshots/unfinished.png`：旧未结束记录，不展示/伪造起床时间。

截图由设备 UiAutomation 直接取得，等待平台对话框进出动画完成后保存原始PNG；没有裁剪/修改。设备时间为模拟器原样时钟，批次日期按 Asia/Shanghai 记录。

### 失败定位

- 第一轮设备测试因模拟器处于 Asleep，Activity 已启动但无 Compose hierarchy，4项失败；第二轮在手动唤醒前首项超时，唤醒后另外3项通过。已在夹具加入唤醒/解锁并等首帧，最终4项全部通过，日志原样保留。
- 首次截图捕获到 Android Dialog 淡入/淡出中间帧；加入平台动画等待后重采，人工检查最终PNG。没有把过渡帧当最终界面证据。

## 发布与主管整合

- 实现提交及长期签名APK校验在交付后续补记；最终HEAD以对话回报为准。
- 使用既有 Git 外 `%LOCALAPPDATA%\LifeLogAndroid\signing\signing.properties`，不生成/替换密钥，不输出密码。
- 未连接荣耀200 Pro；当前证据为主机与模拟器，仍须在荣耀/MagicOS确认真实滚轮手感、系统大字体及覆盖升级。
- 主管从 main 审查 `fix/sleep-wheel-picker` 的 diff，核对新增交互与旧数据不变用例，再整合并安排实机更新。中央 STATUS 与原 T06 历史交接均未改写。
