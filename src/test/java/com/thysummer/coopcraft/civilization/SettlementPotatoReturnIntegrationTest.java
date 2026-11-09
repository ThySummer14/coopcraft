package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Service integration with real block states, containers and NBT, supplied loot/world callbacks.
 * No server, entity, loot-table evaluation, navigation or GameTest is executed here. */
class SettlementPotatoReturnIntegrationTest {
	private static final UUID ACTOR = new UUID(73, 1);
	private static final BlockPos STORE = BlockPos.ZERO.east(), PLOT = BlockPos.ZERO.north();
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static final class Fixture {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Potato return", 0);
		SettlementSimulationState state;
		final SimpleContainer bag;
		final AtomicBoolean present = new AtomicBoolean(true);
		BlockState crop = ripe();
		int replants;
		Fixture() { this(8); }
		Fixture(int slots) {
			bag = new SimpleContainer(slots);
			data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, STORE); data.toggleResource(camp, PLOT);
			state = data.getOrCreateSimulation(camp); state.mapResident(ACTOR, false, 0);
			state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
		}
		int harvest(int gross) { return harvest(List.of(new ItemStack(Items.POTATO, gross))); }
		int harvest(List<ItemStack> loot) {
			crop = ripe();
			return SettlementCropHarvest.harvest(data, camp, ACTOR, PLOT, bag,
				() -> true, () -> true, () -> true, () -> crop, () -> loot, mature -> {
					assertEquals(ripe(), mature); replants++;
					crop = ((CropBlock) Blocks.POTATOES).getStateForAge(0); return true;
				});
		}
		int berries(int amount) {
			BlockState[] bush = { Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3) };
			return SettlementForaging.harvest(data, camp, ACTOR, bag, () -> true, () -> true, () -> true,
				() -> bush[0], () -> List.of(new ItemStack(Items.SWEET_BERRIES, amount)), mature -> {
					bush[0] = mature.setValue(SweetBerryBushBlock.AGE, 1); return true;
				});
		}
		SettlementCargoDelivery.Ticket prepare() { return SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, present::get); }
		SettlementCargoDelivery.Result finish(SettlementCargoDelivery.Ticket ticket, SimpleContainer store, boolean arrived) {
			return SettlementCargoDelivery.finish(ticket, data, camp, ACTOR, bag, store, () -> arrived);
		}
		int deliver(SimpleContainer store) { return finish(prepare(), store, true).moved(); }
		long food(ResourceType type) { return state.world().inventoryTotals().getOrDefault(type, 0L); }
		void advanceTo(long day) { while (state.world().day() < day) state.world().advanceDay(); }
		void load(CompoundTag saved) {
			data = SettlementSavedData.load(saved, null); camp = data.find(BlockPos.ZERO).orElseThrow();
			state = data.simulationOf(camp).orElseThrow();
		}
	}

	private static BlockState ripe() { return ((CropBlock) Blocks.POTATOES).getStateForAge(7); }
	private static void same(ItemStack expected, ItemStack actual) { assertTrue(ItemStack.matches(expected, actual)); }

	@Test void harvestReservesOneSeedCarriesOnlyNewFoodAndWaitsForArrival() {
		Fixture f = new Fixture(); SimpleContainer store = new SimpleContainer(1);
		ItemStack personal = new ItemStack(Items.POTATO, 9); f.bag.setItem(0, personal.copy());
		assertEquals(4, f.harvest(5)); assertEquals(1, f.replants);
		assertEquals(((CropBlock) Blocks.POTATOES).getStateForAge(0), f.crop);
		var stamp = SettlementFoodAge.read(f.bag.getItem(1)).orElseThrow();
		assertEquals(ResourceType.TUBERS, stamp.resourceType()); assertEquals(8, stamp.expiryDay());
		assertEquals(0, f.food(ResourceType.TUBERS)); assertTrue(store.isEmpty());
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.finish(f.prepare(), store, false).status());
		assertEquals(13, f.bag.countItem(Items.POTATO));
		var ticket = f.prepare(); assertEquals(4, f.finish(ticket, store, true).moved());
		assertEquals(0, f.finish(ticket, store, true).moved());
		same(personal, f.bag.getItem(0)); assertEquals(9, f.bag.countItem(Items.POTATO));
		assertEquals(4, f.food(ResourceType.TUBERS)); assertEquals(0, f.food(ResourceType.WILD_FRUIT));
		assertTrue(store.isEmpty()); assertEquals(14, 1 + f.bag.countItem(Items.POTATO) + f.food(ResourceType.TUBERS));
		f.state.world().assertConservation();
	}

	@Test void poisonAndCustomLootNeverBecomeSeedsOrImportedTubers() {
		Fixture f = new Fixture(); ItemStack named = new ItemStack(Items.POTATO, 2);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Personal cultivar"));
		ItemStack poison = new ItemStack(Items.POISONOUS_POTATO, 2), ordinary = new ItemStack(Items.POTATO, 4);
		assertEquals(3, f.harvest(List.of(named, poison, ordinary)));
		assertEquals(4, ordinary.getCount()); same(named, f.bag.getItem(0)); same(poison, f.bag.getItem(1));
		assertFalse(SettlementFoodAge.isMarked(f.bag.getItem(0))); assertFalse(SettlementFoodAge.isMarked(f.bag.getItem(1)));
		SimpleContainer store = new SimpleContainer(3); assertEquals(3, f.deliver(store));
		same(named, f.bag.getItem(0)); same(poison, f.bag.getItem(1));
		assertEquals(3, f.food(ResourceType.TUBERS)); assertTrue(store.isEmpty()); f.state.world().assertConservation();
	}

	@Test void wholeHarvestMustFitMarkedPotatoesAndPoisonBeforeReplanting() {
		Fixture f = new Fixture(1); assertEquals(0, f.harvest(List.of(new ItemStack(Items.POTATO, 4), new ItemStack(Items.POISONOUS_POTATO))));
		assertEquals(ripe(), f.crop); assertEquals(0, f.replants); assertTrue(f.bag.isEmpty());
		// Unmarked personal potatoes cannot provide merge capacity for newly marked cargo.
		f.bag.setItem(0, new ItemStack(Items.POTATO, 60));
		assertEquals(0, f.harvest(5)); assertEquals(ripe(), f.crop); assertEquals(0, f.replants);
		assertEquals(60, f.bag.countItem(Items.POTATO)); assertTrue(f.state.world().ledger().events().isEmpty());
	}

	@Test void mixedSpeciesCannotReuseTheSameEmptyChestSlotInOneArrival() {
		for (boolean potatoesFirst : List.of(true, false)) {
			Fixture f = new Fixture();
			if (potatoesFirst) { assertEquals(3, f.harvest(4)); assertEquals(2, f.berries(2)); }
			else { assertEquals(2, f.berries(2)); assertEquals(3, f.harvest(4)); }
			SimpleContainer store = new SimpleContainer(1);
			assertEquals(potatoesFirst ? 3 : 2, f.deliver(store));
			assertEquals(potatoesFirst ? 3 : 0, f.food(ResourceType.TUBERS));
			assertEquals(potatoesFirst ? 0 : 2, f.food(ResourceType.WILD_FRUIT));
			assertTrue(store.isEmpty()); assertEquals(potatoesFirst ? 2 : 3, f.deliver(store));
			assertEquals(3, f.food(ResourceType.TUBERS)); assertEquals(2, f.food(ResourceType.WILD_FRUIT));
			assertTrue(f.bag.isEmpty()); assertTrue(store.isEmpty()); f.state.world().assertConservation();
		}
	}

	@Test void differentAgesAndSpeciesKeepSeparateBatchesAndNutritionAfterTravel() {
		Fixture f = new Fixture(); assertEquals(2, f.harvest(3));
		f.advanceTo(2); assertEquals(3, f.harvest(4)); assertEquals(4, f.berries(4));
		f.advanceTo(3); SimpleContainer store = new SimpleContainer(3); assertEquals(9, f.deliver(store));
		var batches = f.state.world().batches(); assertEquals(3, batches.size());
		assertEquals(List.of(ResourceType.TUBERS, ResourceType.TUBERS, ResourceType.WILD_FRUIT), batches.stream().map(b -> b.resourceType()).toList());
		assertEquals(List.of(0L, 2L, 2L), batches.stream().map(b -> b.harvestDay()).toList());
		assertEquals(List.of(8L, 10L, 5L), batches.stream().map(b -> b.expiryDay()).toList());
		assertEquals(List.of(48L, 72L, 72L), batches.stream().map(b -> b.nutrition()).toList());
		assertTrue(store.isEmpty()); assertTrue(f.bag.isEmpty()); f.state.world().assertConservation();
	}

	@Test void fullThenPartialReturnAndDeathRecoveryConserveRemainingPotatoesAcrossNbt() {
		Fixture f = new Fixture(); assertEquals(5, f.harvest(List.of(new ItemStack(Items.POTATO, 6), new ItemStack(Items.POISONOUS_POTATO, 2))));
		var age = SettlementFoodAge.read(f.bag.getItem(0)).orElseThrow();
		SimpleContainer store = new SimpleContainer(1); store.setItem(0, new ItemStack(Items.DIRT, 64));
		assertEquals(SettlementCargoDelivery.Status.NO_CAPACITY, f.finish(f.prepare(), store, true).status());
		assertEquals(5, f.bag.countItem(Items.POTATO)); assertEquals(0, f.food(ResourceType.TUBERS));
		ItemStack existing = f.bag.getItem(0).copy(); existing.setCount(62); store.setItem(0, existing.copy());
		assertEquals(2, f.deliver(store)); same(existing, store.getItem(0));
		var claims = SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, f.data)), Level.OVERWORLD, ACTOR);
		var recovery = new SettlementCargoRecovery(); var ticket = f.prepare(); f.present.set(false);
		assertEquals(5, recovery.captureDeath(claims, Level.OVERWORLD, ACTOR, STORE, "Resident", () -> f.bag));
		assertEquals(0, recovery.captureDeath(claims, Level.OVERWORLD, ACTOR, STORE, "Resident", () -> f.bag));
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, store, true).status());
		assertTrue(f.bag.isEmpty()); recovery = SettlementCargoRecovery.load(recovery.save(new CompoundTag(), registries), registries);
		SimpleContainer first = new SimpleContainer(1) { @Override public int getMaxStackSize() { return 1; } };
		assertEquals(1, recovery.recover(ACTOR, first, () -> true).moved());
		assertEquals(age, SettlementFoodAge.read(first.getItem(0)).orElseThrow());
		assertEquals(4, recovery.inspect(ACTOR).orElseThrow().totalCount());
		recovery = SettlementCargoRecovery.load(recovery.save(new CompoundTag(), registries), registries);
		SimpleContainer rest = new SimpleContainer(2); assertEquals(4, recovery.recover(ACTOR, rest, () -> true).moved());
		assertEquals(2, rest.countItem(Items.POISONOUS_POTATO));
		assertEquals(age, SettlementFoodAge.read(rest.getItem(0)).orElseThrow()); assertTrue(recovery.inspect(ACTOR).isEmpty());
		assertEquals(6, 1 + f.food(ResourceType.TUBERS) + first.countItem(Items.POTATO) + rest.countItem(Items.POTATO));
		same(existing, store.getItem(0)); f.state.world().assertConservation();
	}

	@Test void potatoesRemainDeliverableOnDayEightButNotDayNine() {
		for (long arrival : List.of(8L, 9L)) {
			Fixture f = new Fixture(); assertEquals(3, f.harvest(4)); ItemStack original = f.bag.getItem(0).copy();
			var ticket = f.prepare(); f.advanceTo(arrival); SimpleContainer store = new SimpleContainer(1);
			var result = f.finish(ticket, store, true);
			if (arrival == 8) {
				assertEquals(3, result.moved()); assertEquals(8, f.state.world().batchesAt(f.state.campOwner()).getFirst().expiryDay());
			} else {
				assertEquals(SettlementCargoDelivery.Status.FOOD_UNAVAILABLE, result.status());
				same(original, f.bag.getItem(0)); assertTrue(f.state.world().ledger().events().isEmpty());
			}
			assertTrue(store.isEmpty()); f.state.world().assertConservation();
		}
	}

	@Test void productionOffThenOnCannotReviveATicketForHarvestedPotatoes() {
		Fixture f = new Fixture(); assertEquals(3, f.harvest(4)); var ticket = f.prepare();
		f.state.setProductionEnabled(false);
		assertFalse(SettlementCargoDelivery.hasDeliverableFood(f.data, f.camp, ACTOR, f.bag));
		f.state.setProductionEnabled(true); SimpleContainer store = new SimpleContainer(1);
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, store, true).status());
		assertEquals(3, f.bag.countItem(Items.POTATO)); assertEquals(0, f.food(ResourceType.TUBERS));
		assertEquals(3, f.deliver(store)); assertTrue(store.isEmpty()); f.state.world().assertConservation();
	}

	@Test void legacyClockMigrationRejectsFormerCargoAndOnlyAcceptsFreshHarvest() {
		Fixture f = new Fixture(); assertEquals(3, f.harvest(4)); ItemStack former = f.bag.getItem(0).copy();
		CompoundTag saved = f.data.save(new CompoundTag(), null);
		saved.getList("Simulations", Tag.TAG_COMPOUND).getCompound(0).remove("FoodClockId"); f.load(saved);
		assertNotEquals(SettlementFoodAge.read(former).orElseThrow().clockId(), f.state.foodClockId().orElseThrow());
		SimpleContainer store = new SimpleContainer(2); assertEquals(0, f.deliver(store)); same(former, f.bag.getItem(0));
		assertEquals(2, f.harvest(3)); assertEquals(2, f.deliver(store)); same(former, f.bag.getItem(0));
		assertEquals(2, f.food(ResourceType.TUBERS)); assertTrue(store.isEmpty());
		CompoundTag stable = f.data.save(new CompoundTag(), null); f.load(stable);
		assertEquals(stable, f.data.save(new CompoundTag(), null)); assertEquals(2, f.food(ResourceType.TUBERS));
		f.state.world().assertConservation();
	}

	@Test void foreignAndCorruptPotatoesCannotConsumeCapacityAheadOfFreshCargo() {
		Fixture f = new Fixture(); assertEquals(3, f.harvest(4)); ItemStack valid = f.bag.getItem(0).copy();
		ItemStack foreign = SettlementFoodAge.stamp(new ItemStack(Items.POTATO, 2), new UUID(73, 99), 0);
		ItemStack corrupt = valid.copy(); CompoundTag tag = corrupt.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.getCompound("coopcraft_food_age").putString("Resource", "WILD_FRUIT");
		corrupt.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		f.bag.setItem(0, foreign.copy()); f.bag.setItem(1, corrupt.copy()); f.bag.setItem(2, valid);
		SimpleContainer store = new SimpleContainer(1); assertEquals(3, f.deliver(store));
		same(foreign, f.bag.getItem(0)); same(corrupt, f.bag.getItem(1)); assertTrue(f.bag.getItem(2).isEmpty());
		assertEquals(3, f.food(ResourceType.TUBERS)); assertTrue(store.isEmpty());
		assertEquals(0, f.deliver(store)); f.state.world().assertConservation();
	}
}
