# 「随手记」DeepSeek 单日回顾输入协议与取数规则（v1）

> **发送与本地边界说明（必读）**
> - **【会发送给 DeepSeek 模型的内容】**：
>   1. **系统消息（`role: "system"`）**：仅限本文 **第 1 节代码块内** 的中文系统提示词正文。
>   2. **用户消息（`role: "user"`）**：仅限符合 [`docs/ai-review/daily-example.v1.json`](daily-example.v1.json) 结构的单日精简 JSON 字符串。
> - **【仅留在本机、绝不发送给模型的内容】**：
>   - 照片二进制文件、缩略图、SHA-256 哈希、应用私有路径（`MediaAsset.relativePath` / `generationId`）；
>   - 数据库主键与外键标识符（`id`、`mealId`、`photoId`、`sessionId`、`itemId`、`exerciseId`、`habitId`）；
>   - 用户自由文本备注与描述（`MealRecord.text`、`MealRecord.satietyText`、`WorkoutSession.note`、`WorkoutSession.effortText`、`WorkoutItem.note`）；
>   - 选中日期之外的其他历史记录、待办/习惯表、以及 `Download/lifelog/latest.json` 或 ZIP 整库备份；
>   - 本文 **第 2 节至第 5 节** 的开发者字段表、口径推导、Room 取数与重试规则及虚构原始记录表（仅供开发者实现与主管验收核对）。

---

## 1. DeepSeek 中文系统提示词【发送给模型 · `system` 消息】

以下代码块内的全文可直接作为 DeepSeek Chat Completions 接口的 `system` 消息发送：

```text
你是「随手记」App 的单日生活记录回顾助手。用户消息会提供一份单日结构化 JSON 摘要（schemaVersion = 1）。请仅依据 JSON 中的事实数据，用温和、平实、客观的中文写出 2～4 句单日回顾（连成一段自然的话，不加标题、项目符号或寒暄，总字数控制在 180 字以内）。

撰写时必须严格遵守以下事实边界：
1. 就寝时段（sleep）按起床所在的结束日整段归档。有已完成记录时，用 completedDurationMinutes 表述“记录了……就寝时段”。进行中的就寝单独表述。不要用钟点重算或拆分时长，也不要推测实际入睡时间或睡眠质量。
2. 工作时长（work.completedDurationMinutes）只计已结束的工作段。hasActiveSegment 为 true 时，可用 activeStartedAt 和 snapshotAt 说明另一段仍在进行。若只有进行中记录，就只说该段仍在进行。开始和快照不在同一天时写明日期。
3. 饮食记录（meals）：recordCount 是当天已保存的饮食记录条数，totalPhotoCount 是关联照片总张数；同一餐关联多张照片仍计为 1 条饮食记录。你无法看到照片内容与文字备注，严禁猜测具体菜品、食材、热量、营养或早中晚餐次，也严禁由“记录了 1 条饮食”推断出“全天只吃了 1 顿饭”。
4. 训练次数用 sessionCount，项目数用 itemCount。durationSeconds 的单位是秒，distanceMeters 是米，weightGrams 是克，repetitions 是每组次数。0 克表示未附加外部重量。只描述适用于该类型且有记录的指标，跑步距离为 null 时可写“距离未填写”。保留自定义项目的通用名称。
5. 已完成时长为 null 表示没有已结束的记录，仍可能有进行中的一段。有已完成段且总分钟数为 0 时写“记录时长不足 1 分钟”。计数为 0 只说明没有该类记录。目标值是生成时的当前设置，不能用来判断历史日期当时是否达标。若 hasAnyRecord 为 false，只写一句：“当天还没有饮食、作息、工作或训练记录。”
```

---

## 2. 单日输入 JSON 字段表【仅本地开发者使用 · 不发送】

本表定义 [`docs/ai-review/daily-example.v1.json`](daily-example.v1.json) 中每一个字段的精确来源、单位、日期归属与缺值语义。所有查询均针对用户在「按日回顾」选中的当地日期 `:selectedDate`（格式 `YYYY-MM-DD`）。

### 2.1 发送字段映射与推导规则表

