package com.moyu.infinitystones.item;

import com.moyu.infinitystones.logic.StonePowers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 无限手套。
 *
 * <p>操作：</p>
 * <ul>
 *   <li>另一只手拿着宝石 → 右键：把宝石镶进手套；已镶嵌则释放该宝石能力</li>
 *   <li>空手右键：无限冲击（能量射线）</li>
 *   <li>六颗齐全 + 潜行右键：打响指</li>
 * </ul>
 */
public class GauntletItem extends Item {

    /** 用 CustomModelData 标记已镶嵌数量，模型按 1~6 切换贴图，空手套显示空宝石座。 */
    private static final String CUSTOM_MODEL_DATA = "CustomModelData";

    public GauntletItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide) {
            return;
        }
        int count = GauntletData.count(stack);
        int current = stack.getTag() == null ? 0 : stack.getTag().getInt(CUSTOM_MODEL_DATA);
        if (current != count) {
            stack.getOrCreateTag().putInt(CUSTOM_MODEL_DATA, count);
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return GauntletData.hasAll(stack);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack gauntlet = player.getItemInHand(hand);
        InteractionHand otherHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack otherStack = player.getItemInHand(otherHand);

        if (otherStack.getItem() instanceof InfinityStoneItem stoneItem) {
            InfinityStone stone = stoneItem.getStone();
            if (!level.isClientSide) {
                if (GauntletData.has(gauntlet, stone)) {
                    StonePowers.activate(level, player, stone, this);
                } else if (GauntletData.socket(gauntlet, stone)) {
                    if (!player.getAbilities().instabuild) {
                        otherStack.shrink(1);
                    }
                    player.displayClientMessage(Component.translatable(
                            "message.infinitystones.socketed",
                            Component.translatable(stone.getDisplayKey()),
                            GauntletData.count(gauntlet)), true);
                    level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.3F);
                    if (level instanceof ServerLevel serverLevel) {
                        serverLevel.sendParticles(ParticleTypes.END_ROD,
                                player.getX(), player.getY() + 1.0D, player.getZ(),
                                20, 0.4D, 0.5D, 0.4D, 0.02D);
                    }
                }
            }
            return InteractionResultHolder.sidedSuccess(gauntlet, level.isClientSide());
        }

        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
            if (GauntletData.hasAll(gauntlet) && player.isShiftKeyDown()) {
                StonePowers.snap(serverLevel, serverPlayer, gauntlet);
            } else if (GauntletData.count(gauntlet) > 0) {
                StonePowers.blast(serverLevel, serverPlayer, gauntlet);
            } else {
                serverPlayer.displayClientMessage(Component.translatable("message.infinitystones.empty"), true);
                serverLevel.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6F, 1.1F);
            }
        }
        return InteractionResultHolder.sidedSuccess(gauntlet, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        List<InfinityStone> socketed = GauntletData.list(stack);

        tooltip.add(Component.translatable("tooltip.infinitystones.gauntlet.socketed", socketed.size())
                .withStyle(socketed.isEmpty() ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
        for (InfinityStone stone : socketed) {
            tooltip.add(Component.literal(" \u25C6 ")
                    .withStyle(ChatFormatting.DARK_PURPLE)
                    .append(Component.translatable(stone.getDisplayKey()).withStyle(ChatFormatting.LIGHT_PURPLE)));
        }

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.infinitystones.gauntlet.socket").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.infinitystones.gauntlet.blast").withStyle(ChatFormatting.DARK_GRAY));

        if (GauntletData.hasAll(stack)) {
            tooltip.add(Component.translatable("tooltip.infinitystones.gauntlet.ready")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
    }
}
