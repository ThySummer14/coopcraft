package com.moyu.infinitystones.item;

import com.moyu.infinitystones.logic.StonePowers;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 单颗宝石。单独拿在手上右键就能释放能力；
 * 如果另一只手拿着无限手套，则交给手套处理（镶嵌 / 通过手套释放）。
 */
public class InfinityStoneItem extends Item {

    private final InfinityStone stone;

    public InfinityStoneItem(InfinityStone stone, Properties properties) {
        super(properties);
        this.stone = stone;
    }

    public InfinityStone getStone() {
        return this.stone;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        boolean gauntletInOtherHand = player.getMainHandItem().getItem() instanceof GauntletItem
                || player.getOffhandItem().getItem() instanceof GauntletItem;
        if (gauntletInOtherHand) {
            // 让手套那条分支去处理，避免一次右键触发两次
            return InteractionResultHolder.pass(stack);
        }

        if (!level.isClientSide) {
            StonePowers.activate(level, player, this.stone, this);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(this.stone.getDescriptionKey()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.infinitystones.stone.usage")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
