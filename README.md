# 生活随记 · Android 第一版

给自己使用的轻量生活记录 App，目标手机为荣耀 200 Pro。饮食、作息、运动共用一条时间线，以随手记录为核心。

**当前阶段：T01–T07 已整合，主机构建和自动化检查通过；荣耀真机待验证，完整第一版尚未完成。** 已有饮食记录、作息记录、内置和自定义运动项目库；训练记录从 T08 开始实现。

## 从哪里开始

| 文档 | 用途 |
| --- | --- |
| [主管进度板](docs/STATUS.md) | 已接受进度、当前任务和风险 |
| [T01–T07 整体验收报告](docs/reviews/T01-T07_INTEGRATION.md) | 整合来源、修复、检查证据和未验证项 |
| [第一版范围](docs/V1_SPEC.md) | 需要实现什么、交互规则、明确推迟什么 |
| [技术路线](docs/TECHNICAL_ROADMAP.md) | 架构、数据模型、照片、作息、备份和发布方案 |
| [对话协作与能力估算](docs/WORKFLOW.md) | 任务粒度、Git 流程、交接方式 |
| [任务总表](docs/TASKS.md) | 16 个任务、依赖、任务包入口 |
| [验收标准](docs/ACCEPTANCE.md) | 数据、故障、性能与荣耀真机检查 |
| [交接模板](docs/handoffs/TEMPLATE.md) | 实现对话向主管回报的格式 |

下一包为 [T08：多项目训练基础记录](docs/tasks/T08.md)，从最新已接受的 `main` 建立独立任务分支。开工前先读 [AGENTS.md](AGENTS.md)。

## 第一版边界

- 饮食：一句文字或一张照片即可保存，饱腹感选填。
- 作息：「准备睡了／起床了」，支持跨日、午睡、补录和修改。
- 运动：跑步、力量及其他项目，允许自定义；一次训练可有多个动作，力量可选填每组重量与次数。
- 回顾：今天的时间线、按日期查看、编辑和删除。
- 数据：本地离线保存，完整备份导出和恢复，更新 App 后保留记录。

以上是完整第一版目标，不能把 T07 的项目库当作已完成训练记录。桌面组件、提醒、云同步、GPS 轨迹、AI、热量估算、训练计划和复杂统计放在后续版本。

## 构建与仓库

Kotlin + Compose Material3 + Room，单 `app` 模块，使用 JDK 17 和 Android SDK；本机路径写入被 Git 忽略的 `local.properties`。运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- [GitHub 仓库](https://github.com/ruxecaqibi067-maker/life-log) 的 `main` 为主管接受基线；分支和 worktree 只隔离开发任务，最终仍是同一个 App。
- 一个工作目录同时只允许一个实现对话写入；并行任务使用不同分支与独立 worktree。
- 代码、文档、Gradle Wrapper 和 Room schema 纳入 Git；用户照片、备份、构建产物、签名密钥不纳入 Git。
- 本轮 debug 安装包和原始验收日志留在本地 `artifacts/supervisor-t07/`，详见验收报告；它们不是正式签名发布产物。

进度更新：2026-10-23，Asia/Shanghai。
