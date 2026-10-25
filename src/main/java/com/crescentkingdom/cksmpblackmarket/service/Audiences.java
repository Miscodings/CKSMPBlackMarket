package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpcore.Managers.PlayerData.Data.PlayerData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Who receives broadcasts: everyone on this shard except players who switched the announcement toggle
 * off in CKSMPCore's native player settings. Reads only the local PlayerData copy (no Redis round trip),
 * and treats a missing key as "on" so the plugin works before Core's config/menu gain the setting.
 */
public final class Audiences {

    private final CKSMPBlackMarket plugin;

    public Audiences(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    public Audience announcements() {
        return Bukkit.getServer().filterAudience(audience -> !(audience instanceof Player player) || wants(player));
    }

    public boolean wants(Player player) {
        PlayerData data = plugin.core().getPlayerAPI().getProfile(player.getUniqueId());
        if (data == null) return true;

        JsonObject settings = data.getSettings();
        String key = plugin.config().settings().announcementSettingKey();
        if (settings == null || !settings.has(key)) return true;

        JsonElement value = settings.get(key);
        return !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || value.getAsBoolean();
    }
}
