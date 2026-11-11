package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real stock/service inputs for the demand rule; this does not execute a Villager Goal. */
class VillagerToolDemandInventoryTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void backpackCommonStoreAndEnabledSimulationTogetherSatisfyTheSameTwoStickNeed() {
		for (int personal = 0; personal <= 2; personal++) {
			for (int common = 0; common <= 2; common++) {
				for (int simulated = 0; simulated <= 2; simulated++) {
					Fixture f = fixture(simulated);
					SimpleContainer backpack = sticks(personal);
					SimpleContainer store = sticks(common);
					CompoundTag saved = f.data.save(new CompoundTag(), null);
					f.data.setDirty(false);
					for (int read = 0; read < 3; read++) {
						assertEquals(personal + common + simulated < 2, needsSticks(f, backpack, store));
					}
					assertEquals(personal, backpack.countItem(Items.STICK));
					assertEquals(common, store.countItem(Items.STICK));
					assertEquals(saved, f.data.save(new CompoundTag(), null));
					assertFalse(f.data.isDirty());
					f.state().world().assertConservation();
				}
			}
		}
	}

	@Test
	void disablingProductionHidesAbstractStockWithoutMovingOrDeletingIt() {
		Fixture f = fixture(2);
		SimpleContainer backpack = sticks(0);
		SimpleContainer store = sticks(0);
		assertFalse(needsSticks(f, backpack, store));
		var ledger = List.copyOf(f.state().world().ledger().events());
		f.state().setProductionEnabled(false);
		assertTrue(needsSticks(f, backpack, store));
		f.state().setProductionEnabled(true);
		assertFalse(needsSticks(f, backpack, store));
		f.state().setSurvivalEnabled(false, 0);
		assertTrue(needsSticks(f, backpack, store));
		assertEquals(2L, f.state().world().inventoryTotals().get(ResourceType.STICK));
		assertEquals(ledger, f.state().world().ledger().events());
		assertEquals(0, backpack.countItem(Items.STICK));
		assertEquals(0, store.countItem(Items.STICK));
		f.state().world().assertConservation();
	}

	@Test
	void fullSaveReloadKeepsCurrentStockDecisionWithoutPersistingANewDemand() {
		for (int simulated : List.of(0, 1, 2, 8)) {
			Fixture f = fixture(simulated);
			SimpleContainer backpack = sticks(0);
			SimpleContainer store = sticks(0);
			boolean needed = needsSticks(f, backpack, store);
			CompoundTag saved = f.data.save(new CompoundTag(), null);
			for (int round = 0; round < 2; round++) {
				SettlementSavedData loaded = SettlementSavedData.load(saved, null);
				f = new Fixture(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
				assertEquals(needed, needsSticks(f, backpack, store));
				assertEquals(saved, loaded.save(new CompoundTag(), null));
				f.state().world().assertConservation();
			}
		}
	}

	@Test
	void readingRealOnlyDemandDoesNotCreateSimulationOrWriteSave() {
		SettlementSavedData data = new SettlementSavedData();
		Fixture f = new Fixture(data, data.getOrCreate(BlockPos.ZERO, "Real only", 0));
		CompoundTag saved = data.save(new CompoundTag(), null);
		data.setDirty(false);
		assertTrue(needsSticks(f, sticks(1), sticks(0)));
		assertFalse(needsSticks(f, sticks(1), sticks(1)));
		assertTrue(data.simulationOf(f.settlement).isEmpty());
		assertEquals(saved, data.save(new CompoundTag(), null));
		assertFalse(data.isDirty());
	}

	private static boolean needsSticks(Fixture f, SimpleContainer backpack, SimpleContainer store) {
		long available = backpack.countItem(Items.STICK) + store.countItem(Items.STICK)
			+ SettlementSimulationService.craftingResourceCount(f.data, f.settlement, Items.STICK);
		return VillagerToolDemand.needsSticks(VillagerToolDemand.HandState.STONE_PICKAXE, true, available);
	}

	private static SimpleContainer sticks(int count) {
		SimpleContainer container = new SimpleContainer(2);
		if (count > 0) container.setItem(0, new ItemStack(Items.STICK, count));
		return container;
	}

	private static Fixture fixture(int simulatedSticks) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Tool materials", 0);
		var state = data.getOrCreateSimulation(settlement);
		UUID resident = new UUID(21, 1);
		data.registerMember(settlement, resident, 0);
		state.mapResident(resident, false, 100);
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		if (simulatedSticks > 0) state.world().importExternalBatch(ResourceType.STICK, simulatedSticks, state.campOwner());
		return new Fixture(data, settlement);
	}

	private record Fixture(SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state() { return data.simulationOf(settlement).orElseThrow(); }
	}
}
