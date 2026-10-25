# 备份格式（v1 基础与 v2/v3/v4/v5 增量）

本开发分支导出 `formatVersion/dataVersion=5`，读取兼容 v1–v5；已发布 0.1.4 的格式仍为 v4，App交付与升级证据见 [0.1.4报告](releases/0.1.4.md)。本轮运动录入优化尚待主管整合及发布，不代表已有新版安装包。以下 v1 字段为基础，后续增量见文末。manifest 与 data 的版本必须相同。

ZIP 只允许 `manifest.json`、`data.json` 和 `media/<规范小写UUID>.jpg`，不包含目录条目、系统文件、设备路径、运行时 generation 或恢复日志。ZIP64、多卷、加密和符号链接不支持。未加密，T14 须在保存前说明包含生活记录和照片。

`manifest.json` 必需字段：`formatVersion:1`、`appVersion:string`、`exportedAtUtcMs:int64`、`counts`（meals/sleeps/exercises/sessions/items/sets/media 各为整数）、`files`（每项 path/byteSize/sha256）。files 覆盖 data.json 和所有照片，不含 manifest 自身。大小与 SHA-256 均按实际字节验证。

`data.json` 必需字段：`dataVersion:1`、`meals`、`sleeps`、`exercises`、`sessions`、`items`、`sets`、`media` 数组和单个 `settings` 对象。各对象与 Room v1 实体的同名字段一一对应，以下例外：

- MediaAsset 不导出 generationId/relativePath；照片路径由 id 推导，导入时生成新的本地路径。
- SleepRecord 不导出 activeSlot；按 endedAtUtcMs 是否为 null 重建。
- ExerciseDefinition 不导出 normalizedName/activeNameKey；按名称和隐藏状态重建。
- RestoreState 完全不导出，提交标记只属于本地恢复协议。

所有其余字段必需存在；可空字段显式为 JSON null。ID 是规范小写 UUID；枚举使用实体枚举名；数值使用整数，重量为克、距离为米、时长为秒、时间点为 UTC 毫秒；时区和保存的当地日期同时保留。禁止字符串到数值的隐式转换、浮点数、重复 JSON 键、超深嵌套、无效 UTF-8。保留 null 与 0 的不同含义，组中的 0 克合法。未知可选字段可忽略；未知格式/数据版本、未知枚举或缺必需字段拒绝，不做部分恢复。

上限见 `BackupLimits`，与路线一致：ZIP/展开各 2 GiB、data 32 MiB、照片 8 MiB、10002 条目、10000 条记录（饮食＋作息＋训练＋工作，四类合计）、1000 定义、每训练 100 项、每项 100 组、总 100000 组；manifest 额外限制 4 MiB。实际流量计数，不依赖 ZIP 大小声明。图片先读尺寸限制 1 亿像素，再降采样解码验证，避免按外部尺寸申请整图内存。计数、派生日期、全部引用、顺序、活动作息/工作各自唯一性及各自区间重叠、类型数值规则均验证。

导出持有全局 WriteCoordinator，通过 Room 事务取得 DataSnapshot，复制并校验照片，打包后从 ZIP 重新读取自检，再通过 BackupDestination 写入并关闭 SAF 文档，随后记录成功时间。失败或取消尽力删除目标和暂存；provider 拒绝删除时残留文档仍不得报告成功。内部回滚复用 prepare，不更新成功导出时间。

正常虚构样例由 `BackupExportTest.completePortableSnapshotAndFixture` 生成，位于忽略目录 `artifacts/t11/synthetic-backup.zip` 与 `.sha256`；可重复生成，包含三类记录、照片、未结束作息、隐藏未使用自定义项目和含空值/零重量的逐组训练。测试源是入库的格式样例，不提交用户数据。

## v2 饱腹感增量

v2 为饮食新增可空 `satietyText`，支持 `NOT_FULL` 和 `CUSTOM`；只有 CUSTOM 可携带去首尾空白后 1–40 字的描述。v1 缺少该字段按 null 读取，既有枚举名与含义不变。

## v3 每日睡眠目标增量（T18）

- `settings.targetSleepMinutes` 为当前全局每日时长目标，单位分钟；合法值为整数 1–1440 或 JSON null（未设置）。不带时区，不保存历史逐日目标，不默认填入480。
- v3 必须显式包含该字段；读取 v1/v2 时缺失按 null。存在但类型错误、非整数、溢出、越界均拒绝预检，不静默变成 null。
- 导出、快照、全量恢复及回滚完整保留该设置。成功恢复旧备份会将当前目标恢复为 null；取消或提交前失败不改原目标，回滚恢复对应快照目标。
- 新 App 可读旧备份；旧版只支持至 v1/v2 的 App 拒绝新 v3，不提供向旧版降级导出。升级前保留旧备份，正式版本递增、签名覆盖验证与发布由主管执行。
- `scripts/verify_backup.py` 保留 `--allow-v1-to-v2`，新增 `--allow-v2-to-v3`、`--allow-v1-to-v3`。跨版本比较须显式选择准确方向，仅补齐定义的可空新增字段；目标变成480、记录改变或照片字节改变仍失败。`--ignore-export-clock` 只允许原有两个导出时钟字段变化。

