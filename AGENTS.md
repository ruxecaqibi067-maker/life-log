# 项目执行约定

## 产品与当前阶段

- 产品名「随手记」，面向Android9及以上手机，本地离线；荣耀200 Pro优先真机验证。
- 当前发布0.1.6 / versionCode9，Room/备份v6；在code8主线`559367b`上接受作息按日/七日分析补丁`625910a`。本轮用户明确要求更新GitHub APK，按授权发布v0.1.6；以`docs/STATUS.md`、`docs/releases/0.1.6.md`及实际Git祖先为准。后续仍默认不上传APK，除非用户明确要求。
- 前置0.1.5完整主线`ec005a5`，包含既有饮食/睡眠/工作/训练、黑屏起床修复、批量力量/感受、公共滚轮和首页并排双卡。开始工作留普通首页，不进入睡前黑屏。历史交付不重新实现。
- 用户指定主管对话负责规划、整合、验收与发布，实现对话执行各包。新用户指令优先；不要额外制造审批步骤。

## 先读取

1. `docs/STATUS.md`及本任务`docs/tasks/Txx.md`。
2. `docs/WORKFLOW.md`、相关产品/技术章节与`docs/README.md`导航。
3. 直接前置交接：已完成任务在`docs/archive/handoffs/`，具体文件见`docs/TASKS.md`，然后读取相关代码。

T01–T09任务已合并到`docs/archive/tasks/T01-T09-基础功能任务.md`，T10–T24原任务书在同一archive/tasks目录；T22–T24交接已汇总到0.1.6报告。历史提示词不代表当前待执行要求。新任务仍在docs/tasks，新交接按`docs/handoffs/TEMPLATE-交接模板.md`建立。

## 实现边界

- 单app模块，Kotlin、Compose、Room，手动AppContainer；无服务器和重型架构。
- Room是记录唯一来源，AppSettings保存可备份偏好；UI不直接写DAO或文件。
- 时间使用完整时间点和时区；照片正式保存到应用files，不依赖临时URI或cache。
- 所有新建/编辑/筛选的日期时间默认沿用`ui/components/RecordDateTimePicker`及`RecordDatePicker`原生滚轮（年月日、24小时制时分）；取消不写入，未改端点保留原精度。禁止自行引入文本时间、日历盘或另一套时间控件。纯时长/频率数值不属于钟点。
- 待办/习惯使用本地日历日期和可选分钟，不把没有时区的日期伪装为UTC时间点。习惯固定21个自然日（开始日为第1天），第22天隐藏并停止生成，旧待办/完成历史保留；漏做或暂停不延长，不按21次完成计算。
- 饮食文字/照片任一即可，运动数值选填；缺记录不能解释为零睡眠、未吃饭或没运动。
- 自定义项目、历史名称/类型快照都要保留、备份。睡眠与工作独立，汇总沿用结束日整段归档；进行中不计入完成量。
- 不加非必要权限、云同步、广告、统计、GPS、AI或超出任务的功能。
- 照片、真实备份、密钥、密码、设备序列号及完整生活记录不提交Git。APK/日志/截图放忽略的artifacts；本地APK固定交付到`artifacts/phone-test/随手记.apk`。**以后默认只推送源码/文档，只有用户明确要求更新GitHub安装包时才创建Release或上传APK**；不能把“整合/更新/推送”自行解释为发包授权。

## 多对话与Git

- 开工检查Git状态、分支及前置实际提交；一个目录一个写入对话，并行必须独立worktree。不自动创建用户对话。
- 从最新已接受main建立任务分支；先查已有工作，保留他人未提交内容。
- 正常合并，不强制回退、不重写已交付提交、不删除他人工作树。共享schema/AppContainer/导航变更写清交接。
- 实现对话只维护自己的新交接，中央STATUS由主管汇总；提交包含实现、必要测试、schema和交接。

## 验证

本轮T25–T27用户明确要求减少子agent设备重复：实现agent仅做必要编译/定向主机检查，不自行启动模拟器、连接手机或执行设备测试；由主管整合后统一一轮隔离设备验证。失败或实际修改只重跑受影响项，不重复全套。详见`docs/tasks/FEEDBACK-2026-10-26-第二轮界面与饮食照片.md`。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
# 仅在隔离包或明确安排的设备上执行：
.\gradlew.bat :app:connectedDebugAndroidTest
```

为时间规则、数据库、照片一致性和恢复失败路径做有意义的检查，不为纯文案/布局写镜像测试。记录命令、实际结果、证据、未测项；构建、模拟器、真机结论分开。测试用虚构数据和隔离包，不覆盖用户正式安装。发包沿用长期签名并递增版本，按`docs/testing/RELEASE.md`执行。
