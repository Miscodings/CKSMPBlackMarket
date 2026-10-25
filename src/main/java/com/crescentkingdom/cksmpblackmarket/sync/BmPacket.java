package com.crescentkingdom.cksmpblackmarket.sync;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpcore.Managers.Redis.RedisPacket;
import com.google.gson.JsonObject;

/**
 * The single Redis channel this plugin uses. One registered receiver instance (no data) handles
 * inbound traffic; outbound messages are short-lived instances that carry a payload.
 */
public final class BmPacket implements RedisPacket {

    public static final String ID = "cksmpbm:event";

    private final CKSMPBlackMarket plugin;
    private final JsonObject data;

    public BmPacket(CKSMPBlackMarket plugin) {
        this(plugin, null);
    }

    public BmPacket(CKSMPBlackMarket plugin, JsonObject data) {
        this.plugin = plugin;
        this.data = data;
    }

    @Override
    public String getIdentifier() {
        return ID;
    }

    @Override
    public void onReceived(JsonObject received) {
        if (received != null) plugin.sync().handle(received);
    }

    @Override
    public JsonObject getData() {
        return data;
    }

    @Override
    public String getDataExpireIdentifier() {
        return "";
    }

    @Override
    public int getDataExpire() {
        return 0;
    }
}
