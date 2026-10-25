package com.crescentkingdom.cksmpblackmarket;

import com.crescentkingdom.cksmpblackmarket.command.BlackMarketCommandManager;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.gui.GuiService;
import com.crescentkingdom.cksmpblackmarket.hook.BlackMarketExpansion;
import com.crescentkingdom.cksmpblackmarket.listener.PlayerListener;
import com.crescentkingdom.cksmpblackmarket.service.BidService;
import com.crescentkingdom.cksmpblackmarket.service.BossBarService;
import com.crescentkingdom.cksmpblackmarket.service.EventScheduler;
import com.crescentkingdom.cksmpblackmarket.service.MarketState;
import com.crescentkingdom.cksmpblackmarket.service.PayoutService;
import com.crescentkingdom.cksmpblackmarket.service.RewardService;
import com.crescentkingdom.cksmpblackmarket.storage.BlackMarketDb;
import com.crescentkingdom.cksmpblackmarket.sync.SyncService;
import com.crescentkingdom.cksmpcore.API.CKSMPCoreAPI;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CKSMPBlackMarket extends JavaPlugin {

    private volatile Config config;

    private CKSMPCoreAPI core;
    private Economy economy;
    private ExecutorService dbExecutor;

    private BlackMarketDb db;
    private MarketState state;
    private RewardService rewards;
    private PayoutService payouts;
    private BidService bids;
    private SyncService sync;
    private BossBarService bossBar;
    private GuiService gui;
    private EventScheduler scheduler;

    @Override
    public void onEnable() {
        try {
            config = Config.load(this);
        } catch (Exception err) {
            getLogger().severe("Invalid configuration, disabling: " + err.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        core = CKSMPCoreAPI.getInstance();

        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("No Vault economy registered (CKSMPCore should provide one), disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = registration.getProvider();

        // Bounded so a slow database queues work here instead of draining CKSMPCore's shared pool.
        AtomicInteger threadId = new AtomicInteger();
        dbExecutor = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "CKSMPBlackMarket-DB-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        db = new BlackMarketDb(core.getDatabase(), dbExecutor);
        try {
            db.createSchema();
        } catch (Exception err) {
            getLogger().severe("Could not create the database schema, disabling: " + err.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        state = new MarketState();
        rewards = new RewardService(this);
        payouts = new PayoutService(this);
        bids = new BidService(this);
        sync = new SyncService(this);
        bossBar = new BossBarService(this);
        gui = new GuiService(this);
        scheduler = new EventScheduler(this, core.getServerManager().getServerData().getServerID());

        sync.register();
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        var commandManager = new BlackMarketCommandManager(this);
        core.getCommandManager().getCommandManagersCache().put(commandManager.name(), commandManager);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) new BlackMarketExpansion(this).register();

        // One shared 1s heartbeat for everything time-driven on this shard.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            bossBar.update();
            gui.tick();
        }, 20L, 20L);

        gui.resync(true);
        scheduler.start();
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.stop();
        if (bossBar != null) bossBar.hide();

        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) dbExecutor.shutdownNow();
            } catch (InterruptedException err) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Re-reads config, lang, menus and the pool. A failed parse leaves the previous config running. */
    public void reload() {
        config = Config.load(this);
        rewards.invalidate();
    }

    public Config config() {
        return config;
    }

    public CKSMPCoreAPI core() {
        return core;
    }

    public Economy economy() {
        return economy;
    }

    public BlackMarketDb db() {
        return db;
    }

    public MarketState state() {
        return state;
    }

    public RewardService rewards() {
        return rewards;
    }

    public PayoutService payouts() {
        return payouts;
    }

    public BidService bids() {
        return bids;
    }

    public SyncService sync() {
        return sync;
    }

    public BossBarService bossBar() {
        return bossBar;
    }

    public GuiService gui() {
        return gui;
    }

    public EventScheduler scheduler() {
        return scheduler;
    }
}
