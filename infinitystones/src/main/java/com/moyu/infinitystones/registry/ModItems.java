package com.moyu.infinitystones.registry;

import com.moyu.infinitystones.InfinityStones;
import com.moyu.infinitystones.item.GauntletItem;
import com.moyu.infinitystones.item.InfinityStone;
import com.moyu.infinitystones.item.InfinityStoneItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, InfinityStones.MOD_ID);

    public static final RegistryObject<Item> INFINITY_GAUNTLET = ITEMS.register("infinity_gauntlet",
            () -> new GauntletItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
                    .fireResistant()));

    public static final RegistryObject<Item> SPACE_STONE = stone(InfinityStone.SPACE);
    public static final RegistryObject<Item> MIND_STONE = stone(InfinityStone.MIND);
    public static final RegistryObject<Item> REALITY_STONE = stone(InfinityStone.REALITY);
    public static final RegistryObject<Item> POWER_STONE = stone(InfinityStone.POWER);
    public static final RegistryObject<Item> TIME_STONE = stone(InfinityStone.TIME);
    public static final RegistryObject<Item> SOUL_STONE = stone(InfinityStone.SOUL);

    private ModItems() {
    }

    private static RegistryObject<Item> stone(InfinityStone stone) {
        return ITEMS.register(stone.getItemName(), () -> new InfinityStoneItem(stone,
                new Item.Properties()
                        .stacksTo(1)
                        .rarity(Rarity.EPIC)
                        .fireResistant()));
    }

    /** 按枚举顺序返回六颗宝石，方便遍历（创造模式标签页等）。 */
    public static List<RegistryObject<Item>> allStones() {
        return List.of(SPACE_STONE, MIND_STONE, REALITY_STONE, POWER_STONE, TIME_STONE, SOUL_STONE);
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
