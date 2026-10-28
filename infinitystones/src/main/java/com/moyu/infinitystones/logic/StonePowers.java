package com.moyu.infinitystones.logic;

import com.moyu.infinitystones.item.GauntletData;
import com.moyu.infinitystones.item.InfinityStone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 六颗宝石的能力实现，以及手套的「无限冲击」和「响指」。
 *
 * <p>所有方法都只在服务端执行，返回值表示这次能力是否真的放出去了
 * （没打中目标 / 没找到落点时不进冷却）。</p>
 */
public final class StonePowers {

    private StonePowers() {
    }

    // ------------------------------------------------------------------
    // 通用入口
    // ------------------------------------------------------------------

    public static void activate(Level level, Player player, InfinityStone stone, Item cooldownItem) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.getCooldowns().isOnCooldown(cooldownItem)) {
            return;
        }

        boolean used = switch (stone) {
            case SPACE -> bendSpace(serverLevel, serverPlayer);
            case MIND -> touchMind(serverLevel, serverPlayer);
            case REALITY -> warpReality(serverLevel, serverPlayer);
            case POWER -> releasePower(serverLevel, serverPlayer);
            case TIME -> bendTime(serverLevel, serverPlayer);
            case SOUL -> stealSoul(serverLevel, serverPlayer);
        };

        if (used) {
            serverPlayer.getCooldowns().addCooldown(cooldownItem, cooldownOf(stone));
            serverPlayer.swing(InteractionHand.MAIN_HAND, true);
        }
    }

    private static int cooldownOf(InfinityStone stone) {
        return switch (stone) {
            case SPACE -> 60;
            case MIND -> 200;
            case REALITY -> 300;
            case POWER -> 100;
            case TIME -> 400;
            case SOUL -> 160;
        };
    }

    // ------------------------------------------------------------------
    // 空间宝石：向前折跃
    // ------------------------------------------------------------------

    private static boolean bendSpace(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(32.0D)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double limit = hit.getType() == HitResult.Type.MISS
                ? 32.0D
                : hit.getLocation().distanceTo(eye);
        double start = Math.min(limit, 26.0D);

        for (double distance = start; distance >= 2.0D; distance -= 1.0D) {
            Vec3 point = eye.add(look.scale(distance));
            BlockPos column = BlockPos.containing(point);
            for (int dy = 3; dy >= -8; dy--) {
                BlockPos feet = column.offset(0, dy, 0);
                if (!isStandable(level, feet)) {
                    continue;
                }
                level.sendParticles(stoneDust(InfinityStone.SPACE, 2.0F),
                        player.getX(), player.getY() + 1.0D, player.getZ(), 40, 0.4D, 0.8D, 0.4D, 0.05D);
                player.teleportTo(level, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                        player.getYRot(), player.getXRot());
                player.fallDistance = 0.0F;
                level.sendParticles(stoneDust(InfinityStone.SPACE, 2.0F),
                        player.getX(), player.getY() + 1.0D, player.getZ(), 60, 0.5D, 0.9D, 0.5D, 0.08D);
                level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.3F, 1.0F);
                return true;
            }
        }

        player.displayClientMessage(Component.translatable("message.infinitystones.space.fail"), true);
        return false;
    }

    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet)) {
            return false;
        }
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        BlockState atFeet = level.getBlockState(feet);
        BlockState atHead = level.getBlockState(feet.above());
        if (!atFeet.getCollisionShape(level, feet).isEmpty() || !atHead.getCollisionShape(level, feet.above()).isEmpty()) {
            return false;
        }
        // 别把人送进岩浆 / 火里
        return atFeet.getFluidState().isEmpty() && atHead.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------
    // 心灵宝石：压制周围的敌意
    // ------------------------------------------------------------------

    private static boolean touchMind(ServerLevel level, ServerPlayer player) {
        List<Monster> monsters = level.getEntitiesOfClass(Monster.class,
                player.getBoundingBox().inflate(20.0D), LivingEntity::isAlive);
        if (monsters.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.infinitystones.mind.none"), true);
            return false;
        }

        for (Monster monster : monsters) {
            monster.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 20, 0, true, false, false));
            monster.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 15, 1, true, false, false));
            monster.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 15, 1, true, false, false));
            level.sendParticles(stoneDust(InfinityStone.MIND, 1.4F),
                    monster.getX(), monster.getY() + monster.getBbHeight() * 0.5D, monster.getZ(),
                    12, 0.3D, 0.3D, 0.3D, 0.02D);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.4F, 0.7F);
        player.displayClientMessage(Component.translatable("message.infinitystones.mind", monsters.size()), true);
        return true;
    }

    // ------------------------------------------------------------------
    // 现实宝石：改写自身的「存在感」
    // ------------------------------------------------------------------

    private static boolean warpReality(ServerLevel level, ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 12, 0, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 12, 1, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 12, 0, false, false, false));

        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(8.0D),
                entity -> entity != player && entity.isAlive() && !(entity instanceof Player));
        for (LivingEntity entity : nearby) {
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 6, 0, true, false, false));
        }

        for (int i = 0; i < 40; i++) {
            double x = player.getX() + (level.random.nextDouble() - 0.5D) * 3.0D;
            double y = player.getY() + level.random.nextDouble() * 2.0D;
            double z = player.getZ() + (level.random.nextDouble() - 0.5D) * 3.0D;
            level.sendParticles(ParticleTypes.WARPED_SPORE, x, y, z, 1, 0.0D, 0.02D, 0.0D, 0.0D);
        }
        level.playSound(null, player.blockPosition(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.PLAYERS, 1.2F, 0.6F);
        player.displayClientMessage(Component.translatable("message.infinitystones.reality"), true);
        return true;
    }

    // ------------------------------------------------------------------
    // 力量宝石：紫色能量炮
    // ------------------------------------------------------------------

    private static boolean releasePower(ServerLevel level, ServerPlayer player) {
        fireBeam(level, player, 24.0D, 12.0F, InfinityStone.POWER, 0.9D);
        level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2F, 1.5F);
        player.displayClientMessage(Component.translatable("message.infinitystones.power"), true);
        return true;
    }

    // ------------------------------------------------------------------
    // 时间宝石：加速自己，冻结周围的生物
    // ------------------------------------------------------------------

    private static boolean bendTime(ServerLevel level, ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 15, 2, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 20 * 15, 2, false, false, false));

        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(12.0D),
                entity -> entity != player && entity.isAlive() && !(entity instanceof Player));
        for (LivingEntity entity : nearby) {
            entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 6, 6, true, false, false));
            entity.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 20 * 6, 3, true, false, false));
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 6, 2, true, false, false));
            level.sendParticles(stoneDust(InfinityStone.TIME, 1.6F),
                    entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                    18, 0.4D, 0.5D, 0.4D, 0.0D);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 0.5F);
        player.displayClientMessage(Component.translatable("message.infinitystones.time"), true);
        return true;
    }

    // ------------------------------------------------------------------
    // 灵魂宝石：抽取周围生命
    // ------------------------------------------------------------------

    private static boolean stealSoul(ServerLevel level, ServerPlayer player) {
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(12.0D),
                entity -> entity != player && entity.isAlive() && !(entity instanceof Player));
        if (targets.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.infinitystones.soul.none"), true);
            return false;
        }

        float drained = 0.0F;
        for (LivingEntity target : targets) {
            level.sendParticles(ParticleTypes.SOUL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    10, 0.25D, 0.4D, 0.25D, 0.03D);
            target.hurt(level.damageSources().magic(), 5.0F);
            drained += 2.0F;
        }

        player.heal(Math.min(drained, 12.0F));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 6, 0, false, false, false));
        level.playSound(null, player.blockPosition(), SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 1.3F, 0.6F);
        player.displayClientMessage(Component.translatable("message.infinitystones.soul", targets.size()), true);
        return true;
    }

    // ------------------------------------------------------------------
    // 手套：无限冲击
    // ------------------------------------------------------------------

    public static void blast(ServerLevel level, ServerPlayer player, ItemStack gauntlet) {
        Item item = gauntlet.getItem();
        if (player.getCooldowns().isOnCooldown(item)) {
            return;
        }
        boolean full = GauntletData.hasAll(gauntlet);
        fireBeam(level, player, full ? 32.0D : 20.0D, full ? 26.0F : 12.0F,
                InfinityStone.POWER, full ? 1.4D : 0.9D);

        level.playSound(null, player.blockPosition(),
                full ? SoundEvents.WARDEN_SONIC_BOOM : SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS, full ? 1.6F : 1.0F, full ? 1.0F : 1.6F);
        player.getCooldowns().addCooldown(item, full ? 80 : 40);
        player.displayClientMessage(Component.translatable("message.infinitystones.blast"), true);
    }

    /**
     * 沿视线打一条能量束。
     */
    private static void fireBeam(ServerLevel level, ServerPlayer player, double range, float damage,
                                 InfinityStone stone, double knockback) {
        Vec3 start = player.getEyePosition();
        Vec3 direction = player.getLookAngle();
        List<LivingEntity> hit = new ArrayList<>();

        // 从 2 格之外开始画粒子：否则粒子贴脸生成会把整个视野糊住
        for (double travelled = 2.0D; travelled <= range; travelled += 1.2D) {
            Vec3 point = start.add(direction.scale(travelled));

            AABB box = new AABB(point.x - 0.7D, point.y - 0.7D, point.z - 0.7D,
                    point.x + 0.7D, point.y + 0.7D, point.z + 0.7D);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box,
                    candidate -> candidate != player && candidate.isAlive())) {
                if (!hit.contains(entity)) {
                    hit.add(entity);
                }
            }

            level.sendParticles(stoneDust(stone, 0.85F), point.x, point.y, point.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);

            BlockPos pos = BlockPos.containing(point);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                level.sendParticles(stoneDust(stone, 1.6F), point.x, point.y, point.z, 10, 0.3D, 0.3D, 0.3D, 0.06D);
                break;
            }
        }

        for (LivingEntity entity : hit) {
            entity.hurt(level.damageSources().magic(), damage);
            Vec3 push = entity.position().subtract(player.position());
            push = push.lengthSqr() < 1.0E-4D ? direction : push.normalize();
            entity.push(push.x * knockback, 0.45D, push.z * knockback);
            entity.setSecondsOnFire(5);
            level.sendParticles(ParticleTypes.CRIT,
                    entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                    10, 0.3D, 0.3D, 0.3D, 0.2D);
        }
    }

    // ------------------------------------------------------------------
    // 手套：响指
    // ------------------------------------------------------------------

    public static void snap(ServerLevel level, ServerPlayer player, ItemStack gauntlet) {
        Item item = gauntlet.getItem();
        if (player.getCooldowns().isOnCooldown(item)) {
            return;
        }
        player.getCooldowns().addCooldown(item, 20 * 180);

        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(64.0D),
                entity -> entity != player && entity.isAlive() && !(entity instanceof Player));

        int erased = 0;
        for (LivingEntity target : targets) {
            if (!level.random.nextBoolean()) {
                continue;
            }
            level.sendParticles(stoneDust(InfinityStone.SOUL, 2.4F),
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    24, 0.5D, 0.7D, 0.5D, 0.03D);
            level.sendParticles(ParticleTypes.SOUL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    12, 0.3D, 0.5D, 0.3D, 0.05D);
            target.hurt(level.damageSources().magic(), 1000.0F);
            if (target.isAlive()) {
                target.kill();
            }
            erased++;
        }

        level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.0F, 0.6F);
        level.playSound(null, player.blockPosition(), SoundEvents.ENDER_DRAGON_DEATH, SoundSource.PLAYERS, 0.9F, 1.7F);
        level.sendParticles(ParticleTypes.FLASH,
                player.getX(), player.getY() + 1.2D, player.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);

        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 30, 2, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 20 * 30, 1, false, true, true));
        player.displayClientMessage(Component.translatable("message.infinitystones.snap", erased), false);

        Component broadcast = Component.translatable("message.infinitystones.snap.broadcast", player.getDisplayName());
        for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
            if (other != player) {
                other.displayClientMessage(broadcast, false);
            }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static ParticleOptions stoneDust(InfinityStone stone, float scale) {
        int color = stone.getColor();
        Vector3f rgb = new Vector3f(
                ((color >> 16) & 0xFF) / 255.0F,
                ((color >> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F);
        return new DustParticleOptions(rgb, scale);
    }
}