| JSON 字段路径 | 类型 | 样例值 | Room 来源或推导规则 | 单位 | 日期归属规则 | 缺值 / `null` / `0` 含义 |
| --- | --- | --- | --- | --- | --- | --- |
| `schemaVersion` | `Int` | `1` | 固定常量 `1`，标识单日回顾输入协议 v1 | 版本号 | 不涉及 | 必填，无缺值 |
| `selectedDate` | `String` | `"2026-11-18"` | `ReviewViewModel.date` 当前选中的 `LocalDate.toString()` | `YYYY-MM-DD` | 回顾页面的目标归档日 | 必填，无缺值 |
| `timeZone` | `String` | `"Asia/Shanghai"` | 取数时刻由 `zoneIdProvider().id` 提供（IANA 时区 ID，经 `RecordTime.zone` 校验） | IANA ZoneId | 用于格式化快照时刻及展示钟点 | 必填，无缺值 |
| `snapshotAt` | `String` | `"2026-11-18T16:30:00+08:00"` | 单次 Room 读取事务开始时读取一次 `clock.millis()`，按 `Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(timeZone))` 格式化为带偏移的 ISO-8601 | ISO-8601 时间戳 | 标识本次快照截取的精确时刻，界定进行中记录的观测截点 | 必填，无缺值 |
| `hasAnyRecord` | `Boolean` | `true` | 当 `sleep.completedSegmentCount > 0 \|\| sleep.hasActiveSegment \|\| work.completedSegmentCount > 0 \|\| work.hasActiveSegment \|\| meals.recordCount > 0 \|\| workouts.sessionCount > 0` 时为 `true`，否则为 `false` | 布尔值 | 汇总判断 `:selectedDate` 当日是否至少有 1 条归档或进行中记录 | `false` 表示该日四类记录均为空，待办和习惯另计，不代表数据库读取失败 |
| `sleep.targetMinutes` | `Int?` | `480` | `AppSettings(id = 1).targetSleepMinutes`（经 `Rules.targetSleepMinutes` 校验，范围 `1..1440`） | 分钟（`min`） | 全局当前每日睡眠目标设置 | `null` 表示用户未设置睡眠目标（不得默认填 `0` 或 `480`） |
| `sleep.completedSegmentCount` | `Int` | `1` | `SELECT COUNT(*) FROM SleepRecord WHERE archiveLocalDate = :selectedDate AND endedAtUtcMs IS NOT NULL` | 段（条） | 按完成起床时的结束当地日期 `archiveLocalDate` 归属 | `0` 表示该日无已结束的就寝记录 |
| `sleep.completedDurationMinutes` | `Int?` | `480` | 对满足 `archiveLocalDate = :selectedDate AND endedAtUtcMs IS NOT NULL` 的 `SleepRecord` 求和 `SUM(endedAtUtcMs - startedAtUtcMs)`，若无已结束记录则为 `null`；有记录时按 `(sumMs / 60000).toInt()` 转为整分钟，以此字段为日总量 | 分钟（`min`） | 按结束日（起床日 `archiveLocalDate`）**整段归入**，跨夜时段不按 `00:00` 截断 | `null` 表示当日没有已结束的就寝记录（严禁在无记录时传 `0`，以免被误读为“睡了 0 分钟”） |
| `sleep.hasActiveSegment` | `Boolean` | `false` | `SELECT EXISTS(SELECT 1 FROM SleepRecord WHERE archiveLocalDate = :selectedDate AND activeSlot = 1)`（等价于同日 `endedAtUtcMs IS NULL`） | 布尔值 | 未结束的作息在保存时 `archiveLocalDate = RecordTime.localDate(startedAtUtcMs, startZoneId)`（开始日）；**必须带 `archiveLocalDate = :selectedDate` 过滤**，禁止直接复用 `SleepSummaryDao` 的全库 `activeSlot = 1` | `false` 表示该选中日期下没有未结束的就寝记录 |
| `sleep.activeStartedAt` | `String?` | `null` | 若 `sleep.hasActiveSegment == true`，取该条 `SleepRecord` 的 `Instant.ofEpochMilli(startedAtUtcMs).atZone(ZoneId.of(startZoneId))` ISO-8601 字符串；否则为 `null` | ISO-8601 时间戳 | 归属在就寝开始日 `archiveLocalDate = :selectedDate` | `null` 表示该选中日期无进行中就寝记录 |
| `sleep.completedSegments` | `Array` | 见 JSON 样例 | 查询 `SleepRecord WHERE archiveLocalDate = :selectedDate AND endedAtUtcMs IS NOT NULL ORDER BY endedAtUtcMs ASC, id ASC` 映射列表 | 数组 | 每段均以 `archiveLocalDate == :selectedDate`（起床当地日期）归属 | `[]`（空数组）表示该日无已完成就寝段 |
| `sleep.completedSegments[].startedAt` | `String` | `"2026-11-17T23:00:00+08:00"` | `Instant.ofEpochMilli(startedAtUtcMs).atZone(ZoneId.of(startZoneId))` 格式化为 ISO-8601 | ISO-8601 时间戳 | 可落在前一日（跨夜就寝），但整段随结束日归档至 `:selectedDate` | 必填 |
| `sleep.completedSegments[].endedAt` | `String` | `"2026-11-18T07:00:00+08:00"` | `Instant.ofEpochMilli(endedAtUtcMs).atZone(ZoneId.of(endZoneId))` 格式化为 ISO-8601 | ISO-8601 时间戳 | 其当地日期即 `archiveLocalDate`（`2026-11-18`） | 必填（仅已结束段进入此数组） |
| `sleep.completedSegments[].durationMinutes` | `Int` | `480` | `((endedAtUtcMs - startedAtUtcMs) / 60000).toInt()`（受 `IntervalDurationPolicy.MIN_SLEEP_MS = 10 分钟` 保护，新建已完成段 `>= 10`） | 分钟（`min`） | 整段计入结束日 `archiveLocalDate` | 必填非负整数；旧记录不足 1 分钟时为 `0` |
| `work.targetMinutes` | `Int?` | `480` | `AppSettings(id = 1).targetWorkMinutes`（经 `Rules.targetWorkMinutes` 校验，范围 `1..1440`） | 分钟（`min`） | 全局当前每日工作目标设置 | `null` 表示用户未设置每日工作目标 |
| `work.completedSegmentCount` | `Int` | `1` | `SELECT COUNT(*) FROM WorkRecord WHERE archiveLocalDate = :selectedDate AND endedAtUtcMs IS NOT NULL` | 段（条） | 按结束工作时的当地日期 `archiveLocalDate` 归属 | `0` 表示该日无已结束的工作段 |
| `work.completedDurationMinutes` | `Int?` | `180` | 对满足 `archiveLocalDate = :selectedDate AND endedAtUtcMs IS NOT NULL` 的 `WorkRecord` 求和 `SUM(endedAtUtcMs - startedAtUtcMs)`，若无已结束段则为 `null`；有记录时按 `(sumMs / 60000).toInt()` 转为整分钟。**不含进行中工作段** | 分钟（`min`） | 按结束日 `archiveLocalDate` 整段归入；进行中记录不计入完成量 | `null` 表示当日无已结束工作段（即使 `hasActiveSegment == true`，若无已结束段，此字段仍为 `null`） |
| `work.hasActiveSegment` | `Boolean` | `true` | `SELECT EXISTS(SELECT 1 FROM WorkRecord WHERE archiveLocalDate = :selectedDate AND activeSlot = 1)`（等价于同日 `endedAtUtcMs IS NULL`） | 布尔值 | 未结束工作在 `WorkRepository.saveWorkInTransaction` 中保存 `archiveLocalDate = startDate`；**必须按 `archiveLocalDate = :selectedDate` 过滤**，禁止直接复用 `WorkSummaryDao` 的全库 `activeSlot = 1` | `false` 表示该选中日期下没有进行中的工作记录 |
| `work.activeStartedAt` | `String?` | `"2026-11-18T15:00:00+08:00"` | 若 `work.hasActiveSegment == true`，取该条 `WorkRecord` 的 `Instant.ofEpochMilli(startedAtUtcMs).atZone(ZoneId.of(startZoneId))` ISO-8601 字符串；否则为 `null` | ISO-8601 时间戳 | 归属在开始工作的当地日期 `archiveLocalDate = :selectedDate` | `null` 表示该选中日期无进行中工作 |
| `work.completedSegments` | `Array` | 见 JSON 样例 | 查询 `WorkRecord WHERE archiveLocalDate = :selectedDate AND endedAtUtcMs IS NOT NULL ORDER BY endedAtUtcMs ASC, id ASC` 映射列表 | 数组 | 每段均以结束日 `archiveLocalDate == :selectedDate` 归属 | `[]` 表示该日无已完成工作段 |
| `work.completedSegments[].startedAt` | `String` | `"2026-11-18T09:00:00+08:00"` | `Instant.ofEpochMilli(startedAtUtcMs).atZone(ZoneId.of(startZoneId))` 格式化为 ISO-8601 | ISO-8601 时间戳 | 随该段结束日归档 | 必填 |
| `work.completedSegments[].endedAt` | `String` | `"2026-11-18T12:00:00+08:00"` | `Instant.ofEpochMilli(endedAtUtcMs).atZone(ZoneId.of(endZoneId))` 格式化为 ISO-8601 | ISO-8601 时间戳 | 其当地日期即 `archiveLocalDate` | 必填 |
| `work.completedSegments[].durationMinutes` | `Int` | `180` | `((endedAtUtcMs - startedAtUtcMs) / 60000).toInt()`（受 `IntervalDurationPolicy.MIN_WORK_MS = 5 分钟` 保护） | 分钟（`min`） | 整段计入结束日 `archiveLocalDate` | 必填非负整数；旧记录不足 1 分钟时为 `0` |
| `meals.recordCount` | `Int` | `1` | `SELECT COUNT(*) FROM MealRecord WHERE localDate = :selectedDate`（按 `MealRecord` 主表行数统计，**不得因 JOIN `MealPhoto` 而按照片张数翻倍**） | 条 | 按 `MealRecord.localDate = RecordTime.localDate(occurredAtUtcMs, zoneId)` 归属 | `0` 表示当日未记录任何饮食（不代表没吃饭） |
| `meals.photoOnlyRecordCount` | `Int` | `1` | 当日 `MealRecord` 中满足 `text IS NULL` 且关联照片数 `photoCount > 0` 的记录条数 | 条 | 同 `MealRecord.localDate` | `0` 表示当日无纯照片饮食记录 |
| `meals.totalPhotoCount` | `Int` | `2` | 当日所有 `MealRecord` 关联照片数 `records.sumOf { it.photoCount }` | 张 | 随所属 `MealRecord.localDate` 归属 | `0` 表示当日饮食记录均未附图 |
| `meals.records` | `Array` | 见 JSON 样例 | 查询 `MealRecord WHERE localDate = :selectedDate ORDER BY occurredAtUtcMs ASC, id ASC` 映射列表 | 数组 | 按 `MealRecord.localDate == :selectedDate` 归属 | `[]` 表示当日无饮食记录 |
| `meals.records[].occurredAt` | `String` | `"2026-11-18T12:30:00+08:00"` | `Instant.ofEpochMilli(occurredAtUtcMs).atZone(ZoneId.of(zoneId))` 格式化为 ISO-8601 | ISO-8601 时间戳 | 当地日期必为 `selectedDate` | 必填 |
| `meals.records[].hasText` | `Boolean` | `false` | `meal.text != null`（注意：`meal.text` 原文留在手机，只发送布尔标志） | 布尔值 | 同所属 `MealRecord` | `false` 表示该条饮食未填文字描述 |
| `meals.records[].photoCount` | `Int` | `2` | 与 `TimelineRepository.timelineOn` 口径一致：优先统计 `MealPhoto` 表中 `mealId == meal.id` 的行数；若旧兼容行无 `MealPhoto` 明细则回退 `if (meal.photoId == null) 0 else 1`（范围 `0..9`） | 张 | 同所属 `MealRecord` | `0` 表示该条饮食无照片（此时 `hasText` 必为 `true`） |
| `meals.records[].satiety` | `String?` | `null` | `meal.satiety?.name`，取值枚举：`NOT_FULL` / `FIVE` / `SEVEN` / `NINE` / `FULL` / `CUSTOM`（注意：`satietyText` 自定义原文留在手机，不发送） | 枚举名 | 同所属 `MealRecord` | `null` 表示用户未选饱腹感 |
| `workouts.sessionCount` | `Int` | `1` | `SELECT COUNT(*) FROM WorkoutSession WHERE localDate = :selectedDate` | 次 | 按 `WorkoutSession.localDate = RecordTime.localDate(occurredAtUtcMs, zoneId)` 归属 | `0` 表示当日无训练记录（不代表没运动） |
| `workouts.itemCount` | `Int` | `2` | 当日所有 `WorkoutSession` 下属的 `WorkoutItem` 总数 `sessions.sumOf { it.items.size }` | 项（个） | 随所属 `WorkoutSession.localDate` 归属 | `0` 表示当日无训练记录（只要有训练，每次训练至少 1 个项目） |
| `workouts.sessions` | `Array` | 见 JSON 样例 | 查询 `WorkoutDao.workoutsOn(:selectedDate)` 并按 `occurredAtUtcMs ASC, id ASC` 及 `WorkoutWithItems.sorted()`（按 `WorkoutItem.sortOrder`、`WorkoutSet.sortOrder` 升序）排列 | 数组 | 按 `WorkoutSession.localDate == :selectedDate` 归属 | `[]` 表示当日无训练记录 |
| `workouts.sessions[].occurredAt` | `String` | `"2026-11-18T13:30:00+08:00"` | `Instant.ofEpochMilli(session.occurredAtUtcMs).atZone(ZoneId.of(session.zoneId))` 格式化为 ISO-8601 | ISO-8601 时间戳 | 当地日期必为 `selectedDate` | 必填 |
| `workouts.sessions[].effort` | `String?` | `"MODERATE"` | `session.effort?.name`，取值枚举：`EASY` / `MODERATE` / `HARD` / `VERY_HARD` / `CUSTOM`（注意：`effortText` 与 `session.note` 留在手机，不发送） | 枚举名 | 同所属 `WorkoutSession` | `null` 表示用户未选训练感受 |
| `workouts.sessions[].itemCount` | `Int` | `2` | 该次 `WorkoutSession` 下属的 `WorkoutItem` 数量（范围 `1..100`） | 项（个） | 同所属 `WorkoutSession` | 必填正整数 |
| `workouts.sessions[].items` | `Array` | 见 JSON 样例 | 该次 `WorkoutSession` 下按 `sortOrder ASC` 排序的 `WorkoutItem` 列表 | 数组 | 同所属 `WorkoutSession` | 非空数组（`1..100` 项） |
| `workouts.sessions[].items[].name` | `String` | `"跑步"` / `"卧推"` | 在手机端用 `exerciseId` 与 `typeSnapshot` 核对 `BuiltinExercises.entries`。只有匹配固定内置项目、定义标记为内置且类型一致时，才取内置目录的标准名。其余均按 `typeSnapshot` 映射为“自定义有氧项目”“自定义力量项目”或“自定义其他项目” | 展示名称 | 同所属 `WorkoutSession` | 必填；不发送 `nameSnapshot`、自定义名称原文或 ID |
| `workouts.sessions[].items[].type` | `String` | `"CARDIO"` / `"STRENGTH"` | `WorkoutItem.typeSnapshot.name`，取值枚举：`CARDIO` / `STRENGTH` / `OTHER` | 枚举名 | 同所属 `WorkoutSession` | 必填 |
| `workouts.sessions[].items[].durationSeconds` | `Long?` | `1800` / `null` | `WorkoutItem.durationSeconds`（`CARDIO` 与 `OTHER` 可选填正整数；`STRENGTH` 按 `WorkoutRepository` 规则恒为 `null`） | 秒（`s`） | 同所属 `WorkoutSession` | `null` 表示用户**未填写时长**（对 `CARDIO`/`OTHER`）或该动作类型不适用时长（对 `STRENGTH`）；**绝不写成 `0`** |
| `workouts.sessions[].items[].distanceMeters` | `Long?` | `null` | `WorkoutItem.distanceMeters`（仅 `CARDIO` 可选填正整数；`STRENGTH` 与 `OTHER` 按 `WorkoutRepository` 规则恒为 `null`） | 米（`m`） | 同所属 `WorkoutSession` | `null` 表示用户**未填写距离**（对 `CARDIO`）或该类型不适用距离（对 `STRENGTH`/`OTHER`）；**绝不写成 `0`** |
| `workouts.sessions[].items[].setCount` | `Int` | `0` / `3` | 该 `WorkoutItem` 下属的 `WorkoutSet` 数量 `sets.size`（`CARDIO` 与 `OTHER` 恒为 `0`；`STRENGTH` 为 `0..100`） | 组 | 同所属 `WorkoutSession` | `0` 表示未记录力量组（或非力量项目） |
| `workouts.sessions[].items[].sets` | `Array` | `[]` / 3 组对象 | 该 `WorkoutItem` 下按 `sortOrder ASC` 排序的 `WorkoutSet` 列表（空白占位组在保存时已由 `WorkoutRepository` 过滤，不落库） | 数组 | 同所属 `WorkoutSession` | `[]` 表示无力量组明细 |
| `workouts.sessions[].items[].sets[].weightGrams` | `Long?` | `60000` | `WorkoutSet.weightGrams`（用户输入 kg 经 `Units.kilogramsToGrams` 精确乘以 `1000` 保存为整克） | 克（`g`） | 同所属 `WorkoutSession` | `null` 表示该组**未填重量**；`0` 表示未附加外部重量，两者严格区分 |
| `workouts.sessions[].items[].sets[].repetitions` | `Int?` | `8` | `WorkoutSet.repetitions`（正整数） | 次（`reps`） | 同所属 `WorkoutSession` | `null` 表示该组**未填次数**（此时 `weightGrams` 必非空） |

