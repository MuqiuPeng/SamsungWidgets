package com.guans.widgets.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.guans.widgets.R

/**
 * 有特殊行为的事项：AI 额度的循环倒计时。其他事项都是用户自己输入的名字。
 * id 写进存储，不要改。显示距下次重置还剩多久；关联账号后用真实时间（见 UsageSource），
 * 没关联时手动计时：每周额度固定循环，5 小时额度到点就停，下次用的时候再点开始。
 */
enum class RoutinePreset(@StringRes val nameRes: Int, val loopMs: Long) {
    CLAUDE_SESSION(R.string.routine_claude_session, 5 * HOUR_MS),
    CLAUDE_WEEKLY(R.string.routine_claude_weekly, 7 * DAY_MS),
    CODEX_SESSION(R.string.routine_codex_session, 5 * HOUR_MS),
    CODEX_WEEKLY(R.string.routine_codex_weekly, 7 * DAY_MS);

    /** 空闲时不会自动开始下一轮的窗口。 */
    val stopsAtReset: Boolean get() = this == CLAUDE_SESSION || this == CODEX_SESSION

    companion object {
        fun of(id: String): RoutinePreset? = entries.firstOrNull { it.name == id }
    }
}

/** 一个事项的当前状态。preset 为 null 的是普通事项；lastDone 为 0 表示还没记录过。 */
data class Routine(
    val id: String,
    val name: String,
    val preset: RoutinePreset?,
    val lastDone: Long,
    val previous: Long,
    val live: LiveReset? = null,
) {
    val isLoop: Boolean get() = preset != null

    fun elapsed(now: Long): Long? = if (lastDone > 0) (now - lastDone).coerceAtLeast(0) else null

    /** 循环倒计时的下次重置时间；没开始或不是倒计时时为 null。 */
    fun nextReset(now: Long): Long? {
        val preset = preset ?: return null
        live?.resetAt?.takeIf { it > now }?.let { return it }
        if (live?.failed == false) return null
        if (lastDone <= 0) return null
        if (now < lastDone) return lastDone + preset.loopMs
        // 5 小时窗口空闲时不会自动开始下一轮
        if (preset.stopsAtReset) return (lastDone + preset.loopMs).takeIf { it > now }
        val rounds = (now - lastDone) / preset.loopMs + 1
        return lastDone + rounds * preset.loopMs
    }

    /** 本轮真实用量（0 到 1）；窗口已结束或没有真实数据时为 null。 */
    fun usage(now: Long): Float? = live?.takeIf { it.resetAt > now }?.let { (it.percent / 100f).coerceIn(0f, 1f) }

    /** 本轮倒计时已走过的比例（0 刚开始，接近 1 快重置）；不是倒计时或没开始时为 null。 */
    fun loopProgress(now: Long): Float? {
        val loopMs = preset?.loopMs ?: return null
        val reset = nextReset(now) ?: return null
        return (1f - (reset - now).toFloat() / loopMs).coerceIn(0f, 1f)
    }
}

/** 倒计时条目的底色：随本轮进度从几乎透明逐渐变红。 */
fun loopTintAlpha(progress: Float): Float = 0.06f + 0.5f * progress

const val LOOP_TINT_RED = 0xFFE53935

private const val HOUR_MS = 60 * 60 * 1000L
private const val DAY_MS = 24 * HOUR_MS
private const val MINUTE_MS = 60 * 1000L

/** 日常事项及每项上次完成的时间，所有小工具实例共用一份。 */
object Routines {
    private const val PREFS = "routines"
    const val MAX_ITEMS = 8
    const val MAX_NAME = 20

    /** 误点后可以撤销的时间窗口。 */
    const val UNDO_WINDOW_MS = 5 * 60 * 1000L

    private val DEFAULT = listOf("SHOWER", "SKINCARE", "WASH_HAIR", "BEDDING")

    /** 旧版本的固定事项：存储里还是这些 id，名字第一次读到时按当时的语言写进存储。 */
    private val LEGACY_NAMES = mapOf(
        "SHOWER" to R.string.routine_shower,
        "WASH_HAIR" to R.string.routine_wash_hair,
        "SKINCARE" to R.string.routine_skincare,
        "FACE_MASK" to R.string.routine_face_mask,
        "SHAVE" to R.string.routine_shave,
        "NAILS" to R.string.routine_nails,
        "HAIRCUT" to R.string.routine_haircut,
        "TOOTHBRUSH" to R.string.routine_toothbrush,
        "LAUNDRY" to R.string.routine_laundry,
        "BEDDING" to R.string.routine_bedding,
        "TOWELS" to R.string.routine_towels,
        "CLEAN" to R.string.routine_clean,
        "MOP" to R.string.routine_mop,
        "TRASH" to R.string.routine_trash,
        "PLANTS" to R.string.routine_plants,
        "FRIDGE" to R.string.routine_fridge,
        "WORKOUT" to R.string.routine_workout,
        "WEIGH" to R.string.routine_weigh,
        "VITAMINS" to R.string.routine_vitamins,
        "WATER_FILTER" to R.string.routine_water_filter,
        "CALL_FAMILY" to R.string.routine_call_family,
        "PET_BATH" to R.string.routine_pet_bath,
        "CAR_WASH" to R.string.routine_car_wash,
        "BACKUP" to R.string.routine_backup,
    )

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ids(context: Context): List<String> =
        prefs(context).getString("items", null)?.split(',')?.filter { it.isNotEmpty() } ?: DEFAULT

