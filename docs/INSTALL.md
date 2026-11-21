# 安装、更新与备份

当前 GitHub 公开版本为随手记 **0.1.9 / versionCode 14**（包名 `com.personal.lifelog`，最低 Android 9，Room/备份 v7），沿用原长期签名。包含荣耀非 Google 语音路径、首页中央圆形麦克风、描述字段语音追加、一句话预览以及睡眠/工作时长防护，详见 [0.1.8 发布报告](releases/0.1.8.md)。

- [下载 0.1.9 / code14 界面美化离线版](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.9/suishouji-0.1.9.apk)
- [版本页、SHA256SUMS 与 release.json](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.9)
- 电脑固定本地入口：`artifacts/phone-test/随手记.apk`

## 独立AI测试包

[下载随手记 AI测试](https://github.com/ruxecaqibi067-maker/life-log/releases/download/v0.1.9/suishouji-ai-test-0.1.9.apk)，应用ID`com.personal.lifelog.aireview`，与离线正式版并存，各自存储数据。进入「回顾→DeepSeek 测试」输入自己的Key，点「用虚构样例测试连接」，先核对样例和费用提示，再确认发送；真实Key不需要交给开发者。Key只在本次进程内有效，强制停止或进程结束后需重输。真实连接由用户测试，模拟测试不能替代真实请求。

AI测试的自动JSON为`Download/lifelog-ai-test/latest.json`，不会替换正式版`Download/lifelog/latest.json`。本机入口`artifacts/ai-review-test/随手记-AI测试.apk`。正式版无联网权限，界面美化版本支持code13覆盖升级。

## 覆盖更新

1. 在旧 App 设置中导出完整 ZIP 备份，确认成功并保留文件。
2. 从 GitHub 下载 `suishouji-0.1.9.apk`（或复制电脑 `artifacts/phone-test/随手记.apk` 到手机），直接覆盖安装，不要先卸载或清除存储。
3. 检查旧记录、照片、目标和待办完成状态。沿用原长期签名，公开 `0.1.7 / code11` 与此前本地 `0.1.8 / code12` 均可直接覆盖升级到 `0.1.8 / code13`。
4. 首次点击语音且手机存在可直接绑定的非 Google 语音服务时，会按需请求麦克风权限；若无可用服务，可点击「使用输入法语音」或直接键入文字。应用不申请联网权限，也不保存录音文件。

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

默认0.1.8/code13，标准产物位于`artifacts/t16/v0.1.9-14/`。固定入口`artifacts/phone-test/随手记.apk`及安装说明记录精确源提交和SHA。[0.1.8发布报告](releases/0.1.8.md)记录交付范围；后续远端发包继续按用户明确授权执行。