### 2.2 留在本机、严禁进入请求 JSON 的字段清单

| 本地数据表 / 来源 | 留在本机的字段或文件 | 不发送的原因 |
| --- | --- | --- |
| 所有实体表 | `id`、`mealId`、`photoId`、`sessionId`、`itemId`、`exerciseId`、`habitId`、`createdAtUtcMs`、`updatedAtUtcMs`、`activeSlot` | 内部 UUID 与审计列对生成回顾无意义，属于本地标识符 |
| `MealRecord` / `MealPhoto` / `MediaAsset` | `photoId`、`relativePath`（`media/<generationId>/<id>.jpg`）、`generationId`、`sha256`、`width`、`height`、`byteSize` 及 JPEG 原图文件 | 隐私与体积边界：照片二进制、内部路径和哈希绝不离机 |
| `MealRecord` | `text`（上限 500 字）、`satietyText`（自定义饱腹感描述，上限 40 字） | 用户自由文本备注，留在手机本地 |
| `WorkoutSession` / `WorkoutItem` | `WorkoutSession.note`（上限 1000 字）、`WorkoutSession.effortText`（上限 40 字）、`WorkoutItem.note`（上限 1000 字）、自定义运动名称及 `WorkoutItem.nameSnapshot` | 用户自由文本留在手机本地 |
| `TodoRecord` / `HabitRecord` | 全部字段 | 首版「按日回顾」仅聚合饮食、睡眠、工作、训练四类时间线记录；待办与习惯独立于回顾时间线 |
| `Download/lifelog/latest.json` 及完整 ZIP 备份 | 整库导出、备份文件及内部恢复状态 | 仅取选中归档日的记录；归入该日的跨夜就寝可含前一日起点 |

