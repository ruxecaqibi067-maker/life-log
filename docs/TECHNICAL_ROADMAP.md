# 第一版技术路线

基线日期：2026-10-22。需求以 [V1_SPEC.md](V1_SPEC.md) 为准，执行入口见 [TASKS.md](TASKS.md)。本文件保留首版架构及后续增补；当前实现、构建和设备证据以[主管进度板](STATUS.md)及[本版报告](releases/0.1.4.md)为准。

## 1. 技术选型与理由

| 层面 | 第一版选择 | 理由/边界 |
| --- | --- | --- |
| 平台 | Android 原生，Kotlin | 当前只面向安卓个人手机，可直接接系统照片、文件与安装更新流程 |
| UI | Jetpack Compose + Material 3，单 Activity | 声明式界面，统一主题、表单、列表和深色模式 |
| 状态 | ViewModel + StateFlow + 协程 | UI 订阅持久化状态，长任务切到 IO，避免主线程读写文件 |
| 数据 | Room + KSP | 本地关系数据、事务、Flow、schema 与后续迁移 |
| 设置 | Room `AppSettings` 单行表 | 主题等偏好与用户记录能在一次恢复事务中切换；暂不引入 DataStore |
| 依赖组织 | 单 `app` 模块、手动 `AppContainer` | 个人项目足够，不引入 Hilt、多模块或复杂领域框架 |
| 照片 | 系统相机 `TakePicture` + Photo Picker + FileProvider | 首版无需自建相机；选择后复制成应用自有文件 |
| 图片显示 | 轻量图片库，如 Coil 的本地文件能力 | 统一采样、列表缩略图与缓存，不引入网络请求能力 |
| 备份 | SAF + ZIP + kotlinx.serialization JSON + SHA-256 | 文件由用户选择位置，可跨手机恢复，不依赖云服务 |
| 测试 | JVM 边界测试 + Room/文件仪器测试 + 少量 Compose 流程测试 | 优先覆盖数据与失败路径，界面细节用真机检查 |
| 交付 | 可安装 APK，固定 applicationId 和 release 签名 | 首版个人使用，暂不涉及商店分发 |

