package com.starinn.update;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 计算下一次周四 12:00 的 tick 延迟（默认 Asia/Shanghai），周期 7 天。
 * Paper 无 cron API，用 Calendar 求 delay + runTaskTimerAsynchronously 以 7 天为周期。
 */
public final class UpdateScheduler {

    private static final long TICKS_PER_SECOND = 20L;
    private static final long ONE_WEEK_TICKS = 7L * 24 * 60 * 60 * TICKS_PER_SECOND;

    /** 注册每周四中午 12:00 的异步检查任务。 */
    public static void scheduleWeekly(JavaPlugin plugin, Runnable task, String timezone) {
        TimeZone tz;
        try {
            tz = (timezone == null || timezone.isBlank())
                    ? TimeZone.getTimeZone("Asia/Shanghai")
                    : TimeZone.getTimeZone(timezone);
        } catch (RuntimeException ex) {
            tz = TimeZone.getTimeZone("Asia/Shanghai");
        }
        Calendar now = Calendar.getInstance(tz);
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY);
        next.set(Calendar.HOUR_OF_DAY, 12);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) {
            next.add(Calendar.WEEK_OF_YEAR, 1);
        }
        long delayTicks = (next.getTimeInMillis() - now.getTimeInMillis()) / (1000L / TICKS_PER_SECOND);
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, ONE_WEEK_TICKS);
    }
}