---

## 3. 四个易错边界问题的口径与样例对照【仅本地开发者与主管复算使用 · 不发送】

[`docs/ai-review/daily-example.v1.json`](daily-example.v1.json) 在同一个虚构日期 `2026-11-18`（`Asia/Shanghai`）内同时覆盖以下四个典型边界场景：

### 3.1 跨夜就寝按结束日整段归档（8 小时 vs 自然日图 7 小时 vs 实际睡眠/睡眠质量）

- **样例数据**：用户在 `2026-11-17 23:00:00+08:00`（`startedAtUtcMs = 1794927600000`）点击就寝，在 `2026-11-18 07:00:00+08:00`（`endedAtUtcMs = 1794956400000`）点击起床。
- **归档与时长计算**：
  - 在 `RecordRepository.saveSleepInTransaction` 中，已结束记录的 `archiveLocalDate = RecordTime.localDate(endedAtUtcMs, endZoneId)`，即按**起床所在结束日**归档为 `"2026-11-18"`。
  - 整段时长 `1794956400000 - 1794927600000 = 28,800,000 ms = 480 分钟 = 8 小时`。因此 `2026-11-18` 的 `sleep.completedDurationMinutes = 480`（8 小时）。
- **三种不同含义的严格区分**：
  1. **结束日归档整段时长（8 小时 / 480 分钟）**：「今天」睡眠卡片与「按日回顾」使用的唯一汇总口径，完整保留跨夜作息的起止区间（前一天 23:00 至当天 07:00）。
  2. **自然日分布图落在当天的裁切时长（7 小时 / 420 分钟）**：`DayTimeAnalysis.dayTimeAnalysis`（用于作息历史页的当日 24 小时分布条和七日图）会按当天 `00:00`（`1794931200000`）至次日 `00:00` 裁切，前一天 `23:00–24:00` 的 1 小时落在 `11-17` 图上，当天 `00:00–07:00` 的 **7 小时**落在 `11-18` 图上。单日回顾输入 JSON **不使用**自然日裁切值，模型更不得自行按零点截断算成 7 小时。
  3. **实际睡着多久与睡眠质量（不可知）**：App 只记录用户手动标记的就寝至起床时间点（卧床/就寝时段），无生理传感器数据。回顾文案**必须写“记录了 8 小时就寝时段”**，严禁写“实际睡着 8 小时”或虚构“睡眠质量良好/深睡充足”。

