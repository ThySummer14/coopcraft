package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual containers, production receipts and full settlement NBT, without a world or Villager Goal. */
class SettlementPersonalCraftingIntegrationTest {

	private static final UUID ACTOR = new UUID(22, 1);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void fullBackpackStickBatchRemainsSingleAuthorityWithProductionOnOrOff() {
		for (boolean production : List.of(false, true)) {
			completeAndReload(fixture(production), Items.OAK_LOG, Items.STICK, 8, ResourceType.STICK, "sticks");
		}
	}

	@Test
	void fullBackpackGrindingRemainsSingleAuthorityWithProductionOnOrOff() {
		for (boolean production : List.of(false, true)) {
			completeAndReload(fixture(production), Items.COBBLESTONE, Items.SMOOTH_STONE, 1,
				ResourceType.SMOOTH_STONE, "smooth_stone");
		}
	}

	private static void completeAndReload(Fixture f, Item input, Item output, int count,
		ResourceType resource, String recipe) {
		SimpleContainer backpack = new SimpleContainer(8);
		backpack.setItem(0, new ItemStack(input));
		for (int slot = 1; slot < 8; slot++) backpack.setItem(slot, new ItemStack(Items.DIRT, 64));
		Predicate<ItemStack> ingredient = stack -> stack.is(input);
		CompoundTag before = f.data.save(new CompoundTag(), null);
		f.data.setDirty(false);
		for (int i = 0; i < 3; i++) {
			assertTrue(SettlementPersonalCrafting.canCraft(backpack, ingredient, ItemStack.EMPTY, new ItemStack(output, count)));
		}
		assertEquals(before, f.data.save(new CompoundTag(), null));
		assertFalse(f.data.isDirty());
		var attempt = SettlementSimulationService.beginCrafting(f.data, f.settlement, ACTOR, recipe, output, count);
		assertTrue(SettlementPersonalCrafting.craft(backpack, ingredient, new ItemStack(output, count)));
		var completion = SettlementSimulationService.recordCrafting(f.data, f.settlement, ACTOR, attempt).orElseThrow();
		assertEquals(0,
			SettlementSimulationService.recordCraftedProduct(f.data, f.settlement, ACTOR, backpack, completion, output, count));
		assertEquals(0, backpack.countItem(input));
		assertEquals(7 * 64, backpack.countItem(Items.DIRT));
		assertEquals(count, backpack.countItem(output) + quantity(f, resource));
		assertEquals(1, eventCount(f, EventType.CRAFT_COMPLETED));
		assertEquals(1, eventCount(f, EventType.SKILL_PRACTICED));
		assertTrue(f.state().world().ledger().event(completion.eventId()).orElseThrow().causalLinks().isEmpty());
		assertEquals(0, SettlementSimulationService.recordCraftedProduct(
			f.data, f.settlement, ACTOR, backpack, completion, output, count));
		CompoundTag saved = f.data.save(new CompoundTag(), null);
		for (int round = 0; round < 2; round++) {
			SettlementSavedData loaded = SettlementSavedData.load(saved, null);
			f = new Fixture(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
			assertEquals(saved, loaded.save(new CompoundTag(), null));
			assertEquals(count, backpack.countItem(output) + quantity(f, resource));
			assertEquals(1, eventCount(f, EventType.CRAFT_COMPLETED));
			assertEquals(1, eventCount(f, EventType.SKILL_PRACTICED));
			assertFalse(SettlementPersonalCrafting.craft(backpack, ingredient, new ItemStack(output, count)));
			f.state().world().assertConservation();
		}
	}

	@Test
	void failedOutputCommitKeepsExportedIngredientAndReloadedRetryUsesFreshEvidence() {
		Fixture f = fixture(true);
		f.state().world().importExternalBatch(ResourceType.WOOD, 1, f.state().campOwner());
		AtomicBoolean acceptOutput = new AtomicBoolean(true);
		SimpleContainer backpack = new SimpleContainer(1) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) {
				return !stack.is(Items.STICK) || acceptOutput.get();
			}
		};
		Predicate<ItemStack> log = stack -> stack.is(Items.OAK_LOG);
		assertTrue(SettlementPersonalCrafting.canCraft(backpack, log, new ItemStack(Items.OAK_LOG), new ItemStack(Items.STICK, 8)));
		SettlementSimulationService.beginCrafting(f.data, f.settlement, ACTOR, "sticks", Items.STICK, 8);
		var batch = f.state().world().batchesAt(f.state().campOwner()).getFirst();
		String person = f.state().residentId(ACTOR).orElseThrow();
		assertEquals(1, MinecraftStockpileAdapter.exportToContainer(f.state().world(), f.state().campOwner(),
			batch.batchId(), 1, backpack, List.of(person), Map.of("activity", "craft", "recipe", "sticks")));
		acceptOutput.set(false);
		assertFalse(SettlementPersonalCrafting.craft(backpack, log, new ItemStack(Items.STICK, 8)));
		assertEquals(1, backpack.countItem(Items.OAK_LOG));
		assertEquals(0, backpack.countItem(Items.STICK));
		assertEquals(0, quantity(f, ResourceType.WOOD));
		assertEquals(0, eventCount(f, EventType.CRAFT_COMPLETED));
		assertEquals(0, eventCount(f, EventType.SKILL_PRACTICED));
		CompoundTag saved = f.data.save(new CompoundTag(), null);
		SettlementSavedData loaded = SettlementSavedData.load(saved, null);
		f = new Fixture(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
		assertEquals(saved, loaded.save(new CompoundTag(), null));
		acceptOutput.set(true);
		var retry = SettlementSimulationService.beginCrafting(f.data, f.settlement, ACTOR, "sticks", Items.STICK, 8);
		assertTrue(SettlementPersonalCrafting.craft(backpack, log, new ItemStack(Items.STICK, 8)));
		var completion = SettlementSimulationService.recordCrafting(f.data, f.settlement, ACTOR, retry).orElseThrow();
		assertTrue(f.state().world().ledger().event(completion.eventId()).orElseThrow().causalLinks().isEmpty(),
			"Input from the failed attempt is preexisting inventory, not a current direct export");
		assertEquals(0, SettlementSimulationService.recordCraftedProduct(
			f.data, f.settlement, ACTOR, backpack, completion, Items.STICK, 8));
		assertEquals(8, backpack.countItem(Items.STICK));
		assertEquals(0, quantity(f, ResourceType.STICK));
		assertEquals(1, eventCount(f, EventType.EXTERNAL_EXPORT));
		assertEquals(1, eventCount(f, EventType.CRAFT_COMPLETED));
		assertEquals(1, eventCount(f, EventType.SKILL_PRACTICED));
		f.state().world().assertConservation();
	}

	private static long eventCount(Fixture f, EventType type) {
		return f.state().world().ledger().events().stream().filter(event -> event.type() == type).count();
	}

	private static long quantity(Fixture f, ResourceType type) {
		return f.state().world().inventoryTotals().getOrDefault(type, 0L);
	}

	private static Fixture fixture(boolean production) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Personal crafting", 0);
		var state = data.getOrCreateSimulation(settlement);
		data.registerMember(settlement, ACTOR, 0);
		state.mapResident(ACTOR, false, 0);
		state.setSurvivalEnabled(true, 0);
		state.setSkillsEnabled(true);
		state.setProductionEnabled(production);
		return new Fixture(data, settlement);
	}

	private record Fixture(SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state() { return data.simulationOf(settlement).orElseThrow(); }
	}
}
