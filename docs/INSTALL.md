# 安装、更新与备份

当前为已签名的验收版本。荣耀 200 Pro 的系统相机/图库、MagicOS、重启和性能仍需补测；签名成功不等于第一版全部验收通过。

## 手机上安装

1. 用手机打开 [实机测试下载页](https://github.com/ruxecaqibi067-maker/life-log/releases/tag/v0.1.1-test.1)，下载 `life-log-0.1.1-2-release.apk`。也可从电脑下载后经 USB 复制到手机；[首次实机测试清单](testing/HONOR_PHONE_TEST.md) 给出具体步骤。
2. 在手机文件管理器打开 APK；系统询问时允许这个安装来源。安装后打开「生活随记」。不需要账号。
3. 首次只使用虚构记录，验证饮食、作息、运动、照片和备份流程。完整检查表在 `docs/ACCEPTANCE.md`。

正式包名固定为 `com.personal.lifelog`。`.qa` 包是独立候选测试安装，里面的数据不会自动出现在正式包中；需要经设置中的 ZIP 备份迁移。

## 更新并保留数据

1. 在旧版设置中导出备份，确认显示「备份已导出」，保留 ZIP 副本。
2. 打开新版同签名 APK，选择安装/更新；不要先卸载旧版，也不要清除应用存储。
3. 更新后核对历史照片、逐组训练、自定义项目、未结束作息及主题，再做一次备份。

若提示签名冲突，先停止安装并核对原安装来源。开发 debug 包与正式包使用不同密钥，不能直接覆盖；先从原 App 导出并在独立测试安装验证备份，保留原安装和数据，由维护者安排迁移，不能为解决冲突直接卸载抹掉记录。

## 导出与恢复

- 打开设置 →「导出备份」→ 选择本机或自行保管的位置 → 等待成功提示。ZIP 未加密，含全部记录和照片，请自行保管。仅复制 APK 不会备份记录。
- 打开设置 →「从备份恢复」→ 选 ZIP → 核对数量与时间范围。取消预览不会恢复。
- 点击「覆盖并恢复」会全量替换本机记录、照片与设置，不合并。开始前会生成并校验回滚备份；若失败则不进入替换。
- 恢复成功后可导出最近的回滚备份。要回到之前的数据，按相同预检/确认流程恢复回滚 ZIP。
- 恢复失败或出现「需要恢复检查」时，按页面提示重试；保留现有安装和原 ZIP，不要清除应用数据。

## 签名保管与下次构建

本机签名资料位于 `%LOCALAPPDATA%\LifeLogAndroid\signing\`，含 `lifelog-release.p12` 与 `signing.properties`；不在 Git 或 APK 交付目录。请将整个目录备份到本人控制的另一处安全位置，不要公开发送密码或私钥。

在 Windows PowerShell 设置 JDK 17 的 `JAVA_HOME`，从干净提交执行：

```powershell
.\scripts\Build-Release.ps1 -SigningProperties "$env:LOCALAPPDATA\LifeLogAndroid\signing\signing.properties"
```

脚本生成 APK、SHA256SUMS、证书验证结果与 `release.json`（Git 提交、版本及构建类型）。后续必须继续使用这份签名资料并提高 `-VersionCode`，例如 `-VersionCode 3 -VersionName 0.1.2`。不要重新运行密钥初始化来替换已有密钥。

签名机制与 Android 更新要求见 [Android 官方应用签名说明](https://developer.android.com/studio/publish/app-signing)。本轮主管按用户明确授权整合并推送 GitHub，下载附件标记为预发布实机测试候选；未上传应用商店。

交付后安排一周自用，将不便录入、难以查找或修改的具体场景交回主管；一周反馈不能提前写成已经通过。
