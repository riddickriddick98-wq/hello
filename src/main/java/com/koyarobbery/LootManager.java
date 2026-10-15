package com.koyarobbery;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Builds a loot inventory from loots.json (real loots.yml values). */
public final class LootManager {
    private LootManager() {}

    public static SimpleContainer generate(String section, String perKey) {
        SimpleContainer c = new SimpleContainer(27);
        JsonObject sec = Cfg.loots.getAsJsonObject(section);
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < 27; i++) free.add(i);
        Collections.shuffle(free);
        for (var e : sec.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(o.get("Item").getAsString().toLowerCase(Locale.ROOT)));
            if (item == null) continue;
            int per = o.get(perKey).getAsInt();
            int amount = Math.min(o.get("Item-Amount").getAsInt(), item.getMaxStackSize());
            for (int i = 0; i < per && !free.isEmpty(); i++) {
                ItemStack s = new ItemStack(item, amount);
                s.setHoverName(Text.comp(o.get("Name").getAsString()));
                c.setItem(free.remove(0), s);
            }
        }
        return c;
    }

    public static String section(String type) {
        return switch (type) { case "SAFE" -> "Safe"; case "TABLE" -> "Table"; default -> "Jewelry"; };
    }
    public static String perKey(String type) { return type.equals("SAFE") ? "Amount-Per-Safe" : "Amount-Per-Table"; }
    public static String toolKey(String type) {
        return switch (type) { case "SAFE" -> "SAFE_OPENING_TOOL"; case "TABLE" -> "TABLE_OPENING_TOOL"; default -> "JEWELRY_OPENING_TOOL"; };
    }
}