### 3.2 已完成工作与进行中工作并存，以及查看历史日期时进行中记录的归属判定

- **样例数据与快照时刻**：
  - 快照时刻 `snapshotAt = "2026-11-18T16:30:00+08:00"`（`1794990600000`）。
  - **第一段工作（已结束）**：`09:00:00+08:00`（`1794963600000`）至 `12:00:00+08:00`（`1794974400000`），`archiveLocalDate = "2026-11-18"`，`activeSlot = null`，时长 `10,800,000 ms = 180 分钟（3 小时）`。
  - **第二段工作（进行中）**：`15:00:00+08:00`（`1794985200000`）开始，`endedAtUtcMs = null`，`endZoneId = null`，`activeSlot = 1`，`archiveLocalDate = "2026-11-18"`。
  - **已完成时长只计第一段**：尽管截至快照时刻 `16:30` 第二段已持续 90 分钟（首页圆环在 `ActiveDurationPreview.kt` 中仅作前台预览），持久化完成统计 `work.completedSegmentCount = 1`、`work.completedDurationMinutes = 180` **只计第一段（3 小时）**；第二段通过 `work.hasActiveSegment = true` 与 `work.activeStartedAt = "2026-11-18T15:00:00+08:00"` 单独表达。
- **为什么不能直接套用现有 `WorkSummaryDao` / `SleepSummaryDao` 的活跃标志**：
  - 现有 `WorkSummaryDao.kt` 第 23 行与 `SleepSummaryDao.kt` 第 23 行的查询为：
    ```sql
    EXISTS(SELECT 1 FROM WorkRecord WHERE activeSlot = 1) AS hasActiveWork
    EXISTS(SELECT 1 FROM SleepRecord WHERE activeSlot = 1) AS hasActiveSleep
    ```
  - 这两个 DAO 是专为**首页（「今天」）**设计的全局状态查询（参见 `SleepSummaryRepository.kt` 注释：`Pass HomeViewModel.today's date`），子查询**没有** `AND archiveLocalDate = :date` 条件。
  - 如果在「按日回顾」查看历史日期（例如查看 `2026-11-10`）时直接调用 `WorkSummaryDao.observe("2026-11-10")`，只要用户今天（`2026-11-18`）有一条 `activeSlot = 1` 的进行中工作，全库 `EXISTS` 就会返回 `true`，把今天正在工作的状态错误带到 `2026-11-10` 的历史回顾里。
- **查看历史日期时判断进行中记录归属的正确规则**：
  - 根据 `WorkRepository.saveWorkInTransaction`（第 116–127 行）和 `RecordRepository.saveSleepInTransaction`（第 153–164 行）：
    - 当记录**尚未结束**（`endedAtUtcMs == null`、`activeSlot == 1`）时，其 `archiveLocalDate` 保存的是**开始时刻所在的当地日期** `RecordTime.localDate(startedAtUtcMs, startZoneId)`；
    - 当记录**结束**后，其 `archiveLocalDate` 更新为**结束时刻所在的当地日期** `RecordTime.localDate(endedAtUtcMs, endZoneId)`。
  - 因此，在「按日回顾」生成任意选中日期 `:selectedDate` 的摘要时，判断该日是否有进行中记录，**必须同时限定 `archiveLocalDate = :selectedDate AND activeSlot = 1`（或 `endedAtUtcMs IS NULL`）**。这样：
    - 今天（`2026-11-18`）15:00 开始的进行中工作，`archiveLocalDate` 为 `"2026-11-18"`，仅在查看 `2026-11-18` 时 `hasActiveSegment = true`；
    - 查看 `2026-11-17` 或更早历史日期时，`WHERE archiveLocalDate = '2026-11-17' AND activeSlot = 1` 为 `false`，绝不会把今天的活跃状态污染到历史日期。

