package com.koyarobbery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/** Legacy '&' colour codes + messages from language.json (the real language.yml values). */
public final class Text {
    private Text() {}

    public static String c(String s) { return s == null ? "" : s.replace('&', '\u00a7'); }
    public static MutableComponent comp(String s) { return Component.literal(c(s)); }

    public static String raw(String... path) {
        JsonElement e = Cfg.language.getAsJsonObject("Messages");
        for (String p : path) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has(p)) return "&cMissing message: " + String.join(".", path);
            e = e.getAsJsonObject().get(p);
        }
        return e.getAsString();
    }

    /** send(player, "JOINED", "%target%", "Steve") */
    public static void send(ServerPlayer p, String key, String... kv) {
        if (p != null) p.sendSystemMessage(comp(fill(raw(key), kv)));
    }

    public static String fill(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace(kv[i], kv[i + 1]);
        return s;
    }

    public static void actionBar(ServerPlayer p, String legacy) {
        if (p != null) p.displayClientMessage(comp(legacy), true);
    }

    public static void actionBarMsg(ServerPlayer p, String key, String... kv) {
        actionBar(p, fill(raw("ACTION_BAR", key), kv));
    }
}
