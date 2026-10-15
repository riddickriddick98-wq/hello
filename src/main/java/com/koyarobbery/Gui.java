package com.koyarobbery;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Chest-style GUIs matching the plugin's menus (titles, filler panes, item types). */
public final class Gui {
    private Gui() {}

    /** Chest menu where every click is cancelled and routed to a callback. */
    public static class Menu extends ChestMenu {
        private final int rows;
        private final BiConsumer<Integer, ServerPlayer> onClick;
        private final Consumer<Player> onClose;

        Menu(int id, Inventory inv, SimpleContainer c, int rows, BiConsumer<Integer, ServerPlayer> onClick, Consumer<Player> onClose) {
            super(type(rows), id, inv, c, rows);
            this.rows = rows; this.onClick = onClick; this.onClose = onClose;
        }
        private static MenuType<ChestMenu> type(int rows) {
            return switch (rows) {
                case 1 -> MenuType.GENERIC_9x1; case 2 -> MenuType.GENERIC_9x2; case 3 -> MenuType.GENERIC_9x3;
                case 4 -> MenuType.GENERIC_9x4; case 5 -> MenuType.GENERIC_9x5; default -> MenuType.GENERIC_9x6;
            };
        }
        @Override public void clicked(int slot, int button, ClickType type, Player player) {
            if (slot >= 0 && slot < rows * 9 && onClick != null && player instanceof ServerPlayer sp) onClick.accept(slot, sp);
            sendAllDataToRemote();
        }
        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
        @Override public void removed(Player p) { super.removed(p); if (onClose != null) onClose.accept(p); }
    }

