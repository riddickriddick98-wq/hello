package com.koya.robbery;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ObjectiveCriteria;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.*;

@Mod(KoyaRobberyMod.MODID)
public class KoyaRobberyMod {
    public static final String MODID = "koyarobbery";
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final RegistryObject<Item> VAULT_DRILL = ITEMS.register("vault_drill", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MODID);
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> ROBBERY_START = SOUNDS.register("robbery_start", () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(new ResourceLocation(MODID, "robbery_start")));

    private static final Map<String, Robbery> ROBBERIES = new HashMap<>();
    private static final Map<UUID, UUID> PLAYER_CREWS = new HashMap<>();
    private static final Map<UUID, Invite> INVITES = new HashMap<>();
    private static final Map<String, Long> COOLDOWNS = new HashMap<>();
    private static final Map<String, ServerBossEvent> BOSS_BARS = new HashMap<>();
    private static final List<DrillTask> DRILLS = new ArrayList<>();

    public KoyaRobberyMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        SOUNDS.register(bus);
        bus.addListener(this::addCreativeItems);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(VAULT_DRILL);
    }

    private record Invite(UUID leader, long expiresAt) {}
    private record DrillTask(ResourceKey<Level> dimension, BlockPos pos, UUID player, long finishTick) {}
    private static class Robbery {
        final String name; final UUID leader; final long startTick; final long endTick; final Set<UUID> members;
        Robbery(String name, UUID leader, long startTick, long endTick, Collection<UUID> members) {
            this.name = name; this.leader = leader; this.startTick = startTick; this.endTick = endTick; this.members = new HashSet<>(members);
        }
    }

    private static String key(String place) { return place.toLowerCase(Locale.ROOT); }
    private static long now(MinecraftServer server) { return server.overworld().getGameTime(); }
    private static void msg(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal("[KoyaRobbery] ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }
    private static UUID crewLeader(ServerPlayer p) { return PLAYER_CREWS.getOrDefault(p.getUUID(), p.getUUID()); }
    private static Set<UUID> crewMembers(UUID leader) {
        Set<UUID> members = new HashSet<>(); members.add(leader);
        PLAYER_CREWS.forEach((member, root) -> { if (root.equals(leader)) members.add(member); });
        return members;
    }
    private static void setupScoreboard(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        Objective obj = sb.getObjective("koya_robbery");
        if (obj == null) obj = sb.addObjective("koya_robbery", ObjectiveCriteria.DUMMY,
            Component.literal("KOYA ROBBERY").withStyle(ChatFormatting.GOLD), ObjectiveCriteria.RenderType.INTEGER, true);
        sb.setDisplayObjective(DisplaySlot.SIDEBAR, obj);
        for (String place : List.of("CentralBank", "GunStore")) {
            Robbery robbery = ROBBERIES.get(key(place));
            String entry = (robbery == null ? ChatFormatting.GRAY : ChatFormatting.RED) + place;
            sb.getOrCreatePlayerScore(entry, obj).setScore(robbery == null ? 0 : (int)Math.max(1, (robbery.endTick - now(server)) / 20));
        }
    }
    private static void updateBossBars(MinecraftServer server) {
        long tick = now(server);
        for (Robbery r : ROBBERIES.values()) {
            ServerBossEvent bar = BOSS_BARS.computeIfAbsent(key(r.name), k -> new ServerBossEvent(
                Component.literal("ROBBERY: " + r.name).withStyle(ChatFormatting.GOLD), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));
            int seconds = (int)Math.max(0, (r.endTick - tick) / 20);
            bar.setName(Component.literal("ROBBERY: " + r.name + "  |  " + seconds + "s").withStyle(ChatFormatting.GOLD));
            bar.setProgress(Math.max(0f, Math.min(1f, (float)(r.endTick - tick) / (float)(r.endTick - r.startTick))));
            for (ServerPlayer player : server.getPlayerList().getPlayers()) if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        }
        for (String k : new ArrayList<>(BOSS_BARS.keySet())) if (!ROBBERIES.containsKey(k)) {
            ServerBossEvent bar = BOSS_BARS.remove(k);
            if (bar != null) bar.removeAllPlayers();
        }
    }

    @SubscribeEvent public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("robbery")
            .then(Commands.literal("help").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                msg(p, "/robbery crew | invite <player> | accept | disband");
                msg(p, "/robbery start <CentralBank|GunStore> | status"); return 1;
            }))
            .then(Commands.literal("crew").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException(); UUID leader = crewLeader(p);
                Set<UUID> members = crewMembers(leader);
                msg(p, "Crew leader: " + ctx.getSource().getServer().getProfileCache().get(leader).map(g -> g.getName()).orElse(leader.toString().substring(0, 8)) + ". Members: " + members.size());
                msg(p, "Invite a player with /robbery invite <player>."); return 1;
            }))
            .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException(); ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                if (p == target) { msg(p, "You cannot invite yourself."); return 0; }
                if (PLAYER_CREWS.containsKey(target.getUUID())) { msg(p, "That player is already in a crew."); return 0; }
                UUID leader = crewLeader(p); PLAYER_CREWS.putIfAbsent(p.getUUID(), leader);
                INVITES.put(target.getUUID(), new Invite(leader, now(p.getServer()) + 600));
                msg(p, "Invite sent to " + target.getGameProfile().getName() + ". They have 30 seconds to accept.");
                msg(target, p.getGameProfile().getName() + " invited you. Use /robbery accept within 30 seconds."); return 1;
            })))
            .then(Commands.literal("accept").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException(); Invite invite = INVITES.remove(p.getUUID());
                if (invite == null || now(p.getServer()) > invite.expiresAt()) { msg(p, "You have no active crew invitation."); return 0; }
                PLAYER_CREWS.put(p.getUUID(), invite.leader()); msg(p, "You joined the crew.");
                ServerPlayer leader = p.getServer().getPlayerList().getPlayer(invite.leader()); if (leader != null) msg(leader, p.getGameProfile().getName() + " joined your crew."); return 1;
            }))
            .then(Commands.literal("disband").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException(); UUID leader = crewLeader(p);
                if (!leader.equals(p.getUUID())) { msg(p, "Only the crew leader can disband the crew."); return 0; }
                PLAYER_CREWS.entrySet().removeIf(e -> e.getValue().equals(leader)); PLAYER_CREWS.remove(leader);
                msg(p, "Crew disbanded."); return 1;
            }))
            .then(Commands.literal("start").then(Commands.argument("place", StringArgumentType.word()).executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException(); String supplied = StringArgumentType.getString(ctx, "place");
                String place = supplied.equalsIgnoreCase("CentralBank") ? "CentralBank" : supplied.equalsIgnoreCase("GunStore") ? "GunStore" : null;
                if (place == null) { msg(p, "Unknown place. Use CentralBank or GunStore."); return 0; }
                MinecraftServer server = p.getServer(); long tick = now(server); String k = key(place);
                if (ROBBERIES.containsKey(k)) { msg(p, "That robbery is already active."); return 0; }
                if (COOLDOWNS.getOrDefault(k, 0L) > tick) { msg(p, "That robbery is on cooldown for " + ((COOLDOWNS.get(k) - tick) / 20) + " seconds."); return 0; }
                UUID leader = crewLeader(p); Set<UUID> members = crewMembers(leader); members.add(p.getUUID());
                int duration = place.equals("CentralBank") ? 300 : 180;
                Robbery robbery = new Robbery(place, p.getUUID(), tick, tick + duration * 20L, members);
                ROBBERIES.put(k, robbery);
                Component alert = Component.literal("[KoyaRobbery] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(p.getGameProfile().getName() + " started robbing " + place + "!").withStyle(ChatFormatting.RED));
                server.getPlayerList().broadcastSystemMessage(alert, false);
                for (ServerPlayer nearby : server.getPlayerList().getPlayers()) {
                    if (nearby.serverLevel() == p.serverLevel() && nearby.distanceToSqr(p) <= 400.0D) {
                        nearby.playNotifySound(ROBBERY_START.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
                    }
                }
                setupScoreboard(server); updateBossBars(server); msg(p, "Robbery started: " + place + ". No police-online or minimum-crew requirement applies."); return 1;
            })))
            .then(Commands.literal("status").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException(); long tick = now(p.getServer());
                if (ROBBERIES.isEmpty()) msg(p, "No robberies are active.");
                else ROBBERIES.values().forEach(r -> msg(p, r.name + " active; " + Math.max(0, (r.endTick - tick) / 20) + " seconds remaining."));
                return 1;
            }))
        );
    }

    @SubscribeEvent public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        MinecraftServer server = event.getServer(); long tick = now(server);
        INVITES.entrySet().removeIf(e -> tick > e.getValue().expiresAt());
        Iterator<DrillTask> drillIterator = DRILLS.iterator();
        while (drillIterator.hasNext()) {
            DrillTask task = drillIterator.next();
            if (tick < task.finishTick()) continue;
            Level level = server.getLevel(task.dimension());
            if (level != null) {
                BlockPos pos = task.pos();
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock && state.is(Blocks.IRON_DOOR)) {
                    BlockPos lowerPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
                    BlockPos upperPos = lowerPos.above();
                    BlockState lower = level.getBlockState(lowerPos);
                    BlockState upper = level.getBlockState(upperPos);
                    if (lower.getBlock() instanceof DoorBlock && upper.getBlock() instanceof DoorBlock) {
                        level.setBlock(lowerPos, lower.setValue(DoorBlock.OPEN, true), 3);
                        level.setBlock(upperPos, upper.setValue(DoorBlock.OPEN, true), 3);
                    }
                }
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(task.player());
            if (owner != null) msg(owner, "Vault drill complete.");
            drillIterator.remove();
        }
        Iterator<Map.Entry<String, Robbery>> it = ROBBERIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Robbery> entry = it.next(); Robbery r = entry.getValue();
            if (tick >= r.endTick) {
                server.getPlayerList().broadcastSystemMessage(Component.literal("[KoyaRobbery] Robbery ended: " + r.name).withStyle(ChatFormatting.GREEN), false);
                COOLDOWNS.put(entry.getKey(), tick + (r.name.equals("CentralBank") ? 600 : 400) * 20L);
                it.remove();
            }
        }
        if (tick % 20 == 0) { setupScoreboard(server); updateBossBars(server); }
    }

    @SubscribeEvent public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack held = player.getItemInHand(event.getHand());
        if (held.getItem() != VAULT_DRILL.get()) return;
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.getBlock() instanceof DoorBlock && state.is(Blocks.IRON_DOOR)) {
            if (state.getValue(DoorBlock.OPEN)) { msg(player, "This vault door is already open."); event.setCanceled(true); return; }
            BlockPos pos = event.getPos();
            boolean alreadyDrilling = DRILLS.stream().anyMatch(task -> task.dimension().equals(event.getLevel().dimension()) && task.pos().closerThan(pos, 1.5));
            if (alreadyDrilling) { msg(player, "A drill is already working on this door."); event.setCanceled(true); return; }
            DRILLS.add(new DrillTask(event.getLevel().dimension(), pos.immutable(), player.getUUID(), now(player.getServer()) + 300));
            held.shrink(1);
            player.sendSystemMessage(Component.literal("[KoyaRobbery] Vault drill started; the door will open in 15 seconds.").withStyle(ChatFormatting.AQUA));
            event.setCanceled(true);
        }
    }
}
