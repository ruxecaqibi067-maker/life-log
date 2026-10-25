# 安装、更新与备份

当前本地测试版为随手记0.1.5 / versionCode7，沿用长期签名，面向Android9及以上手机。该APK按本轮要求只在本机生成，**不上传GitHub**；最近公开下载仍为[完整0.1.4/code6](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.4)。源码及文档已同步不表示已经发布新APK。

## 本轮手机安装

1. 在旧App设置中导出备份，确认成功并保留ZIP。
2. 电脑上找到`artifacts/phone-test/随手记.apk`，经USB等方式复制到手机，在文件管理器打开并选择覆盖安装；不要先卸载或清除存储。包名仍为`com.personal.lifelog`，版本code6→7。
3. 按[本轮说明](releases/0.1.5-local.md)检查首页并排卡/底部四个按钮、工作不黑屏、力量快速录入和感受、各类时间滚轮及原记录/照片/目标。
4. 新版再导出一次备份；分别保留旧版v4及新版v5副本。v5新增运动感受，读取兼容v1–v4，旧App不能读取v5。迁移新增字段为空，旧训练不会自动得到评价。

本轮只做简要主机及签名构建检查，实机覆盖升级尚待用户执行，未重复之前的1001条记录升级演练；历史证据见[0.1.4报告](releases/0.1.4.md)。App暂无自动更新提醒。

## 备份与恢复

设置→导出备份→选择保存位置→等待成功。ZIP包含全部记录、照片和设置，未加密；只有APK不能备份记录。

从备份恢复会先预检，确认后全量替换记录、照片与设置，不合并；取消不改当前数据，提交前会创建并校验回滚备份。旧备份没有的工作/目标/运动感受按对应版本规则归一；具体见[备份格式](BACKUP_FORMAT.md)。恢复失败时保留当前安装和原ZIP，按页面提示重试。

## 下次本地构建

在干净提交上设置JDK17/Android SDK，沿用仓库外的原签名：

```powershell
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

默认0.1.5/code7，标准输出在`artifacts/t16/v0.1.5-7/`。交付给用户的固定入口为`artifacts/phone-test/随手记.apk`及同目录安装说明，记录版本、来源提交和SHA。后续继续递增code；不重新生成签名或上传私钥。
