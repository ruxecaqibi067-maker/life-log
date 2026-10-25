# T18 整合与 T19 前置基线

日期：2026-10-25（Asia/Shanghai）；主管维护。

**T18 源码及本包检查已接受，代码整合提交为 `ebc638e`；本轮接受文档随整合分支正常快进到本地main，T19可继续完整接线。** T06黑屏修复另行跟踪，不作为T19的新增前置。

## 来源与范围

- 原主线 `59c1261`；T18 交付 `6d8d54d`，实现 `a639287`。
- 在 `integrate/t18-for-t19` 正常合并，代码整合提交 `ebc638e`；全部任务提交保留，整合后生产代码、脚本与交付分支一致。
- [T18交接](../handoffs/T18-睡眠目标与今日汇总.md) 已核对；本包未修改首页、业务页面、导航、MainActivity、App版本、签名、权限或依赖，旧schema1/2保持。

## 检查

| 范围 | 结果及来源 |
| --- | --- |
| 作者完整主机检查 | 253项、37类，0失败/错误/跳过；构建与测试APK通过，Lint 0错误/13警告。已核对主仓库留存摘要及日志 |
| 作者API34迁移检查 | 5项、0失败/错误/跳过；包含完整schema2→3和1→2→3、旧字段及引用核对、新目标保存重开。已核对JUnit XML及构建日志 |
| 主管合并后定向回归 | SleepSummaryRepositoryTest、SleepGoalBackupTest、RepositoryTest，共39项，0失败/错误/跳过；debug构建、Lint和设备测试APK通过；Lint 0错误/13警告 |
| 主管备份脚本回归 | `python scripts/test_verify_backup.py -v`，4项通过 |

作者证据在主仓库忽略目录 `artifacts/t18-20261025/`；主管检查在 `artifacts/supervisor-t18-t19/`。区分作者完整套件、主管定向检查和设备证据，不把它们合并成同一次测试数量。本轮没有重复运行作者5项迁移设备用例；接受其已核对的原始证据，后续正式包覆盖升级另行验证。

## T19 可复用接口

```kotlin
container.sleepSummary.observe(date) // Flow<DataResult<SleepSummary>>，date来自HomeViewModel.today
container.settings.targetSleepMinutes() // Flow<Int?>
container.settings.setTargetSleepMinutes(minutes) // suspend；DataResult<AppSettings>
```

`SleepSummary` 提供目标分钟、已完成段数、总毫秒数及全局进行中标记。没有完成记录为null，读取错误为Failure；失败后重新收集以重试。目标1–1440分钟，null表示未设；只有用户确认且保存Success后生效。跨夜与午睡沿用原归档和真实时间差，完整规则见交接。

开发源码的Room/备份格式升为3，可读旧v1/v2；当前公开0.1.3仍使用v2，没有因本次源码整合自动升级手机上的安装包。正式签名覆盖升级及新包发布在后续统一验证。

## 与 T06 黑屏修复并行

- 用户另行交给T06的修复位于独立 `fix/bedtime-wake-button` 分支和 `.worktrees/life-log-sleep-wake`，主管核对时仍有未提交修改；本轮不据此宣称交付或合入。
- 已见改动集中于 `BedtimeScreen.kt`、`SleepScreen.kt`、`LifelogNavHost.kt` 和相关测试，未改T18字段、数据库、AppContainer或汇总接口；不构成T19数据前置。
- 与T19可能重叠的文件是 `SleepScreen.kt`、`LifelogNavHost.kt`。T19按自己的分支继续首页接线，保留原作息反馈；后续合并按函数和参数保留双方功能，不整文件覆盖。
- 黑屏修复当前意图包括直接“起床了”、同一SleepViewModel完成记录、Room确认结束后返回、失败重试及共用长区间确认。最终行为以T06正式交接为准。T19不得将旧版“黑页只能返回首页起床”当作不可修改要求。
- T19完成后，主管统一整合已交付的黑屏修复，并回归“黑页起床→首页已记录时长刷新”、进行中/撤销、长区间取消确认及返回栈，再生成新安装包。

T19使用自己的工作目录，保留现有准备文件，通过正常合并主管接受后的本地main更新基线；中央STATUS继续由主管维护。
