# UI补充：并排双卡、底部工作操作与随机评价

## 给主管的汇合摘要

- 状态：实现待审；用户在T21接受后直接追加本界面要求，按本说明替代T21原纵排双卡/卡内工作按钮/已完成段数展示，不需要回改旧任务或中央STATUS。
- 接受基线：main `3358d2b`（T21实际接受后）；分支 `task/home-dual-card-feedback`；独立目录 `C:\Users\user1\Desktop\workspace\NEW TRY\.worktrees\life-log-home-dual`。开工主仓库、T21旧工作树均干净；创建新分支保留旧交付。
- 实现提交：待提交后填写。最终HEAD在交付消息回报；主管正常merge本分支后统一决定版本与发包。
- 本次没有修改Room/schema/迁移、备份协议、Repository/汇总算法、睡眠与工作业务状态机、MainActivity/NavHost、权限依赖或版本签名；中央STATUS保持不动，不推送、不自行发布。

## 用户可见变化

1. 今日睡眠与今日工作在同一行等宽、等高并排。卡内统一竖排：标题→有边框的目标时长编辑按钮（铅笔图标）→独立圆环→真实完成时长→评价短句→按结束日统计→查看/补录按钮与箭头。两张卡的目标与真实值仍独立。
2. 删除卡内“已记录”标题及“多少段已完成记录”，保留真实时长；每段明细仍在历史及时间线中。图表是各自目标占比，超过目标图形封顶，但真实时长和文案判断不封顶。
3. 底部固定快捷区采用两行两列：第一行“准备睡了/起床了”与“开始/结束工作”相邻；第二行“记饮食”“记运动”。工作操作从卡内移出，滚动后仍可直接开始/结束；忙碌、读取就绪/失败禁用、长区间确认、安全撤销等保持原ViewModel。
4. 工作开始保持普通首页和系统栏；只有睡眠快捷操作进入既有睡前页。活动项编辑、开始时间、错误/重试、撤销反馈留在双卡下的滚动区；确认对话框仍在LazyColumn外，滚动不丢确认。
5. 两卡在360dp/1.5倍字体下也保持并排，不用缩字挤一屏；文字自然换行，历史入口可滚动到达。底部两项主要按钮并排、可触达。目标弹窗仍使用已有小时分钟、清除、取消及保存逻辑。

## 评价规则（个人目标提示）

分子只取T18/T20已完成毫秒总量，分母为各自目标分钟×60000；不重算区间，不加进行中、不使用圆环封顶结果。阈值是本次界面默认值，代表个人目标比较，不作健康诊断或强制考勤。

| 类型 | 条件 | 文案语气 | 每组条数 |
| --- | --- | --- | --- |
| 睡眠 | 实际/目标 < 80% | 温和提醒多休息、早点放下忙碌 | 5 |
| 睡眠 | 实际/目标 ≥ 80% | 积极鼓励、照顾自己 | 5 |
| 工作 | 实际/目标 < 80% | 提醒手边计划、一步一步推进，不责备 | 5 |
| 工作 | 80% ≤ 实际/目标 ≤ 120% | 积极肯定、保持自己的节奏 | 5 |
| 工作 | 实际/目标 > 120% | 提醒暂停、休息、喝水走动 | 5 |
| 两类各自 | 未设目标、无完整记录、仅进行中 | 中性提示；不把缺记录解释为0 | 每种各3 |

共11组43条。`rememberSaveable`保存当前类别的随机下标，普通重组、滚动及Activity重建不无故换句；类别改变或重新进入首页可重新选择，随机允许连续两次选中同一句。加载/读取失败不显示比例评价，已有同日陈旧值保持明确标记。

文案例子：“今天休息得偏少，记得给自己多留些睡眠。”“稳稳向前的每一步，都值得肯定。”“已经超过自己的目标不少，起来休息一下吧。”“先做一件小事，让今天的计划向前一点。”

## 代码与整合位置

- `feature/home/HomeScreen.kt`：并排组合、固定2×2操作区与独立工作按钮装配。
- `DurationSummaryCard.kt`：共享紧凑视觉、目标/历史明确按钮、无障碍对象名称；`SleepSummaryCard.kt`、`WorkSummaryCard.kt`仅传入各自状态。
- `DurationFeedback.kt`及`res/values/home_feedback.xml`：个人目标分类与随机文案；仅显示逻辑，无持久化数据字段。
- `feature/work/WorkScreen.kt`：原WorkHomeActions改为WorkHomeFeedback，只保留反馈；开始/结束由首页固定入口调用同一WorkViewModel。
- 原测试只按实际位置调整：懒列表key合并为duration-summaries，工作开始/结束不再先搜索懒列表；保存、精度、导航、错误和数据库断言继续保留。
- `docs/UI_STYLE_GUIDE.md`第10/11节更新本轮布局和评价口径。T21历史交接不改，供主管追溯原验收。