    public static void open(ServerPlayer p, String legacyTitle, SimpleContainer c, int rows,
                            BiConsumer<Integer, ServerPlayer> onClick, Consumer<Player> onClose) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, c, rows, onClick, onClose), Text.comp(legacyTitle)));
    }

    public static ItemStack named(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.setHoverName(Text.comp(name));
        if (lore.length > 0) setLore(s, List.of(lore));
        return s;
    }

    public static void setLore(ItemStack s, List<String> lines) {
        CompoundTag tag = s.getOrCreateTag();
        CompoundTag display = tag.getCompound("display");
        ListTag l = new ListTag();
        for (String line : lines) l.add(StringTag.valueOf(Component.Serializer.toJson(Text.comp(line))));
        display.put("Lore", l);
        tag.put("display", display);
    }

    public static ItemStack filler() { return named(Items.GRAY_STAINED_GLASS_PANE, " "); }

    public static void fill(SimpleContainer c) { for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, filler()); }

    public static void fillBorder(SimpleContainer c, int rows) {
        for (int i = 0; i < rows * 9; i++) {
            int r = i / 9, col = i % 9;
            if (r == 0 || r == rows - 1 || col == 0 || col == 8) c.setItem(i, filler());
        }
    }

    // ---------- "&8Start Robbery" ----------
    public static void openStart(ServerPlayer p, String placeName) {
        SimpleContainer c = new SimpleContainer(27);
        fillBorder(c, 3);
        c.setItem(13, named(Items.CONDUIT, "&4&lSTART"));
        open(p, "&8Start Robbery", c, 3, (slot, sp) -> {
            if (slot == 13) {
                sp.server.execute(() -> { sp.closeContainer(); RobberyManager.begin(sp, placeName); });
            }
        }, null);
    }

    // ---------- "&8Crew Menu" ----------
    public static void openCrew(ServerPlayer p) {
        Crews.Crew crew = Crews.of(p.getUUID());
        if (crew == null) { Text.send(p, "HELP"); return; }
        boolean leader = crew.leader.equals(p.getUUID());
        SimpleContainer c = new SimpleContainer(27);
        fill(c);
        ServerPlayer lp = p.server.getPlayerList().getPlayer(crew.leader);
        ItemStack info = head(lp != null ? lp.getGameProfile().getName() : crew.name, "&6&lCrew Info",
            "&r", "&8\u00bb &7Leader: &6" + crew.name);
        c.setItem(4, info);
        int slot = 9;
        UUID[] slotOwner = new UUID[27];
        for (UUID m : crew.members) {
            ServerPlayer mp = p.server.getPlayerList().getPlayer(m);
            if (mp == null || slot > 17) continue;
            List<String> lore = new java.util.ArrayList<>();
            lore.add("&8");
            BlockPos b = mp.blockPosition();
            lore.add("&8\u00bb &7Location: &7(x:" + b.getX() + " y:" + b.getY() + " z:" + b.getZ() + ")");
            lore.add("&8\u00bb &7Distance: &a" + (int) Math.sqrt(mp.distanceToSqr(p)) + " &7blocks");
            if (leader && !m.equals(p.getUUID())) lore.add("&8\u00bb &7Click to &4Kick");
            ItemStack h = head(mp.getGameProfile().getName(), m.equals(crew.leader) ? "&6&l" + mp.getGameProfile().getName() : "&d" + mp.getGameProfile().getName(), lore.toArray(new String[0]));
            c.setItem(slot, h);
            slotOwner[slot] = m;
            slot++;
        }
        c.setItem(22, leader ? named(Items.SPRUCE_DOOR, "&4&lDisband") : named(Items.SPRUCE_DOOR, "&4&lLeave"));
        c.setItem(26, named(Items.BARRIER, "&7Close"));
        open(p, "&8Crew Menu", c, 3, (s, sp) -> {
            if (s == 22) { sp.server.execute(() -> { sp.closeContainer(); if (leader) Crews.disband(crew); else Crews.leave(sp.getUUID()); }); }
            else if (s == 26) sp.server.execute(sp::closeContainer);
            else if (leader && slotOwner[s] != null && !slotOwner[s].equals(sp.getUUID())) {
                UUID target = slotOwner[s];
                sp.server.execute(() -> { Crews.kick(sp, target); openCrew(sp); });
            }
        }, null);
    }

    private static ItemStack head(String owner, String name, String... lore) {
        ItemStack s = named(Items.PLAYER_HEAD, name, lore);
        s.getOrCreateTag().putString("SkullOwner", owner);
        return s;
    }

    // ---------- "&8Structures" (admin) ----------
    public static void openStructures(ServerPlayer p) {
        SimpleContainer c = new SimpleContainer(27);
        fill(c);
        c.setItem(10, structItem("TABLE", Items.BLACK_GLAZED_TERRACOTTA, "&7(&aStructure Block&7) &8(&4&lTABLE&8)"));
        c.setItem(11, structItem("SAFE", Items.WHITE_GLAZED_TERRACOTTA, "&7(&aStructure Block&7) &8(&4&lSAFE&8)"));
        c.setItem(12, structItem("JEWELRY_TABLE", Items.RED_GLAZED_TERRACOTTA, "&7(&aStructure Block&7) &8(&4&lJEWELRY TABLE&8)"));
        ItemStack door = structItem("VAULT_DOOR", Items.IRON_DOOR, "&7(&aStructure Block&7) &8(&4&lVAULT DOOR&8)");
        setLore(door, List.of("&7Vault Door Structure", "&7Size: &f3 &7(Length) \u00d7 &f7 &7(Height) \u00d7 &f7 &7(Width)", "&8Total Blocks: &f147"));
        c.setItem(14, door);
        c.setItem(16, drillItem());
        open(p, "&8Structures", c, 3, (slot, sp) -> {
            ItemStack it = c.getItem(slot);
            if (slot >= 10 && slot <= 16 && !it.isEmpty() && it.getItem() != Items.GRAY_STAINED_GLASS_PANE) {
                if (!sp.getInventory().add(it.copy())) sp.drop(it.copy(), false);
            }
        }, null);
    }

    public static ItemStack structItem(String type, Item item, String name) {
        ItemStack s = named(item, name);
        s.getOrCreateTag().putString("koya_struct", type);
        return s;
    }

    public static ItemStack drillItem() {
        var d = Cfg.config.getAsJsonObject("Vault-Drill");
        Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
            new net.minecraft.resources.ResourceLocation(d.get("Material").getAsString().toLowerCase()));
        ItemStack s = new ItemStack(item == null ? Items.DIAMOND_PICKAXE : item);
        s.setHoverName(Text.comp(d.get("Name").getAsString()));
        List<String> lore = new java.util.ArrayList<>();
        d.getAsJsonArray("Lore").forEach(e -> lore.add(e.getAsString()));
        setLore(s, lore);
        CompoundTag tag = s.getOrCreateTag();
        tag.putByte("koya_drill", (byte) 1);
        tag.putInt("CustomModelData", d.get("Custom-Model-Data").getAsInt());
        tag.putInt("HideFlags", 3);
        if (d.get("Glow").getAsBoolean()) s.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 1);
        return s;
    }

    public static ItemStack stickItem() {
        ItemStack s = named(Items.STICK, "&6&lStructure Stick", "&7Right-click a structure or vault door to remove it");
        s.getOrCreateTag().putByte("koya_stick", (byte) 1);
        return s;
    }
}
