# 安装、更新与备份

当前公开下载为随手记0.1.6 / versionCode9，沿用长期签名，面向Android9及以上手机。[下载APK](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.6/suishouji-0.1.6.apk)，[版本页与校验](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.6)。本次用户明确授权公开安装包；以后仍默认不上传，除非明确要求。

## 最新本地0.1.7/code10

本轮7项反馈只更新电脑`artifacts/phone-test/随手记.apk`，不替换GitHub附件。先在旧版导出备份，再复制APK到手机覆盖安装，不卸载/清存储；code9→10，沿用包名和长期签名。检查中央卡片入口/进行中扇形、历史编辑撤销、四入口折叠、运动选择及同餐多照片，见[报告](releases/0.1.7-local.md)。

本地版新备份7包含所有照片/顺序，可读旧1–6；公开0.1.6仍用v6，不能读新7。旧备份全量恢复会清空其原本没有的新数据。本轮设备为隔离debug包，正式包实机覆盖和真实相机/图库仍需手机测试。

## 公开0.1.6安装

1. 在旧App设置中导出备份，确认成功并保留ZIP。
2. 下载APK，或从电脑复制`artifacts/phone-test/随手记.apk`到手机，在文件管理器打开并覆盖安装；不要卸载或清除存储。包名仍为`com.personal.lifelog`，code6/7/8→9，因此此前同名0.1.6本地包也能覆盖。
3. 按[本轮报告](releases/0.1.6.md)检查作息按日/当天分布/七日分析、历史日期补录，并检查原记录、照片、目标、待办和习惯保留。日期/钟点使用统一滚轮。
4. 新版再导出一次备份；分别保留旧版和新版v6副本。v6读取兼容v1–v5，旧App不能读取v6；恢复旧包会全量替换，待办/习惯等原本不存在的数据会清空。

若系统提示签名冲突，保留旧安装及数据，反馈原App来源，不要用卸载解决。实际荣耀等实体手机的覆盖升级尚待用户执行；本轮未重复历史1001条记录的设备升级演练，详见[0.1.4报告](releases/0.1.4.md)。App暂无自动更新提醒。

## 备份与恢复

设置→导出备份→选择保存位置→等待成功。ZIP包含全部记录、照片和设置，未加密；只有APK不能备份记录。

恢复先预检，明确确认后全量替换记录、照片与设置，不合并；取消不改当前数据，提交前会创建并校验完整回滚备份。旧包没有的新字段按对应版本归一，见[备份格式](BACKUP_FORMAT.md)。失败时保留当前安装与原ZIP，按页面提示重试。

## 下次构建

干净提交上设置JDK17/Android SDK，沿用仓库外原签名：

```powershell
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

默认本地0.1.7/code10，标准输出在`artifacts/t16/v0.1.7-10/`。固定入口为`artifacts/phone-test/随手记.apk`及安装说明，记录版本、源提交和SHA。继续递增code，不重生成密钥或上传私钥；未来GitHub安装包更新须明确授权。