## 检查、截图与证据

最终检查已通过。所有记录、截图和ZIP均虚构；证据在本worktree忽略目录 `artifacts/home-dual/`，交付后同步到主仓库忽略目录 `artifacts/home-dual-20261025/`；不提交APK、照片或备份到Git。

| 命令/场景 | 实际结果 | 证据（相对证据目录） |
| --- | --- | --- |
| `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain --max-workers=2` | BUILD SUCCESSFUL；44 suites / 302 tests，0失败、0错误、0跳过 | `host-final.log`、`host-final-summary.json`、`host-final-results/` |
| Lint | 0 errors / 13既有warnings；版本提示、既有SleepDateTimePicker及RestoreEngine；无新增界面告警 | `lint-final.txt` |
| `./gradlew.bat -I artifacts/home-dual/device.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --max-workers=2` | 隔离debug及androidTest构建成功；不改项目版本、包名和签名配置 | `device-build-final.log`、`device.init.gradle` |
| `python artifacts/home-dual/device_session.py home` | 2项实际MainActivity并排卡/比例文案/固定按钮/重建/大字体/真实键盘检查通过 | `home-device.log` |
| `python artifacts/home-dual/device_session.py work` | 原T21完整工作/目标/失败/长段/撤销/历史/跨夜回顾/备份预检与恢复3项通过 | `work-device.log` |
| `python artifacts/home-dual/device_session.py regression` | 原生睡眠选择器、睡前直接起床、训练共11项通过 | `regression-device.log` |
| `python artifacts/home-dual/restart_check.py` | 强停重开仍有结束工作按钮；全部用户表行逐项一致 | `restart-check.json`、`restart-ui.xml` |
| `git diff --check`、受保护路径及前置祖先检查 | 通过，17个范围内文件；受保护路径无改动 | `scope-check.json`、`tested-source-sha256.json` |

- 主机为Windows、JDK17.0.20.1与仓库Gradle wrapper。新增测试覆盖80%/120%精确边界、实际比例大于1、毫秒级临界、未设/缺数据/进行中不误判；UI保留真实值、隐藏旧标题/段数、普通重组文案稳定。原有数据与保存失败回归一起通过。
- 设备为本任务自建只读API34 AOSP Android14模拟器，隔离包 `com.personal.lifelog.homedual.t21check`；720×1600、density320、360dp、中文、Asia/Shanghai；系统字体1.0与实际1.5倍。16项全部通过，无失败/跳过。未修改用户正式安装或既有模拟器，取证后仅关闭本任务实例。
- 设备实测按坐标断言两卡顶部一致、等宽且左右不重叠；睡眠/工作主按钮同一行且可见。6小时/8小时睡眠命中提醒休息，10小时/8小时工作命中休息提示，工作目标调至12小时后命中积极文案且睡眠目标480分钟不变；Activity重建和无关状态更新时原类别短句保持一致。
- 实际开始工作留首页且睡眠记录不变；结束成功后再由睡眠按钮进入睡前页、直接起床返回，原工作/睡眠记录数正确。大字体下两个历史入口均能滚动到达；真实数字键盘展开时目标保存按钮可见，取消不写目标。
- 35张最终原始PNG及逐图设备元数据在`screenshots/`；`screenshots.json`包含尺寸、fontScale、SHA256，`index.html`可直接查看全部场景。10张为系统1.5倍字体。所有图片由实际应用截图直接提取，未裁剪、重绘或合成；页面文本、记录和时间均虚构。
- 建议主管优先查看：`light-short-sleep-long-work.png`、`dark-paired-cards.png`、`dark-balanced-work.png`、`large-paired-cards.png`、`large-history-actions.png`、`large-goal-keyboard.png`、`large-active-after-recreate.png`、`restart-active-work.png`。
- 初版截图发现大字体标题会与编辑图标争宽，已改为标题独占一行、整块目标时长按钮；重建隔离包并重跑16项后重新取证。第一轮Lint提示14项旧资源无用，删除已被本次布局替代的旧“已记录/段数/文字入口”资源后，最终回到13项既有告警。初版日志、`header-first-screenshots/`只作过程追溯，当前结果以最终日志与`index.html`为准。

## 未验证与后续

- 本次不包含荣耀200 Pro/其他品牌真机、Android9设备、完整TalkBack朗读、横屏全场景、性能/一周自用或正式长期签名覆盖升级；模拟器可用性不等同真机验收。
- 主管从接受基线查看完整diff；重点复核并排卡与底部2×2入口、80%/120%阈值及文案、工作不黑屏、两项目标独立、系统大字体与键盘截图，然后正常合入。版本递增、正式签名升级及发布仍由主管安排。
