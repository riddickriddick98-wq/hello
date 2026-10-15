package com.koyarobbery;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

/** Port of CrewUtils + the crew/invite maps from Storage. */
public final class Crews {
    private Crews() {}

    public static final class Crew {
        public final UUID leader; public final String name;
        public final List<UUID> members = new ArrayList<>();
        Crew(UUID l, String n) { leader = l; name = n; members.add(l); }
    }
    private record Invite(UUID leader, long expires) {}

    private static final Map<UUID, Crew> BY_MEMBER = new HashMap<>();
    private static final Map<UUID, Invite> INVITES = new HashMap<>();

    public static Crew of(UUID p) { return BY_MEMBER.get(p); }
    public static boolean isLeader(UUID p) { Crew c = of(p); return c != null && c.leader.equals(p); }
    private static MinecraftServer srv() { return ServerLifecycleHooks.getCurrentServer(); }
    private static ServerPlayer player(UUID id) { return srv().getPlayerList().getPlayer(id); }

    public static Crew create(ServerPlayer leader) {
        Crew c = new Crew(leader.getUUID(), leader.getGameProfile().getName());
        BY_MEMBER.put(leader.getUUID(), c);
        Text.send(leader, "CREW_CREATED");
        return c;
    }

    public static void invite(ServerPlayer leader, ServerPlayer target) {
        Crew c = of(leader.getUUID());
        if (c == null) { Text.send(leader, "HELP"); return; }
        if (!c.leader.equals(leader.getUUID())) { Text.send(leader, "NOT_LEADER"); return; }
        if (RobberyManager.of(leader.getUUID()) != null) { Text.send(leader, "INVITE_ON_ROBBERY"); return; }
        if (target.getUUID().equals(leader.getUUID())) { Text.send(leader, "SELF_INVITE"); return; }
        if (of(target.getUUID()) != null) { Text.send(leader, "ALREADY_IN_CREW"); return; }
        Invite old = INVITES.get(target.getUUID());
        if (old != null && old.expires > System.currentTimeMillis()) { Text.send(leader, "ALREADY_INVITED"); return; }
        if (c.members.size() >= Cfg.config.get("Max-Crew-Size").getAsInt()) { Text.send(leader, "CREW_IS_FULL"); return; }
        INVITES.put(target.getUUID(), new Invite(leader.getUUID(), System.currentTimeMillis() + 30_000));
        Text.send(leader, "SEND_INVITE", "%target%", target.getGameProfile().getName());
        Text.send(target, "GET_INVITE", "%leader%", leader.getGameProfile().getName());
    }

    public static void accept(ServerPlayer target) {
        Invite inv = INVITES.get(target.getUUID());
        if (inv == null) { Text.send(target, "NOT_INVITED"); return; }
        INVITES.remove(target.getUUID());
        if (inv.expires < System.currentTimeMillis()) { Text.send(target, "TIME_IS_UP"); return; }
        Crew c = of(inv.leader);
        if (c == null) { Text.send(target, "NOT_INVITED"); return; }
        if (c.members.size() >= Cfg.config.get("Max-Crew-Size").getAsInt()) { Text.send(target, "CREW_IS_FULL"); return; }
        for (UUID m : c.members) Text.send(player(m), "JOINED", "%target%", target.getGameProfile().getName());
        c.members.add(target.getUUID());
        BY_MEMBER.put(target.getUUID(), c);
        Text.send(target, "JOIN");
    }

    /** Member leaves. Leader leaving disbands the crew. */
    public static void leave(UUID id) {
        Crew c = of(id);
        if (c == null) return;
        if (c.leader.equals(id)) { disband(c); return; }
        c.members.remove(id);
        BY_MEMBER.remove(id);
        ServerPlayer p = player(id);
        String n = p != null ? p.getGameProfile().getName() : "?";
        for (UUID m : c.members) Text.send(player(m), "LEAVE", "%target%", n);
    }

    public static void kick(ServerPlayer leader, UUID target) {
        Crew c = of(leader.getUUID());
        if (c == null) { Text.send(leader, "HELP"); return; }
        if (!c.leader.equals(leader.getUUID())) { Text.send(leader, "NOT_LEADER"); return; }
        if (target.equals(leader.getUUID()) || !c.members.contains(target)) return;
        ServerPlayer t = player(target);
        String n = t != null ? t.getGameProfile().getName() : "?";
        c.members.remove(target);
        BY_MEMBER.remove(target);
        for (UUID m : c.members) Text.send(player(m), "KICK", "%target%", n);
        Text.send(t, "KICK", "%target%", n);
    }

    public static void disband(Crew c) {
        for (UUID m : new ArrayList<>(c.members)) {
            Text.send(player(m), "DISBAND");
            BY_MEMBER.remove(m);
        }
        c.members.clear();
    }
}
