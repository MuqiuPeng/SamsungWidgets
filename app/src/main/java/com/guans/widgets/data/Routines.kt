package com.guans.widgets.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.guans.widgets.R

/** 可选的日常事项。id 写进存储，不要改；名字随语言变化。cycleDays 是添加时的默认提醒周期。 */
enum class RoutinePreset(@StringRes val nameRes: Int, val category: Category, val cycleDays: Int) {
    SHOWER(R.string.routine_shower, Category.CARE, 1),
    WASH_HAIR(R.string.routine_wash_hair, Category.CARE, 2),
    SKINCARE(R.string.routine_skincare, Category.CARE, 1),
    FACE_MASK(R.string.routine_face_mask, Category.CARE, 3),
    SHAVE(R.string.routine_shave, Category.CARE, 2),
    NAILS(R.string.routine_nails, Category.CARE, 7),
    HAIRCUT(R.string.routine_haircut, Category.CARE, 30),
    TOOTHBRUSH(R.string.routine_toothbrush, Category.CARE, 90),

    LAUNDRY(R.string.routine_laundry, Category.HOME, 3),
    BEDDING(R.string.routine_bedding, Category.HOME, 7),
    TOWELS(R.string.routine_towels, Category.HOME, 7),
    CLEAN(R.string.routine_clean, Category.HOME, 7),
    MOP(R.string.routine_mop, Category.HOME, 7),
    TRASH(R.string.routine_trash, Category.HOME, 1),
    PLANTS(R.string.routine_plants, Category.HOME, 3),
    FRIDGE(R.string.routine_fridge, Category.HOME, 30),

    WORKOUT(R.string.routine_workout, Category.HEALTH, 2),
    WEIGH(R.string.routine_weigh, Category.HEALTH, 7),
    VITAMINS(R.string.routine_vitamins, Category.HEALTH, 1),
    WATER_FILTER(R.string.routine_water_filter, Category.HEALTH, 90),

    CALL_FAMILY(R.string.routine_call_family, Category.LIFE, 7),
    PET_BATH(R.string.routine_pet_bath, Category.LIFE, 14),
    CAR_WASH(R.string.routine_car_wash, Category.LIFE, 30),
    BACKUP(R.string.routine_backup, Category.LIFE, 30);

    enum class Category(@StringRes val nameRes: Int) {
        CARE(R.string.routine_cat_care),
        HOME(R.string.routine_cat_home),
        HEALTH(R.string.routine_cat_health),
        LIFE(R.string.routine_cat_life),
    }

    companion object {
        fun of(id: String): RoutinePreset? = entries.firstOrNull { it.name == id }
    }
}

/** 一个事项的当前状态。lastDone 为 0 表示还没记录过；cycleDays 为 0 表示不提醒。 */
data class Routine(val preset: RoutinePreset, val lastDone: Long, val cycleDays: Int, val previous: Long) {
    fun elapsed(now: Long): Long? = if (lastDone > 0) (now - lastDone).coerceAtLeast(0) else null

    /** 已过去的时间占周期的比例；没有周期或没记录过时为 null。 */
    fun progress(now: Long): Float? {
        val elapsed = elapsed(now) ?: return null
        if (cycleDays <= 0) return null
        return elapsed.toFloat() / (cycleDays * DAY_MS)
    }

    fun overdue(now: Long): Boolean = (progress(now) ?: 0f) >= 1f
}

private const val HOUR_MS = 60 * 60 * 1000L
const val DAY_MS = 24 * HOUR_MS

/** 日常事项及每项上次完成的时间，所有小工具实例共用一份。 */
object Routines {
    private const val PREFS = "routines"
    const val MAX_ITEMS = 8
    val CYCLE_OPTIONS = listOf(0, 1, 2, 3, 7, 14, 30, 90)

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
                cycleDays = prefs.getInt("cycle_${preset.name}", preset.cycleDays),
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
            .remove("cycle_${preset.name}")
            .apply()
    }

    fun setCycle(context: Context, preset: RoutinePreset, days: Int) {
        prefs(context).edit().putInt("cycle_${preset.name}", days).apply()
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

fun formatCycle(context: Context, days: Int): String = when (days) {
    0 -> context.getString(R.string.routine_cycle_none)
    1 -> context.getString(R.string.routine_cycle_daily)
    7 -> context.getString(R.string.routine_cycle_weekly)
    14 -> context.getString(R.string.routine_cycle_biweekly)
    30 -> context.getString(R.string.routine_cycle_monthly)
    90 -> context.getString(R.string.routine_cycle_quarterly)
    else -> context.getString(R.string.routine_cycle_days, days)
}