界面、分层及状态来源遵循 [Android 架构建议](https://developer.android.com/topic/architecture/recommendations)；本地数据持久化采用 [Room](https://developer.android.com/training/data-storage/room)。这里选择单模块与手动组装是项目规模下的设计判断，并非官方强制要求。

## 2. 开发环境与版本锁定

### 2.1 本机只读检查结果

2026-10-22 检查当前工作区：原目录为空，不属于已有 Git 仓库；Git 2.54.0.windows.1 可运行。

在 PATH 和常见 Android Studio、Android SDK、Java 安装位置未检测到 `java`、`javac`、`adb`、`gradle` 或 Android Studio。此为有限路径检查，不代表全盘一定没有安装。当前主管任务不安装开发环境，T01 再确认并补齐。

### 2.2 T01 必须形成的工具链基线

1. 检查已有安装，再从官方来源补齐 Android Studio、配套 JBR/JDK、Android SDK、Platform Tools。
2. 创建 Compose Empty Activity 工程，使用相互兼容的稳定版本，不选 alpha/beta/RC。
3. `minSdk = 28` 为本项目支持下限，不是对荣耀机型当前系统的推断；实际手机版本在验收时记录。
4. `compileSdk`、`targetSdk`、AGP、Gradle、Kotlin/Compose 编译器、Compose BOM、Room、KSP、serialization、图片库版本在 T01 实际核对并锁定。
5. 所有依赖集中于 `gradle/libs.versions.toml`，不使用 `+` 或动态版本；Gradle Wrapper 与 JAR 一并入库，配置分发校验值。
6. 写 `docs/ENVIRONMENT.md`：实际版本、安装位置（不含秘密）、官方兼容来源、构建命令与成功日志摘要。
7. 固定 `namespace`/`applicationId = com.personal.lifelog`，显示名暂为「生活随记」。首次保存真实数据前锁定身份，后续不随意更改。

构建 JDK 根据所选 AGP 的要求确定，不能仅凭机器上的 Java 版本推断兼容；参见 [Android 构建中的 Java 版本](https://developer.android.com/build/jdks)。若选 AGP 9 系列，要核对内置 Kotlin 机制；Compose 编译器与 Kotlin 的配套见 [官方 Compose 设置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)。本方案不预填未经构建验证的“最新版本号”。

### 2.3 标准验证命令

以下命令从 T01 工程建立后才成立：

```powershell
Set-Location 'C:\Users\user1\Desktop\workspace\NEW TRY\life-log-android'
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb devices
# 连接并授权手机/模拟器后：
.\gradlew.bat :app:connectedDebugAndroidTest
```

首次解析和下载依赖需要网络；完成构建的 App 核心功能不需要网络。无手机连接时只能标记真机检查待执行。

## 3. 代码组织与调用方向

以下为计划结构，当前不存在这些 Android 源码目录：

```text
app/src/main/java/com/personal/lifelog/
  App.kt / MainActivity.kt / AppContainer.kt
  core/time/                 # Clock、日期归属、时区与时长规则
  core/validation/           # 空值、长度、数字与区间规则
  data/local/                # AppDatabase、entity/、dao/
  data/repository/           # 记录与项目库的业务写入/Flow 查询
  data/media/                # 拍摄暂存、照片规范化、引用与清理
  data/backup/format/        # DTO、manifest、版本及约束
  data/backup/export/        # 一致快照和 ZIP 导出
  data/backup/importer/      # 解析、预检、暂存与预览
  data/backup/restore/       # 覆盖恢复、一致性与中断恢复
  ui/theme/ navigation/ components/
  feature/home/ review/ meal/ sleep/ exercise/ workout/ settings/
app/src/test/                # 纯规则、格式及故障边界
app/src/androidTest/         # Room、文件、Compose 流程
app/schemas/                 # 已导出的真实 Room schema，纳入 Git
```

调用方向：`Compose UI → ViewModel → Repository → Room/PhotoStorage`。`PhotoStorage` 是应用内部照片服务，正式照片保存在应用私有目录。

- UI 通过 `collectAsStateWithLifecycle` 观察状态，不把内存列表当作数据库替代品。
- Repository 实现校验、去重、事务与文件一致性；状态来源始终可从 Room 重建。
- 注入 `Clock` 和当前时区提供器，便于确定性测试；不在规则中散落 `now()`。
- 所有正式写入通过应用级 `WriteCoordinator`；备份与恢复时互斥写入，避免边导出边删图。
- 基础导航、依赖和 `AppContainer` 是共享文件。默认串行集成；每个功能任务结束时自己的入口必须可访问，不能把首次联调全部留到 T10。

## 4. 数据模型与跨任务契约

T02 冻结实际字段名、DAO/Repository 公共签名和 schema。实现可以调整物理字段，但须保持以下语义，并在交接记录迁移影响。

### 4.1 通用规则

- 用户数据主键为稳定 UUID 字符串，不能把自增主键当作跨备份身份。
- 所有记录保存 `createdAtUtcMs`、`updatedAtUtcMs`；发生时间单独存，不以创建时间替代。
- 完整时间点用 UTC epoch milliseconds，另存 IANA `zoneId` 与据此确定的 `localDate`；显示沿用记录时区语义。
- 空值用 `null`，不能用 0 代替未记录；数值在业务入口和导入入口共用校验规则。
- 时长存整数秒、距离存整数米、重量存整数克、次数存正整数。UI 使用分钟、km、kg；输入用十进制转换，避免浮点误差。
- 外键与级联关系覆盖训练、项目和组。删除训练应删除所属项目和组；隐藏运动定义不能级联删除历史。

### 4.2 核心表

| 表 | 关键字段/约束 |
| --- | --- |
| MealRecord | id、occurredAtUtcMs、zoneId、localDate、text?、satiety?、satietyText?、photoId?；文字/照片至少一个；satiety 使用稳定枚举；仅 CUSTOM 携带不超过 40 字的描述 |
| SleepRecord | id、startedAtUtcMs、startZoneId、endedAtUtcMs?、endZoneId?、archiveLocalDate、activeSlot?；未结束 activeSlot=1，已结束为空；activeSlot 唯一索引 |
| ExerciseDefinition | id、name、normalizedName、type、isBuiltin、isHidden、lastUsedAtUtcMs?；隐藏代替物理删除 |
| WorkoutSession | id、occurredAtUtcMs、zoneId、localDate、note?；至少一个 WorkoutItem |
| WorkoutItem | id、sessionId、exerciseId、nameSnapshot、typeSnapshot、sortOrder、durationSeconds?、distanceMeters?、note? |
| WorkoutSet | id、itemId、sortOrder、weightGrams?、repetitions?；各组可以不同，纯空占位组不入库 |
| MediaAsset | id、generationId、relativePath、mimeType、width、height、byteSize、sha256；路径仅由本地程序生成 |
| AppSettings | 固定单行 id、themeMode、lastSuccessfulExportAtUtcMs?；T18 schema3新增 targetSleepMinutes?（每日分钟目标，1–1440，未设置为null） |
| RestoreState | 固定单行/元数据，lastCommittedRestoreId；用于启动后判断恢复事务是否提交 |

`activeSlot` 唯一索引使数据库最多容纳一个进行中的作息；开始/结束仍在事务内做完整校验。导入必须重建并验证该字段，不能信任外部值。

`nameSnapshot/typeSnapshot` 是历史内容，项目更名不能批量改写；手动编辑某条历史训练时也保留原快照，只有用户明确替换该动作才写入新快照。项目类型被使用后不原地更改。

内置项目用固定 ID，只在首次建库播种；恢复和后续启动不能重新添加重复项目，也不能覆盖用户隐藏/改名状态。恢复含完整项目库，未知类型拒绝，不能悄悄映射成其他。

### 4.3 Repository 行为

- 饮食保存先准备好正式照片文件，再提交记录和引用；失败保留输入，不能提前提示成功。
- 作息开始、结束、撤销、补录和重叠检查在数据库事务内完成；双击和并发调用不得产生两个活动记录。
- 训练及全部项目/组一次事务保存；列表序号重新规范化，不能只保存主表后逐项补写。
- 修改发生日期后，今日/回顾自然重新查询；UI 不手工维护第二份时间线。
- DAO 暴露按日期、活动作息、项目列表与历史数据的 Flow；T10 在 Repository 层映射为统一时间线展示模型。
- 失败返回可区分的领域错误，例如校验、空间不足、照片损坏、文件权限和备份版本不支持；UI 显示可操作提示，不直接展示堆栈。

### 4.4 迁移

首次 schema 为真实 v1，提交导出 JSON。首次建库验收不能伪称完成不存在的 v0→v1 迁移。

此后改结构必须有显式迁移及真实旧 schema 到新 schema 测试；用户数据构建禁止 `fallbackToDestructiveMigration`。备份格式版本独立于 Room 数据库版本。

## 5. 作息与时间线规则实现

- 时长使用 `Duration.between(Instant(start), Instant(end))`；禁止对单独的时分字符串相减。
- 完成项按结束地点时区的当地日期归档；活动项按开始地点时区归档。开始和结束可在不同时区。
- 保存后旅行或更改当前时区，不自动改变记录归属；手动编辑时明确使用录入界面所示日期/时区。
- 夏令时重复/不存在的当地时间需要明确解析或提示，不能静默取错偏移；至少以模拟时区做单元测试，真实手机以当前时区实测。
- 活动项只保存时间点，无需 foreground service、闹钟、WakeLock 或后台循环。
- 重叠检查在排除当前编辑记录后执行；只差边界的相邻区间可以接续，实际交集不允许。
- 首页跨午夜仍持续显示活动状态；该行最终结束后移动到起床日，不产生两条重复记录。

## 6. 照片链路与生命周期

使用 [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) 选择单张图片；不可用时通过 Activity 合约回退到 `ACTION_OPEN_DOCUMENT`，不依赖 Google Play 服务补装。拍摄使用 [TakePicture](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture)，向系统相机提供临时可写的 [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider) URI。

### 6.1 处理顺序

1. 相机输出到应用专用暂存子目录；相册 URI 以流方式读取，设置输入大小上限。
2. 检查文件签名、头信息、像素、可解码性；按目标尺寸采样解码，处理 EXIF 旋转/镜像。
3. 统一重编码 JPEG，最长边默认 2048px、质量默认85；不复制原图 GPS 等 EXIF。参数为第一轮真机验证基线，可据画质与性能调整。
4. 先写临时文件并完成校验，再原子移动到 `files/media/<generationId>/<photoId>.jpg`。正式文件不可原地覆写。
5. 数据库事务保存 `MediaAsset` 与饮食引用，成功后返回成功状态。
6. DB 失败可留下无引用文件，由受保护的延迟清理回收；不能先删旧图再尝试保存新图。

参考 [应用专用文件存储](https://developer.android.com/training/data-storage/app-specific)：正式图不能仅放 cache；也不能只存图库 URI。删除原相册图片后，保存过的记录仍可回看。

### 6.2 文件边界

- FileProvider `exported=false`、临时 URI 授权，仅暴露拍摄暂存子目录；不暴露整个 files 或外部存储根目录。
- 取消拍摄/选择不生成记录；清除无引用暂存文件，短暂重建时保留正在使用的相机 URI。
- 单次输入最大100MiB，图像头最大100MP；在采样后解码，禁止将整张大图直接载入内存。
- 规范化后的正式照片最大8MiB。超限无法安全处理时明确拒绝，保留已有记录。
- 删除/替换记录先提交新 DB 状态，再清理已无引用文件。清理任务受 WriteCoordinator 与恢复状态保护，不能误删暂存恢复或正在导出的文件。
- 清理 cache 允许丢缩略图但不丢正式照片；首版照片一餐一张，不维护公共相册副本。

## 7. 备份协议：实现前冻结

### 7.1 格式与包含范围

ZIP 扩展名 `.zip`，文件名示例 `life-log-20261022-203000.zip`：

```text
manifest.json
data.json
media/<photoId>.jpg
```

- `manifest`：当前导出 `formatVersion=4`（读取兼容 v1/v2/v3），与 `dataVersion` 相同；另含 appVersion、exportedAtUtcMs、数据与照片数量，以及 `data.json` 和每张照片的相对路径/大小/SHA-256；manifest不计算自身哈希。v2 为饮食增加可选 `satietyText`，v1 包缺失时按 null；v3 为设置增加 `targetSleepMinutes`，v1/v2 缺失时按 null。v4新增独立workRecords及targetWorkMinutes，旧1–3按空工作/null目标归一。发布基线由STATUS与发布报告确认，格式细节见 BACKUP_FORMAT.md。
- `data`：稳定 UUID、三类记录、完整项目库、所有项目快照和组、照片语义元数据、进行中作息、AppSettings。
- 不导出原手机绝对路径、content URI、恢复日志、临时文件或签名资料。照片在备份里的路径与运行时 generation 目录分离。
- 导入保留记录 ID 与历史内容；生成新的本地媒体 generation，并重建 DB 路径。导出计数、引用与哈希必须互相吻合。
- JSON 格式有独立版本；未知可选字段允许忽略，未知必需版本/类型/缺失必需字段拒绝。
- T02 写出数据字段契约；T11 提交虚构示例包及协议说明。虚构小型 fixture 可入库，真实数据不得入库。

### 7.2 初始技术上限

这些是保护资源与可测试性的工程上限，不是产品容量承诺；T15 如调整，协议说明与边界用例同步更新。

| 项目 | 上限 |
| --- | --- |
| ZIP 文件大小、总展开大小 | 各2GiB |
| data.json 展开大小 | 32MiB |
| 单张规范化照片 | 8MiB |
| ZIP 条目数 | 10002（最多10000照片＋2个JSON） |
| 三类记录总数 | 10000 |
| 运动定义数 | 1000 |
| 每次训练项目数 | 100 |
| 每项目组数、全包组总数 | 100、100000 |

计数、大小限制在流读取/解压过程中执行，不能解压完才检查。导出预检也使用同一限制，保证自己导出的包能被自己导入。遇到上限不截断用户数据，需明确失败并保留原数据。

### 7.3 导出

通过系统 [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) CreateDocument 选择位置。导出期间暂时禁止业务写入并显示进度，可取消；在事务里读取一致的数据快照，在受保护阶段复制所引用照片，避免边导出边删除。

先在应用暂存区生成并自校验完整包，再写到用户所选文档。仅在完整写入/关闭成功后提示导出成功；失败时尽力删除不完整的目标文档，并明确没有成功备份。更新「最后成功导出时间」，清理临时文件并释放写锁。

不直接复制正在运行的 SQLite 主文件，避免遗漏 WAL 或数据库版本耦合。导出为未加密 ZIP，界面在用户选择保存前说明所含内容。

## 8. 恢复执行与失败一致性

### 8.1 预检阶段：不改现有数据

1. OpenDocument 选包，复制/流式读取到受控暂存区并检查大小。
2. 拒绝绝对路径、盘符、`..`、反斜线歧义、重复条目、符号链接及所有不在白名单的路径。
3. 对每个规范化后的落盘路径验证仍在暂存根内，防止 [ZIP 路径穿越](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)。
4. 验证版本、计数、哈希、照片解码、字段长度/枚举/范围、主外键、快照、唯一活动作息与时间区间。
5. 生成只读的预检结果和数量预览，返回专属 staging token；用户取消后清理暂存，不接触正式 DB。

预检结果不能通过更换源包绕过：T13 只接受仍有效且哈希一致的 staging token。

### 8.2 全量替换阶段

1. UI 清楚显示全量替换说明，用户明确点击恢复；不能在选中备份后立即覆盖。
2. 获得排他写锁，确认空间足够容纳旧数据、回滚包、暂存及新照片。
3. 自动制作并自校验当前数据的完整回滚包；若此步骤失败则停止，当前内容不变。成功后提供将回滚包保存到用户选择位置的入口。
4. 将已验证图片准备到新的不可覆盖 generation 目录；任何文件失败均在 DB 变更前停止。
5. 写入可持久化恢复日志：restoreId、新旧 generation、回滚包位置、当前阶段。
6. Room **单个事务**替换全部业务表与 AppSettings，并写入 `lastCommittedRestoreId`。正式数据库文件不直接替换，避免连接/WAL不一致。
7. 成功提交后清理旧无引用媒体、暂存与日志；清理失败只留待重试，不把已完成恢复误报为业务失败。

数据库事务不能回滚文件操作。因此采用“新文件先就位、DB 最后切引用、旧文件后清理”。导入失败路径不能先清空旧库或覆写旧照片。

### 8.3 中断恢复与回滚边界

- 启动时在正式业务写入和垃圾清理前检查日志与 `lastCommittedRestoreId`。
- DB 未提交：旧引用仍有效；丢弃新无引用 generation，保留原状态。
- DB 已提交：新引用已全部就位；完成清理并恢复可用状态，不回到半套旧照片。
- DB 事务提交是恢复的明确提交点；提交前失败显示失败且原数据不变，提交后仅清理失败显示已恢复并安排清理重试。
- 引擎结果至少区分 `FailedBeforeCommit`、`Committed`、`CommittedCleanupPending`，设置页按该事实反馈，不能把清理待完成当作需要自动回退数据库的失败。
- 回滚包存于应用私有的非 cache 目录，保留最近一次；先生成并验证新回滚包才能替换旧回滚包。卸载 App 会丢私有回滚包，界面需提供导出入口。
- 恢复回滚包也走相同预检与完整恢复流程，不能偷偷跳过校验。
- 需用故障注入覆盖文件就位前、文件就位后、DB 提交前、提交后/清理前，另做实际进程终止检查。

## 9. 权限、隐私与系统行为

- 不加入业务 INTERNET、定位、通知、整库媒体读取等非必要权限；使用系统相机委托时不先加入自建相机权限。
- 不接入统计、广告、联网图片或账号 SDK；检查 release 合并后的 Manifest，不能只看主 Manifest。
- 明确配置旧版及新版系统备份规则，排除自动云备份中的用户记录和照片；手动备份为产品支持的数据迁移路径。
- [Android Auto Backup](https://developer.android.com/identity/data/autobackup) 与厂商换机行为不完全由一个 `allowBackup` 开关决定；在荣耀系统实测并记录，不承诺控制所有系统级复制。
- 文件分享使用临时 URI 授权；日志只含错误码/技术上下文，禁止完整真实记录、照片和备份内容。

## 10. 验证、发布与更新

### 10.1 分阶段门槛

| 阶段 | 完成标志 |
| --- | --- |
| 工程基线 | Wrapper 可复现构建、环境和版本落盘、空 APK 可安装 |
| 数据基线 | schema、规则、事务和项目快照契约落实，关键边界测试通过 |
| 三类记录 | 每类各自能进入、保存、重开回读、编辑和删除，有独立证据 |
| 整体使用 | 今日/回顾归属正确，大图可用，主题/字体放大正常 |
| 备份可靠 | 干净安装往返一致，损坏包不改原数据，中断恢复状态一致 |
| 发布候选 | 本地检查通过、荣耀真机验收及性能测量，问题有记录 |
| 正式第一版 | release APK、固定签名、SHA-256、安装与覆盖升级证据齐全 |

详尽用例和测量口径见 [ACCEPTANCE.md](ACCEPTANCE.md)。性能目标不通过时先记录原始数据并修正瓶颈，不把未测项目写成已达标。

T15放行仅针对候选功能、数据可靠性与性能，不要求尚未发生的T16正式签名/覆盖升级或一周试用证据。T16完成后交付安装版本，再进行一周本人试用并回到主管做最终使用验收；不需要实现对话连续运行等待一周。

### 10.2 APK 与签名

- debug APK 用于开发，不应成为长期真实数据安装通道。正式自用阶段统一使用可复用 release 签名。
- T15使用隔离的 `qaRelease` 构建：继承release优化与资源配置、`debuggable=false`、本机测试签名、`applicationIdSuffix=.qa`，命令为 `:app:assembleQaRelease`。仅存虚构数据，不与正式安装混用；T16才接入长期release密钥。
- T15性能使用该release等效候选构建，不能用debug计时替代。T16在正式release构建上复核关键性能与资源差异。
- 签名身份一旦用于真实数据版本，后续必须沿用；递增 `versionCode`，保持 applicationId。
- 密钥和密码不入 Git，保存位置与保管说明写在本地私有配置中；文档只记脱敏证书指纹和验证方式。
- 不能把“覆盖安装新签名包”当作正常升级；不要靠卸载再安装伪造保留数据验收。
- T16 先用同一 release 签名的候选版本建立测试数据，再安装递增 versionCode 的版本，验证三类记录、照片、项目与活动作息保留。
- 输出 APK、文件 SHA-256、versionName/versionCode、对应 Git 提交、签名指纹、构建环境和发布说明。产物放忽略目录 `artifacts/`，不进入源码历史。

官方行为依据：[应用签名](https://developer.android.com/studio/publish/app-signing)。是否安装到手机、开启调试和提供真机操作证据需由持有设备的人完成；电脑构建成功不等于这些步骤已完成。

## 11. 主要风险与处理

| 风险 | 第一版处理 |
| --- | --- |
| 多对话改坏共同接口 | T02先冻结契约；默认串行；分支提交与交接；主管接受后再启动下游 |
| 大图处理阻塞/OOM | 采样解码、输入与像素限制、IO处理、真机大图验证 |
| 临时 URI 或 cache 失效 | 记录成功前落应用正式文件并存相对引用 |
| 作息跨日/漏点/重复点击 | Instant + 时区归属 + 唯一活动项 + 事务 + 补录 |
| 导出不完整/恢复半套数据 | 一致快照、自校验、预检、新generation、DB提交点、恢复日志 |
| 更新丢数据 | 稳定ID与签名、显式schema迁移、覆盖升级验收 |
| 工具安装或手机未连接 | 在交接中单列环境/外部依赖，不伪称代码或真机通过 |

首版任务量与多对话调度见 [WORKFLOW.md](WORKFLOW.md)。技术路线可以据实际验证调整，变更理由与影响须进入版本化文档。

## 12. 工作时间功能增补（T20已接受，T21待接线）

2026-10-25：T20数据层已接受并整合，Room/备份均为v4；T21工作页面、双卡、时间线及预览显示待接线。已发布0.1.3为v2，0.1.4升级覆盖测试见发布报告。

| 分层 | 新增与复用 |
| --- | --- |
| 记录 | 独立WorkRecord和WorkRepository，完整开始/结束时点与时区、结束日归档、唯一活动工作；复用时间校验、Clock和写事务机制，保持睡眠独立 |
| 目标/汇总 | AppSettings新增可空targetWorkMinutes，WorkSummary只汇总已完成时段，返回独立活动标记；复用T18的一致快照及错误契约，两个目标互不覆盖 |
| 数据迁移 | T20已实现Room3→4新增工作表和可空目标，保留旧1→2→3链；schema4经完整Room验证，禁止破坏性回退 |
| 快照/备份 | DataSnapshot、恢复清理/重建、中断回滚加入workRecords；已实现备份v4及对应计数，读取旧1–3按空工作/null目标归一，严格校验新增格式及资源限额 |
| 展示 | T21在T19首页基础上增加工作卡及开始/结束/历史/目标，接入第四种时间线及回顾/预览；开始工作不触发BedtimeScreen或系统栏隐藏 |
| 验证 | T20覆盖时段、汇总、迁移和完整备份恢复；T21覆盖真实页面、双卡、两类目标独立、小屏/大字体、原三类记录和黑屏修复回归；主管最后正式签名升级发包 |

精确接口和文件边界见 [T20](archive/tasks/T20.md)、[T21](tasks/T21.md)。T20不提前扩展只读时间线枚举导致旧UI编译失败，T21统一接入读模型和对应穷尽分支；T21在已接受T19首页上增量接入，保留黑屏起床修复。
