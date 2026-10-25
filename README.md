# 随手记 · Android 生活记录

随手记录饮食、作息、工作和训练，按日期回顾。文字或一张照片即可记饮食，点一下记录就寝和起床，训练可以只记做了什么，也可以细记重量和次数；每日待办和21天习惯帮助安排今天。

面向 **Android 9 及以上手机，不限品牌**，离线使用，无需账号或Google服务。荣耀200 Pro优先实测；支持范围与证据见[兼容性说明](docs/COMPATIBILITY.md)。

## 当前本地测试版

源码已整合至 **0.1.6 / code8**：双卡下方每日待办、手动添加与21天习惯、完成/撤销和历史查看；两卡精简、底部快捷区更紧凑。习惯默认每天，可自定义间隔或星期；第22天从进行中隐藏、停止生成，保留已有待办与完成历史。Room/备份v6，读取兼容v1–v5。测试说明见[本轮记录](docs/releases/0.1.6-local.md)。

按用户要求，0.1.6安装包仅保存在本机`artifacts/phone-test/随手记.apk`，没有上传GitHub。**以后默认只更新源码和文档，只有用户明确要求才更新GitHub安装包**；下面的公开下载仍为0.1.4。

## 公开下载与更新

- [下载随手记 0.1.4 安装包](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.4/suishouji-0.1.4.apk)
- [版本页与校验文件](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.4)
- [安装与数据保留](docs/INSTALL.md)、[手机测试清单](docs/testing/ANDROID_PHONE_TEST.md)

完整版本0.1.4 / versionCode6，沿用长期签名；可覆盖之前0.1.4/code5候选。旧版先导出备份，再直接覆盖安装，保留记录。App暂无自动检查更新或提醒。GitHub附件使用拼音名`suishouji-0.1.4.apk`，安装后显示「随手记」。

## 已公开0.1.4功能

- 首页睡眠与工作双卡显示各自每日目标、已记录时长及扇形进度；目标可修改，无记录与进行中分别显示。
- 睡眠卡片可以查看/补录，训练记录入口增加明显按钮提示。
- 纯黑睡前页可直接点击“起床了”，成功保存后返回首页并刷新统计；返回键仍只退出睡前页。
- 工作卡片支持开始/结束，保持普通首页；可补录、编辑、删除和撤销，按日回顾纳入工作，备份预览包含工作及两项目标。数据库/备份仍为v4，读取兼容v1–v3。

饮食照片、自定义饱腹感、作息滚轮、训练逐组、自定义动作、按日回顾及完整ZIP备份恢复继续可用。记录的睡眠时长来自卧床时段；进行中不计入已完成统计。无服务器、广告、云同步、GPS或热量估算。

## 开发与资料

Kotlin + Compose + Room，单app模块，手动AppContainer，JDK17、项目Gradle Wrapper；minSdk28、targetSdk36。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

| 入口 | 用途 |
| --- | --- |
| [文档导航与文件夹说明](docs/README.md) | 看懂工程、找到相关文件 |
| [主管进度板](docs/STATUS.md)、[任务索引](docs/TASKS.md) | 已接受任务与后续安排 |
| [本地0.1.6说明](docs/releases/0.1.6-local.md) / [公开0.1.4报告](docs/releases/0.1.4.md) | 当前本地验证与历史发布证据 |
| [21天习惯规则](docs/HABIT_RULES.md) | 自动生成、频率、到期与历史保留 |
| [产品范围](docs/V1_SPEC.md)、[技术路线](docs/TECHNICAL_ROADMAP.md) | 行为与实现契约 |
| [协作方法](docs/WORKFLOW.md)、[发包步骤](docs/testing/RELEASE.md) | 实现、交接与发布 |
| [历史档案](docs/archive/README.md) | 已完成任务及原始交接 |

T01–T24实现与整合状态见进度板；历史任务和原交接保留来源，后续任务入口为`docs/tasks/`。代码、文档、Wrapper和历次schema进入Git；APK默认本地交付，签名密钥、照片、备份、日志和缓存不进入源码。

更新：2026-10-26。本轮采用定向检查及签名构建，实际结果见0.1.6说明；已发布0.1.4的全套及升级证据保留在对应历史报告。
