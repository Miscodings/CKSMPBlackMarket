package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import com.crescentkingdom.cksmpcore.Utils.ServerUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The market area, defined by a CKSMPCore warp so its shard, world and position are kept in one place.
 * A player is "inside" when they are on the warp's shard, in its world and (if a radius is set) near it;
 * that is the only audience for broadcasts, the boss bar and bidding. Membership is derived from where the
 * player is standing, never remembered, so leaving by any route (/spawn, /home, /warp, a Lands teleport,
 * an admin) drops them from the market on the spot and nothing here can send them anywhere afterwards.
 */
public final class MarketArea {

    /** How long a repeated /blackmarket is ignored while the first warp is still travelling. */
    private static final long PENDING_MS = 10_000;

    private final CKSMPBlackMarket plugin;
    private final Map<UUID, Long> entering = new ConcurrentHashMap<>();

    public MarketArea(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** The warp's location section, or null when the warp does not exist on this shard. */
    private ConfigurationSection spot() {
        FileConfiguration warp = plugin.core().getPluginInstance().getWarpsManager().getWarpConfigFilesCache().get(plugin.config().settings().areaWarp());
        return warp == null ? null : warp.getConfigurationSection("warp.location");
    }

    public boolean isConfigured() {
        return spot() != null;
    }

    /**
     * Where the prize is shown: display.position in the warp's world, only on the shard that hosts that world.
     * Null on every other shard, when the warp or the position is missing, or when the world is not loaded.
     */
    public Location displayLocation() {
        Settings settings = plugin.config().settings();
        var position = settings.displayPosition();
        if (!settings.displayEnabled() || position.size() != 3) return null;

        ConfigurationSection spot = spot();
        if (spot == null) return null;

        String currentServer = plugin.core().getServerManager().getServerData().getServerID();
        if (!ServerUtils.isOnCorrectServer(spot.getString("server-id", ""), currentServer)) return null;

        org.bukkit.World world = Bukkit.getWorld(spot.getString("world", ""));
        return world == null ? null : new Location(world, position.get(0), position.get(1), position.get(2));
    }

    /** Whether the player is standing in the market. Safe from any thread (reads the player's last known location). */
    public boolean contains(Player player) {
        ConfigurationSection spot = spot();
        if (spot == null) return false;

        String currentServer = plugin.core().getServerManager().getServerData().getServerID();
        Location at = player.getLocation();
        if (at.getWorld() == null) return false;

        return inside(spot, currentServer, at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
    }

    /**
     * Same shard and world as the warp, then: inside the area.min / area.max cuboid if one is set (full X/Y/Z, so a
     * room under spawn does not include spawn), else within area.radius of the warp on X/Z, else the whole world.
     */
    private boolean inside(ConfigurationSection spot, String serverId, String world, double x, double y, double z) {
        if (!ServerUtils.isOnCorrectServer(spot.getString("server-id", ""), serverId)) return false;
        if (!world.equals(spot.getString("world"))) return false;

        Settings settings = plugin.config().settings();
        if (settings.hasAreaBox()) {
            var min = settings.areaMin();
            var max = settings.areaMax();

            return x >= Math.min(min.get(0), max.get(0)) && x <= Math.max(min.get(0), max.get(0))
                    && y >= Math.min(min.get(1), max.get(1)) && y <= Math.max(min.get(1), max.get(1))
                    && z >= Math.min(min.get(2), max.get(2)) && z <= Math.max(min.get(2), max.get(2));
        }

        double radius = settings.areaRadius();
        if (radius <= 0) return true;

        return Math.abs(x - spot.getDouble("x")) <= radius && Math.abs(z - spot.getDouble("z")) <= radius;
    }

    /** Warps the player in through CKSMPCore, so the warmup, combat check and /back origin all behave like /warp. */
    public void enter(Player player) {
        if (!claim(entering, player.getUniqueId())) return;

        player.performCommand("warp " + plugin.config().settings().areaWarp());
    }

    /** True the first time within the window; a repeat while the first is still pending is ignored. */
    private boolean claim(Map<UUID, Long> pending, UUID uuid) {
        long now = System.currentTimeMillis();
        Long until = pending.get(uuid);
        if (until != null && until > now) return false;

        pending.put(uuid, now + PENDING_MS);
        return true;
    }

    /** Once a second: forgets enters that completed, expired, or whose player went offline. */
    public void tick() {
        long now = System.currentTimeMillis();

        entering.entrySet().removeIf(e -> e.getValue() <= now || playerGone(e.getKey()) || insideNow(e.getKey()));
    }

    private boolean playerGone(UUID uuid) {
        return Bukkit.getPlayer(uuid) == null;
    }

    private boolean insideNow(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && contains(player);
    }
}