    fun all(context: Context): List<Routine> {
        val prefs = prefs(context)
        return ids(context).mapNotNull { id ->
            val preset = RoutinePreset.of(id)
            val name = prefs.getString("name_$id", null)
                ?: preset?.let { context.getString(it.nameRes) }
                ?: LEGACY_NAMES[id]?.let { res ->
                    context.getString(res).also { prefs.edit().putString("name_$id", it).apply() }
                }
                ?: return@mapNotNull null
            Routine(
                id = id,
                name = name,
                preset = preset,
                lastDone = prefs.getLong("last_$id", 0L),
                previous = prefs.getLong("prev_$id", 0L),
                live = preset?.let { UsageSource.live(context, it) },
            )
        }
    }

    fun setOrder(context: Context, ids: List<String>) {
        prefs(context).edit().putString("items", ids.take(MAX_ITEMS).joinToString(",")).apply()
    }

    /** 新增一个自己起名的事项。 */
    fun add(context: Context, name: String) {
        val current = ids(context)
        if (current.size >= MAX_ITEMS) return
        val id = "u" + System.currentTimeMillis().toString(36)
        prefs(context).edit().putString("name_$id", name.trim().take(MAX_NAME)).apply()
        setOrder(context, current + id)
    }

    fun add(context: Context, preset: RoutinePreset) {
        val current = ids(context)
        if (preset.name in current || current.size >= MAX_ITEMS) return
        setOrder(context, current + preset.name)
    }

    fun rename(context: Context, id: String, name: String) {
        prefs(context).edit().putString("name_$id", name.trim().take(MAX_NAME)).apply()
    }

    fun remove(context: Context, id: String) {
        setOrder(context, ids(context) - id)
        prefs(context).edit()
            .remove("name_$id")
            .remove("last_$id")
            .remove("prev_$id")
            .apply()
    }

    /** 记为在 time 完成；旧的时间留作撤销。 */
    fun markDone(context: Context, id: String, time: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        prefs.edit()
            .putLong("prev_$id", prefs.getLong("last_$id", 0L))
            .putLong("last_$id", time)
            .putString("undo_id", id)
            .putLong("undo_at", System.currentTimeMillis())
            .apply()
    }

    /** 小工具上可撤销的最近一次点击；超过时间窗口返回 null。 */
    fun pendingUndo(context: Context, now: Long = System.currentTimeMillis()): String? {
        val prefs = prefs(context)
        if (now - prefs.getLong("undo_at", 0L) > UNDO_WINDOW_MS) return null
        return prefs.getString("undo_id", null)
    }

    /** 恢复到上一次完成的时间。 */
    fun undo(context: Context, id: String) {
        val prefs = prefs(context)
        prefs.edit()
            .putLong("last_$id", prefs.getLong("prev_$id", 0L))
            .putLong("prev_$id", 0L)
            .remove("undo_id")
            .remove("undo_at")
            .apply()
    }
}

/** 小工具和设置页右侧显示的状态：普通事项是“多久之前”，倒计时是剩余时间“剩 4:24”。 */
fun formatStatus(context: Context, routine: Routine, now: Long): String {
    if (!routine.isLoop) return formatElapsed(context, routine.elapsed(now))
    val reset = routine.nextReset(now) ?: return context.getString(
        routine.live?.idleRes ?: if (routine.lastDone > 0) R.string.routine_loop_ended else R.string.routine_loop_not_started,
    )
    return formatRemaining(context, reset - now)
}

/** 剩余时间：一天以内 “4:24”（时:分），更久 “2 天 5 时”。 */
fun formatRemaining(context: Context, remaining: Long): String {
    // 向上取整到分钟，避免还剩几秒时显示 0:00
    val minutes = (remaining + MINUTE_MS - 1) / MINUTE_MS
    return if (minutes < 24 * 60) {
        context.getString(R.string.routine_left_hm, (minutes / 60).toInt(), (minutes % 60).toInt())
    } else {
        context.getString(R.string.routine_left_dh, (minutes / (24 * 60)).toInt(), (minutes / 60 % 24).toInt())
    }
}

/** “刚刚”“5 小时前”“2 天 3 小时前”“12 天前”；越久越粗略。 */
fun formatElapsed(context: Context, elapsed: Long?): String {
    if (elapsed == null) return context.getString(R.string.routine_never)
    val hours = elapsed / HOUR_MS
    val days = elapsed / DAY_MS
    return when {
        hours < 1 -> context.getString(R.string.routine_just_now)
        hours < 24 -> context.getString(R.string.routine_hours_ago, hours.toInt())
        days < 3 && hours % 24 > 0 -> context.getString(R.string.routine_days_hours_ago, days.toInt(), (hours % 24).toInt())
        else -> context.getString(R.string.routine_days_ago, days.toInt())
    }
}

