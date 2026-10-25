# 作息睡前页简报（供主管及 T17 整合）

- 日期：2026-10-24；基线 main `1896715`（已正常快进同步主管新增的 T17 文档）；独立分支 `feat/sleep-bedtime-screen`；工作区为 `.worktrees/life-log-t06`。
- 范围：本轮仅实现「准备睡了」后的功能与交互，整体视觉统一交给 T17。未修改中央 STATUS、数据库、备份格式、依赖、权限或全局主题；未发布安装包。
- 实现提交：`a4c3dbfad10e490df47d0e7067ebc2c3f5e4560b`；最终HEAD在交付摘要回报。

## 实际行为

1. 首页点「准备睡了」，作息成功提交数据库后才进入睡前页；保存失败留在首页并可重试。重复点击仍受原忙碌状态、事务及唯一活动项约束。
2. 睡前页为纯黑背景，中央显示手机当前当地时间 `HH:mm`；下方从8条内置短句中随机取3条，例如「好好睡觉，好好生活」。不同次进入允许重复，本次停留与页面重建时保持原短句。
3. 页面只显示时间和短句，无顶栏、底部导航及额外按钮。系统返回键/手势回到首页，未结束作息保持不变；之后仍通过首页「起床了」结束同一条记录。
4. 时钟只在页面前台按分钟刷新，读取已有注入 Clock 和系统时区提供器。保留系统自动熄屏；没有常驻服务、WakeLock、后台计时、亮度改写或睡眠监测。
5. 进入时暂时隐藏系统栏，可通过系统边缘手势临时唤出；离开时恢复原系统栏可见性和图标模式。不会改变用户已保存的主题设置。

## 修改文件

路径均相对于仓库根目录：

| 文件 | 用途 |
| --- | --- |
| `app/src/main/java/com/personal/lifelog/feature/sleep/BedtimeScreen.kt` | 独立睡前页、前台时钟、稳定随机短句、系统栏进入/退出处理 |
| `app/src/main/java/com/personal/lifelog/feature/sleep/SleepViewModel.kt` | 提交成功后产生一次性 bedtimeRequest；消费后清除，失败和结束操作不产生进入请求 |
| `app/src/main/java/com/personal/lifelog/ui/navigation/LifelogRoutes.kt`、`app/src/main/java/com/personal/lifelog/ui/navigation/LifelogNavHost.kt` | 新增 sleep/bedtime 路由；首页消费请求，黑页独立处理外层背景/Insets，返回不结束作息 |
| `app/src/main/res/values/bedtime.xml` | 8条本地短句，便于后续改文案 |
| `app/src/test/java/com/personal/lifelog/feature/sleep/SleepUiTest.kt`、`app/src/test/java/com/personal/lifelog/feature/sleep/SleepFlowTest.kt` | 持久化后进入、失败重试、返回不结束、重建保留短句、请求消费与重复调用回归 |
| `app/src/test/java/com/personal/lifelog/feature/meal/MealFlowUiTest.kt` | 饮食/作息/项目库整合测试补上睡前页返回路径 |
| `app/src/androidTest/java/com/personal/lifelog/feature/sleep/BedtimeDeviceTest.kt` | Android导航、黑屏像素/截图、系统栏恢复、同一记录结束及两倍Compose字号布局验证 |
| `docs/archive/handoffs/T06-BEDTIME-睡前黑屏交互.md` | 本简报与检查证据 |

## T17 必须保留的交互

- 数据库提交是进入睡前页的前提；不能用按钮点下或内存布尔值提前宣告就寝保存成功。
- 请求消费只发生一次，已离开首页的晚到写入结果不打断其他表单；旋转/重建不重新开始作息，不重复压入黑页。
- 系统返回只关闭睡前页，不能误记起床、删除记录或清空未结束状态；首页「起床了」沿用原结束/异常长时段确认逻辑。
- 中央显示当前手机时间，数据层仍保留完整真实时点；不要把展示时钟作为作息时长或精确睡眠时间。
- 短句每次进入随机、阅读中稳定；黑底是此页的专门要求。T17 可统一排版、间距及组件组织，但需保留时间居中、短句在下方、放大字体/小屏时可读与可返回。
- 本页全屏状态不能泄漏到普通页面。若 T17 统一系统栏/edge-to-edge，请合并本页的进入与恢复逻辑，避免多个处理器互相覆盖。
- Android 首次全屏可能出现系统自己的边缘手势教学提示；这是系统窗口，不能作为应用文案隐藏。截图测试按正常用户操作确认提示后检查纯黑页面。

