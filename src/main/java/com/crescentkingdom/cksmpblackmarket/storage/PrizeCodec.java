package com.crescentkingdom.cksmpblackmarket.storage;

import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.ItemSpec;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** JSON <-> model conversion for the prize table's text columns. */
public final class PrizeCodec {

    private static final Gson GSON = new Gson();

    private PrizeCodec() {}

    public static String encodeStack(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    public static ItemStack decodeStack(String base64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
    }

    public static String encodeItems(List<ItemSpec> specs) {
        JsonArray array = new JsonArray();
        for (ItemSpec spec : specs) array.add(encodeSpec(spec));
        return array.toString();
    }

    public static List<ItemSpec> decodeItems(String json) {
        List<ItemSpec> specs = new ArrayList<>();
        if (json == null || json.isBlank()) return specs;

        for (JsonElement element : GSON.fromJson(json, JsonArray.class)) {
            ItemSpec spec = decodeSpec(element.getAsJsonObject());
            if (spec != null) specs.add(spec);
        }
        return specs;
    }

    public static String encodeSpecOrNull(ItemSpec spec) {
        return spec == null ? null : encodeSpec(spec).toString();
    }

    public static ItemSpec decodeSpecOrNull(String json) {
        return json == null || json.isBlank() ? null : decodeSpec(GSON.fromJson(json, JsonObject.class));
    }

    public static String encodeStrings(List<String> values) {
        return GSON.toJson(values);
    }

    public static List<String> decodeStrings(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;

        for (JsonElement element : GSON.fromJson(json, JsonArray.class)) out.add(element.getAsString());
        return out;
    }

    private static JsonObject encodeSpec(ItemSpec spec) {
        JsonObject json = new JsonObject();

        if (spec.stack() != null) {
            json.addProperty("t", "stack");
            json.addProperty("d", spec.stack());
        } else if (spec.cksmpItem() != null) {
            json.addProperty("t", "cksmp");
            json.addProperty("id", spec.cksmpItem());
            json.addProperty("n", spec.amount());
        } else {
            json.addProperty("t", "mat");
            json.addProperty("m", spec.material().name());
            json.addProperty("n", spec.amount());
        }

        return json;
    }

    private static ItemSpec decodeSpec(JsonObject json) {
        return switch (json.get("t").getAsString()) {
            case "stack" -> ItemSpec.ofStack(json.get("d").getAsString());
            case "cksmp" -> new ItemSpec(json.get("id").getAsString(), null, json.get("n").getAsInt(), null);
            case "mat" -> {
                Material material = Material.matchMaterial(json.get("m").getAsString());
                yield material == null ? null : new ItemSpec(null, material, json.get("n").getAsInt(), null);
            }
            default -> null;
        };
    }
}
