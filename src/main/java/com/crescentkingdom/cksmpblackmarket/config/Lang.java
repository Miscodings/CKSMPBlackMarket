package com.crescentkingdom.cksmpblackmarket.config;

import com.crescentkingdom.cksmpcore.Utils.ColourUtils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lang file flattened into maps at load time, so lookups are a single hash probe.
 * Rendering goes through CKSMPCore's ColourUtils (MiniMessage, no italics, {@code <center>}).
 */
public final class Lang {

    private final ColourUtils colours = new ColourUtils();
    private final Map<String, String> strings;
    private final Map<String, List<String>> lists;
    private final TagResolver prefix;

    private Lang(Map<String, String> strings, Map<String, List<String>> lists) {
        this.strings = strings;
        this.lists = lists;
        this.prefix = Placeholder.parsed("prefix", strings.getOrDefault("prefix", ""));
    }

    public static Lang parse(FileConfiguration yaml) {
        Map<String, String> strings = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();

        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) strings.put(key, yaml.getString(key));
            else if (yaml.isList(key)) lists.put(key, List.copyOf(yaml.getStringList(key)));
        }

        return new Lang(Map.copyOf(strings), Map.copyOf(lists));
    }

    public String raw(String key) {
        return strings.getOrDefault(key, "");
    }

    public Component component(String key, TagResolver... resolvers) {
        String raw = strings.get(key);
        if (raw == null) return Component.text("[missing lang: " + key + "]");

        return colours.miniFormatChat(raw, resolver(resolvers));
    }

    public List<Component> components(String key, TagResolver... resolvers) {
        List<String> raw = lists.get(key);
        if (raw == null) return List.of(Component.text("[missing lang: " + key + "]"));

        TagResolver resolver = resolver(resolvers);
        List<Component> out = new ArrayList<>(raw.size());
        for (String line : raw) out.add(colours.miniFormatChat(line, resolver));
        return out;
    }

    /** Formats arbitrary MiniMessage (menu / pool text) with placeholders. */
    public Component format(String miniMessage, TagResolver... resolvers) {
        return colours.miniFormat(miniMessage, resolver(resolvers));
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(component(key, resolvers));
    }

    public void sendLines(Audience audience, String key, TagResolver... resolvers) {
        for (Component line : components(key, resolvers)) audience.sendMessage(line);
    }

    /** Plain text (no colours) for Discord and logs. */
    public String plain(String key, TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, resolvers));
    }

    private TagResolver resolver(TagResolver[] resolvers) {
        return resolvers.length == 0 ? prefix : TagResolver.resolver(prefix, TagResolver.resolver(resolvers));
    }
}