## v4 工作时段与每日工作目标增量（T20）

- `data.workRecords` 为必需数组（没有工作时为 `[]`）；`manifest.counts.workRecords` 为必需整数，必须与数组条数一致。工作与训练 `sessions/items/sets` 分开，不修改旧三类字段。v4 的数组、计数或目标缺失/类型错误均拒绝预检，不进行部分恢复。
- 每个工作对象必需字段：`id: UUID`、`startedAtUtcMs: int64`、`startZoneId: string`、`endedAtUtcMs: int64|null`、`endZoneId: string|null`、`archiveLocalDate: string`、`createdAtUtcMs: int64`、`updatedAtUtcMs: int64`。不导出 `activeSlot`，导入按结束点是否为 null 重建为1或null。时间点保留原毫秒；结束须晚于开始，差值须可用有符号int64精确表示；保存日期必须与保存时区推导的日期一致，更新时间不早于创建时间。
- 未结束工作必须无结束时区，最多一条；完成工作必须有结束时区。工作段各自不重叠，相邻可相接；与睡眠、训练时间重合合法，不自动结束另一类别。超24小时的已保存合法工作不因导入被截断或拆分；补录/结束时的确认属于交互写入协议。
- 进行中归开始日；完成后整段归结束时区的结束日。跨夜不按午夜分摊。工作计入总记录资源上限和预览最早/最晚时点；进行中工作有独立 `hasActiveWork` 标志，不能仅根据旧三类判断空备份。
- `settings.targetWorkMinutes` 必需，单位分钟，合法值为整数1–1440或JSON null（未设置）。与 `targetSleepMinutes` 完全独立；null不默认补480。浮点、字符串、布尔、溢出、越界拒绝。
- 读取真实旧v1/v2/v3时，工作列表为空、工作目标为null；旧manifest比较前补 `workRecords:0`，不要求旧ZIP包含新计数。v3保留原睡眠目标，v1/v2继续按原规则缺睡眠目标为null。manifest/data版本仍必须完全一致。
- 全量恢复旧备份会清空当前工作与工作目标；取消或提交前失败保留原完整快照。回滚备份和中断恢复均包含工作、两个目标、旧三类和照片，提交前保留旧整份状态，提交后保留新整份状态。
- `BackupPreview` 在旧字段后增加 `hasActiveWork: Boolean`、`targetSleepMinutes: Int?`、`targetWorkMinutes: Int?`；`counts` 对所有支持的版本均包含归一后的 `workRecords`。时间范围包含工作开始/结束点。T21负责预览与结果页面显示，本包只交付字段。
- 新App读取v1–v4；只支持至v3的旧App拒绝v4，不提供降级导出。当前公开0.1.3仍为v2；Room/备份v4不等于App versionCode或已发布版本。
- 比较脚本保留1→2、2→3、1→3，增加 `--allow-v1-to-v4`、`--allow-v2-to-v4`、`--allow-v3-to-v4`。仅在显式正确方向下为旧格式补定义的空工作列表/null工作目标，并按原链补satietyText及睡眠目标；v3非空睡眠目标必须保持。真实工作、其他记录、两个目标或照片字节发生改变仍失败；`--ignore-export-clock`仍只忽略两个既定导出时钟字段。脚本用于虚构升级样例的语义比较，不能替代App完整预检。

## v5 运动后感觉增量（2026-10-26 用户反馈）

- 每个 `sessions` 对象新增必需的可空字段：`effort` 和 `effortText`。`effort` 为 `EASY / MODERATE / HARD / VERY_HARD / CUSTOM` 或 null，依次显示轻松、适中、吃力、非常吃力、自定义。评价属于整次训练，选填；未评价保持 null，不根据训练量或项目类型推断。
- 仅 `CUSTOM` 可以携带 `effortText`，须为去首尾空白后的 1–40 个 Unicode 码点；其余评价或 null 必须搭配 `effortText=null`。未知枚举、字段缺失、类型不符、空白/超长自定义、非自定义携带文字均拒绝预检。
- v1–v4 读取时统一补 `effort=null, effortText=null`，不把旧备注解释为评价；既有工作、目标、照片、训练项目与组、历史快照及时间语义全部保留。新导出只用 v5，旧版 App 会拒绝 v5，不提供降级导出。
- Room 同时新增真实 v4→v5 加列迁移；旧训练保持未评价，无删表或重建训练量。快速输入的“组数、每组次数、重量”在保存时展开为原有的独立组，`items/sets` 结构、单位和 ID 语义未变，因此备份仍可保留各组不同训练量。
- 全量恢复和回滚使用同一完整 `DataSnapshot`，包含评价字段。比较脚本新增 `--allow-v1-to-v5` 至 `--allow-v4-to-v5`；只补已定义的空评价及必要的旧格式增量，4→5 保留工作及两个目标，任何真实记录/评价/照片变化仍应失败。
