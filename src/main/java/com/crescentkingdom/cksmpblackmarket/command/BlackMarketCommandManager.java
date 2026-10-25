package com.crescentkingdom.cksmpblackmarket.command;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.service.RoundText;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.crescentkingdom.cksmpcore.Managers.Commands.CommandManagers;
import com.crescentkingdom.cksmpcore.Managers.Commands.SubCommands.SubCommands;
import com.crescentkingdom.cksmpcore.libs.commandapi.CommandAPICommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.logging.Level;

/**
 * /blackmarket (aliases /bm, /darkauction). Registered through CKSMPCore's command manager registry like
 * the other CKSMP plugins. Every node has its own permission.
 */
public final class BlackMarketCommandManager implements CommandManagers {

    private final CKSMPBlackMarket plugin;
    private final ArrayList<SubCommands> commands = new ArrayList<>();

    public BlackMarketCommandManager(CKSMPBlackMarket plugin) {
        this.plugin = plugin;

        commands.add(this::history);
        commands.add(this::status);
        commands.add(() -> control("start", "START", "admin.start-sent"));
        commands.add(() -> control("stop", "STOP", "admin.stop-sent"));
        commands.add(() -> control("skip", "SKIP", "admin.skip-sent"));
        commands.add(this::reload);

        register();
    }

    @Override
    public String name() {
        return "blackmarket";
    }

    @Override
    public ArrayList<SubCommands> getCommands() {
        return commands;
    }

    private void register() {
        CommandAPICommand root = new CommandAPICommand(name())
                .withAliases("bm", "darkauction")
                .withPermission("cksmpblackmarket.commands.open")
                .executesPlayer((player, args) -> {
                    plugin.gui().open(player);
                });

        commands.forEach(sub -> root.withSubcommand(sub.onCommand()));
        root.register();
    }

    private CommandAPICommand history() {
        return new CommandAPICommand("history")
                .withPermission("cksmpblackmarket.commands.history")
                .executes((sender, args) -> {
                    Config cfg = plugin.config();

                    plugin.db().async(() -> plugin.db().recentWinners(cfg.settings().historySize())).whenComplete((rows, err) -> {
                        if (err != null) {
                            plugin.getLogger().log(Level.SEVERE, "History query failed", err);
                            return;
                        }

                        if (rows.isEmpty()) {
                            cfg.lang().send(sender, "history.empty");
                            return;
                        }

                        long now = System.currentTimeMillis();
                        cfg.lang().send(sender, "history.header");

                        for (var row : rows) {
                            cfg.lang().send(sender, "history.line", TagResolver.resolver(
                                    Placeholder.component("item", plugin.rewards().name(cfg, row.entryId(), cfg.pool().get(row.entryId()))),
                                    Placeholder.unparsed("player", String.valueOf(row.winnerName())),
                                    Placeholder.component("amount", Text.money(cfg.lang(), row.price())),
                                    Placeholder.component("ago", Text.time(cfg.lang(), (now - row.endedAt()) / 1000))));
                        }
                    });
                });
    }

    private CommandAPICommand status() {
        return new CommandAPICommand("status")
                .withPermission("cksmpblackmarket.commands.status")
                .executes((sender, args) -> {
                    Config cfg = plugin.config();
                    long now = System.currentTimeMillis();
                    RoundView round = plugin.state().current();

                    if (round != null && round.isActiveAt(now)) {
                        cfg.lang().send(sender, "status.active", TagResolver.resolver(
                                RoundText.resolver(plugin, cfg, round, now),
                                Placeholder.component("amount", Text.money(cfg.lang(), round.highBid())),
                                Placeholder.component("player", round.highBidder() == null
                                        ? cfg.lang().format(cfg.lang().raw("no-leader-name"))
                                        : net.kyori.adventure.text.Component.text(round.highBidderName()))));
                        return;
                    }

                    long next = cfg.settings().firstSlotAfter(now);
                    if (next < 0) cfg.lang().send(sender, "status.idle-none");
                    else cfg.lang().send(sender, "status.idle-next", Placeholder.component("time", Text.time(cfg.lang(), (next - now) / 1000)));
                });
    }

    /** Start / stop / skip are executed by the leader shard, wherever it is; we only relay the request. */
    private CommandAPICommand control(String literal, String action, String sentKey) {
        return new CommandAPICommand(literal)
                .withPermission("cksmpblackmarket.commands.admin." + literal)
                .executes((sender, args) -> {
                    plugin.db().async(() -> plugin.db().leaseHolder()).whenComplete((holder, err) -> {
                        Config cfg = plugin.config();

                        if (err != null || holder == null) {
                            cfg.lang().send(sender, "admin.no-leader");
                            return;
                        }

                        plugin.sync().publishControl(action);
                        cfg.lang().send(sender, sentKey);
                    });
                });
    }

    private CommandAPICommand reload() {
        return new CommandAPICommand("reload")
                .withPermission("cksmpblackmarket.commands.admin.reload")
                .executes((sender, args) -> {
                    reloadAndReport(sender);
                });
    }

    private void reloadAndReport(CommandSender sender) {
        try {
            plugin.reload();
            plugin.config().lang().send(sender, "general.reloaded");
        } catch (Exception err) {
            plugin.getLogger().log(Level.SEVERE, "Reload failed; keeping the previous configuration", err);
            sender.sendMessage("Reload failed, previous configuration kept: " + err.getMessage());
        }
    }
}
