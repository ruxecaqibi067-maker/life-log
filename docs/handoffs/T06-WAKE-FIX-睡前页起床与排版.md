# T06 睡前页起床按钮与排版修复

- 日期：2026-10-25（Asia/Shanghai）；启动基线main `59c1261`（已接受0.1.3）；独立分支 `fix/bedtime-wake-button`、工作区 `.worktrees/life-log-sleep-wake`。此前T06和T18分支保留。本分支起点不含T18，开发中主管已将T18接受到main `ebc638e`；本包继续单独交付作息界面修复，主机全套数字对应启动基线，T18与此修复的集成检查由主管执行。
- 用户反馈：当前版本黑屏后没有起床按钮，要求黑屏仍能直接起床，同时美化并居中文字。本次明确更新原睡前页“只有时钟/短句、回首页起床”的交互，原交接保留为历史。
- 实现状态：实现及本包检查完成，交主管审查；实现提交提交后补记，最终HEAD在交付摘要回报。没有修改中央STATUS、数据库/schema/备份、AppContainer、业务ViewModel、首页布局、依赖、权限、版本或签名；不发布安装包，交主管统一整合。

## 修复行为

1. 黑屏下方固定显示“起床了”，至少56dp高；正文独立滚动，小屏/大字体时按钮仍可见。沿用 `SleepViewModel.finish(record)`，同一行补充真实起床时点/时区，原校验、忙碌防重复与长时段确认不变。
2. Room确认没有活动作息后才回首页；初次进入等待活动Flow，避免导航先于Room通知导致黑页提前关闭。读取失败不当作起床成功，写入失败留在黑页展示错误，可用同一按钮重试。
3. 超24小时在黑页弹出原确认，取消保留未结束作息；确认使用最初点击时捕获的时点。首页和黑页共用确认组件。按钮保存中禁用并显示保存状态。
4. 时钟与三条短句作为一组在正文区域居中；时间使用系统无衬线/轻字重/等宽数字特性，中文使用现有系统字体、行高与居中均衡换行，限制长行宽度、增强灰字对比度。时钟随可用宽度和字号适配，不加载外部字体。
5. 保留纯黑、系统时间/时区、分钟刷新、停留/重建不刷新随机短句、系统返回只退出不结束、系统栏恢复、自动熄屏及首页撤销。系统栏窗口可从包装Context逐层找到Activity；黑页使用局部深色主题，错误与确认弹窗不会突然变成浅色，用户保存的全局主题不变。退出回调检查当前路由，防止返回动画期间的晚到Room通知重复弹栈。

## 修改文件与整合注意

Kotlin路径前缀为 `app/src/main/java/com/personal/lifelog/`：

- `feature/sleep/BedtimeScreen.kt`：增加作息状态和直接起床操作，重排时钟/短句/固定按钮，反馈与重建处理。
- `feature/sleep/SleepScreen.kt`：提取内部 `SleepFinishConfirmation(viewModel)`，复用原 `LongIntervalDialog` 和完整时点详情，首页与黑页一致。
- `ui/navigation/LifelogNavHost.kt`：向 `BedtimeScreen` 传入已有 `sleepViewModel`，路由名与返回栈不变。
- `app/src/test/java/com/personal/lifelog/feature/sleep/SleepUiTest.kt`：新增黑页起床、重建后操作、失败重试、取消/确认与保留点击时点；验证首页撤销仍可用。
- `app/src/androidTest/java/com/personal/lifelog/feature/sleep/BedtimeDeviceTest.kt`：黑页按钮、实际起床、系统返回、两倍字号/截图。
- 本交接文档。

T19接线时继续沿用本分支的BedtimeScreen参数和起床按钮，不重新写起床逻辑；保留长时段确认、保存失败反馈及Room成功后退出。日期/时间滚轮本轮未改，Android9明暗原生主题与旧精度约定继续沿用原滚轮交接。

## 检查与证据

- 主机：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain --max-workers=2` 成功；241项测试、0失败/错误/跳过（基线238+新增3），Lint 0错误/13条警告，debug和设备测试APK构建成功。日志 `artifacts/sleep-wake/host-check.log`，摘要 `host-test-summary.json`，完整XML `host-results/`。
- 最后换行、窗口Context兼容和退出保护调整后，执行 `:app:testDebugUnitTest --tests com.personal.lifelog.feature.sleep.SleepUiTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain --max-workers=2` 成功；8项作息界面测试、0失败/错误/跳过，Lint仍为0错误/13警告。日志 `final-check.log`、`final-sleep-ui-results.xml`。这次定向复查不替换前述241项整套回归证据。
- API34/Android14隔离模拟器，720×1600、320dpi（360dp宽），临时包 `com.personal.lifelog.sleepwake`：最终4项测试、0失败/错误/跳过，涵盖按钮可见可点、黑页直接结束同一记录、失败重试、系统返回不结束与系统栏恢复、两倍Compose字号的完整时钟/短句滚动和固定按钮。没有覆盖已有正式签名安装。命令为 `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.personal.lifelog.feature.sleep.BedtimeDeviceTest`，搭配 `-I artifacts/sleep-wake/device.init.gradle --console=plain --max-workers=2`；日志 `device-check.log`、`device-results.xml`。
- 最终普通/两倍字号截图 `artifacts/sleep-wake/bedtime.png`、`bedtime-font2x.png` 已逐张查看，均可见下方“起床了”。来自实际Compose导航/Room虚构夹具，使用测试ComponentActivity；画面含模拟器状态栏，未把该夹具截图当作真实MainActivity/手机全屏系统行为的证据。两倍字号通过测试内LocalDensity模拟，没有改变系统字体设置。
- 截图工具修复：旧共用路径残留root所有的旧截图，首次UI测试通过但导出未成功，取回时已识别并隔离为 `stale-*`，不作为本轮效果证据。改用包名独立路径并校验复制字节数；UiAutomation命令不解析shell连接符，曾导致2项截图辅助步骤失败，改为逐条cp/chmod/stat后4项通过；后续收尾继续复跑。日志 `device-first.log`、`device-capture-first.log`、`device-before-polish.log`；未修改业务校验以绕过失败。
- 未执行：手机真机与正式签名升级/发布；不把模拟器结果当作荣耀或其他品牌实测。版本递增与正式安装包由主管统一安排。
- 未新增Android9设备、真实系统字体设置切换或进程强杀矩阵；页面重建由主机覆盖。`git diff --check` 通过。成功日志、XML、最终截图与隔离脚本另复制到主仓库忽略目录 `artifacts/sleep-wake-fix-20261025/`，旧截图明确排除；原失败日志留在本工作区。
