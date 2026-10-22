# T01 构建环境

本文件记录 T01 的命令行工程基线；不表示业务功能或荣耀手机验收已经完成。检查日期：2026-10-22（Asia/Shanghai）。

## 工具链与来源

| 项目 | 锁定值 | 本机安装位置 / 依据 |
| --- | --- | --- |
| 构建 JDK | Microsoft OpenJDK 17.0.20.1+1，Windows x64 | `C:\Users\user1\AppData\Local\LifeLogAndroid\jdk\jdk-17.0.20.1+1`；[官方下载及校验文件](https://learn.microsoft.com/en-us/java/openjdk/download) |
| Gradle | 8.13，bin 分发 | 由仓库 Wrapper 使用；[官方分发 SHA-256](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256) |
| Android Gradle Plugin | 8.13.2 | [官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：Gradle 8.13、JDK 17、默认 Build Tools 35.0.0，支持 API 36.1 及以下 |
| Kotlin / Compose 编译器插件 | 均为 2.3.21 | [Kotlin 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)：2.3.20–2.3.21 支持 Gradle 7.6.3–9.3.0、AGP 8.2.2–9.0.0；[Compose 插件配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)要求编译器插件沿用 Kotlin 版本 |
| SDK 根目录 | `%LOCALAPPDATA%\Android\Sdk` | 本机为 `C:\Users\user1\AppData\Local\Android\Sdk` |
| SDK Command-line Tools | 19.0（13114758） | `cmdline-tools\19.0`；[Google 官方仓库元数据](https://dl.google.com/android/repository/repository2-3.xml)中的稳定包，适用于 JDK 17 |
| SDK Platform / Build Tools | `platforms;android-36` / `build-tools;35.0.0` | `compileSdk=36`、`targetSdk=36`、`minSdk=28`；Build Tools 在 app 配置中显式固定 |
| Platform Tools / adb | 37.0.1-15733141 / 1.0.41 | `C:\Users\user1\AppData\Local\Android\Sdk\platform-tools\adb.exe`；本次 `adb devices` 无设备 |
| 应用身份 | `com.personal.lifelog` | namespace 与 applicationId 相同；`versionName=0.1.0`、`versionCode=1`，显示名「生活随记」 |
| Compose BOM / Activity / Core | 2025.10.01 / 1.11.0 / 1.17.0 | [BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.01/compose-bom-2025.10.01.pom)、[Activity](https://developer.android.com/jetpack/androidx/releases/activity)、[Core](https://developer.android.com/jetpack/androidx/releases/core)；固定稳定组合，不追随最新版本自动升级 |

所有应用依赖和插件版本集中于 `gradle/libs.versions.toml`；Compose UI / Material 3 版本由固定 BOM 管理。没有 `+`、预发布版本或本机绝对路径写入构建配置。

`gradlew --version` 显示的 Kotlin 2.0.21 是 Gradle 8.13 自带的 Kotlin DSL 版本；应用 Kotlin 和 Compose 编译器插件使用上表的 2.3.21。

后续包预留版本：Room 2.8.3、KSP 2.3.6、kotlinx.serialization JSON 1.9.0、Coil Compose 3.3.0。已确认对应官方 Maven POM 存在；T01 未把它们引入空 App，尚未验证 Room 代码生成、序列化编译或图片处理。这些验证分别随实际接入执行，不作为本包通过项。参考：[Room 发布说明](https://developer.android.com/jetpack/androidx/releases/room)、[KSP 2.3.6](https://github.com/google/ksp/releases/tag/2.3.6)、[serialization 1.9.0](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0)、[Coil 发布说明](https://coil-kt.github.io/coil/changelog/)。只选 Coil 本地文件能力，不预留网络模块。

## 安装边界与下载校验

开始时 PATH 和常见安装目录均未发现 JDK、Android SDK、adb 或 Android Studio。本包仅补齐可构建 APK 的命令行工具，未安装 Android Studio、模拟器或系统镜像；未改系统/用户级环境变量、Git 全局配置或其他软件设置。Android Studio 不是命令行构建的必要依赖，后续需要 IDE 时可另行安装，并将 Gradle JDK 指向上述 JDK 17。

- JDK ZIP：`https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.zip`，官方 SHA-256 为 `3d9006956fc8af5601cd24ffc4f468bef48279c7ebd8171b9bdf90d0aabfbf1f`。
- Command-line Tools ZIP：`https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip`。官方仓库 SHA-1 为 `54a582f3bf73e04253602f2d1c80bd5868aac115`；下载后 SHA-256 为 `98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003`。
- Gradle 8.13 bin ZIP：官方 SHA-256 为 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`；Wrapper 的 `distributionSha256Sum` 固定此值。官方分发跳转 GitHub 在本机出现连接重置/超时，安装下载回退到华为镜像，并以官方校验值验真。仓库 Wrapper 仍使用官方分发地址。
- Wrapper JAR 由已校验的 Gradle 8.13 生成；SHA-256 为 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`，与[官方 JAR 校验文件](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256)相符。
- 下载包、安装工具、Gradle 缓存及 SDK 均不入 Git。下载和检查的原始证据放在被忽略的 `artifacts/t01/`。

本次 SDK 安装遇到 Windows 逐文件复制缓慢：API 36 包已完整解压，安装器仍在复制 11,342 个文件。停止本包安装进程后，目录移动返回 `Access to the path ... is denied`，改用 `robocopy /E /MT:32 /R:1 /W:1` 将完整官方暂存内容补齐到本包新建的 `platforms\android-36`，没有递归删除或改写其他 SDK。复制结果 0 失败，再运行上述 sdkmanager 安装命令成功退出，`--list_installed` 确认 Platform 36 rev 2、Build Tools 35.0.0、Platform Tools 37.0.1。残留暂存目录留在 SDK 的 `.temp` 下，未提交。证据：`artifacts/t01/sdk-platform-copy.log`、`sdk-install-retry.log`、`sdk-installed.log`。这是本机安装处理记录，普通机器可直接使用 sdkmanager；遇到类似情况应先停止安装/构建并核对包完整性，不能一边安装一边移动目录。

## 干净检出后的配置

1. 安装上表的 JDK 17。下载并解压 Command-line Tools，使 `bin\sdkmanager.bat` 位于 SDK 的 `cmdline-tools\19.0\bin` 内；安装包中的 `cmdline-tools` 内容应直接放入 `19.0`。
2. 在 PowerShell 会话中设置自己的路径，不需要永久修改环境：

   ```powershell
   $env:JAVA_HOME = '自己的 JDK 17 安装目录'
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   $env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
   & "$env:ANDROID_HOME\cmdline-tools\19.0\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_HOME" --licenses
   & "$env:ANDROID_HOME\cmdline-tools\19.0\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
   ```

   阅读 SDK 许可后确认。`platform-tools` 是 sdkmanager 当前稳定包；adb 的实际版本另见交接，不决定 APK 的编译结果。
3. 在仓库根目录创建不入 Git 的 `local.properties`。路径改用正斜线，并转义盘符冒号，以满足 Java properties 和 Lint 的转义检查：

   ```powershell
   $sdkPropertyPath = $env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:')
   Set-Content -LiteralPath local.properties -Encoding ascii -NoNewline -Value ('sdk.dir=' + $sdkPropertyPath + [char]10)
   ```

4. 运行以下检查。首次构建需要能访问 Google Maven、Maven Central、Gradle 官方分发及其重定向地址；App 本身不依赖网络。

   ```powershell
   java -version
   adb version
   .\gradlew.bat --version
   .\gradlew.bat assembleDebug
   .\gradlew.bat lintDebug
   .\gradlew.bat :app:testDebugUnitTest
   adb devices
   ```

   在官方分发重定向不可访问的网络，可从 `https://mirrors.huaweicloud.com/gradle/gradle-8.13-bin.zip` 手动下载相同 ZIP。先以 `Get-FileHash -Algorithm SHA256` 比对上述官方值；相符后复制到 `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13-bin.zip`（先建目录），再运行 Wrapper，它仍会校验并自行解压。此目录名由仓库中固定的官方分发 URL 确定。本次即以此办法完成 Wrapper 启动。不要关闭校验或把镜像写成业务仓库。项目随库保留标准 Wrapper 脚本与 JAR，不要求全局安装 Gradle。

## 产物与检查证据

- 实际最终检查：`.\gradlew.bat :app:clean assembleDebug lintDebug :app:testDebugUnitTest :app:processReleaseMainManifest --console=plain` 成功，45s、54 个任务执行；清理迁移后残留空目录后再运行 `lintDebug` 成功，27s，最终 **0 errors / 6 warnings**。6 项均为版本升级提示：Gradle 1 项、Kotlin 插件 3 项、serialization 与 Coil 各 1 项。保留警告，没有禁用规则或创建 Lint baseline；`abortOnError=true`，使用默认警告策略。
- 首次 Lint 的路径转义与冗余资源目录问题已修复。图标迁移后的增量构建曾报告 `mipmap/ic_launcher not found`，上面的 clean 构建已通过；失败日志与最终成功日志分别保留，详见交接。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- Lint 报告：`app/build/reports/lint-results-debug.html`，以及同目录 XML/TXT 报告。
- 本包检查命令、实际结果、工具精确路径与未执行项见 [T01 交接](handoffs/T01.md)；原始日志在 `artifacts/t01/`，均未提交。
- 仅有启动页，没有业务测试；`testDebugUnitTest` 若为 `NO-SOURCE`，只能说明当前没有单元测试，不能称为业务验证通过。
- 当前无正式签名；debug 密钥由 Android 工具在用户私有目录自动管理，APK、密钥与产物不提交。真机安装、启动截图和荣耀系统备份行为需要实际设备证据。