### 3.3 单条纯照片饮食关联多张照片（1 条饮食 vs 2 张照片 vs 菜品与全天餐次不可知）

- **样例数据**：当天 `12:30:00+08:00` 有一条 `MealRecord`（`text = null`，`satiety = null`，`satietyText = null`，`photoId` 指向首张封面），并在 `MealPhoto` 表中关联了 2 行（`sortOrder = 0` 和 `sortOrder = 1`）。
- **计数口径**：
  - 饮食记录条数必须按 `MealRecord` 主表统计：`meals.recordCount = 1`、`meals.photoOnlyRecordCount = 1`。
  - 关联照片张数按 `MealPhoto` 关联表统计：该条记录 `photoCount = 2`，当日 `meals.totalPhotoCount = 2`。
  - 实现查询时须像 `TimelineRepository.timelineOn` 一样先查 `MealRecord` 再按 `mealId` 聚合 `MealPhoto` 数量，避免 SQL `JOIN` 导致 1 餐 2 图被重复计为 2 条饮食记录。
- **模型表达边界**：照片与文字均不发送给模型；模型仅知“当天记录了 1 条饮食，包含 2 张照片，未填文字和饱腹感”，**既无法判断吃了什么菜品，也不能推断用户全天实际只吃了 1 餐**（用户可能吃了三餐但只随手拍了其中一餐）。

### 3.4 一次训练包含两个项目且可选数值空缺，以及整天无记录时的标准表达

- **样例数据**：当天 `13:30:00+08:00` 有 **1 次训练**（1 行 `WorkoutSession`），下属 **2 个训练项目**（2 行 `WorkoutItem`）：
  1. 项目 1（`sortOrder = 0`）：`nameSnapshot = "跑步"`，`typeSnapshot = CARDIO`，`durationSeconds = 1800`（30 分钟），**`distanceMeters = null`（可选训练数值空缺）**，`sets = []`。
  2. 项目 2（`sortOrder = 1`）：`nameSnapshot = "卧推"`，`typeSnapshot = STRENGTH`，`durationSeconds = null`，`distanceMeters = null`（按 `WorkoutRepository` 的 `INVALID_FIELDS_FOR_TYPE` 规则，力量项目不适用时长与距离），下属 3 组 `WorkoutSet`（每组 `weightGrams = 60000` 即 60 公斤、`repetitions = 8` 次）。
- **三者严格区分**：
  - **训练次数（`workouts.sessionCount = 1`）**：代表当天进行了 1 次训练（对应时间线上的 1 张训练卡片）。
  - **项目数（`workouts.itemCount = 2`）**：代表这 1 次训练内完成了 2 个动作项目（跑步 + 卧推），绝不能表述为“进行了 2 次训练”。
  - **未填写的可选数值（`distanceMeters = null`）**：在 `CARDIO`（有氧）项目中，时长和距离均为选填（正整数或 `null`）；`distanceMeters: null` 表示用户没有填写跑步距离，**绝不能转换成 `0` 米，模型也不能说“跑了 0 公里”或自行按配速估算距离**。
- **整天没有记录时模型的一句标准表达示例**：
  - 当选中日期在四类记录表中均无归档及进行中记录（`hasAnyRecord: false`）时，模型应输出：
    > **「当天还没有饮食、作息、工作或训练记录。」**
  - **严禁输出**：“这一天你没有吃饭、没有运动、没有工作，睡眠也是 0 小时。”（参见 `V1_SPEC.md` 第 7 节与 `AGENTS.md`：“缺记录不能解释为零睡眠、未吃饭或没运动”）。

---

## 4. 取数一致性、预览重试与失效刷新设计【仅本地开发者使用 · 不发送】

本节结合现有 [`SnapshotRepository.kt`](../../app/src/main/java/com/personal/lifelog/data/repository/SnapshotRepository.kt)、[`TimelineRepository.kt`](../../app/src/main/java/com/personal/lifelog/data/repository/TimelineRepository.kt)、[`SleepSummaryDao.kt`](../../app/src/main/java/com/personal/lifelog/data/local/dao/SleepSummaryDao.kt)、[`WorkSummaryDao.kt`](../../app/src/main/java/com/personal/lifelog/data/local/dao/WorkSummaryDao.kt) 与 [`RepositorySupport.kt`](../../app/src/main/java/com/personal/lifelog/data/repository/RepositorySupport.kt) 说明后续实现单日回顾取数层的四项机制：

1. **在一次 Room 一致性读取中生成选中日期的摘要**：
   - `TimelineRepository.timelineOn(date)` 通过 `combine` 组合多条独立 `Flow`，适合 UI 列表持续订阅，但在跨表写入或恢复期间不提供单事务快照；而 `SleepSummaryDao` / `WorkSummaryDao` 仅各自覆盖单表且 `activeSlot = 1` 未按日期过滤。
   - 因此，单日回顾取数应参照 `SnapshotRepository.read()` 的模式，在 `db.withTransaction { ... }` 单一 Room 事务内一次性完成：
     1. 固定本次快照的 `nowUtcMs = clock.millis()` 与 `zoneId = zoneIdProvider().id`；
     2. 读取 `db.metadata().settingsNow()` 获取 `targetSleepMinutes` 与 `targetWorkMinutes`；
     3. 按 `archiveLocalDate = :selectedDate` 读取当日 `SleepRecord` 与 `WorkRecord`（在事务内分别聚合已完成段与当日 `endedAtUtcMs == null` 的进行中段）；
     4. 按 `localDate = :selectedDate` 读取当日 `MealRecord` 及其在 `MealPhoto` 中的照片计数；
     5. 按 `localDate = :selectedDate` 读取当日 `WorkoutWithItems`（含 `WorkoutItem` 与 `WorkoutSet`，调用 `.sorted()` 保持稳定顺序）。
   - 单一 SQLite 事务可保证设置、四类记录、多图关联与训练组明细来自同一个数据库一致性时间点，不会出现半更新状态。
   - 睡眠、工作均按原始毫秒差先求和，再向下取整一次。举例：两段工作各 5 分 40 秒，单段各显示 5 分钟，全天合计 11 分钟。无已完成段时合计为 `null`。有合法历史短段且合计不足 1 分钟时为 `0`。时间戳按各自时区以带偏移量的 ISO-8601 输出（如 `ISO_OFFSET_DATE_TIME`），时长以 UTC 时间点相减。
