package com.koyarobbery;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = KoyaRobberyMod.MODID)
public final class Events {
    private Events() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent e) { Cfg.load(); }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || Cfg.config == null) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        RobberyManager.tick();
        StructureManager.tick(server);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(e.getEntity() instanceof ServerPlayer p) || Cfg.config == null) return;
        ItemStack st = e.getItemStack();
        CompoundTag tag = st.getTag();
        BlockPos pos = e.getPos();

        if (tag != null && tag.contains("koya_struct") && p.hasPermissions(2)) {
            e.setCanceled(true);
            StructureManager.place(p, tag.getString("koya_struct"), pos.relative(e.getFace()));
            return;
        }
        if (tag != null && tag.contains("koya_stick") && p.hasPermissions(2)) {
            e.setCanceled(true);
            StructureManager.removeAt(p, pos);
            return;
        }
        if (tag != null && tag.contains("koya_drill")) {
            e.setCanceled(true);
            StructureManager.tryDrill(p, pos, st);
            return;
        }
        if (StructureManager.tryLoot(p, pos)) e.setCanceled(true);
    }

    /** Structure blocks and vault doors can only be removed with the Structure Stick. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Cfg.config == null || e.getLevel().isClientSide()) return;
        var level = (net.minecraft.world.level.Level) e.getLevel();
        if (StructureManager.structureAt(level, e.getPos()) != null || StructureManager.buildAt(level, e.getPos()) != null) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && Cfg.config != null) RobberyManager.onDeath(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && Cfg.config != null) RobberyManager.onLogout(p);
    }
}
