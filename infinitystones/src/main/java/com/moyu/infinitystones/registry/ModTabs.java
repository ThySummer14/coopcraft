package com.moyu.infinitystones.registry;

import com.moyu.infinitystones.InfinityStones;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, InfinityStones.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.infinitystones.main"))
            .icon(() -> new ItemStack(ModItems.INFINITY_GAUNTLET.get()))
            .displayItems((parameters, output) -> {
                output.accept(ModItems.INFINITY_GAUNTLET.get());
                for (RegistryObject<Item> stone : ModItems.allStones()) {
                    output.accept(stone.get());
                }
            })
            .build());

    private ModTabs() {
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
