# 文档导航

本轮版本：随手记 0.1.4 / versionCode 5。当前状态以 [主管进度板](STATUS.md) 为准，检查结果见 [本版报告](releases/0.1.4.md)。

| 你想了解什么 | 从这里开始 |
| --- | --- |
| 下载、安装、更新后保留数据 | [安装与更新](INSTALL.md) |
| 手机上应该测哪些功能 | [手机测试清单](testing/ANDROID_PHONE_TEST.md)、[兼容性](COMPATIBILITY.md) |
| 做完了哪些、接下来做什么 | [主管进度板](STATUS.md)、[任务索引](TASKS.md) |
| 当前可以交给实现对话的任务 | [T21 工作界面与首页双统计](tasks/T21.md) |
| 产品与代码逻辑 | [产品范围](V1_SPEC.md)、[技术路线](TECHNICAL_ROADMAP.md) |
| 配色、字体、按钮和首页卡片规则 | [视觉规范](UI_STYLE_GUIDE.md) |
| 数据备份的版本、字段和兼容 | [备份格式](BACKUP_FORMAT.md) |
| 开发、测试和签名发包 | [开发环境](ENVIRONMENT.md)、[协作流程](WORKFLOW.md)、[发包步骤](testing/RELEASE.md) |
| 历史任务、作者交接与旧验收 | [历史档案](archive/README.md)、[历次发布](releases/) |

## 文件夹怎么看

```text
life-log-android/
  app/src/main/       真正安装到手机里的代码和资源
  app/src/test/       在电脑上运行的业务、数据和界面检查
  app/src/androidTest/  在模拟器或手机上运行的检查
  app/schemas/        数据库各历史版本；升级和验证需要，必须保留
  docs/tasks/         当前待实现任务；现在只有 T21
  docs/handoffs/      新交接入口和模板；已接受的交接进入历史档案
  docs/releases/     各安装包的整合验收报告
  docs/archive/      已完成任务、原始交接、历史整合及证据说明
  scripts/           构建签名包、核对备份等辅助工具
  artifacts/         本机生成的 APK、日志、截图和虚构测试数据，不上传 Git
```

代码通常按“页面 → ViewModel → Repository → Room数据库”流动：页面负责显示与点击，ViewModel处理状态，Repository执行校验和保存，Room长期保存记录。`MainActivity.kt` 是入口，`AppContainer.kt` 装配这些服务，`ui/navigation/` 管理页面跳转；`feature/home/` 是今天，`feature/meal/`、`sleep/`、`workout/` 分别负责饮食、作息和训练，工作数据位于 `data/repository/Work*`，工作页面待 T21 接入。

主管验收先看“提交已合入、检查有证据、安装包版本和来源一致”，再看手机操作。文件名叫“完成”不能替代这些证据。历史任务不需要重新执行，也无需让每个新对话读取全部档案。
