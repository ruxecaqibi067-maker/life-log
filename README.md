# 随手记 · Android 生活记录

随手记录饮食、作息和训练，按日期回顾生活。文字或一张照片即可记饮食，点一下记录就寝和起床，训练可以只记做了什么，也可以细记每组重量和次数。

**面向 Android 9 及以上手机，不限品牌；本地离线使用，无需账号或 Google 服务。** 这是根据最低系统版本、标准 Android API、依赖和安装包配置确认的支持范围；跨品牌实机验证仍在进行，详见 [兼容性说明](docs/COMPATIBILITY.md)。

## 下载与安装

- [下载随手记 0.1.2](<https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.2-test.1/%E9%9A%8F%E6%89%8B%E8%AE%B0-0.1.2.apk>)
- [版本下载页与更新说明](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.2-test.1)
- [手机测试清单](docs/testing/ANDROID_PHONE_TEST.md)
- [安装、覆盖更新与备份](docs/INSTALL.md)

下载文件为 **随手记-0.1.2.apk**，安装后显示名称为「随手记」。版本 0.1.2 / versionCode 3，长期签名 release 构建。无需开发者模式；已有旧版请先导出备份，直接覆盖安装，保留原有数据。App 暂无自动检查更新或更新提醒。

## 0.1.2 更新

- 作息时间改为中文日期卡和上下滑动的年月日、时分滚轮，采用 24 小时制。
- 自动读取手机系统时间和时区，界面移除时区、UTC 偏移、T、秒及毫秒输入。
- 保留旧记录的完整时间点；未修改时间时不会截掉秒/毫秒或改变历史归档。
- 日期按月份和闰年校验；保留跨夜、未结束作息、取消、确认和删除。
- 下载名及应用显示名统一为「随手记」；兼顾 Android 9 下 App/系统主题不一致时的滚轮可读性。

## 主要功能

- **饮食**：一句文字或一张照片，饱腹感选填；详情、编辑和删除。
- **作息**：就寝、起床、午睡、多段记录和补录，记录卧床时段。
- **训练**：跑步、力量和其他动作，自定义项目、一场多个动作、逐组记录及复制。
- **回顾**：三类记录共用时间线，支持按日查看和修改。
- **备份**：完整 ZIP 导出、只读预检、预览确认后全量恢复、回滚备份。

无服务器、广告、统计、云同步、GPS、AI 或热量估算。导出 ZIP 未加密，请保存在自己控制的位置。

## 开发与资料

Kotlin + Compose Material3 + Room，单 app 模块，手动 AppContainer。最低 Android 9（API 28），targetSdk 36；工具链为 JDK 17 和项目 Gradle Wrapper。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

| 文档 | 用途 |
| --- | --- |
| [本版整合与发布报告](docs/reviews/V0.1.2_RELEASE.md) | 源提交、检查、升级与限制 |
| [主管进度板](docs/STATUS.md) | 当前接受状态和后续 |
| [兼容性说明](docs/COMPATIBILITY.md) | 系统、品牌、架构与证据边界 |
| [产品范围](docs/V1_SPEC.md) | 首版交互与边界 |
| [技术路线](docs/TECHNICAL_ROADMAP.md) | 数据、照片、时间和备份 |
| [任务索引](docs/TASKS.md) / [协作方法](docs/WORKFLOW.md) | 任务、Git 与交接 |
| [完整验收矩阵](docs/ACCEPTANCE.md) | 功能、数据、真机与性能 |

代码、文档、Wrapper 和 Room schema 纳入 Git；照片、用户备份、私钥和密码不入 Git。一个工作目录一个写入对话，并行使用独立 worktree；开工先读 [AGENTS.md](AGENTS.md)。

更新：2026-10-24（Asia/Shanghai）。当前为实机测试候选；不同品牌、系统相机/文件选择器、真实性能及一周自用仍需反馈。
