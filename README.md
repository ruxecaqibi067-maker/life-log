# 随手记 · Android 生活记录

随手记录饮食、作息、工作和训练，按日期回顾。文字或一张照片即可记饮食，点一下记录就寝和起床；训练可简单记动作，也可细记重量和次数。每日待办和21天习惯帮助安排今天。

面向 **Android 9及以上手机，不限品牌**，离线使用，无需账号或Google服务。荣耀200 Pro优先实测；支持范围及实际证据见[兼容性说明](docs/COMPATIBILITY.md)。

## 下载与更新

- [下载随手记0.1.7安装包](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.7/suishouji-0.1.7.apk)
- [版本页与SHA校验](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.7)
- [安装与数据保留](docs/INSTALL.md)、[手机测试清单](docs/testing/ANDROID_PHONE_TEST.md)

当前公开版本为 **0.1.7 / versionCode10**，发布标签指向实际构建提交`cf14590`。沿用原包名和长期签名，可从0.1.6/code9覆盖更新。先在旧App导出完整ZIP备份，再安装新版。电脑固定入口为`artifacts/phone-test/随手记.apk`。

## 0.1.7公开更新

- 首页双卡中央点击进入记录，进行中显示时长和扇形预览，编辑/撤销集中在记录页。
- 四个快捷入口默认展开，可收起。
- 运动列表紧凑排列，筛选与新增按钮自适应布局。
- 同餐最多9张照片，可追加、预览和删除单张。完整ZIP保留全部照片与顺序。
- 原作息按日/七日分析、工作计时、饮食/训练、目标、待办和21天习惯继续可用。Room/备份v7读取兼容v1–v6。

[发布报告](docs/releases/0.1.7.md)与[原本地验收](docs/releases/0.1.7-local.md)记录检查范围。App暂无自动更新提醒，后续GitHub安装包发布继续按用户明确要求执行。

## 后续源码候选

main包含code11候选，增加工作按日/七日分析和自动写入`Download/lifelog/latest.json`，其默认开关及权限规则见[候选报告](docs/releases/0.1.7-code11-candidate.md)。该候选待恢复原长期签名后发包。本次按用户选择发布现成的已签名code10。

## 既有功能

- 睡眠/工作等宽双卡显示独立目标、圆环、完成时长和提示，底部四个快捷入口紧凑排列；工作开始不黑屏，睡眠保留纯黑时钟和直接起床。
- 作息按日查看，默认今天，可用滚轮选日；列表按起床日期归档，新增当天休息时段分布和近七天时间轴，支持午睡、跨夜、多段及历史补录。
- 今日待办支持手动添加、勾选/撤销、编辑/删除和历史查看；习惯默认每天，可自定义间隔或星期。开始日为第1天，第22天自动隐藏且停止生成，已有待办/完成历史保留。
- 饮食支持文字/照片及“没吃饱”等饱腹感；自定义运动、力量批量/逐组、可选运动感受、四类按日回顾继续可用。所有日期/钟点输入默认公共原生滚轮。
- 完整ZIP备份包含记录、照片、目标、待办/习惯和完成状态，当前格式v7、读取兼容v1–v6；旧App不能读取v7。旧备份全量恢复会清空它原本没有的新数据。

记录时长来自就寝时段，不代表精确入睡或睡眠质量。图表按手机当前时区的自然日拆分展示，首页完成时长仍整段归结束日；进行中不计完成，空白不等于没有休息。无服务器、广告、云同步、GPS或热量估算。

## 开发与资料

Kotlin + Compose + Room，单app模块，手动AppContainer，JDK17、项目Gradle Wrapper；minSdk28、targetSdk36。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

| 入口 | 用途 |
| --- | --- |
| [文档导航与文件夹说明](docs/README.md) | 看懂工程、找到相关文件 |
| [主管进度板](docs/STATUS.md)、[任务索引](docs/TASKS.md) | 接受状态与后续安排 |
| [0.1.7发布报告](docs/releases/0.1.7.md) / [前次本地报告](docs/releases/0.1.7-local.md) | 当前本地验收与公开下载证据 |
| [21天习惯规则](docs/HABIT_RULES.md) | 自动生成、频率、到期与历史 |
| [产品范围](docs/V1_SPEC.md)、[技术路线](docs/TECHNICAL_ROADMAP.md) | 行为与实现契约 |
| [协作方法](docs/WORKFLOW.md)、[发包步骤](docs/testing/RELEASE.md) | 实现、交接与发布 |
| [历史档案](docs/archive/README.md) | 原任务、交接与历史发布证据 |

代码、文档、Wrapper和历次schema进入Git；APK只在授权时作为Release附件，签名密钥、照片、备份、日志和缓存不进入源码。2026-11-10更新。发布门槛包括定向主机检查、签名构建和统一隔离设备覆盖升级，实体设备继续收集使用反馈。
