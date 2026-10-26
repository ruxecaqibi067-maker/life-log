# 随手记 · Android 生活记录

随手记录饮食、作息、工作和训练，按日期回顾。文字或一张照片即可记饮食，点一下记录就寝和起床；训练可简单记动作，也可细记重量和次数。每日待办和21天习惯帮助安排今天。

面向 **Android 9及以上手机，不限品牌**，离线使用，无需账号或Google服务。荣耀200 Pro优先实测；支持范围及实际证据见[兼容性说明](docs/COMPATIBILITY.md)。

## 下载与更新

- [下载随手记0.1.6安装包](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.6/suishouji-0.1.6.apk)
- [版本页与SHA校验](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.6)
- [安装、数据保留与备份](docs/INSTALL.md)、[手机测试清单](docs/testing/ANDROID_PHONE_TEST.md)

当前公开下载为 **0.1.6 / versionCode9**，包含最新作息补丁及此前0.1.5、0.1.6本地更新。此前同名0.1.6本地版为code8；本包沿用长期签名并递增内部版本号，可直接覆盖。先在旧App导出备份，再安装新版，不要卸载或清除存储。App暂无自动更新提醒。GitHub文件名为`suishouji-0.1.6.apk`，下载标签和安装后名称为“随手记”。

本机同版入口为`artifacts/phone-test/随手记.apk`。本次远端APK由用户明确授权；**后续默认只更新源码/文档，只有用户明确要求才更新GitHub安装包**。

## 最新本地测试版

源码/本地APK为 **0.1.7 / code10，Room/备份v7**：双卡中央点击、进行中实时扇形、编辑/撤销移入记录页、四快捷入口可折叠；运动选择紧凑且按钮不重叠；同餐最多9张照片，可追加/预览/删单张，旧单图和完整备份兼容。

三包完成后由主管统一API34综合验收，各子agent未运行设备。APK仅在电脑`artifacts/phone-test/随手记.apk`，公开下载仍是上面的0.1.6；[本轮报告](docs/releases/0.1.7-local.md)记录95项定向主机及统一设备证据。新备份7可读旧1–6，旧App不能读7。

## 已公开0.1.6功能

- 睡眠/工作等宽双卡显示独立目标、圆环、完成时长和提示，底部四个快捷入口紧凑常驻；工作开始不黑屏，睡眠保留纯黑时钟和直接起床。
- 作息按日查看，默认今天，可用滚轮选日；列表按起床日期归档，新增当天休息时段分布和近七天时间轴，支持午睡、跨夜、多段及历史补录。
- 今日待办支持手动添加、勾选/撤销、编辑/删除和历史查看；习惯默认每天，可自定义间隔或星期。开始日为第1天，第22天自动隐藏且停止生成，已有待办/完成历史保留。
- 饮食支持文字/照片及“没吃饱”等饱腹感；自定义运动、力量批量/逐组、可选运动感受、四类按日回顾继续可用。所有日期/钟点输入默认公共原生滚轮。
- 完整ZIP备份包含记录、照片、目标、待办/习惯和完成状态，当前格式v6、读取兼容v1–v5；旧App不能读取v6。旧备份全量恢复会清空它原本没有的新数据。

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
| [0.1.7本地报告](docs/releases/0.1.7-local.md) / [公开0.1.6报告](docs/releases/0.1.6.md) | 当前本地验收与公开下载证据 |
| [21天习惯规则](docs/HABIT_RULES.md) | 自动生成、频率、到期与历史 |
| [产品范围](docs/V1_SPEC.md)、[技术路线](docs/TECHNICAL_ROADMAP.md) | 行为与实现契约 |
| [协作方法](docs/WORKFLOW.md)、[发包步骤](docs/testing/RELEASE.md) | 实现、交接与发布 |
| [历史档案](docs/archive/README.md) | 原任务、交接与历史发布证据 |

代码、文档、Wrapper和历次schema进入Git；APK只在授权时作为Release附件，签名密钥、照片、备份、日志和缓存不进入源码。2026-10-26更新；本轮做作息定向检查与签名构建，未重复完整手机矩阵，实体设备仍待实测。
