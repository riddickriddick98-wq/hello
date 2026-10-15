package com.koyarobbery;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Ports Safe / Table / Jewelry tasks, StructuresUtils, BuildUtils and DrillUtils.
 * Structure blocks (glazed terracotta) and vault doors are stored in data.json.
 */
public final class StructureManager {
    private StructureManager() {}

    public static final Set<String> OPEN_DOORS = new HashSet<>();
    public static final Set<String> ACTIVE_DRILLS = new HashSet<>();
    private static final Map<String, SimpleContainer> LOOT = new HashMap<>();
    private static final Set<String> BUSY = new HashSet<>();           // structure ids currently being picked/looted
    private static final List<Drill> DRILLS = new ArrayList<>();
    private static final List<HoldTask> HOLDS = new ArrayList<>();
    private static final Map<UUID, Pick> PICKS = new HashMap<>();
    private static final Random RNG = new Random();

    // ------------------------------------------------------------ helpers
    public static ServerLevel level(MinecraftServer s, String dim) {
        return s.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dim)));
    }

    public static Block block(String key) {
        String n = Cfg.config.getAsJsonObject("Blocks").get(key).getAsString().toLowerCase(Locale.ROOT);
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(n));
        return b == null ? Blocks.AIR : b;
    }

    private static BlockState withFace(Block b, String face) {
        BlockState s = b.defaultBlockState();
        Direction d = face == null ? null : Direction.byName(face);
        if (d != null && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
        return s;
    }

    public static Map.Entry<String, Cfg.Structure> structureAt(net.minecraft.world.level.Level l, BlockPos p) {
        String d = Cfg.dimOf(l);
        for (var e : Cfg.data.structures.entrySet()) {
            Cfg.Structure s = e.getValue();
            if (s.dim.equals(d) && s.x == p.getX() && s.y == p.getY() && s.z == p.getZ()) return e;
        }
        return null;
    }

    public static Map.Entry<String, Cfg.Build> buildAt(net.minecraft.world.level.Level l, BlockPos p) {
        String d = Cfg.dimOf(l);
        long key = p.asLong();
        for (var e : Cfg.data.builds.entrySet())
            if (e.getValue().dim.equals(d) && e.getValue().blocks.contains(key)) return e;
        return null;
    }

    // ------------------------------------------------------------ placing (admin)
    public static void place(ServerPlayer p, String type, BlockPos pos) {
        ServerLevel lvl = p.serverLevel();
        String region = Cfg.regionAt(lvl, pos);
        if (region == null) { Text.send(p, "WRONG_STRUCTURE_LOCATION"); return; }
        if (type.equals("VAULT_DOOR")) { placeDoor(p, lvl, pos, region); return; }
        String face = p.getDirection().getOpposite().getName();
        lvl.setBlockAndUpdate(pos, withFace(block(type), face));
        Cfg.Structure s = new Cfg.Structure();
        s.region = region; s.type = type; s.dim = Cfg.dimOf(lvl); s.face = face;
        s.x = pos.getX(); s.y = pos.getY(); s.z = pos.getZ();
        Cfg.data.structures.put(String.valueOf(Cfg.data.id++), s);
        Cfg.saveData();
        Text.send(p, "STRUCTURE_PLACED");
    }

    /** Vault door: 3 deep x 7 wide x 7 high box (147 blocks, as stated in the plugin's Structures GUI lore). */
    private static void placeDoor(ServerPlayer p, ServerLevel lvl, BlockPos pos, String region) {
        Direction depth = p.getDirection();
        Direction width = depth.getClockWise();
        Cfg.Build b = new Cfg.Build();
        b.region = region; b.dim = Cfg.dimOf(lvl); b.face = depth.getOpposite().getName();
        b.x = pos.getX(); b.y = pos.getY(); b.z = pos.getZ();
        BlockState fill = block("VAULT_DOOR_FILL").defaultBlockState();
        for (int d = 0; d < 3; d++) for (int w = -3; w <= 3; w++) for (int h = 0; h < 7; h++) {
            BlockPos bp = pos.relative(depth, d).relative(width, w).above(h);
            if (lvl.getBlockState(bp).isAir()) { lvl.setBlockAndUpdate(bp, fill); b.blocks.add(bp.asLong()); }
        }
        Cfg.data.builds.put(String.valueOf(Cfg.data.id++), b);
        Cfg.saveData();
        Text.send(p, "STRUCTURE_PLACED");
    }

    public static void removeAt(ServerPlayer p, BlockPos pos) {
        ServerLevel lvl = p.serverLevel();
        var s = structureAt(lvl, pos);
        if (s != null) {
            lvl.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            Cfg.data.structures.remove(s.getKey()); LOOT.remove(s.getKey());
            Cfg.saveData(); Text.send(p, "STRUCTURE_REMOVED"); return;
        }
        var b = buildAt(lvl, pos);
        if (b != null) {
            for (long l : b.getValue().blocks) lvl.setBlockAndUpdate(BlockPos.of(l), Blocks.AIR.defaultBlockState());
            OPEN_DOORS.remove(b.getKey()); ACTIVE_DRILLS.remove(b.getKey());
            DRILLS.removeIf(d -> d.id.equals(b.getKey()));
            Cfg.data.builds.remove(b.getKey());
            Cfg.saveData(); Text.send(p, "STRUCTURE_REMOVED");
        }
    }

    // ------------------------------------------------------------ restore
    public static void restoreRegion(MinecraftServer srv, String region) {
        for (var e : Cfg.data.structures.entrySet()) {
            Cfg.Structure s = e.getValue();
            if (!s.region.equalsIgnoreCase(region)) continue;
            ServerLevel lvl = level(srv, s.dim);
            if (lvl == null) continue;
            lvl.setBlockAndUpdate(new BlockPos(s.x, s.y, s.z), withFace(block(s.type), s.face));
            LOOT.remove(e.getKey()); BUSY.remove(e.getKey());
        }
        BlockState fill = block("VAULT_DOOR_FILL").defaultBlockState();
        for (var e : Cfg.data.builds.entrySet()) {
            Cfg.Build b = e.getValue();
            if (!b.region.equalsIgnoreCase(region)) continue;
            ServerLevel lvl = level(srv, b.dim);
            if (lvl == null) continue;
            for (long l : b.blocks) lvl.setBlockAndUpdate(BlockPos.of(l), fill);
            OPEN_DOORS.remove(e.getKey()); ACTIVE_DRILLS.remove(e.getKey());
            DRILLS.removeIf(d -> d.id.equals(e.getKey()));
        }
    }

    public static void restoreAll(MinecraftServer srv) {
        Set<String> regions = new HashSet<>();
        Cfg.data.structures.values().forEach(s -> regions.add(s.region));
        Cfg.data.builds.values().forEach(b -> regions.add(b.region));
        for (String r : regions) restoreRegion(srv, r);
        LOOT.clear(); BUSY.clear(); DRILLS.clear(); ACTIVE_DRILLS.clear(); OPEN_DOORS.clear(); HOLDS.clear(); PICKS.clear();
    }

    private static boolean allDoorsOpen(String region) {
        for (var e : Cfg.data.builds.entrySet())
            if (e.getValue().region.equalsIgnoreCase(region) && !OPEN_DOORS.contains(e.getKey())) return false;
        return true;
    }

    // ------------------------------------------------------------ vault drill
    private static final class Drill {
        String id; ServerLevel lvl; List<BlockPos> blocks = new ArrayList<>(); int tick, total, removed; Vec3 center;
    }

    public static void tryDrill(ServerPlayer p, BlockPos pos, ItemStack stack) {
        ServerLevel lvl = p.serverLevel();
        var e = buildAt(lvl, pos);
        RobberyManager.Active a = RobberyManager.of(p.getUUID());
        if (e == null || a == null || !a.region.equalsIgnoreCase(e.getValue().region)) {
            Text.send(p, "INVALID_DOOR");
            lvl.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1f, 0.5f);
            return;
        }
        String id = e.getKey();
        if (ACTIVE_DRILLS.contains(id)) { Text.send(p, "ALREADY_DRILLING"); return; }
        if (OPEN_DOORS.contains(id)) { Text.send(p, "ALREADY_OPENED"); return; }
        Drill d = new Drill();
        d.id = id; d.lvl = lvl;
        double sx = 0, sy = 0, sz = 0;
        for (long l : e.getValue().blocks) { BlockPos bp = BlockPos.of(l); d.blocks.add(bp); sx += bp.getX() + .5; sy += bp.getY() + .5; sz += bp.getZ() + .5; }
        int n = Math.max(1, d.blocks.size());
        d.center = new Vec3(sx / n, sy / n, sz / n);
        d.total = Math.max(20, Cfg.config.getAsJsonObject("Vault-Drill").get("Drill-Duration").getAsInt() * 20);
        DRILLS.add(d); ACTIVE_DRILLS.add(id);
        if (Cfg.config.getAsJsonObject("Vault-Drill").get("Consume-On-Use").getAsBoolean()) stack.shrink(1);
        lvl.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1f, 1f);
    }

    private static void tickDrills() {
        for (Iterator<Drill> it = DRILLS.iterator(); it.hasNext(); ) {
            Drill d = it.next();
            d.tick++;
            double progress = Math.min(1.0, d.tick / (double) d.total);
            int target = (int) (d.blocks.size() * progress);
            while (d.removed < target) d.lvl.setBlockAndUpdate(d.blocks.get(d.removed++), Blocks.AIR.defaultBlockState());
            Vec3 c = d.center;
            if (d.tick % 4 == 0) {
                d.lvl.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 6, 2.5, 2.5, 2.5, 0.02);
                d.lvl.sendParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 4, 2.5, 2.5, 2.5, 0.1);
                d.lvl.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 3, 2.0, 2.0, 2.0, 0.01);
                d.lvl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.defaultBlockState()), c.x, c.y, c.z, 8, 2.5, 2.5, 2.5, 0.1);
            }
            if (d.tick % 10 == 0) {
                var snd = progress < 0.25 ? SoundEvents.ANVIL_LAND : progress < 0.5 ? SoundEvents.PISTON_EXTEND
                        : progress < 0.75 ? SoundEvents.IRON_GOLEM_DAMAGE : SoundEvents.CHAIN_BREAK;
                d.lvl.playSound(null, BlockPos.containing(c), snd, SoundSource.BLOCKS, 1f, (float) (0.6 + progress * 0.8));
                if (d.tick % 20 == 0) d.lvl.playSound(null, BlockPos.containing(c), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1f);
                for (ServerPlayer pl : d.lvl.players()) {
                    double dist = pl.position().distanceTo(c);
                    if (dist < 12) {
                        double k = 0.04 * (1 - dist / 12.0) * (0.5 + progress);
                        pl.setDeltaMovement(pl.getDeltaMovement().add((RNG.nextDouble() - .5) * k, 0, (RNG.nextDouble() - .5) * k));
                        pl.hurtMarked = true;
                    }
                }
            }
            if (d.tick >= d.total) {
                while (d.removed < d.blocks.size()) d.lvl.setBlockAndUpdate(d.blocks.get(d.removed++), Blocks.AIR.defaultBlockState());
                d.lvl.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 2, 2, 2, 0);
                d.lvl.sendParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                d.lvl.playSound(null, BlockPos.containing(c), SoundEvents.ANVIL_BREAK, SoundSource.BLOCKS, 1.2f, 0.8f);
                OPEN_DOORS.add(d.id); ACTIVE_DRILLS.remove(d.id);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------ looting
    /** @return true if the click was handled (event should be cancelled). */
    public static boolean tryLoot(ServerPlayer p, BlockPos pos) {
        ServerLevel lvl = p.serverLevel();
        var e = structureAt(lvl, pos);
        if (e == null) return false;
        RobberyManager.Active a = RobberyManager.of(p.getUUID());
        Cfg.Structure s = e.getValue();
        if (a == null || !a.region.equalsIgnoreCase(s.region)) return false;
        String id = e.getKey();
        JsonObject place = Cfg.placeByRegion(s.region);
        if (place != null && place.get("Check-Vault-Doors").getAsBoolean() && !allDoorsOpen(s.region)) {
            Text.send(p, "OPEN_DOORS_FIRST"); return true;
        }
        if (lvl.getBlockState(pos).is(block("EMPTY_" + s.type))) return true;
        if (LOOT.containsKey(id)) { openLoot(p, id, s, pos); return true; }
        if (BUSY.contains(id)) return true;
        String tool = Cfg.loots.get(LootManager.toolKey(s.type)).getAsString();
        if (!tool.equalsIgnoreCase("NOTHING")) {
            var key = ForgeRegistries.ITEMS.getKey(p.getMainHandItem().getItem());
            if (key == null || !key.getPath().equalsIgnoreCase(tool)) {
                Text.actionBar(p, "&8[&c&lFail&8] &7Hold &f" + tool); return true;
            }
        }
        BUSY.add(id);
        if (s.type.equals("SAFE")) startPick(p, id, s, pos);
        else {
            HoldTask h = new HoldTask();
            h.p = p.getUUID(); h.id = id; h.s = s; h.pos = pos; h.start = p.position();
            h.ticks = Cfg.config.getAsJsonObject("Loot-Time-Seconds").get(s.type).getAsInt() * 20;
            h.total = h.ticks;
            HOLDS.add(h);
        }
        return true;
    }

    /** Table / jewelry: stand still next to the structure for a few seconds. */
    private static final class HoldTask { UUID p; String id; Cfg.Structure s; BlockPos pos; Vec3 start; int ticks, total; }

    private static void tickHolds(MinecraftServer srv) {
        for (Iterator<HoldTask> it = HOLDS.iterator(); it.hasNext(); ) {
            HoldTask h = it.next();
            ServerPlayer p = srv.getPlayerList().getPlayer(h.p);
            if (p == null || p.isDeadOrDying() || p.position().distanceTo(h.start) > 1.5 || RobberyManager.of(h.p) == null) {
                if (p != null) Text.actionBar(p, "&8[&c&lFail&8]");
                BUSY.remove(h.id); it.remove(); continue;
            }
            if (--h.ticks <= 0) {
                Text.actionBar(p, "&8[&a&lDone&8]");
                p.serverLevel().playSound(null, h.pos, SoundEvents.ANVIL_DESTROY, SoundSource.BLOCKS, 1f, 1f);
                LOOT.put(h.id, LootManager.generate(LootManager.section(h.s.type), LootManager.perKey(h.s.type)));
                BUSY.remove(h.id); it.remove();
                openLoot(p, h.id, h.s, h.pos);
            } else if (h.ticks % 10 == 0) {
                int pct = (int) ((h.total - h.ticks) * 100.0 / h.total);
                Text.actionBar(p, "&7Looting... &e" + pct + "%");
            }
        }
    }

    private static String label(String type) { return type.equals("SAFE") ? "Safe" : type.equals("TABLE") ? "Table" : "Jewelry"; }

    private static void openLoot(ServerPlayer p, String id, Cfg.Structure s, BlockPos pos) {
        SimpleContainer c = LOOT.get(id);
        if (c == null) return;
        if (s.type.equals("SAFE") && !p.serverLevel().getBlockState(pos).is(block("OPEN_SAFE")))
            p.serverLevel().setBlockAndUpdate(pos, withFace(block("OPEN_SAFE"), s.face));
        p.openMenu(new net.minecraft.world.SimpleMenuProvider((cid, inv, pl) -> new net.minecraft.world.inventory.ChestMenu(
            net.minecraft.world.inventory.MenuType.GENERIC_9x3, cid, inv, c, 3) {
                @Override public void removed(net.minecraft.world.entity.player.Player player) {
                    super.removed(player);
                    if (c.isEmpty() && player.level() instanceof ServerLevel sl)
                        sl.setBlockAndUpdate(pos, withFace(block("EMPTY_" + s.type), s.face));
                }
            }, Text.comp("&8Robbery (" + label(s.type) + ")")));
    }

    // ------------------------------------------------------------ safe lock-pick
    private static final class Pick { ServerPlayer p; String id; Cfg.Structure s; BlockPos pos; SimpleContainer c = new SimpleContainer(27); int idx = 0, dir = 1, tick; boolean finished; }

    private static net.minecraft.world.item.Item paneFor(int i) {
        return (i >= 3 && i <= 5) ? net.minecraft.world.item.Items.GREEN_STAINED_GLASS_PANE
             : (i == 2 || i == 6) ? net.minecraft.world.item.Items.ORANGE_STAINED_GLASS_PANE
             : net.minecraft.world.item.Items.RED_STAINED_GLASS_PANE;
    }

    private static void startPick(ServerPlayer p, String id, Cfg.Structure s, BlockPos pos) {
        Pick pk = new Pick();
        pk.p = p; pk.id = id; pk.s = s; pk.pos = pos;
        Gui.fill(pk.c);
        for (int i = 0; i < 9; i++) pk.c.setItem(9 + i, Gui.named(paneFor(i), "&7"));
        pk.c.setItem(9, Gui.named(net.minecraft.world.item.Items.CONDUIT, "&7"));
        PICKS.put(p.getUUID(), pk);
        Gui.open(p, "&8Lock Picking ...", pk.c, 3, (slot, sp) -> {
            Pick cur = PICKS.get(sp.getUUID());
            if (cur == null || cur.finished) return;
            cur.finished = true;
            boolean ok = cur.idx >= 3 && cur.idx <= 5;
            sp.server.execute(() -> {
                PICKS.remove(sp.getUUID());
                sp.closeContainer();
                if (!ok) { Text.actionBar(sp, "&8[&c&lFail&8]"); BUSY.remove(cur.id); return; }
                Text.actionBar(sp, "&8[&a&lDone&8]");
                sp.serverLevel().playSound(null, cur.pos, SoundEvents.ANVIL_DESTROY, SoundSource.BLOCKS, 1f, 1f);
                LOOT.put(cur.id, LootManager.generate("Safe", "Amount-Per-Safe"));
                BUSY.remove(cur.id);
                openLoot(sp, cur.id, cur.s, cur.pos);
            });
        }, player -> {
            Pick cur = PICKS.get(player.getUUID());
            if (cur != null && !cur.finished) { PICKS.remove(player.getUUID()); BUSY.remove(cur.id); }
        });
    }

    private static void tickPicks() {
        for (Pick pk : PICKS.values()) {
            if (pk.finished || ++pk.tick % 4 != 0) continue;
            pk.c.setItem(9 + pk.idx, Gui.named(paneFor(pk.idx), "&7"));
            if (pk.idx + pk.dir > 8 || pk.idx + pk.dir < 0) pk.dir = -pk.dir;
            pk.idx += pk.dir;
            pk.c.setItem(9 + pk.idx, Gui.named(net.minecraft.world.item.Items.CONDUIT, "&7"));
        }
    }

    // ------------------------------------------------------------ per-tick entry
    public static void tick(MinecraftServer srv) {
        tickDrills();
        tickHolds(srv);
        tickPicks();
    }
}
