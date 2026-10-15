package com.koyarobbery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

/**
 * Port of RobberyUtils + RobberyTask. The police-online and minimum-crew checks are intentionally NOT ported.
 * Player feedback is action-bar text, exactly as in the plugin (it has no scoreboard or boss bar).
 */
public final class RobberyManager {
    private RobberyManager() {}

    public static final class Active {
        public final String place, region; public final Crews.Crew crew; public int remaining;
        Active(String place, String region, Crews.Crew crew, int remaining) { this.place = place; this.region = region; this.crew = crew; this.remaining = remaining; }
    }

    private static final List<Active> ACTIVE = new ArrayList<>();
    private static final Map<String, Long> COOLDOWNS = new HashMap<>();
    private static int ticks;

    public static Active of(UUID player) {
        Crews.Crew c = Crews.of(player);
        if (c == null) return null;
        for (Active a : ACTIVE) if (a.crew == c) return a;
        return null;
    }

    public static void clearCooldowns() { COOLDOWNS.clear(); }
    public static void clearAll() { ACTIVE.clear(); COOLDOWNS.clear(); }

    private static MinecraftServer srv() { return ServerLifecycleHooks.getCurrentServer(); }

    public static void begin(ServerPlayer p, String placeInput) {
        String name = Cfg.placeName(placeInput);
        if (name == null) { Text.send(p, "PLACE_NOT_FOUND"); return; }
        JsonObject place = Cfg.places().getAsJsonObject(name);
        String region = place.get("Region-Id").getAsString();
        if (!region.equalsIgnoreCase(String.valueOf(Cfg.regionAt(p.serverLevel(), p.blockPosition())))) {
            Text.send(p, "NOT_IN_ROBBERY_REGION"); return;
        }
        for (Active a : ACTIVE) if (a.region.equalsIgnoreCase(region) || a.crew.members.contains(p.getUUID())) { Text.send(p, "ALREADY_STARTED"); return; }
        Long end = COOLDOWNS.get(region.toLowerCase(Locale.ROOT));
        if (end != null && end > System.currentTimeMillis()) {
            Text.send(p, "COOLDOWN", "%cooldown%", fmt((end - System.currentTimeMillis()) / 1000)); return;
        }
        Crews.Crew crew = Crews.of(p.getUUID());
        if (crew == null) crew = Crews.create(p);
        else if (!crew.leader.equals(p.getUUID())) { Text.send(p, "NOT_LEADER"); return; }

        MinecraftServer server = srv();
        StructureManager.restoreRegion(server, region);
        COOLDOWNS.put(region.toLowerCase(Locale.ROOT), System.currentTimeMillis() + place.get("Robbery-Cooldown").getAsLong() * 1000);
        runCommands(place, "Robbery-Start-Command-List", p);

        String snd = Cfg.config.get("Robbery-Start-Sound").getAsString();
        for (ServerPlayer o : server.getPlayerList().getPlayers())
            if (o.level() == p.level() && o.distanceTo(p) <= 20) Sounds.playCustom(o, snd, 1f, 1f);

        ACTIVE.add(new Active(name, region, crew, place.get("Robbery-Time").getAsInt()));
    }

    private static String fmt(long s) { return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s"; }

    private static void runCommands(JsonObject place, String key, ServerPlayer leader) {
        JsonObject list = place.getAsJsonObject(key);
        if (list == null) return;
        MinecraftServer server = srv();
        for (Map.Entry<String, JsonElement> e : list.entrySet()) {
            String cmd = e.getValue().getAsString().replace("%leader%", leader == null ? "?" : leader.getGameProfile().getName());
            if (cmd.startsWith("broadcast ")) server.getPlayerList().broadcastSystemMessage(Text.comp(cmd.substring(10)), false);
            else server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
        }
    }

    public static void tick() {
        if (++ticks % 20 != 0) return;
        for (Active a : new ArrayList<>(ACTIVE)) secondTick(a);
    }

    private static void secondTick(Active a) {
        MinecraftServer server = srv();
        a.remaining--;
        if (a.remaining <= 0) { end(a); return; }
        boolean leaderFailed = false;
        for (UUID id : new ArrayList<>(a.crew.members)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (!a.region.equalsIgnoreCase(String.valueOf(Cfg.regionAt(p.serverLevel(), p.blockPosition())))) {
                Text.actionBarMsg(p, "ROBBERY_FAILED");
                if (a.crew.leader.equals(id)) leaderFailed = true; else Crews.leave(id);
            } else {
                Text.actionBarMsg(p, "ROBBERY_COUNTDOWN", "%countdown%", String.valueOf(a.remaining));
            }
        }
        if (leaderFailed) end(a);
    }

    public static void onDeath(ServerPlayer p) {
        Active a = of(p.getUUID());
        if (a == null) return;
        Text.actionBarMsg(p, "DEATH");
        if (a.crew.leader.equals(p.getUUID())) end(a); else Crews.leave(p.getUUID());
    }

    public static void onLogout(ServerPlayer p) {
        Active a = of(p.getUUID());
        if (a != null && a.crew.leader.equals(p.getUUID())) end(a);
        Crews.leave(p.getUUID());
    }

    public static void end(Active a) {
        if (!ACTIVE.remove(a)) return;
        MinecraftServer server = srv();
        JsonObject place = Cfg.places().getAsJsonObject(a.place);
        ServerPlayer leader = server.getPlayerList().getPlayer(a.crew.leader);
        List<UUID> members = new ArrayList<>(a.crew.members);

        JsonObject tp = place.getAsJsonObject("Teleport-Robbers-On-End");
        boolean teleport = tp != null && tp.get("Enable").getAsBoolean();
        for (UUID id : members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            Text.send(p, "ROBBERY_ENDED");
            if (teleport && a.region.equalsIgnoreCase(String.valueOf(Cfg.regionAt(p.serverLevel(), p.blockPosition())))) teleportTo(p, tp.get("Teleport-Location").getAsString());
        }
        if (place.get("Restore-Structures-On-End").getAsBoolean()) StructureManager.restoreRegion(server, a.region);
        runCommands(place, "Robbery-End-Command-List", leader);
    }

    /** Format: world;x;y;z;yaw;pitch ("world" = overworld). */
    private static void teleportTo(ServerPlayer p, String loc) {
        String[] s = loc.split(";");
        if (s.length < 4) return;
        String w = s[0];
        String dim = switch (w) { case "world" -> "minecraft:overworld"; case "world_nether" -> "minecraft:the_nether"; case "world_the_end" -> "minecraft:the_end"; default -> w; };
        ServerLevel lvl = StructureManager.level(srv(), dim.contains(":") ? dim : "minecraft:" + dim);
        if (lvl == null) lvl = p.serverLevel();
        float yaw = s.length > 4 ? Float.parseFloat(s[4]) : 0, pitch = s.length > 5 ? Float.parseFloat(s[5]) : 0;
        p.teleportTo(lvl, Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]), yaw, pitch);
    }
}
