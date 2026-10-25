# T22 数据契约（供 T23 接入）

`AppContainer.todos: TodoRepository`，沿用 container 可注入 `clock` / `zoneIdProvider`。

`domain/Todo.kt`：

```kotlin
enum class HabitFrequency { DAILY, INTERVAL, WEEKLY }
enum class HabitStatus { ACTIVE, PAUSED, ARCHIVED }
data class TodoDraft(val id: String, val title: String, val date: LocalDate,
    val scheduledMinute: Int? = null)
data class HabitDraft(val id: String, val title: String, val startDate: LocalDate,
    val scheduledMinute: Int? = null, val frequency: HabitFrequency = HabitFrequency.DAILY,
    val intervalDays: Int = 1, val weekdaysMask: Int = 0)
```

`data/local/entity/TodoRecord`：`id, title, localDate: String (ISO), scheduledMinute: Int?, habitId: String?, habitDate: String?, completedAtUtcMs: Long?, deletedAtUtcMs: Long?, createdAtUtcMs, updatedAtUtcMs`。`habitDate` 固定为原生成日；单独编辑待办可改 `localDate`，不改变来源或生成去重键。`isCompleted` 只读属性。

`HabitRecord`：`id, title, startDate: String (ISO), scheduledMinute: Int?, frequency: HabitFrequency, intervalDays: Int, weekdaysMask: Int, status: HabitStatus, createdAtUtcMs, updatedAtUtcMs`；`isOngoingOn(date: LocalDate)`（非归档且日期不晚于 start+20）、`isDueOn(date: LocalDate)`。周一 bit0，周日 bit6；WEEKLY 必须 1..127，INTERVAL 必须 1..365。DAILY/INTERVAL 非相关字段会规范化（DAILY=1/0，INTERVAL mask=0，WEEKLY interval=1）。

`TodoRepository`：

```kotlin
fun today(): LocalDate
fun observeOn(date: LocalDate): Flow<List<TodoRecord>> // 隐藏墓碑，真正 Room Flow
fun observeHabits(): Flow<List<HabitRecord>> // 包含全部状态及过期习惯
fun observeOngoingHabits(date: LocalDate): Flow<List<HabitRecord>> // ACTIVE/PAUSED 且未过21自然日
suspend fun todo(id: String): TodoRecord?
suspend fun habit(id: String): HabitRecord?
suspend fun saveTodo(draft: TodoDraft): DataResult<TodoRecord>
suspend fun setCompleted(id: String, completed: Boolean): DataResult<TodoRecord>
suspend fun deleteTodo(id: String): DataResult<Boolean>
suspend fun saveHabit(draft: HabitDraft): DataResult<HabitRecord> // 新建 ACTIVE；编辑保留状态
suspend fun setHabitStatus(id: String, status: HabitStatus): DataResult<HabitRecord>
suspend fun generateFor(date: LocalDate = today()): DataResult<Int> // 返回新生成数量
```

页面进入、日期切换、跨午夜/时区变更，以及保存/恢复习惯后调用 `generateFor(selectedDate)`；方法只生成手机当前本地今日，过去/未来传入安全返回 0，不补造过去、不预造未来。习惯保存不自动生成：便于 UI 先处理保存结果再刷新。已生成待办保留标题/时间快照；暂停/结束不删实例。删除墓碑永久占据 `(habitId, habitDate)`，不再自动复活。

21天为 start..start+20（第1–21天）；第22天隐藏、不生成。暂停不延长周期，漏打不重置；未来开始习惯可显示。ARCHIVED 为结束状态，不提供恢复成 ACTIVE；尝试恢复返回 NOT_FOUND。

校验沿用现有 ErrorCode：空标题 INVALID_NAME，>120 字 TEXT_TOO_LONG，日期 INVALID_LOCAL_TIME（1900-01-01..2199-12-31），分钟/间隔/周掩码 INVALID_NUMBER，失效/已删除/归档编辑 NOT_FOUND。不新增 ErrorCode。

备份预览 `BackupPreview` 将追加 `todoCount`、`habitCount`（均默认0；包含软删除，准确反映完整恢复资源数量）；另有 `earliestLocalDate/latestLocalDate: String?` 表示待办/习惯的本地日期范围（避免把无时区日期伪造为UTC时间）。读取v1–5为0且全量恢复会清空这两表。新导出v6，Room5→6；T23负责设置页预览文案。
