package com.crescentkingdom.cksmpblackmarket.command;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.service.RoundText;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.crescentkingdom.cksmpcore.Managers.Commands.CommandManagers;
import com.crescentkingdom.cksmpcore.Managers.Commands.SubCommands.SubCommands;
import com.crescentkingdom.cksmpcore.libs.commandapi.CommandAPICommand;
import com.crescentkingdom.cksmpcore.libs.commandapi.arguments.DoubleArgument;
import com.crescentkingdom.cksmpcore.libs.commandapi.arguments.GreedyStringArgument;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * /blackmarket (alias /bm). Registered through CKSMPCore's command manager registry like
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
        commands.add(this::editor);
        commands.add(this::setPrices);
        commands.add(this::help);

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
                .withAliases("bm")
                .withPermission("cksmpblackmarket.commands.open")
                .executesPlayer((player, args) -> {
                    // Outside the market the command takes you there; inside it opens the bidding menu.
                    if (plugin.area().contains(player)) {
                        plugin.gui().open(player);
                        return;
                    }

                    if (!plugin.area().isConfigured()) {
                        plugin.config().lang().send(player, "area.unavailable");
                        return;
                    }

                    plugin.area().enter(player);
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
                .withAliases("info")
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

    /** Player commands for everyone, admin commands only for those who could run them (as AxDarkAuctions does). */
    private CommandAPICommand help() {
        return new CommandAPICommand("help")
                .withPermission("cksmpblackmarket.commands.open")
                .executes((sender, args) -> {
                    Config cfg = plugin.config();
                    cfg.lang().sendLines(sender, "help.player");
                    if (sender.hasPermission("cksmpblackmarket.commands.admin.start")) cfg.lang().sendLines(sender, "help.admin");
                });
    }

    private CommandAPICommand editor() {
        return new CommandAPICommand("editor")
                .withPermission("cksmpblackmarket.commands.admin.editor")
                .executesPlayer((player, args) -> {
                    plugin.editor().openList(player, 0);
                });
    }

    /**
     * Bulk price change: /blackmarket setprices &lt;amount&gt; [except words...]. Every prize gets the new start bid except
     * those whose id or plain display name contains one of the words. Refuses to run when a word matches nothing, so a
     * typo cannot overwrite the prize that was meant to be left alone.
     */
    private CommandAPICommand setPrices() {
        return new CommandAPICommand("setprices")
                .withPermission("cksmpblackmarket.commands.admin.setprices")
                .withArguments(new DoubleArgument("amount", 0.01))
                .withOptionalArguments(new GreedyStringArgument("except"))
                .executes((sender, args) -> {
                    Config cfg = plugin.config();
                    double amount = (double) args.get("amount");
                    String raw = (String) args.getOptional("except").orElse("");
                    List<String> words = Arrays.stream(raw.toLowerCase().split("\s+")).filter(w -> !w.isBlank()).toList();

                    List<PoolEntry> skipped = new ArrayList<>();
                    List<PoolEntry> changing = new ArrayList<>();
                    for (PoolEntry entry : cfg.pool().all()) {
                        String name = entry.id().toLowerCase() + " " + plainName(cfg, entry).toLowerCase();
                        if (words.stream().anyMatch(name::contains)) skipped.add(entry);
                        else changing.add(entry);
                    }

                    for (String word : words) {
                        boolean used = skipped.stream().anyMatch(e -> (e.id().toLowerCase() + " " + plainName(cfg, e).toLowerCase()).contains(word));
                        if (!used) {
                            cfg.lang().send(sender, "admin.setprices-no-match", Placeholder.unparsed("word", word));
                            return;
                        }
                    }

                    for (PoolEntry entry : changing) plugin.prizes().save(entry.withStartBid(amount));

                    cfg.lang().send(sender, "admin.setprices-done", TagResolver.resolver(
                            Placeholder.unparsed("count", Integer.toString(changing.size())),
                            Placeholder.component("amount", Text.money(cfg.lang(), amount)),
                            Placeholder.unparsed("skipped", skipped.isEmpty() ? "none" : String.join(", ", skipped.stream().map(PoolEntry::id).toList()))));
                });
    }

    private String plainName(Config cfg, PoolEntry entry) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(plugin.rewards().name(cfg, entry.id(), entry));
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
            plugin.config().lang().send(sender, "general.reload-failed", Placeholder.unparsed("error", String.valueOf(err.getMessage())));
        }
    }
}