2. **用户预览后让首次发送和重试使用同一份内容**：
   - 用户在「按日回顾」点击生成/预览时，执行一次上述 Room 事务，构造不可变的单日摘要对象并序列化为确定性的 JSON 字符串（包含固定的 `snapshotAt`），作为本次会话的**冻结快照（Frozen Payload）**保存在 ViewModel 状态中供用户预览。
   - 用户点击「发送」以及网络失败后点击「重试发送」时，**直接发送预览状态中已冻结的那份 JSON 字符串**，不在点击发送或重试时重新查询 Room，也不重新读取 `clock.millis()` 刷新 `snapshotAt`，从而保证“用户预览的内容 == 首次发送的内容 == 失败重试发送的内容”完全一致。
3. **用户修改或删除记录后在下次生成时读到新值**：
   - 与 `TimelineRepository`、`SleepSummaryDao` 一致，App **不建立持久化摘要缓存表**，内存中的冻结快照仅服务于当前预览与网络重试。
   - 当用户进入详情页新增、修改、删除任何记录（包括修改起床/结束工作日期导致 `archiveLocalDate` 迁移到其他日期），或切换 `ReviewViewModel.date`、或监听到 Room `invalidationTracker` 对应表变更时，立即清除（或标记过期）当前内存中的旧预览快照。用户下次点击生成时重新执行 `db.withTransaction { ... }`，即可读到修改或删除后的最新数值。
   - 回顾文案仅放在当前回顾页的 ViewModel 内存中。切日、相关记录或目标变更、重新生成时，旧结果和预览一起失效，需重新预览；旧请求晚到的响应不得覆盖新结果。退出该页或进程结束即释放。文案不写 Room、文件、持久化界面状态、日志或备份。
4. **数据库读取失败作为错误处理（Fail-Closed，绝不伪造空白日回顾）**：
   - 事务读取或必需设置校验失败时返回 `DataResult.Failure`。数据库已满映射 `STORAGE_FULL`，数据库无法打开或 I/O 不可用映射 `STORAGE_UNAVAILABLE`，其余数据库异常和缺失设置行映射 `STORAGE`。数据校验错误保留具体错误码；协程取消原样向上抛出。
   - UI 收到 `Failure` 时必须展示读取失败提示与重试入口，并**禁止发起 DeepSeek 请求**；严禁在 `catch` 块中吞掉异常并用空列表/零值拼装出一份 `hasAnyRecord = false` 的空白日 JSON 发给模型，否则会把数据库故障伪装成“这一天还没有记录”的正常回顾。

---

## 5. 虚构原始记录与示例回顾【末尾核对区：标明本地 vs 发送 vs 模型输出】

### 5.1 虚构 Room 原始记录【仅保存在手机本地 SQLite · 绝不发送】

取数上下文：
- 用户在「按日回顾」选中日期：`2026-11-18`
- 手机当前时区：`Asia/Shanghai`（`UTC+08:00`）
- 取数快照时刻 `clock.millis()`：`1794990600000`（即 `2026-11-18T16:30:00+08:00`）

#### （1）`AppSettings` 表（本地 Room 行）

| `id` | `themeMode` | `targetSleepMinutes` | `targetWorkMinutes` | `lastSuccessfulExportAtUtcMs` | `createdAtUtcMs` | `updatedAtUtcMs` |
| --- | --- | --- | --- | --- | --- | --- |
| `1` | `SYSTEM` | `480` | `480` | `null` | `1793491200000` | `1794873600000` |

#### （2）`SleepRecord` 表（本地 Room 行）

| `id`（留在本机） | `startedAtUtcMs` | `startZoneId` | `endedAtUtcMs` | `endZoneId` | `archiveLocalDate` | `activeSlot` | 本地复算说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `a1000000-0000-4000-8000-000000000001` | `1794927600000`（`2026-11-17 23:00:00+08:00`） | `Asia/Shanghai` | `1794956400000`（`2026-11-18 07:00:00+08:00`） | `Asia/Shanghai` | `2026-11-18` | `null` | 已完成，按起床日归档在 `2026-11-18`；整段时长 `1794956400000 - 1794927600000 = 28,800,000 ms = 480 分钟（8 小时）` |

#### （3）`WorkRecord` 表（本地 Room 行）

| `id`（留在本机） | `startedAtUtcMs` | `startZoneId` | `endedAtUtcMs` | `endZoneId` | `archiveLocalDate` | `activeSlot` | 本地复算说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `b2000000-0000-4000-8000-000000000001` | `1794963600000`（`2026-11-18 09:00:00+08:00`） | `Asia/Shanghai` | `1794974400000`（`2026-11-18 12:00:00+08:00`） | `Asia/Shanghai` | `2026-11-18` | `null` | 第一段已完成；时长 `1794974400000 - 1794963600000 = 10,800,000 ms = 180 分钟（3 小时）`，计入 `completedDurationMinutes` |
| `b2000000-0000-4000-8000-000000000002` | `1794985200000`（`2026-11-18 15:00:00+08:00`） | `Asia/Shanghai` | `null` | `null` | `2026-11-18` | `1` | 第二段进行中（未结束时 `archiveLocalDate` 为开始日 `2026-11-18`）；截至快照 `16:30` 已过 90 分钟，但**不计入** `completedDurationMinutes` |

#### （4）`MealRecord`、`MealPhoto` 与 `MediaAsset` 表（本地 Room 行）

- **`MealRecord` 表**：

| `id`（留在本机） | `occurredAtUtcMs` | `zoneId` | `localDate` | `text`（留在本机） | `satiety` | `satietyText`（留在本机） | `photoId`（留在本机） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `c3000000-0000-4000-8000-000000000001` | `1794976200000`（`2026-11-18 12:30:00+08:00`） | `Asia/Shanghai` | `2026-11-18` | `null` | `null` | `null` | `f1000000-0000-4000-8000-000000000001` |

