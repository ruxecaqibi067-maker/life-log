# 项目执行约定

## 产品与当前阶段

- 产品名「随手记」，面向Android9及以上手机，本地离线；荣耀200 Pro优先真机验证。
- 本轮完整版本0.1.4 / versionCode6，Room/备份v4。T01–T21实现已接受；旧test.1/code5是T21接入前候选。最新结论以`docs/STATUS.md`、`docs/releases/0.1.4.md`及实际Git祖先为准。
- T19交付`8955731`、T06黑屏起床修复`83afe9a`、T20数据`806dbe4`已正常整合。T21交付`32dfba7`（实现`f500219`）已整合`ea00652`，四类时间线、双卡与工作页面已可用；后续从最新main核对祖先并使用真实接口；开始工作留在普通首页，不进入睡前黑屏。
- 用户指定主管对话负责规划、整合、验收与发布，实现对话执行各包。新用户指令优先；不要额外制造审批步骤。

## 先读取

1. `docs/STATUS.md`及本任务`docs/tasks/Txx.md`。
2. `docs/WORKFLOW.md`、相关产品/技术章节与`docs/README.md`导航。
3. 直接前置交接：已完成任务在`docs/archive/handoffs/`，具体文件见`docs/TASKS.md`，然后读取相关代码。

T01–T09任务已合并到`docs/archive/tasks/T01-T09-基础功能任务.md`，T10–T21原任务书在同一archive/tasks目录。历史提示词不代表当前待执行要求。新任务仍在docs/tasks，新交接按`docs/handoffs/TEMPLATE-交接模板.md`建立。

## 实现边界

- 单app模块，Kotlin、Compose、Room，手动AppContainer；无服务器和重型架构。
- Room是记录唯一来源，AppSettings保存可备份偏好；UI不直接写DAO或文件。
- 时间使用完整时间点和时区；照片正式保存到应用files，不依赖临时URI或cache。
- 饮食文字/照片任一即可，运动数值选填；缺记录不能解释为零睡眠、未吃饭或没运动。
- 自定义项目、历史名称/类型快照都要保留、备份。睡眠与工作独立，汇总沿用结束日整段归档；进行中不计入完成量。
- 不加非必要权限、云同步、广告、统计、GPS、AI或超出任务的功能。
- 照片、真实备份、密钥、密码、设备序列号及完整生活记录不提交Git。APK/日志/截图放忽略的artifacts，通过Release交付APK。

## 多对话与Git

- 开工检查Git状态、分支及前置实际提交；一个目录一个写入对话，并行必须独立worktree。不自动创建用户对话。
- 从最新已接受main建立任务分支；先查已有工作，保留他人未提交内容。
- 正常合并，不强制回退、不重写已交付提交、不删除他人工作树。共享schema/AppContainer/导航变更写清交接。
- 实现对话只维护自己的新交接，中央STATUS由主管汇总；提交包含实现、必要测试、schema和交接。

## 验证

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
# 仅在隔离包或明确安排的设备上执行：
.\gradlew.bat :app:connectedDebugAndroidTest
```

为时间规则、数据库、照片一致性和恢复失败路径做有意义的检查，不为纯文案/布局写镜像测试。记录命令、实际结果、证据、未测项；构建、模拟器、真机结论分开。测试用虚构数据和隔离包，不覆盖用户正式安装。发包沿用长期签名并递增版本，按`docs/testing/RELEASE.md`执行。
