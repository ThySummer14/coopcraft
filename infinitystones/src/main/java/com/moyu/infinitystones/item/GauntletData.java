package com.moyu.infinitystones.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 手套上「已镶嵌宝石」的数据读写。
 *
 * <p>NBT 结构：<pre>{Socketed: {space: 1b, mind: 1b, ...}}</pre>
 */
public final class GauntletData {

    private static final String ROOT_KEY = "Socketed";

    private GauntletData() {
    }

    public static boolean has(ItemStack gauntlet, InfinityStone stone) {
        CompoundTag root = gauntlet.getTag();
        if (root == null) {
            return false;
        }
        return root.getCompound(ROOT_KEY).getBoolean(stone.getId());
    }

    /**
     * 镶嵌一颗宝石。
     *
     * @return 是否成功（已存在则返回 false）
     */
    public static boolean socket(ItemStack gauntlet, InfinityStone stone) {
        if (has(gauntlet, stone)) {
            return false;
        }
        CompoundTag root = gauntlet.getOrCreateTag();
        CompoundTag socketed = root.getCompound(ROOT_KEY).copy();
        socketed.putBoolean(stone.getId(), true);
        root.put(ROOT_KEY, socketed);
        return true;
    }

    public static int count(ItemStack gauntlet) {
        return list(gauntlet).size();
    }

    public static boolean hasAll(ItemStack gauntlet) {
        return count(gauntlet) >= InfinityStone.values().length;
    }

    public static List<InfinityStone> list(ItemStack gauntlet) {
        List<InfinityStone> result = new ArrayList<>();
        CompoundTag root = gauntlet.getTag();
        if (root == null) {
            return result;
        }
        CompoundTag socketed = root.getCompound(ROOT_KEY);
        for (InfinityStone stone : InfinityStone.values()) {
            if (socketed.getBoolean(stone.getId())) {
                result.add(stone);
            }
        }
        return result;
    }
}