- **`MealPhoto` 表**（同一餐关联 2 张有序照片，仍归属上述 1 条 `MealRecord`）：

| `mealId`（留在本机） | `photoId`（留在本机） | `sortOrder` |
| --- | --- | --- |
| `c3000000-0000-4000-8000-000000000001` | `f1000000-0000-4000-8000-000000000001` | `0` |
| `c3000000-0000-4000-8000-000000000001` | `f1000000-0000-4000-8000-000000000002` | `1` |

- **`MediaAsset` 表**（全部照片元数据与本地文件均留在本机，绝不发送）：

| `id`（留在本机） | `generationId`（留在本机） | `relativePath`（留在本机） | `mimeType` | `width` | `height` | `byteSize` | `sha256`（留在本机） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `f1000000-0000-4000-8000-000000000001` | `11111111-1111-4111-8111-111111111111` | `media/11111111-1111-4111-8111-111111111111/f1000000-0000-4000-8000-000000000001.jpg` | `image/jpeg` | `1440` | `1080` | `412800` | `5a8d...c901` |
| `f1000000-0000-4000-8000-000000000002` | `11111111-1111-4111-8111-111111111111` | `media/11111111-1111-4111-8111-111111111111/f1000000-0000-4000-8000-000000000002.jpg` | `image/jpeg` | `1440` | `1080` | `389120` | `7b4e...d302` |

#### （5）`WorkoutSession`、`WorkoutItem` 与 `WorkoutSet` 表（本地 Room 行）

- **`WorkoutSession` 表**（当天 1 次训练；本地自由文本备注 `note` 留在本机，不进入 JSON）：

| `id`（留在本机） | `occurredAtUtcMs` | `zoneId` | `localDate` | `effort` | `effortText`（留在本机） | `note`（自由文本备注，留在本机） |
| --- | --- | --- | --- | --- | --- | --- |
| `d4000000-0000-4000-8000-000000000001` | `1794979800000`（`2026-11-18 13:30:00+08:00`） | `Asia/Shanghai` | `2026-11-18` | `MODERATE` | `null` | `"午后训练状态平稳，左肩热身充分"` |

- **`WorkoutItem` 表**（同 1 次训练下包含 2 个项目；跑步的 `distanceMeters` 为 `null` 空缺；本地备注 `note` 留在本机）：

| `id`（留在本机） | `sessionId`（留在本机） | `exerciseId`（留在本机） | `nameSnapshot` | `typeSnapshot` | `sortOrder` | `durationSeconds` | `distanceMeters` | `note`（自由文本备注，留在本机） |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `e5000000-0000-4000-8000-000000000001` | `d4000000-0000-4000-8000-000000000001` | `00000000-0000-4000-8000-000000000001` | `跑步` | `CARDIO` | `0` | `1800`（30 分钟） | `null`（未填距离） | `"公园慢跑，未开手表记距离"` |
| `e5000000-0000-4000-8000-000000000002` | `d4000000-0000-4000-8000-000000000001` | `00000000-0000-4000-8000-000000000002` | `卧推` | `STRENGTH` | `1` | `null` | `null` | `null` |

- **`WorkoutSet` 表**（归属项目 2「卧推」的 3 组力量记录）：

| `id`（留在本机） | `itemId`（留在本机） | `sortOrder` | `weightGrams` | `repetitions` |
| --- | --- | --- | --- | --- |
| `f6000000-0000-4000-8000-000000000001` | `e5000000-0000-4000-8000-000000000002` | `0` | `60000`（60 公斤） | `8` |
| `f6000000-0000-4000-8000-000000000002` | `e5000000-0000-4000-8000-000000000002` | `1` | `60000`（60 公斤） | `8` |
| `f6000000-0000-4000-8000-000000000003` | `e5000000-0000-4000-8000-000000000002` | `2` | `60000`（60 公斤） | `8` |

---

### 5.2 发送给 DeepSeek 的用户消息【发送给模型 · `user` 消息】

由 5.1 本地 Room 原始记录在同一事务中脱敏、聚合生成的用户消息即为 [`docs/ai-review/daily-example.v1.json`](daily-example.v1.json) 原文（所有 UUID、照片路径与哈希、以及 `"午后训练状态平稳，左肩热身充分"`、`"公园慢跑，未开手表记距离"` 等自由文本备注均已被剥离留在本机）。

---

### 5.3 根据这份 JSON 可以写出的示例回顾【模型返回文案示例 · 展示给用户】

#### （1）示例回顾正文（针对 `daily-example.v1.json`）

> 从前一天 23:00 到当天 07:00，记录了 8 小时就寝时段。09:00 至 12:00 已记录工作 3 小时，15:00 开始的一段截至 16:30 仍在进行。当天有 1 条纯照片饮食记录，附 2 张照片。1 次训练包含 2 个项目，跑步 30 分钟，距离未填写，卧推 3 组，每组 60 公斤、8 次。

#### （2）示例回顾合规核对【仅本地主管验收核对 · 不发送】

- **睡眠核对**：明确写出“从前一天 23:00 至当天 07:00 记录了 8 小时就寝时段”，未误用自然日裁切图的 7 小时，未臆造实际入睡时长或睡眠质量。
- **工作核对**：明确区分上午已完成的 3 小时（180 分钟）与 15:00 开始、截至 16:30 仍在进行中的第二段工作，未将进行中的 90 分钟混入已完成时长。
- **饮食核对**：明确写出“记录了 1 条纯照片饮食（附 2 张照片）”，未把 2 张照片当成 2 条饮食，未猜测菜品名称，也未断言全天只吃了一顿饭。
- **训练核对**：明确区分“1 次训练”与“2 个项目”，准确指出跑步已填 30 分钟且未填距离（未写成 0 公里），卧推为 3 组 × 60 公斤 × 8 次，且未泄露本地训练备注。
- **整天无记录（`hasAnyRecord: false`）时的一句示例回顾**：
  > 当天还没有饮食、作息、工作或训练记录。
