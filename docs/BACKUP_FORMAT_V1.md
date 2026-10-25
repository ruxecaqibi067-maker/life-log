# 备份格式（v1 基础与 v2/v3 增量）

T18 源码当前导出 `formatVersion/dataVersion=3`，读取兼容 v1/v2/v3；这不代表已发布新的 App 版本。以下 v1 字段为基础，v2/v3 增量见文末。manifest 与 data 的版本必须相同。

ZIP 只允许 `manifest.json`、`data.json` 和 `media/<规范小写UUID>.jpg`，不包含目录条目、系统文件、设备路径、运行时 generation 或恢复日志。ZIP64、多卷、加密和符号链接不支持。未加密，T14 须在保存前说明包含生活记录和照片。

`manifest.json` 必需字段：`formatVersion:1`、`appVersion:string`、`exportedAtUtcMs:int64`、`counts`（meals/sleeps/exercises/sessions/items/sets/media 各为整数）、`files`（每项 path/byteSize/sha256）。files 覆盖 data.json 和所有照片，不含 manifest 自身。大小与 SHA-256 均按实际字节验证。

`data.json` 必需字段：`dataVersion:1`、`meals`、`sleeps`、`exercises`、`sessions`、`items`、`sets`、`media` 数组和单个 `settings` 对象。各对象与 Room v1 实体的同名字段一一对应，以下例外：

- MediaAsset 不导出 generationId/relativePath；照片路径由 id 推导，导入时生成新的本地路径。
- SleepRecord 不导出 activeSlot；按 endedAtUtcMs 是否为 null 重建。
- ExerciseDefinition 不导出 normalizedName/activeNameKey；按名称和隐藏状态重建。
- RestoreState 完全不导出，提交标记只属于本地恢复协议。

所有其余字段必需存在；可空字段显式为 JSON null。ID 是规范小写 UUID；枚举使用实体枚举名；数值使用整数，重量为克、距离为米、时长为秒、时间点为 UTC 毫秒；时区和保存的当地日期同时保留。禁止字符串到数值的隐式转换、浮点数、重复 JSON 键、超深嵌套、无效 UTF-8。保留 null 与 0 的不同含义，组中的 0 克合法。未知可选字段可忽略；未知格式/数据版本、未知枚举或缺必需字段拒绝，不做部分恢复。

上限见 `BackupLimits`，与路线一致：ZIP/展开各 2 GiB、data 32 MiB、照片 8 MiB、10002 条目、10000 条三类记录、1000 定义、每训练 100 项、每项 100 组、总 100000 组；manifest 额外限制 4 MiB。实际流量计数，不依赖 ZIP 大小声明。图片先读尺寸限制 1 亿像素，再降采样解码验证，避免按外部尺寸申请整图内存。计数、派生日期、全部引用、顺序、活动作息唯一性及区间重叠、类型数值规则均验证。

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
