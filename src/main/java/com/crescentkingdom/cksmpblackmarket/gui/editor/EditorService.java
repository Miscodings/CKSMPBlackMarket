package com.crescentkingdom.cksmpblackmarket.gui.editor;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.ItemSpec;
import com.crescentkingdom.cksmpblackmarket.storage.PrizeCodec;
import com.crescentkingdom.cksmpcore.Managers.GUI.MenuInventoryHolder;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** In-game prize editor: entry points and the operations the editor menus share. All methods run on the player's thread. */
public final class EditorService {

    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final CKSMPBlackMarket plugin;

    public EditorService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** Safe from any thread. */
    public void openList(Player player, int page) {
        player.getScheduler().run(plugin, task -> show(player, new PrizeListMenu(plugin, this, player, page)), null);
    }

    void show(Player player, MenuInventoryHolder menu) {
        plugin.core().getGUIManager().setGUI(player, menu);
    }

    void openDetail(Player player, String prizeId) {
        show(player, new PrizeDetailMenu(plugin, this, player, prizeId));
    }

    void openRewards(Player player, String prizeId) {
        PoolEntry entry = plugin.config().pool().get(prizeId);
        if (entry == null) {
            plugin.config().lang().send(player, "editor.msg.gone");
            openList(player, 0);
            return;
        }

        show(player, new RewardItemsMenu(plugin, this, player, entry));
    }

    /**
     * Turns the given item into a new prize. The item is copied exactly as it is (custom enchants, NBT,
     * components) and the player keeps their own; the new prize starts with default bids.
     */
    void addPrize(Player player, ItemStack source) {
        Config cfg = plugin.config();

        if (MenuItems.isAir(source)) {
            cfg.lang().send(player, "editor.msg.not-an-item");
            return;
        }

        ItemStack copy = source.clone();
        String id = newId(copy);
        PoolEntry entry = PoolEntry.created(id, ItemSpec.ofStack(PrizeCodec.encodeStack(copy)));
        plugin.prizes().save(entry);

        cfg.lang().send(player, "editor.msg.added",
                Placeholder.component("item", plugin.rewards().name(cfg, id, entry)),
                Placeholder.unparsed("id", id));
    }

    private String newId(ItemStack item) {
        String base = item.getType().key().value();

        String id;
        do {
            StringBuilder suffix = new StringBuilder(4);
            for (int i = 0; i < 4; i++) suffix.append(ID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ID_ALPHABET.length())));
            id = base + "_" + suffix;
        } while (plugin.config().pool().get(id) != null);

        return id;
    }
}