## 日期/时间滚轮主题注意事项（本轮未改）

- 沿用 `SleepDateTimePicker.kt` 的 `createSleepWheel(context, darkTheme)`：原生 NumberPicker 不会自动跟随 Compose 的手动明暗主题，必须使用匹配的 ContextThemeWrapper。
- Android 9/API28 没有公共 textColor/textSize 设置器，要靠明/暗原生主题保证字色；API29+继续应用 Compose `onSurface` 和随字体缩放计算的像素字号，不使用反射修补。
- 保留 `key(darkTheme)` 以便主题切换重建原生控件，同时从已保存的年月日时分状态恢复选中值。不要仅依赖系统夜间模式覆盖应用内明确选择的主题。
- 保留内容滚动与底部确认/取消、月底/闰年天数限制、24小时制、夏令时重复选择/缺口拦截。取消不改表单；同分钟未变不能损失旧记录秒/毫秒精度。
- 复用既有 `SleepWheelThemeTest`（API28明暗字色）、`SleepWheelModelTest`、`SleepPickerDeviceTest`；T17 修改颜色/组件后至少复查Android9明暗主题及系统两倍字体。

## 检查与证据

- 主机：`:app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain --max-workers=2` 成功；221项测试、0失败/错误/跳过；Lint 0错误、13警告，未出现本轮新增文件警告。证据为 `artifacts/bedtime/host-check.log`、`host-test-summary.json`、`lint-results-debug.txt`。
- 主机覆盖包括跨夜/月末补录、旧记录时间点及精度、取消、未结束作息，以及新增保存后进入、失败重试、返回、重建保留短句。首轮漏导入 `onAllNodesWithTag` 的编译失败已修复；失败日志保留为 `host-first.log`。
- 模拟器：Android 17/API37，1080×2400、420dpi、系统字号1.0；`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.personal.lifelog.feature.sleep.BedtimeDeviceTest` 搭配 `-I artifacts/bedtime/device.init.gradle` 成功，3项测试、0失败/错误/跳过。覆盖保存失败重试、提交后进入、纯黑背景像素、返回不结束、系统栏恢复、起床结束同一记录和两倍Compose字号下的可读/可滚动性。证据为 `device-check.log`、`device-results.xml`。
- 使用临时隔离包名 `com.personal.lifelog.bedtimecheck`，未覆盖已有正式安装。系统字号未改，两倍字号通过测试内 LocalDensity 模拟。设备当前时区为系统返回的 GMT，截图时间自然对应该时区；应用读取系统提供器，没有将此时区写死。
- 最终新增/调整设备测试后再次执行 `:app:assembleDebug :app:lintDebug --console=plain --max-workers=2` 成功，日志为 `final-static-check.log`。`git diff --check` 通过。
- 截图：`artifacts/bedtime/bedtime.png`（普通字号）、`artifacts/bedtime/bedtime-font2x.png`（两倍Compose字号）；来自实际运行的 Compose 导航及 Room 虚构夹具，已逐张检查。上述成功日志、截图、测试摘要和隔离构建脚本另复制到主仓库忽略目录 `artifacts/t06-bedtime-20261024/`，主管整合后可直接查看。
- 设备失败排查：首轮被 Android 首次全屏教学窗口遮挡，测试改为正常确认提示后截屏（`device-first.log`、`device-capture.log`）。两倍字号曾用段落整体溢出标记误判单行时钟，改为检查实际文字边界、单行且无省略，并允许1px整数舍入；截图与边界均通过（原失败 `device-font-first.log`、`device-font-capture.log`）。没有为此改动业务规则或生产页面。
- 未执行：本轮未连接手机；未新增Android9设备截图或真实系统字体设置切换验证。页面保存状态重建由主机测试覆盖，未做设备进程强杀恢复测试。模拟器结果不代表荣耀或其他品牌实机通过。
- 本轮交回源码及测试证据；版本号未调整。正式安装包由主管整合 T17 后统一递增版本并沿用既有长期签名。
