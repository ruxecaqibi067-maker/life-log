# 安装、更新与备份

当前版本为随手记 **0.1.7 / versionCode11**，包名`com.personal.lifelog`，支持Android9及以上。[下载code11](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.7-code11/suishouji-0.1.7.apk)，[版本页和校验](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.7-code11)。

## 覆盖更新

1. 在旧App设置中导出完整ZIP备份，确认成功并保留文件。
2. 下载code11或复制电脑`artifacts/phone-test/随手记.apk`到手机，打开并覆盖安装。
3. 检查旧记录、照片、目标和待办完成状态。原签名已经找回并沿用，code10可直接更新到11。
4. 打开工作卡查看按日/七日分析，检查设置中的自动JSON开关和`Download/lifelog/latest.json`。

覆盖安装保留原存储。如果系统提示签名冲突，请保留旧安装和备份，反馈旧包来源。App暂无自动更新提醒。

## 自动记录JSON

默认开启，将记录快照写到手机`Download/lifelog/latest.json`，设置中可关闭。Android9需要存储权限，Android10起使用MediaStore。JSON包含记录文字与照片引用，完整图片通过ZIP备份保存。文件位于共享下载目录，关闭开关后已生成文件由用户自行管理。

## 完整备份与恢复

设置→导出备份→选择保存位置→等待成功。ZIP包含记录、全部照片和设置，格式v7，兼容读取v1–v6。原0.1.6使用v6，无法读取新版v7。

恢复先预检，确认后全量替换记录、照片和设置。旧包原本缺失的新数据会被清空。取消保持当前数据，提交前创建并校验回滚备份，规则见[备份格式](BACKUP_FORMAT.md)。

## 本机构建

干净提交上设置JDK17和Android SDK，沿用仓库外长期签名。

```powershell
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

默认0.1.7/code11，标准产物位于`artifacts/t16/v0.1.7-11/`。固定入口`artifacts/phone-test/随手记.apk`及安装说明记录精确源提交和SHA。[本轮报告](releases/0.1.7.md)记录交付范围，后续远端发包继续按明确授权执行。
