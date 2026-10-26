# 任务索引

**T01–T24及作息分析补丁已接受；T25–T27正在执行。**作息补丁`625910a`纳入已发布0.1.6/code9，不新增任务编号。最新状态见[主管进度板](STATUS.md)，公开版证据见[0.1.6报告](releases/0.1.6.md)。

## 当前分派

| 编号 | 工作 | 用户反馈 |
| --- | --- | --- |
| [T25](tasks/T25-首页卡片与进行中进度.md) | 首页卡片入口/实时预览、操作迁移、快捷区折叠 | 1–4 |
| [T26](tasks/T26-运动项目紧凑布局.md) | 运动选择紧凑与按钮不重叠 | 5–6 |
| [T27](tasks/T27-饮食多照片全链.md) | 多照片及迁移/备份完整闭环 | 7 |

需求原文汇总和协作边界见[第二轮反馈](tasks/FEEDBACK-2026-10-26-第二轮界面与饮食照片.md)。三个agent不各自重复设备模拟，完成后由主管统一整合/测试；新GitHub APK需另获用户明确要求。

新增运动录入/统一滚轮与首页并排双卡交付合并为[0.1.5本地测试说明](releases/0.1.5-local.md)，源提交、数据和验证范围集中记录。

T22–T24的数据/界面/布局交接已合并到0.1.6报告，临时接口契约移除；源提交中保留原稿。习惯周期见[统一规则](HABIT_RULES.md)。默认仅本地APK交付，只有用户明确要求时才上传GitHub安装包。

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
| T22 | 待办、21天习惯与备份6 | `2e9a7d1` | [任务](archive/tasks/T22-待办与21天习惯数据.md) | [整合报告](releases/0.1.6-local.md) |
| T23 | 清单、习惯与日期界面 | `7997c3c` | [任务](archive/tasks/T23-待办与21天习惯界面.md) | [整合报告](releases/0.1.6-local.md) |
| T24 | 首页紧凑与待办位置 | `3578ef7` | [任务](archive/tasks/T24-首页待办区域与紧凑布局.md) | [整合报告](releases/0.1.6-local.md) |

## 使用规则

1. 读取本任务、直接前置交接、[协作流程](WORKFLOW.md) 和实际代码；无需阅读全部历史档案。
2. 一个目录一个写入对话，独立分支/worktree；不要覆盖其他对话未提交修改。
3. 实现对话提交实现、必要检查和自己的交接；中央STATUS由主管维护。
4. 主管核对来源、正常整合、回归、同签名升级后统一发布。

原始拆分和工作量估计保留在[历史任务](archive/tasks)、[WORKFLOW](WORKFLOW.md)。新任务按实际前置分派，不以编号顺序自动开启并行修改。
