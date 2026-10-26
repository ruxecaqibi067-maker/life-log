# Android 兼容性说明

核对日期：2026-10-26；当前0.1.6/code9，用户授权发布GitHub安装包。本轮定向主机/签名静态检查并核对作者API34作息场景，未重跑设备矩阵；证据范围见[发布报告](releases/0.1.6.md)。作息图表、待办/习惯未增加权限、平台依赖或后台服务，仍面向Android9及以上，不将主机或模拟器结果算作实体手机通过。

## 对外说明

**面向 Android 9 及以上手机，不限品牌，离线使用，无需 Google 服务。**

这不是荣耀专用 App。从工程和安装包条件看，可以面向满足要求的不同品牌 Android 手机；尚未取得全市场机型统计或各品牌完整实测，不能将“绝大多数手机实测通过”写成已验证结论。

## 检查依据

| 项目 | 本项目配置及影响 |
| --- | --- |
| 最低系统 | minSdk 28，即 Android 9；低于此版本不能安装 |
| targetSdk | 36，是适配行为目标，不是最高可安装版本；未设置 maxSdk |
| 品牌 | 无厂商/品牌白名单，未使用荣耀、华为或其他厂商专有 API |
| CPU 架构 | 无 ABI 过滤或按架构拆包；AndroidX 图形库打包 ARM64、ARMv7、x86、x86_64 四种 ABI，覆盖常见手机架构 |
| 内存页对齐 | 内置图形库的 ELF LOAD 段及 APK ZIP 的 16 KB 静态对齐检查通过；不是 16 KB 手机实测 |
| Google 服务 | 生产依赖与实现不依赖 Google Play services、账号或 Play 商店 |
| 网络 | 未声明 INTERNET；记录、回顾、备份恢复均在本地完成 |
| 选图 | 系统 Photo Picker 可用时使用；否则回退到 ACTION_OPEN_DOCUMENT，不要求安装 Google 服务 |
| 相机/文件 | 使用标准系统相机、FileProvider 和 SAF；不同厂商/文件提供方仍需实际验证 |
| 滚轮 | 原生 NumberPicker；新版本文字颜色/字号 API 有版本保护，Android 9 通过原生主题保持明暗可读 |
| 布局 | Compose 自适应宽度、滚动和系统字体；360dp小屏/1.5倍系统字号有API34证据，其他设备与厂商字体仍需实测 |

最终 APK 的版本、权限、native 条目等检查输出保存在本轮本地发布证据。第三方相册/相机/文件管理器的行为不能仅由 minSdk 推断。

## 证据范围

- Android 9 / API 28：Robolectric 上的业务、Room、照片/备份及原生滚轮主题对比度回归；不是 Android 9 真机测试。
- API 33：主机 Compose 界面回归。
- Android14 / API34：0.1.4隔离模拟器回归覆盖Room v1/v2/v3→v4、照片、运动/训练、作息滚轮、睡前页和全页流程；另检查真实MainActivity双卡、工作开始/结束、独立目标、原生时间选择器、恢复、1.5倍系统字体与键盘，以及黑页起床后两类统计互不影响。长期签名0.1.4/code5→code6覆盖安装及记录/照片/目标比对结果见[本版报告](releases/0.1.4.md)。
- API 37：保留此前 T06 滚轮等历史证据，见 [0.1.2 报告](releases/0.1.2.md)。T17 运行时该镜像图形服务不稳定，本轮设备验收采用 API 34，不宣称已通过 API 37 全套检查。
- 荣耀 200 Pro 为优先实机测试设备；其他品牌、真实系统相机/图库/SAF、后台重启、性能及一周自用仍待验证。

只声明 Android 手机支持范围；平板、电视、手表及其他系统未做专门适配。

## 参考与代码入口

- [Android 官方 minSdk / targetSdk 说明](https://developer.android.com/guide/topics/manifest/uses-sdk-element)
- [Android 官方硬件特性声明](https://developer.android.com/guide/topics/manifest/uses-feature-element)
- [Android 官方 Photo Picker 回退说明](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Android 官方 16 KB 页支持与静态检查](https://developer.android.com/guide/practices/page-sizes)
- 配置：`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`gradle/libs.versions.toml`。
- 系统适配：`data/media/PhotoIntents.kt`、`feature/sleep/SleepDateTimePicker.kt`。

通过 [手机测试清单](testing/ANDROID_PHONE_TEST.md) 收集机型与系统版本反馈，不以模拟器代替跨品牌真机结论。
