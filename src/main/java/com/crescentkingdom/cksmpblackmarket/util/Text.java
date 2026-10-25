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

    public static Component time(Lang lang, long seconds) {
        seconds = Math.max(0, seconds);

        if (seconds >= 60) {
            return lang.format(lang.raw("time-format.minutes"),
                    Placeholder.unparsed("m", Long.toString(seconds / 60)),
                    Placeholder.unparsed("s", Long.toString(seconds % 60)));
        }

        return lang.format(lang.raw("time-format.seconds"), Placeholder.unparsed("s", Long.toString(seconds)));
    }

    /** Whole seconds remaining until {@code endsAtMs}, rounded up so the display never shows 0s while live. */
    public static long secondsUntil(long endsAtMs, long nowMs) {
        return Math.max(0, (endsAtMs - nowMs + 999) / 1000);
    }
}
