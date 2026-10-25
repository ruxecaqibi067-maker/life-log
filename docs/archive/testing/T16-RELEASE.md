# T16 签名产物与覆盖升级证据

批次：2026-10-23（Asia/Shanghai）。构建源提交 `a5fa2b93eb4228777027d7a86af42a2bd1d00fae`，两个 APK 均从干净工作区构建；后续交接提交仅补充证据文档。

## 产物

| 项目 | 旧测试版 | 新交付验收版 |
| --- | --- | --- |
| versionName / versionCode | 0.1.0 / 1 | 0.1.1 / 2 |
| 包名 | com.personal.lifelog | com.personal.lifelog |
| 构建 | release；R8 与资源压缩；非 debuggable | release；相同优化；非 debuggable |
| APK | artifacts/t16/v0.1.0-1/life-log-0.1.0-1-release.apk | artifacts/t16/v0.1.1-2/life-log-0.1.1-2-release.apk |
| SHA-256 | 534c38ae49348457a3fb4f57c2cd2b336da2719e48c868e155acfedb5ee8c687 | abc19a3465c64fba546cd74d8aec23ee67f54bdb3cd4e1448e6073c1c4f4de94 |

两个 APK 的长期签名证书 SHA-256 相同：`d7f28734119f923ef9aa7434bf4ed8a4305e6092f54e4ab88a8be70f2143c747`。`apksigner verify --verbose --print-certs` 均成功，APK Signature Scheme v2 有效，minSdk 28。私钥未进入 Git、APK、备份或交付产物目录。

构建工具沿用锁定基线：JDK 17.0.20.1、Gradle 8.13、AGP 8.13.2、Build Tools 35.0.0、compile/target SDK 36。两个构建类型共用源代码与优化配置，区别为包名后缀、版本标记和签名。本轮正式包相比 T15 已冻结候选的额外实现改动是签名/版本接入，以及备份 manifest 的 appVersion 从固定 0.1.0 改为读取实际安装包版本；已通过两次导出分别验证为 0.1.0、0.1.1。Room schema 和备份格式均仍为 v1，没有变更记录、照片与恢复规则。

## 实际检查

| 命令 / 行为 | 结果 | 证据 |
| --- | --- | --- |
| 未配置签名执行 `:app:preReleaseBuild` | 按预期失败，提示 Release signing is missing | artifacts/t16/missing-signing.log |
| Build-Release.ps1，版本 1 / 0.1.0，实际执行 `:app:assembleRelease :app:lintRelease` | 通过 | artifacts/t16/v0.1.0-1/build.log、release.json、signature.txt、badging.txt |
| 同一脚本，版本 2 / 0.1.1 | 通过；Lint 0 错误、13 警告 | artifacts/t16/v0.1.1-2/ 下对应日志/元数据；app/build/reports/lint-results-release.txt |
| 旧 release 首次安装 | 成功 | install-old-release.log、old-package-state.txt |
| 在旧 release 的真实设置页/SAF 恢复规模集，再通过饮食编辑器保存一条 `upgrade-before-001` | 成功；总 1,001 条记录、200 张照片 | old-restore.log、old-release-preview.png、old-release-restored.png、old-saved-meal.xml/png |
| 旧 release 经系统保存选择器导出备份 | 成功 | before-export.log、before-upgrade.zip |
| 对新 release 执行 `adb install -r` | 成功；未在旧 release 与新 release 之间卸载或清除数据 | install-upgrade.log、new-package-state.txt |
| 升级后打开首页 | 新饮食及未结束作息仍显示 | after-upgrade-home.txt/xml/png |
| 新 release 再导出，经 verify_backup.py 比较 | 通过；全部记录字段、关系、项目、组、主题与照片字节保持一致 | after-export.log、after-upgrade.zip、upgrade-comparison.json |

设备层为 Medium Phone / Android 17 / API 37 / x86_64 **模拟器**。此前 T15 安装的是本轮自动化 debug 夹具；在正式旧版首次安装前移除了该 debug 安装，其数据库列表只有独立的 `restore-process-qa.db`，没有用户库 `life-log.db`，记录在 `debug-fixture-only-databases.txt`。这一步属于测试环境准备，不属于覆盖升级证据。真正的升级实验从同长期签名的 release 1 开始，保留数据覆盖到 release 2；`.qa` 安装没有作为正式旧版本。

升级前后应用 UID 保持 `10240`；模拟器报告的 firstInstallTime 始终为 `2026-10-23 12:26:19`，lastUpdateTime 从 `12:26:19` 更新至 `12:29:25`。这些是模拟器原样报告的设备时钟，不转换为主机本地时间；正式验收批次日期使用 Asia/Shanghai。证据为 `old/new-uid.txt`、`old/new-package-state.txt`。

数据明细：600 条饮食、201 条作息（1 条未结束）、200 场训练、9 个运动项目、400 个动作、400 个力量组、200 张合成照片，主题 DARK。比较只允许成功导出时刻及 AppSettings.updatedAt 这两个由导出本身改变的元数据不同；未忽略任何记录、项目、组或照片字段。200 张照片逐一核对 SHA-256，不只比较数量。

```powershell
python scripts\verify_backup.py artifacts\t16\before-upgrade.zip artifacts\t16\after-upgrade.zip --ignore-export-clock
```

## 未执行与放行状态

- 模拟器在覆盖升级验证完成后被外部启动流程重启；重新启动 App 后可观察到已有记录，但后续补充页面操作遇到状态切换，因此不将其当作受控的完整重启/图片详情验收。已保存的升级与 ZIP 比较证据不受影响。
- 荣耀 200 Pro 未连接；MagicOS、真实相机/图库与 SAF、键盘/大字体、重启、正式 release 冷启动/保存性能的 10 次原始样本与 P90 均未执行。模拟器 Activity 启动计时不能替代这些数据。
- 首次安装正式包和模拟器同签名升级已验证；荣耀上的首次安装/覆盖升级、用户独立备份恢复尚未验证。图片文件完整性与备份往返已验证，荣耀图片展示和系统回传尚缺。
- 本机私有签名目录已创建并限制 ACL；异地/离线保管仍需用户完成，不能把同机保存说成已安全备份。
- T15 尚缺荣耀候选放行证据；本包产物为**已签名的待真机验收版本**，不是全部第一版验收完成。交付后安排一周自用，再交主管收集使用反馈。

原始备份和截图只有虚构数据，全部留在 Git 忽略目录。没有公开上传、商店发布、远程仓库创建、分支推送或合入 main。中央 STATUS 保持由主管维护。
