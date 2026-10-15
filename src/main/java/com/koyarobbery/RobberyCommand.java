package com.koyarobbery;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

/** /robbery  (help | start | crew | invite | accept | disband | give | getstick | structure | reload | region ...) */
@Mod.EventBusSubscriber(modid = KoyaRobberyMod.MODID)
public final class RobberyCommand {
    private RobberyCommand() {}
    private static final Map<UUID, BlockPos[]> SELECTION = new HashMap<>();

    private static final SuggestionProvider<CommandSourceStack> PLACES = (c, b) -> SharedSuggestionProvider.suggest(Cfg.config == null ? List.<String>of() : new ArrayList<>(Cfg.places().keySet()), b);
    private static final SuggestionProvider<CommandSourceStack> ITEMS = (c, b) -> SharedSuggestionProvider.suggest(List.of("drill", "table", "safe", "jewelry", "vaultdoor"), b);
    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerList().getPlayerNamesArray(), b);

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("robbery")
            .executes(c -> help(c))
            .then(Commands.literal("help").executes(c -> help(c)))
            .then(Commands.literal("start").then(Commands.argument("name", StringArgumentType.word()).suggests(PLACES).executes(c -> start(c))))
            .then(Commands.literal("crew").executes(c -> { Gui.openCrew(pl(c)); return 1; }))
            .then(Commands.literal("invite").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(c -> invite(c))))
            .then(Commands.literal("accept").executes(c -> { Crews.accept(pl(c)); return 1; }))
            .then(Commands.literal("disband").executes(c -> disband(c)))
            .then(Commands.literal("give").requires(s -> s.hasPermission(2))
                .then(Commands.argument("item", StringArgumentType.word()).suggests(ITEMS)
                    .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(c -> give(c)))))
            .then(Commands.literal("getstick").requires(s -> s.hasPermission(2)).executes(c -> {
                ServerPlayer p = pl(c); p.getInventory().add(Gui.stickItem()); Text.send(p, "GETSTICK"); return 1; }))
            .then(Commands.literal("structure").requires(s -> s.hasPermission(2)).executes(c -> { Gui.openStructures(pl(c)); return 1; }))
            .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> reload(c)))
            .then(Commands.literal("region").requires(s -> s.hasPermission(2))
                .then(Commands.literal("pos1").executes(c -> pos(c, 0)))
                .then(Commands.literal("pos2").executes(c -> pos(c, 1)))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(c -> regionCreate(c))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).executes(c -> regionRemove(c))))
                .then(Commands.literal("list").executes(c -> regionList(c)))));
    }

    private static ServerPlayer pl(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return c.getSource().getPlayerOrException();
    }

    private static int help(CommandContext<CommandSourceStack> c) {
        var lang = Cfg.language.getAsJsonObject("Messages").getAsJsonObject("Commands");
        lang.getAsJsonArray("Default").forEach(l -> c.getSource().sendSystemMessage(Text.comp(l.getAsString())));
        if (c.getSource().hasPermission(2)) lang.getAsJsonArray("Admin").forEach(l -> c.getSource().sendSystemMessage(Text.comp(l.getAsString())));
        c.getSource().sendSystemMessage(Text.comp("&7/Robbery &6Region <pos1|pos2|create|remove|list> &8- &7Define robbery regions (replaces WorldGuard)"));
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = pl(c);
        String name = StringArgumentType.getString(c, "name");
        String place = Cfg.placeName(name);
        if (place == null) { Text.send(p, "PLACE_NOT_FOUND"); return 0; }
        String region = Cfg.places().getAsJsonObject(place).get("Region-Id").getAsString();
        if (!region.equalsIgnoreCase(String.valueOf(Cfg.regionAt(p.serverLevel(), p.blockPosition())))) { Text.send(p, "NOT_IN_ROBBERY_REGION"); return 0; }
        Gui.openStart(p, place);
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = pl(c);
        String n = StringArgumentType.getString(c, "player");
        ServerPlayer t = p.server.getPlayerList().getPlayerByName(n);
        if (t == null) { Text.send(p, "PLAYER_NOT_FOUND", "%target%", n); return 0; }
        Crews.invite(p, t);
        return 1;
    }

    private static int disband(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = pl(c);
        Crews.Crew crew = Crews.of(p.getUUID());
        if (crew == null) { Text.send(p, "HELP"); return 0; }
        if (!crew.leader.equals(p.getUUID())) { Text.send(p, "NOT_LEADER"); return 0; }
        Crews.disband(crew);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack s = c.getSource();
        ServerPlayer t = s.getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(c, "player"));
        if (t == null) { s.sendSystemMessage(Text.comp(Text.fill(Text.raw("PLAYER_NOT_FOUND"), "%target%", StringArgumentType.getString(c, "player")))); return 0; }
        ItemStack it = switch (StringArgumentType.getString(c, "item").toLowerCase(Locale.ROOT)) {
            case "drill" -> Gui.drillItem();
            case "table" -> Gui.structItem("TABLE", Items.BLACK_GLAZED_TERRACOTTA, "&7(&aStructure Block&7) &8(&4&lTABLE&8)");
            case "safe" -> Gui.structItem("SAFE", Items.WHITE_GLAZED_TERRACOTTA, "&7(&aStructure Block&7) &8(&4&lSAFE&8)");
            case "jewelry" -> Gui.structItem("JEWELRY_TABLE", Items.RED_GLAZED_TERRACOTTA, "&7(&aStructure Block&7) &8(&4&lJEWELRY TABLE&8)");
            case "vaultdoor" -> Gui.structItem("VAULT_DOOR", Items.IRON_DOOR, "&7(&aStructure Block&7) &8(&4&lVAULT DOOR&8)");
            default -> ItemStack.EMPTY;
        };
        if (it.isEmpty()) { s.sendSystemMessage(Text.comp(Text.raw("ITEM_NOT_FOUND"))); return 0; }
        if (!t.getInventory().add(it)) t.drop(it, false);
        s.sendSystemMessage(Text.comp(Text.raw("ITEM_GIVEN")));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        var srv = c.getSource().getServer();
        StructureManager.restoreAll(srv);
        RobberyManager.clearAll();
        Cfg.load();
        c.getSource().sendSystemMessage(Text.comp(Text.raw("PLUGIN_RELOADED")));
        return 1;
    }

    private static int pos(CommandContext<CommandSourceStack> c, int i) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = pl(c);
        BlockPos[] sel = SELECTION.computeIfAbsent(p.getUUID(), k -> new BlockPos[2]);
        sel[i] = p.blockPosition();
        p.sendSystemMessage(Text.comp("&8[&6KoyaRobbery&8] &7Position " + (i + 1) + " set to &f" + sel[i].toShortString()));
        return 1;
    }

    private static int regionCreate(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = pl(c);
        BlockPos[] sel = SELECTION.get(p.getUUID());
        if (sel == null || sel[0] == null || sel[1] == null) { p.sendSystemMessage(Text.comp("&8[&6KoyaRobbery&8] &cSet /robbery region pos1 and pos2 first")); return 0; }
        Cfg.Region r = new Cfg.Region();
        r.dim = Cfg.dimOf(p.level());
        r.x1 = sel[0].getX(); r.y1 = sel[0].getY(); r.z1 = sel[0].getZ();
        r.x2 = sel[1].getX(); r.y2 = sel[1].getY(); r.z2 = sel[1].getZ();
        String name = StringArgumentType.getString(c, "name");
        Cfg.data.regions.put(name, r);
        Cfg.saveData();
        p.sendSystemMessage(Text.comp("&8[&6KoyaRobbery&8] &7Region &6" + name + " &7saved. Use this name as &fRegion-Id &7in config.json."));
        return 1;
    }

    private static int regionRemove(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        boolean ok = Cfg.data.regions.remove(name) != null;
        if (ok) Cfg.saveData();
        c.getSource().sendSystemMessage(Text.comp(ok ? "&8[&6KoyaRobbery&8] &7Region removed" : "&8[&6KoyaRobbery&8] &cNo such region"));
        return ok ? 1 : 0;
    }

    private static int regionList(CommandContext<CommandSourceStack> c) {
        Cfg.data.regions.forEach((n, r) -> c.getSource().sendSystemMessage(Text.comp("&7- &6" + n + " &8(" + r.dim + ")")));
        return 1;
    }
}
