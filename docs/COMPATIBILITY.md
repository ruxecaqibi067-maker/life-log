# Android 兼容性说明

核对日期：2026-10-24；产品：随手记 0.1.2（versionCode 3）。

## 对外说明

**面向 Android 9 及以上手机，不限品牌，离线使用，无需 Google 服务。**

这不是荣耀专用 App。从工程和安装包条件看，可以面向满足要求的不同品牌 Android 手机；尚未取得全市场机型统计或各品牌完整实测，不能将“绝大多数手机实测通过”写成已验证结论。

## 检查依据

| 项目 | 本项目配置及影响 |
| --- | --- |
| 最低系统 | minSdk 28，即 Android 9；低于此版本不能安装 |
| targetSdk | 36，是适配行为目标，不是最高可安装版本；未设置 maxSdk |
| 品牌 | 无厂商/品牌白名单，未使用荣耀、华为或其他厂商专有 API |
| CPU 架构 | APK 无内置 native .so、无 ABI 过滤或按架构拆包；本项目 DEX 不限定 ARM/ARM64/x86 架构 |
| Google 服务 | 生产依赖与实现不依赖 Google Play services、账号或 Play 商店 |
| 网络 | 未声明 INTERNET；记录、回顾、备份恢复均在本地完成 |
| 选图 | 系统 Photo Picker 可用时使用；否则回退到 ACTION_OPEN_DOCUMENT，不要求安装 Google 服务 |
| 相机/文件 | 使用标准系统相机、FileProvider 和 SAF；不同厂商/文件提供方仍需实际验证 |
| 滚轮 | 原生 NumberPicker；新版本文字颜色/字号 API 有版本保护，Android 9 通过原生主题保持明暗可读 |
| 布局 | Compose 自适应宽度、滚动和系统字体；大字体有模拟器专项证据，小屏及其他厂商字体仍需实测 |

最终 APK 的版本、权限、native 条目等检查输出保存在本轮本地发布证据。第三方相册/相机/文件管理器的行为不能仅由 minSdk 推断。

## 证据范围

- Android 9 / API 28：Robolectric 上的业务、Room、照片/备份及原生滚轮主题对比度回归；不是 Android 9 真机测试。
- API 33：主机 Compose 界面回归。
- API 37：T06 提供原生滚轮、旧数据精度、跨夜、未结束、大字体和重建的实际模拟器证据；本轮复核结果见 [发布报告](reviews/V0.1.2_RELEASE.md)。
- 荣耀 200 Pro 为优先实机测试设备；其他品牌、真实系统相机/图库/SAF、后台重启、性能及一周自用仍待验证。

只声明 Android 手机支持范围；平板、电视、手表及其他系统未做专门适配。

## 参考与代码入口

- [Android 官方 minSdk / targetSdk 说明](https://developer.android.com/guide/topics/manifest/uses-sdk-element)
- [Android 官方硬件特性声明](https://developer.android.com/guide/topics/manifest/uses-feature-element)
- [Android 官方 Photo Picker 回退说明](https://developer.android.com/training/data-storage/shared/photo-picker)
- 配置：`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`gradle/libs.versions.toml`。
- 系统适配：`data/media/PhotoIntents.kt`、`feature/sleep/SleepDateTimePicker.kt`。

通过 [手机测试清单](testing/ANDROID_PHONE_TEST.md) 收集机型与系统版本反馈，不以模拟器代替跨品牌真机结论。
