# 安装、更新与备份

当前公开版本为随手记 **0.1.7 / versionCode10**，包名`com.personal.lifelog`，支持Android9及以上。[下载APK](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.7/suishouji-0.1.7.apk)，[版本页和校验](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.7)。

本次按用户选择发布已构建的原签名code10，固定本地APK与公开下载一致。main中工作七日分析和自动JSON属于后续code11候选。

## 覆盖更新

1. 在旧App设置中导出完整ZIP备份，确认成功并保留文件。
2. 下载APK或复制电脑`artifacts/phone-test/随手记.apk`到手机，在文件管理器中打开并完成覆盖安装。
3. 检查旧记录、照片、目标及待办的完成状态。原长期签名和包名继续沿用，公开code9可覆盖更新到code10，此前本地code10与本次公开包相同。
4. 检查首页卡片与折叠入口、运动选择及同餐多照片。操作清单见[手机测试](testing/ANDROID_PHONE_TEST.md)。

覆盖安装保留原存储。若系统提示签名冲突，请保留旧安装及数据，反馈原App来源。App暂无自动更新提醒，后续下载新版后覆盖安装。

## 后续候选的自动记录JSON

code11候选默认开启，将记录快照写到手机`Download/lifelog/latest.json`，在设置中可关闭。Android9需要允许存储权限，Android10起使用系统下载目录。JSON包含记录文字和照片引用，完整图片请通过ZIP备份保存。这个共享文件可能被用户授权的其他文件工具读取，关闭后已生成文件由用户自行管理。

## 完整备份与恢复

设置→导出备份→选择保存位置→等待成功。ZIP包含记录、全部照片和设置，格式v7，兼容读取v1–v6。原0.1.6使用v6，无法读取新版v7。

恢复先预检，确认后全量替换记录、照片和设置。旧包原本缺失的新数据会被清空。取消保持当前数据，提交前创建并校验回滚备份，规则见[备份格式](BACKUP_FORMAT.md)。

## 本机构建

干净提交上设置JDK17和Android SDK，沿用仓库外长期签名。

```powershell
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

默认0.1.7/code11，标准产物位于`artifacts/t16/v0.1.7-11/`。固定入口`artifacts/phone-test/随手记.apk`及安装说明记录精确源提交和SHA。[本轮报告](releases/0.1.7.md)记录交付范围，后续远端发包继续按明确授权执行。
