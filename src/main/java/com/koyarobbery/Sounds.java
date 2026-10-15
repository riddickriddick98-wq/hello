package com.koyarobbery;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public final class Sounds {
    private Sounds() {}

    /** Plays a sound defined in the resource pack's sounds.json (e.g. "custom.robbery") to one player. */
    public static void playCustom(ServerPlayer p, String id, float vol, float pitch) {
        SoundEvent ev = SoundEvent.createVariableRangeEvent(new ResourceLocation(id));
        p.connection.send(new ClientboundSoundPacket(Holder.direct(ev), SoundSource.MASTER,
            p.getX(), p.getY(), p.getZ(), vol, pitch, p.level().getRandom().nextLong()));
    }
}
