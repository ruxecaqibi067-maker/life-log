# 随手记 · Android 生活记录

随手记录饮食、作息和训练，按日期回顾。文字或一张照片即可记饮食，点一下记录就寝和起床，训练可以只记做了什么，也可以细记重量和次数。

面向 **Android 9 及以上手机，不限品牌**，离线使用，无需账号或Google服务。荣耀200 Pro优先实测；支持范围与证据见[兼容性说明](docs/COMPATIBILITY.md)。

## 下载与更新

- [下载随手记 0.1.4 安装包](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.4-test.1/suishouji-0.1.4.apk)
- [版本页与校验文件](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.4-test.1)
- [安装与数据保留](docs/INSTALL.md)、[手机测试清单](docs/testing/ANDROID_PHONE_TEST.md)

版本0.1.4 / versionCode5，沿用长期签名。旧版先导出备份，再直接覆盖安装，保留记录。App暂无自动检查更新或提醒。GitHub附件使用拼音名`suishouji-0.1.4.apk`，安装后显示「随手记」。

## 本版变化

- 首页显示每日睡眠时长目标、已记录时长及扇形进度；目标可修改，无记录与进行中分别显示。
- 睡眠卡片可以查看/补录，训练记录入口增加明显按钮提示。
- 纯黑睡前页可直接点击“起床了”，成功保存后返回首页并刷新统计；返回键仍只退出睡前页。
- 数据库和备份升为v4，读取兼容v1–v3。工作时间数据层已完成，**工作按钮、工作统计卡片和时间线待T21接入**。

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
| [主管进度板](docs/STATUS.md)、[任务索引](docs/TASKS.md) | 已接受任务与下一步T21 |
| [本版整合验收报告](docs/releases/0.1.4.md) | 来源、测试、同签名升级和限制 |
| [产品范围](docs/V1_SPEC.md)、[技术路线](docs/TECHNICAL_ROADMAP.md) | 行为与实现契约 |
| [协作方法](docs/WORKFLOW.md)、[发包步骤](docs/testing/RELEASE.md) | 实现、交接与发布 |
| [历史档案](docs/archive/README.md) | 已完成任务及原始交接 |

当前任务在`docs/tasks/`；T01–T09已合并归档，原编号保留。代码、文档、Wrapper和历次schema进入Git，APK通过Release下载；签名密钥、照片、备份、日志和缓存不进入源码。

更新：2026-10-25。主机287项检查通过，Lint无错误；设备及升级结果以本版报告为准，跨品牌实机、真实性能及长期使用仍待补齐。
