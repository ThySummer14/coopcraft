package com.moyu.infinitystones;

import com.moyu.infinitystones.logic.GauntletEvents;
import com.moyu.infinitystones.registry.ModItems;
import com.moyu.infinitystones.registry.ModTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 无限宝石 Infinity Stones
 *
 * <p>六颗宝石 + 一只无限手套。拿在手上即可获得被动增益，
 * 右键释放能量，潜行右键打响指。</p>
 */
@Mod(InfinityStones.MOD_ID)
public final class InfinityStones {

    public static final String MOD_ID = "infinitystones";

    public InfinityStones() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(modBus);
        ModTabs.register(modBus);

        MinecraftForge.EVENT_BUS.register(GauntletEvents.class);
    }
}
