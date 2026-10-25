package com.crescentkingdom.cksmpblackmarket.config;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** config.yml parsed once into plain values; nothing on a hot path reads YAML. */
public record Settings(
        boolean participate,
        boolean leaderEligible,
        int leaseSeconds,
        ZoneId zone,
        List<LocalTime> times,
        Set<DayOfWeek> days,
        int announceSeconds,
        List<Integer> announceAt,
        int manualAnnounceSeconds,
        int lateGraceSeconds,
        int minOnlinePlayers,
        int rounds,
        int breakSeconds,
        int roundSeconds,
        int snipeWindowSeconds,
        int snipeExtendSeconds,
        long bidCooldownMs,
        boolean broadcastBids,
        int historySize,
        boolean bossBarEnabled,
        BossBar.Color bossBarColor,
        BossBar.Overlay bossBarOverlay,
        Map<String, Sound> sounds
) {

    public Sound sound(String key) {
        return sounds.get(key);
    }

    /** First scheduled slot strictly after {@code afterMs}, or -1 when nothing is scheduled. */
    public long firstSlotAfter(long afterMs) {
        if (times.isEmpty()) return -1;

        ZonedDateTime after = java.time.Instant.ofEpochMilli(afterMs).atZone(zone);
        LocalDate date = after.toLocalDate();

        for (int offset = 0; offset <= 7; offset++) {
            LocalDate day = date.plusDays(offset);
            if (!days.isEmpty() && !days.contains(day.getDayOfWeek())) continue;

            for (LocalTime time : times) {
                long slot = day.atTime(time).atZone(zone).toInstant().toEpochMilli();
                if (slot > afterMs) return slot;
            }
        }

        return -1;
    }

    public static Settings parse(FileConfiguration c, Logger log) {
        ZoneId zone;
        try {
            zone = ZoneId.of(c.getString("schedule.zone", "UTC"));
        } catch (Exception err) {
            log.warning("Invalid schedule.zone, using UTC.");
            zone = ZoneId.of("UTC");
        }

        List<LocalTime> times = new ArrayList<>();
        for (String raw : c.getStringList("schedule.times")) {
            try {
                times.add(LocalTime.parse(raw));
            } catch (Exception err) {
                log.warning("Ignoring invalid schedule time '" + raw + "' (expected HH:mm).");
            }
        }
        times.sort(Comparator.naturalOrder());

        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String raw : c.getStringList("schedule.days")) {
            try {
                days.add(DayOfWeek.valueOf(raw.toUpperCase()));
            } catch (IllegalArgumentException err) {
                log.warning("Ignoring invalid schedule day '" + raw + "'.");
            }
        }

        List<Integer> announceAt = new ArrayList<>(c.getIntegerList("schedule.announce-at"));
        announceAt.sort(Comparator.reverseOrder());

        BossBar.Color color = parseEnum(BossBar.Color.class, c.getString("bossbar.color"), BossBar.Color.PURPLE, log);
        BossBar.Overlay overlay = parseEnum(BossBar.Overlay.class, c.getString("bossbar.overlay"), BossBar.Overlay.PROGRESS, log);

        return new Settings(
                c.getBoolean("participate", true),
                c.getBoolean("leader.eligible", true),
                Math.max(6, c.getInt("leader.lease-seconds", 15)),
                zone,
                List.copyOf(times),
                Set.copyOf(days),
                Math.max(1, c.getInt("schedule.announce-seconds", 120)),
                List.copyOf(announceAt),
                Math.max(1, c.getInt("schedule.manual-announce-seconds", 30)),
                Math.max(0, c.getInt("schedule.late-grace-seconds", 300)),
                Math.max(0, c.getInt("schedule.min-online-players", 1)),
                Math.max(1, c.getInt("event.rounds", 5)),
                Math.max(1, c.getInt("event.break-seconds", 15)),
                Math.max(10, c.getInt("round.duration-seconds", 90)),
                Math.max(0, c.getInt("round.anti-snipe.window-seconds", 10)),
                Math.max(1, c.getInt("round.anti-snipe.extend-seconds", 10)),
                Math.max(0, c.getLong("bidding.cooldown-ms", 750)),
                c.getBoolean("bidding.broadcast-bids", false),
                Math.max(1, c.getInt("history.size", 8)),
                c.getBoolean("bossbar.enabled", true),
                color,
                overlay,
                parseSounds(c.getConfigurationSection("sounds"), log)
        );
    }

    private static Map<String, Sound> parseSounds(ConfigurationSection section, Logger log) {
        Map<String, Sound> sounds = new HashMap<>();
        if (section == null) return Map.copyOf(sounds);

        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;

            String id = s.getString("sound", "");
            if (id.isBlank()) continue;

            try {
                Sound.Source source = parseEnum(Sound.Source.class, s.getString("source"), Sound.Source.MASTER, log);
                sounds.put(key, Sound.sound(Key.key(id), source, (float) s.getDouble("volume", 1), (float) s.getDouble("pitch", 1)));
            } catch (Exception err) {
                log.warning("Ignoring invalid sound '" + key + "': " + err.getMessage());
            }
        }

        return Map.copyOf(sounds);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, Logger log) {
        if (raw == null) return fallback;

        try {
            return Enum.valueOf(type, raw.toUpperCase());
        } catch (IllegalArgumentException err) {
            log.warning("Invalid " + type.getSimpleName() + " '" + raw + "', using " + fallback + ".");
            return fallback;
        }
    }
}
