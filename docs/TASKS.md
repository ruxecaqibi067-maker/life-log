# 任务索引

**T01–T21及2026-10-26两份增补均已接受并整合**，当前没有待实施的已分派任务。主机/API34及正式签名升级证据与真机待测项见[主管进度板](STATUS.md)及[完整0.1.4报告](releases/0.1.4.md)。后续根据实际手机反馈分派新任务。

新增运动录入/统一滚轮与首页并排双卡交付合并为[0.1.5本地测试说明](releases/0.1.5-local.md)，源提交、数据和验证范围集中记录。

## 已完成任务目录

T01–T09 的九份任务书已合成一份档案，编号和原文保留；其余完成任务及作者交接归入archive，避免与当前任务混在一起。已交付任务不重新执行。

| 编号 | 工作内容 | 接受来源 | 原始任务 | 交接 |
| --- | --- | --- | --- | --- |
| T01 | 开发环境与空工程 | `87fc4c9` | [任务](archive/tasks/T01-T09-基础功能任务.md#t01) | [交接](archive/handoffs/T01-开发环境与空工程.md) |
| T02 | 数据模型与数据库 | `11f773d` | [任务](archive/tasks/T01-T09-基础功能任务.md#t02) | [交接](archive/handoffs/T02-数据模型与数据库.md) |
| T03 | 主题与页面导航 | `8b1902d` | [任务](archive/tasks/T01-T09-基础功能任务.md#t03) | [交接](archive/handoffs/T03-主题与页面导航.md) |
| T04 | 照片拍摄与存储 | `5e1bdf0` | [任务](archive/tasks/T01-T09-基础功能任务.md#t04) | [交接](archive/handoffs/T04-照片拍摄与存储.md) |
| T05 | 饮食与饱腹感 | `28b9b78` | [任务](archive/tasks/T01-T09-基础功能任务.md#t05) | [交接](archive/handoffs/T05-饮食记录.md) |
| T06 | 作息、滚轮与睡前页 | `83afe9a` | [任务](archive/tasks/T01-T09-基础功能任务.md#t06) | [交接](archive/handoffs/T06-WAKE-FIX-睡前页起床与排版.md) |
| T07 | 内置与自定义运动 | `de0cec4` | [任务](archive/tasks/T01-T09-基础功能任务.md#t07) | [交接](archive/handoffs/T07-内置与自定义运动管理.md) |
| T08 | 多项目训练 | `a0622a4` | [任务](archive/tasks/T01-T09-基础功能任务.md#t08) | [交接](archive/handoffs/T08-多项目训练记录.md) |
| T09 | 力量逐组与复制 | `edb9bc0` | [任务](archive/tasks/T01-T09-基础功能任务.md#t09) | [交接](archive/handoffs/T09-力量训练逐组记录.md) |
| T10 | 今天与按日回顾 | `6eafcfc` | [任务](archive/tasks/T10.md) | [交接](archive/handoffs/T10-今日时间线与按日回顾.md) |
| T11 | 备份导出 | `153cb40` | [任务](archive/tasks/T11.md) | [交接](archive/handoffs/T11-备份导出.md) |
| T12 | 备份导入预检 | `4d1b6a5` | [任务](archive/tasks/T12.md) | [交接](archive/handoffs/T12-备份导入预检.md) |
| T13 | 恢复及失败回滚 | `cb9d626` | [任务](archive/tasks/T13.md) | [交接](archive/handoffs/T13-数据恢复与失败回滚.md) |
| T14 | 备份恢复界面 | `9770bac` | [任务](archive/tasks/T14.md) | [交接](archive/handoffs/T14-备份恢复设置界面.md) |
| T15 | 全链质量与候选包 | `bd8ce7a` | [任务](archive/tasks/T15.md) | [交接](archive/handoffs/T15-全链验收与测试安装包.md) |
| T16 | 签名与覆盖升级 | `c6f784e` | [任务](archive/tasks/T16.md) | [交接](archive/handoffs/T16-正式签名与覆盖升级.md) |
| T17 | 整体视觉统一 | `a9d24d5` | [任务](archive/tasks/T17.md) | [交接](archive/handoffs/T17-整体视觉风格优化与统一.md) |
| T18 | 睡眠目标与今日汇总 | `6d8d54d` | [任务](archive/tasks/T18.md) | [交接](archive/handoffs/T18-睡眠目标与今日汇总.md) |
| T19 | 睡眠扇形卡片与训练入口 | `8955731` | [任务](archive/tasks/T19.md) | [交接](archive/handoffs/T19-首页睡眠图表与入口优化.md) |
| T20 | 工作记录、目标与备份数据 | `806dbe4` | [任务](archive/tasks/T20.md) | [交接](archive/handoffs/T20-工作时段与统计数据.md) |
| T21 | 工作界面、双卡与四类时间线 | `32dfba7` | [任务](archive/tasks/T21.md) | [交接](archive/handoffs/T21-工作界面与首页双统计.md) |

## 使用规则

1. 读取本任务、直接前置交接、[协作流程](WORKFLOW.md) 和实际代码；无需阅读全部历史档案。
2. 一个目录一个写入对话，独立分支/worktree；不要覆盖其他对话未提交修改。
3. 实现对话提交实现、必要检查和自己的交接；中央STATUS由主管维护。
4. 主管核对来源、正常整合、回归、同签名升级后统一发布。

原始拆分和工作量估计保留在[历史任务](archive/tasks)、[WORKFLOW](WORKFLOW.md)。新任务按实际前置分派，不以编号顺序自动开启并行修改。
