package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SettlementStockpileAuthorityTest {
	private static final UUID RESIDENT = new UUID(1, 2);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void survivalDoesNotDistributeRealFoodOrChangeHungerRationsOrEvents() {
		for (int flags = 0; flags < 8; flags++) {
			Fixture f = fixture(true);
			var foods = List.of(Items.POTATO, Items.CARROT, Items.BREAD, Items.BEETROOT, Items.SWEET_BERRIES);
			for (int slot = 0; slot < foods.size(); slot++) {
				f.stockpile().setItem(slot, new ItemStack(foods.get(slot), 64));
			}
			SettlementSimulationService.setProductionEnabled(f.data(), f.settlement(), (flags & 1) != 0);
			SettlementSimulationService.setSkillsEnabled(f.data(), f.settlement(), (flags & 2) != 0);
			SettlementSimulationService.setNormsEnabled(f.data(), f.settlement(), (flags & 4) != 0);
			var state = f.state();
			assertTrue(state.survivalEnabled());
			assertEquals((flags & 1) != 0, state.productionEnabled());
			assertEquals((flags & 2) != 0, state.skillsEnabled());
			assertEquals((flags & 4) != 0, state.normsEnabled());
			String person = state.residentId(RESIDENT).orElseThrow();
			state.world().person(person).setHunger(91);
			state.recordFoodAllocation(person, 1);
			CompoundTag before = f.data().save(new CompoundTag(), null);

			repeatTicks(f);

			for (var food : foods) assertEquals(64, f.stockpile().countItem(food));
			assertTrue(f.inventory().isEmpty());
			assertEquals(91, state.world().person(person).hunger());
			assertEquals(before, f.data().save(new CompoundTag(), null));
			assertEquals(0L, abstractPotatoes(f));
		}
	}

	@Test
	void survivalKeepsExcessBackpackFoodButStillDepositsGatheredMaterials() {
		Fixture f = fixture(true);
		f.inventory().setItem(0, new ItemStack(Items.POTATO, 64));
		f.inventory().setItem(1, new ItemStack(Items.COBBLESTONE, 17));
		f.inventory().setItem(2, new ItemStack(Items.STICK, 9));
		CompoundTag before = f.state().save();

		repeatTicks(f);

		assertEquals(64, f.inventory().countItem(Items.POTATO));
		assertEquals(0, f.stockpile().countItem(Items.POTATO));
		assertEquals(17, f.stockpile().countItem(Items.COBBLESTONE));
		assertEquals(9, f.stockpile().countItem(Items.STICK));
		assertEquals(0, f.inventory().countItem(Items.COBBLESTONE));
		assertEquals(0, f.inventory().countItem(Items.STICK));
		assertEquals(before, f.state().save());
	}

	@Test
	void scheduledRotationVisitsEveryResidentAndDepositsOnlyTheirMaterials() {
		Fixture f = fixture(true);
		Map<UUID, SimpleContainer> inventories = new LinkedHashMap<>();
		Map<UUID, Integer> visits = new LinkedHashMap<>();
		for (int index = 0; index < 3; index++) {
			UUID resident = index == 0 ? RESIDENT : new UUID(1, 2 + index);
			if (index > 0) {
				f.data().registerMember(f.settlement(), resident, 0);
				f.state().mapResident(resident, false, 0);
			}
			SimpleContainer inventory = new SimpleContainer(8);
			inventory.setItem(0, new ItemStack(Items.COBBLESTONE, index + 1));
			inventory.setItem(1, new ItemStack(Items.STICK, index + 2));
			inventories.put(resident, inventory);
			visits.put(resident, 0);
		}
		f.stockpile().setItem(0, new ItemStack(Items.POTATO, 64));
		CompoundTag before = f.data().save(new CompoundTag(), null);
		// Three complete scheduling rounds must visit each member exactly three times.
		for (int cycle = 0; cycle < 9; cycle++) {
			SettlementStockpile.tick(f.data(), f.settlement(), scheduledTime(f) + cycle * 40L,
				f::stockpile, resident -> {
					assertTrue(inventories.containsKey(resident));
					visits.compute(resident, (id, count) -> count + 1);
					return inventories.get(resident);
				});
		}
		for (UUID resident : inventories.keySet()) {
			assertEquals(3, visits.get(resident).intValue());
			assertTrue(inventories.get(resident).isEmpty(), "Food must not replace gathered materials");
		}
		assertEquals(64, f.stockpile().countItem(Items.POTATO));
		assertEquals(6, f.stockpile().countItem(Items.COBBLESTONE));
		assertEquals(9, f.stockpile().countItem(Items.STICK));
		assertEquals(before, f.data().save(new CompoundTag(), null));
		assertEquals(0L, abstractPotatoes(f));
	}

	@Test
	void absentSimulationUsesLegacyDistributionWithoutCreatingSimulation() {
		Fixture f = fixture(false);
		f.stockpile().setItem(0, new ItemStack(Items.POTATO, 64));

		tick(f, scheduledTime(f));

		assertLegacyDistribution(f);
		assertTrue(f.data().simulationOf(f.settlement()).isEmpty());
	}

	@Test
	void disablingSurvivalRestoresLegacyDistributionAndExcessDeposits() {
		Fixture f = fixture(true);
		f.stockpile().setItem(0, new ItemStack(Items.POTATO, 64));
		repeatTicks(f);
		assertTrue(f.inventory().isEmpty());
		f.state().setSurvivalEnabled(false, 0);
		CompoundTag saved = f.data().save(new CompoundTag(), null);
		SettlementSavedData restored = SettlementSavedData.load(saved, null);
		Settlement settlement = restored.find(f.settlement().center()).orElseThrow();
		f = new Fixture(restored, settlement, f.stockpile(), f.inventory());
		assertFalse(f.state().survivalEnabled());
		assertEquals(saved, restored.save(new CompoundTag(), null));
		tick(f, scheduledTime(f));
		assertLegacyDistribution(f);

		f.stockpile().clearContent();
		f.inventory().clearContent();
		f.inventory().setItem(0, new ItemStack(Items.POTATO, 64));
		tick(f, scheduledTime(f) + 40);
		int retained = f.data().membersOf(f.settlement()).getFirst().personality().foodRetentionPoints(12);
		assertEquals(Math.max(12, retained), f.inventory().countItem(Items.POTATO));
		assertEquals(64 - Math.max(12, retained), f.stockpile().countItem(Items.POTATO));
	}

	@Test
	void wholeSavedDataReloadPreservesAuthorityAndExistingInventoryFood() {
		Fixture f = fixture(true);
		f.stockpile().setItem(0, new ItemStack(Items.POTATO, 40));
		f.inventory().setItem(0, new ItemStack(Items.POTATO, 24));
		for (int pass = 0; pass < 3; pass++) {
			CompoundTag saved = f.data().save(new CompoundTag(), null);
			SettlementSavedData restored = SettlementSavedData.load(saved, null);
			Settlement settlement = restored.find(f.settlement().center()).orElseThrow();
			f = new Fixture(restored, settlement, f.stockpile(), f.inventory());
			assertTrue(f.state().survivalEnabled());
			repeatTicks(f);
			assertEquals(saved, restored.save(new CompoundTag(), null));
			assertEquals(40, f.stockpile().countItem(Items.POTATO));
			assertEquals(24, f.inventory().countItem(Items.POTATO));
			assertEquals(0L, abstractPotatoes(f));
		}
	}

	@Test
	void explicitImportThenEatConservesRealAndAbstractFoodAcrossRepeatedTicks() {
		Fixture f = fixture(true);
		f.stockpile().setItem(0, new ItemStack(Items.POTATO, 20));
		f.inventory().setItem(0, new ItemStack(Items.POTATO, 4));
		repeatTicks(f);
		assertEquals(20, f.stockpile().countItem(Items.POTATO));
		assertEquals(4, f.inventory().countItem(Items.POTATO));
		assertEquals(0L, abstractPotatoes(f));

		assertEquals(8, SettlementSimulationService.importFromStockpile(
			f.data(), f.settlement(), f.stockpile(), ResourceType.TUBERS, 8));
		assertEquals(12, f.stockpile().countItem(Items.POTATO));
		assertEquals(8L, abstractPotatoes(f));
		CompoundTag imported = f.state().save();
		repeatTicks(f);
		assertEquals(imported, f.state().save());
		assertEquals(12, f.stockpile().countItem(Items.POTATO));
		assertEquals(4, f.inventory().countItem(Items.POTATO));

		String person = f.state().residentId(RESIDENT).orElseThrow();
		f.state().world().person(person).setHunger(100);
		assertTrue(SettlementSimulationService.eat(f.data(), f.settlement(), RESIDENT) > 0);
		int consumed = f.state().allocatedFoodUnits(person);
		assertTrue(consumed > 0);
		assertEquals(8L - consumed, abstractPotatoes(f));
		CompoundTag eaten = f.state().save();
		repeatTicks(f);
		assertEquals(eaten, f.state().save());
		assertEquals(12, f.stockpile().countItem(Items.POTATO));
		assertEquals(4, f.inventory().countItem(Items.POTATO));
		assertEquals(24L, f.stockpile().countItem(Items.POTATO)
			+ f.inventory().countItem(Items.POTATO) + abstractPotatoes(f) + consumed);
		f.state().world().assertConservation();
	}

	@Test
	void namedAndCustomComponentFoodStaysInItsOriginalContainer() {
		Fixture f = fixture(true);
		ItemStack named = new ItemStack(Items.POTATO, 64);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Resident's potatoes"));
		ItemStack custom = new ItemStack(Items.POTATO, 32);
		CompoundTag marker = new CompoundTag();
		marker.putString("owner", "stockpile");
		custom.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
		f.inventory().setItem(0, named.copy());
		f.stockpile().setItem(0, custom.copy());

		repeatTicks(f);

		assertStack(named, f.inventory().getItem(0));
		assertStack(custom, f.stockpile().getItem(0));
		assertEquals(0L, abstractPotatoes(f));
		assertEquals(0, SettlementSimulationService.importFromStockpile(
			f.data(), f.settlement(), f.stockpile(), ResourceType.TUBERS, 32));
		assertStack(custom, f.stockpile().getItem(0));
		f.inventory().clearContent();
		repeatTicks(f);
		assertTrue(f.inventory().isEmpty());
		assertStack(custom, f.stockpile().getItem(0));
	}

	@Test
	void unscheduledTickDoesNotResolveOrReadContainers() {
		Fixture f = fixture(true);
		SettlementStockpile.tick(f.data(), f.settlement(), scheduledTime(f) + 1,
			() -> fail("Unscheduled tick resolved stockpile"),
			id -> fail("Unscheduled tick resolved member"));
	}

	@Test
	void unregisteredStockpileDoesNotResolveOrReadContainers() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "No stockpile", 0);
		data.registerMember(settlement, RESIDENT, 0);
		long time = Math.floorMod(-settlement.id().getLeastSignificantBits(), 40L);
		SettlementStockpile.tick(data, settlement, time,
			() -> fail("Unregistered stockpile was resolved"),
			id -> fail("Member resolved without stockpile"));
	}

	@Test
	void missingStockpileOrIneligibleMemberNeverReadsOrMutatesContainerContents() {
		Fixture f = fixture(true);
		SettlementStockpile.tick(f.data(), f.settlement(), scheduledTime(f), () -> null,
			id -> fail("Member resolved without loaded stockpile"));
		SimpleContainer unreadable = new SimpleContainer(3) {
			@Override
			public int getContainerSize() {
				return fail("Ineligible member must not inspect container contents");
			}

			@Override
			public ItemStack getItem(int slot) {
				return fail("Ineligible member must not read stacks");
			}

			@Override
			public void setItem(int slot, ItemStack stack) {
				fail("Ineligible member must not mutate stacks");
			}

			@Override
			public void setChanged() {
				fail("Ineligible member must not mark container changed");
			}
		};
		SettlementStockpile.tick(f.data(), f.settlement(), scheduledTime(f), () -> unreadable, id -> null);
		SettlementSavedData empty = new SettlementSavedData();
		Settlement settlement = empty.getOrCreate(BlockPos.ZERO, "No members", 0);
		empty.setStockpile(settlement, BlockPos.ZERO.east());
		SettlementStockpile.tick(empty, settlement, Math.floorMod(-settlement.id().getLeastSignificantBits(), 40L),
			() -> unreadable, id -> fail("Empty settlement resolved a member"));
	}

	private static Fixture fixture(boolean survival) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Stockpile authority", 0);
		data.setStockpile(settlement, BlockPos.ZERO.east());
		data.registerMember(settlement, RESIDENT, 0);
		if (survival) {
			var state = data.getOrCreateSimulation(settlement);
			state.mapResident(RESIDENT, false, 0);
			state.setSurvivalEnabled(true, 0);
			state.setNormsEnabled(true);
		}
		return new Fixture(data, settlement, new SimpleContainer(9), new SimpleContainer(8));
	}

	private static void assertLegacyDistribution(Fixture f) {
		int basic = f.data().membersOf(f.settlement()).getFirst().personality().basicFoodPoints(12);
		assertEquals(Math.max(12, basic), f.inventory().countItem(Items.POTATO));
		assertEquals(64 - Math.max(12, basic), f.stockpile().countItem(Items.POTATO));
		assertFalse(f.inventory().isEmpty());
	}

	private static long abstractPotatoes(Fixture f) {
		return f.state().world().inventoryTotals().getOrDefault(ResourceType.TUBERS, 0L);
	}

	private static long scheduledTime(Fixture f) {
		return Math.floorMod(-f.settlement().id().getLeastSignificantBits(), 40L);
	}

	private static void tick(Fixture f, long time) {
		SettlementStockpile.tick(f.data(), f.settlement(), time, f::stockpile, id -> {
			assertEquals(RESIDENT, id);
			return f.inventory();
		});
	}

	private static void repeatTicks(Fixture f) {
		for (int cycle = 0; cycle < 10; cycle++) {
			tick(f, scheduledTime(f) + cycle * 40L);
		}
	}

	private static void assertStack(ItemStack expected, ItemStack actual) {
		assertEquals(expected.getCount(), actual.getCount());
		assertTrue(ItemStack.isSameItemSameComponents(expected, actual));
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SimpleContainer stockpile, SimpleContainer inventory) {
		SettlementSimulationState state() {
			return data.simulationOf(settlement).orElseThrow();
		}
	}
}
