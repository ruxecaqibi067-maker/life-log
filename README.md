# 生活随记 · Android 第一版

给自己使用的轻量生活记录 App，目标手机为荣耀 200 Pro。饮食、作息、运动共用时间线，以随手记录为核心。

**当前：T01–T16 实现已整合，准备荣耀实机测试；真机、性能与一周自用验收仍待完成。**

## 下载与测试

- [下载页：0.1.1 实机测试候选](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.1-test.1)
- [直接下载 APK](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.1-test.1/life-log-0.1.1-2-release.apk)
- [荣耀首次测试清单](docs/testing/HONOR_PHONE_TEST.md)
- [安装、更新与备份说明](docs/INSTALL.md)

在手机打开 APK 安装即可，不需要开发者模式。已有旧版先导出备份；签名冲突时保留原安装和数据并反馈。此包为长期签名 release 构建，GitHub 标为预发布测试候选。

## 已实现范围

- 饮食：文字或一张照片，选填饱腹感；详情、编辑、删除。
- 作息：就寝/起床、未结束状态、跨日、午睡、补录和修改；记录卧床时段。
- 训练：跑步、力量和其他动作，自定义项目、一场多个动作、独立重量/次数组与复制。
- 回顾：今天的统一时间线、按日查看、日期选择及记录跳转。
- 数据：本地 Room；完整 ZIP 导出、只读预检、确认全量恢复、回滚包和启动协调。
- 发布准备：固定包名/长期签名，模拟器同签名覆盖升级有数据与照片比对证据。

作息补录字段偏技术化，荣耀系统交互和性能待测。桌面组件、提醒、云同步、GPS、AI、热量估算、训练计划不在首版范围。

## 项目资料

| 文档 | 用途 |
| --- | --- |
| [主管进度板](docs/STATUS.md) | 当前接受状态与下一步 |
| [整体验收报告](docs/reviews/V1_PHONE_CANDIDATE.md) | 本轮整合、检查及限制 |
| [第一版范围](docs/V1_SPEC.md) | 产品需求与边界 |
| [技术路线](docs/TECHNICAL_ROADMAP.md) | 架构、照片、时间与备份 |
| [任务总表](docs/TASKS.md) | 任务包和依赖 |
| [协作方法](docs/WORKFLOW.md) | Git、worktree 与交接 |
| [完整验收矩阵](docs/ACCEPTANCE.md) | 真机、故障和性能 |
| [签名/升级证据](docs/testing/T16-RELEASE.md) | 覆盖升级实验和限制 |

## 开发与构建

Kotlin + Compose Material3 + Room，单 app 模块，手动 AppContainer。JDK 17 和 SDK 见 [ENVIRONMENT.md](docs/ENVIRONMENT.md)。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

主机 212 项测试通过；三种构建 Lint 各为 0 错误、13 警告。原始证据在被忽略的 `artifacts/supervisor-v1/`；GitHub Release 仅附安装包及非敏感说明/校验文件。

代码、文档、Wrapper 和 Room schema 纳入 Git；照片、用户备份、私钥和密码不入 Git。一个工作目录一个写入对话，并行使用独立 worktree。开工先读 [AGENTS.md](AGENTS.md)。

更新：2026-10-23（Asia/Shanghai）。
