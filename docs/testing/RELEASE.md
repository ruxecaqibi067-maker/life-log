# 构建与发布

当前版本默认0.1.4 / versionCode5。源代码、schema和文档走Git，APK、日志、照片及备份留在忽略的artifacts，签名私钥位于仓库外。

1. 检查任务交付提交已正常合入main，查看[进度板](../STATUS.md)和[本版报告](../releases/0.1.4.md)；只提交本轮已检查修改。
2. 设置JDK17/Android SDK，执行主机测试、debug构建、Lint及必要隔离设备回归。逐项记录结果，不将条件跳过写为通过。
3. 保持包名`com.personal.lifelog`，沿用长期签名；递增versionCode和版本名，提交后从干净工作区运行：

```powershell
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

4. 验证签名、版本、权限、SHA-256、native架构和16KB静态对齐，检查release Lint。产物位于`artifacts/t16/v0.1.4-5/`；release.json记录准确源提交。不要重新生成已有密钥。
5. 在独立模拟器安装上一已发布同签名包，恢复虚构夹具、导出备份，再用`adb install -r`覆盖新包；不卸载。比较记录、设置、照片SHA及UID/首次安装时间；按实际版本方向运行`verify_backup.py`，仅显式允许定义的新增字段差异。
6. 更新报告、安装说明、手机清单和任务入口，检查文档链接。最终APK必须从报告提交后的干净源构建，release.json中的源提交与推送main、发布tag一致。
7. 正常推送GitHub，建立Release测试候选，上传APK、SHA256SUMS.txt、release.json和手机清单。附件使用`suishouji-版本.apk`，名称标签为「随手记」。私钥、备份和测试记录不上传。
8. 公开下载一次，核对大小与SHA-256，并核对远端main/tag与构建源；最后检查工作区干净。

[T16原始证据批次](../archive/testing/T16-RELEASE.md)保留历史签名升级记录。设备和性能限制见[兼容性](../COMPATIBILITY.md)。
