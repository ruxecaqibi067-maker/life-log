# 文档导航

当前源码/本地随手记0.1.7 / code10，Room/备份v7；公开安装包仍0.1.6/code9。状态见[主管进度板](STATUS.md)，第二轮反馈和集中验收见[0.1.7本地报告](releases/0.1.7-local.md)，公开下载见[0.1.6报告](releases/0.1.6.md)。本轮不更新GitHub安装包，后续仍须明确要求。

| 你想了解什么 | 从这里开始 |
| --- | --- |
| 下载、安装、更新后保留数据 | [安装与更新](INSTALL.md) |
| 手机上应该测哪些功能 | [手机测试清单](testing/ANDROID_PHONE_TEST.md)、[兼容性](COMPATIBILITY.md) |
| 做完了哪些、接下来做什么 | [主管进度板](STATUS.md)、[任务索引](TASKS.md) |
| 当前任务及已完成交付 | [任务入口](tasks/README.md)、[最新整合报告](releases/0.1.7-local.md) |
| 每日待办、21天自动结束与保留历史 | [习惯规则](HABIT_RULES.md) |
| 产品与代码逻辑 | [产品范围](V1_SPEC.md)、[技术路线](TECHNICAL_ROADMAP.md) |
| 配色、字体、按钮和首页卡片规则 | [视觉规范](UI_STYLE_GUIDE.md) |
| 数据备份的版本、字段和兼容 | [备份格式](BACKUP_FORMAT.md) |
| 开发、测试和签名发包 | [开发环境](ENVIRONMENT.md)、[协作流程](WORKFLOW.md)、[发包步骤](testing/RELEASE.md) |
| 历史任务、作者交接与旧验收 | [历史档案](archive/README.md)、[历次发布](releases) |

## 文件夹怎么看

```text
life-log-android/
  app/src/main/       真正安装到手机里的代码和资源
  app/src/test/       在电脑上运行的业务、数据和界面检查
  app/src/androidTest/  在模拟器或手机上运行的检查
  app/schemas/        数据库各历史版本；升级和验证需要，必须保留
  docs/tasks/         后续任务入口；已接受任务移入archive/tasks
  docs/handoffs/      新交接入口和模板；已接受的交接进入历史档案
  docs/releases/     各安装包的整合验收报告
  docs/archive/      已完成任务、原始交接、历史整合及证据说明
  scripts/           构建签名包、核对备份等辅助工具
  artifacts/         本机生成的 APK、日志、截图和虚构测试数据，不上传 Git
```

代码通常按“页面 → ViewModel → Repository → Room数据库”流动：页面负责显示与点击，ViewModel处理状态，Repository执行校验和保存，Room长期保存记录。`MainActivity.kt` 是入口，`AppContainer.kt` 装配这些服务，`ui/navigation/` 管理页面跳转；`feature/home/` 是今天和双卡，`feature/meal/`、`sleep/`、`workout/`、`work/` 分别负责饮食、作息、训练、工作；`feature/todo/`负责清单/习惯界面，`data/repository/TodoRepository.kt`负责生成/保存，`domain/Todo.kt`和`data/local/entity/`定义待办/习惯数据。

主管验收先看“提交已合入、检查有证据、安装包版本和来源一致”，再看手机操作。文件名叫“完成”不能替代这些证据。历史任务不需要重新执行，也无需让每个新对话读取全部档案。
