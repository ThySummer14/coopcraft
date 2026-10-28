package com.moyu.infinitystones.logic;

import com.moyu.infinitystones.item.GauntletData;
import com.moyu.infinitystones.item.GauntletItem;
import com.moyu.infinitystones.item.InfinityStone;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 只要手套拿在手上（主手或副手），已镶嵌的宝石就会持续提供被动增益。
 * 每秒刷新一次，离开手后自动淡出。
 */
public class GauntletEvents {

    private static final int REFRESH_TICKS = 20;
    private static final int EFFECT_DURATION = 60;

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide()) {
            return;
        }
        if (player.tickCount % REFRESH_TICKS != 0) {
            return;
        }

        ItemStack gauntlet = findGauntlet(player);
        if (gauntlet.isEmpty()) {
            return;
        }

        for (InfinityStone stone : GauntletData.list(gauntlet)) {
            MobEffect effect = passiveEffect(stone);
            if (effect != null) {
                player.addEffect(new MobEffectInstance(effect, EFFECT_DURATION,
                        passiveAmplifier(stone), true, false, false));
            }
        }

        if (GauntletData.hasAll(gauntlet)) {
            player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, EFFECT_DURATION, 0, true, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, EFFECT_DURATION, 0, true, false, false));
        }
    }

    private static ItemStack findGauntlet(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof GauntletItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof GauntletItem) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    private static MobEffect passiveEffect(InfinityStone stone) {
        return switch (stone) {
            case SPACE -> MobEffects.SLOW_FALLING;
            case MIND -> MobEffects.NIGHT_VISION;
            case REALITY -> MobEffects.DAMAGE_RESISTANCE;
            case POWER -> MobEffects.DAMAGE_BOOST;
            case TIME -> MobEffects.MOVEMENT_SPEED;
            case SOUL -> MobEffects.REGENERATION;
        };
    }

    private static int passiveAmplifier(InfinityStone stone) {
        return switch (stone) {
            case POWER, TIME -> 1;
            default -> 0;
        };
    }
}
