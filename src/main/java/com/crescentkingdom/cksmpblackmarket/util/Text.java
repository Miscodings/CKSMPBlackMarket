package com.crescentkingdom.cksmpblackmarket.util;

import com.crescentkingdom.cksmpblackmarket.config.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.text.DecimalFormat;

/** Small formatting helpers shared by chat, menu, boss bar and placeholders. */
public final class Text {

    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.##"));

    private Text() {}

    public static String plainMoney(double amount) {
        return MONEY.get().format(amount);
    }

    public static Component money(Lang lang, double amount) {
        return lang.format(lang.raw("money-format"), Placeholder.unparsed("amount", plainMoney(amount)));
    }

    /** "1d 2h 3m 4s", skipping zero units, with the unit letters taken from the lang file's {@code time} section. */
    public static Component time(Lang lang, long seconds) {
        return Component.text(plainTime(lang, seconds));
    }

    public static String plainTime(Lang lang, long seconds) {
        seconds = Math.max(0, seconds);

        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;

        StringBuilder out = new StringBuilder();
        append(out, days, lang.raw("time.day"));
        append(out, hours, lang.raw("time.hour"));
        append(out, minutes, lang.raw("time.minute"));
        if (secs > 0 || out.isEmpty()) append(out, secs, lang.raw("time.second"));
        return out.toString();
    }

    private static void append(StringBuilder out, long value, String unit) {
        if (value <= 0) return;
        if (!out.isEmpty()) out.append(' ');
        out.append(value).append(unit);
    }

    /** Whole seconds remaining until {@code endsAtMs}, rounded up so the display never shows 0s while live. */
    public static long secondsUntil(long endsAtMs, long nowMs) {
        return Math.max(0, (endsAtMs - nowMs + 999) / 1000);
    }
}
