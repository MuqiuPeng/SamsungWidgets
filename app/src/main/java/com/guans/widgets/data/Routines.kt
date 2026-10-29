package com.guans.widgets.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.guans.widgets.R

/**
 * 可选的日常事项。id 写进存储，不要改；名字随语言变化。
 * loopMs > 0 的是循环倒计时：从开始时间起每隔 loopMs 重置一次，显示距下次重置还剩多久。
 */
enum class RoutinePreset(@StringRes val nameRes: Int, val category: Category, val loopMs: Long = 0L) {
    SHOWER(R.string.routine_shower, Category.CARE),
    WASH_HAIR(R.string.routine_wash_hair, Category.CARE),
    SKINCARE(R.string.routine_skincare, Category.CARE),
    FACE_MASK(R.string.routine_face_mask, Category.CARE),
    SHAVE(R.string.routine_shave, Category.CARE),
    NAILS(R.string.routine_nails, Category.CARE),
    HAIRCUT(R.string.routine_haircut, Category.CARE),
    TOOTHBRUSH(R.string.routine_toothbrush, Category.CARE),

    LAUNDRY(R.string.routine_laundry, Category.HOME),
    BEDDING(R.string.routine_bedding, Category.HOME),
    TOWELS(R.string.routine_towels, Category.HOME),
    CLEAN(R.string.routine_clean, Category.HOME),
    MOP(R.string.routine_mop, Category.HOME),
    TRASH(R.string.routine_trash, Category.HOME),
    PLANTS(R.string.routine_plants, Category.HOME),
    FRIDGE(R.string.routine_fridge, Category.HOME),

    WORKOUT(R.string.routine_workout, Category.HEALTH),
    WEIGH(R.string.routine_weigh, Category.HEALTH),
    VITAMINS(R.string.routine_vitamins, Category.HEALTH),
    WATER_FILTER(R.string.routine_water_filter, Category.HEALTH),

    CALL_FAMILY(R.string.routine_call_family, Category.LIFE),
    PET_BATH(R.string.routine_pet_bath, Category.LIFE),
    CAR_WASH(R.string.routine_car_wash, Category.LIFE),
    BACKUP(R.string.routine_backup, Category.LIFE),

    CLAUDE_SESSION(R.string.routine_claude_session, Category.LOOP, 5 * HOUR_MS),
    CLAUDE_WEEKLY(R.string.routine_claude_weekly, Category.LOOP, 7 * DAY_MS);

    val isLoop: Boolean get() = loopMs > 0

    enum class Category(@StringRes val nameRes: Int) {
        CARE(R.string.routine_cat_care),
        HOME(R.string.routine_cat_home),
        HEALTH(R.string.routine_cat_health),
        LIFE(R.string.routine_cat_life),
        LOOP(R.string.routine_cat_loop),
    }

    companion object {
        fun of(id: String): RoutinePreset? = entries.firstOrNull { it.name == id }
    }
}

/** 一个事项的当前状态。lastDone 为 0 表示还没记录过。 */
data class Routine(val preset: RoutinePreset, val lastDone: Long, val previous: Long) {
    fun elapsed(now: Long): Long? = if (lastDone > 0) (now - lastDone).coerceAtLeast(0) else null

    /** 循环倒计时的下次重置时间；没开始或不是倒计时时为 null。 */
    fun nextReset(now: Long): Long? {
        if (!preset.isLoop || lastDone <= 0) return null
        if (now < lastDone) return lastDone + preset.loopMs
        val rounds = (now - lastDone) / preset.loopMs + 1
        return lastDone + rounds * preset.loopMs
    }

    /** 本轮倒计时已走过的比例（0 刚开始，接近 1 快重置）；不是倒计时或没开始时为 null。 */
    fun loopProgress(now: Long): Float? {
        val reset = nextReset(now) ?: return null
        return (1f - (reset - now).toFloat() / preset.loopMs).coerceIn(0f, 1f)
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

    /** 误点后可以撤销的时间窗口。 */
    const val UNDO_WINDOW_MS = 5 * 60 * 1000L

    private val DEFAULT = listOf(RoutinePreset.SHOWER, RoutinePreset.SKINCARE, RoutinePreset.WASH_HAIR, RoutinePreset.BEDDING)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Routine> {
        val prefs = prefs(context)
        val ids = prefs.getString("items", null)?.split(',')?.mapNotNull(RoutinePreset::of)
            ?: DEFAULT
        return ids.map { preset ->
            Routine(
                preset = preset,
                lastDone = prefs.getLong("last_${preset.name}", 0L),
                previous = prefs.getLong("prev_${preset.name}", 0L),
            )
        }
    }

    fun setOrder(context: Context, presets: List<RoutinePreset>) {
        prefs(context).edit().putString("items", presets.take(MAX_ITEMS).joinToString(",") { it.name }).apply()
    }

    fun add(context: Context, preset: RoutinePreset) {
        val current = all(context).map { it.preset }
        if (preset in current || current.size >= MAX_ITEMS) return
        setOrder(context, current + preset)
    }

    fun remove(context: Context, preset: RoutinePreset) {
        setOrder(context, all(context).map { it.preset } - preset)
        prefs(context).edit()
            .remove("last_${preset.name}")
            .remove("prev_${preset.name}")
            .apply()
    }

    /** 记为在 time 完成；旧的时间留作撤销。 */
    fun markDone(context: Context, preset: RoutinePreset, time: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        prefs.edit()
            .putLong("prev_${preset.name}", prefs.getLong("last_${preset.name}", 0L))
            .putLong("last_${preset.name}", time)
            .putString("undo_id", preset.name)
            .putLong("undo_at", System.currentTimeMillis())
            .apply()
    }

    /** 小工具上可撤销的最近一次点击；超过时间窗口返回 null。 */
    fun pendingUndo(context: Context, now: Long = System.currentTimeMillis()): RoutinePreset? {
        val prefs = prefs(context)
        if (now - prefs.getLong("undo_at", 0L) > UNDO_WINDOW_MS) return null
        return prefs.getString("undo_id", null)?.let(RoutinePreset::of)
    }

    /** 恢复到上一次完成的时间。 */
    fun undo(context: Context, preset: RoutinePreset) {
        val prefs = prefs(context)
        prefs.edit()
            .putLong("last_${preset.name}", prefs.getLong("prev_${preset.name}", 0L))
            .putLong("prev_${preset.name}", 0L)
            .remove("undo_id")
            .remove("undo_at")
            .apply()
    }
}

/** 小工具和设置页右侧显示的状态：普通事项是“多久之前”，倒计时是剩余时间“剩 4:24”。 */
fun formatStatus(context: Context, routine: Routine, now: Long): String {
    if (!routine.preset.isLoop) return formatElapsed(context, routine.elapsed(now))
    val reset = routine.nextReset(now) ?: return context.getString(R.string.routine_loop_not_started)
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

