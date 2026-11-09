package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

/** Real crafting transactions and persisted data, without a world or Goal simulation. */
@Isolated("BlueprintManager has a static reload snapshot")
class SettlementStarterCraftingTest {
	private static final UUID ALICE = new UUID(33, 1);
	private static final UUID BOB = new UUID(33, 2);
	private Field plans;
	private Object previous;
	private ConstructionPlan starter;
	private SettlementSavedData data;
	private Settlement settlement;
	private SettlementSimulationState state;
	private SimpleContainer stock;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@BeforeEach void setup() throws Exception {
		plans = BlueprintManager.class.getDeclaredField("plans");
		plans.setAccessible(true);
		previous = plans.get(null);
		try (var input = getClass().getResourceAsStream("/data/coopcraft/civilization/blueprints/starter_workshop_v1.json")) {
			assertNotNull(input);
			try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				new BlueprintManager().apply(Map.of(ConstructionPlanner.STARTER_WORKSHOP, JsonParser.parseReader(reader)), null, null);
			}
		}
		starter = BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP);
		data = new SettlementSavedData();
		settlement = data.getOrCreate(BlockPos.ZERO, "First workshop", 0);
		state = data.getOrCreateSimulation(settlement);
		for (UUID actor : List.of(ALICE, BOB)) {
			data.registerMember(settlement, actor, 0);
			state.mapResident(actor, false, 0);
		}
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.setSkillsEnabled(true);
		data.setStockpile(settlement, BlockPos.ZERO.east());
		stock = new SimpleContainer(9);
	}
	@AfterEach void restore() throws Exception { plans.set(null, previous); }

	@Test void oneRealLogNeedsTwoTicketsAndLeavesOnlyOneRealTable() {
		var initialAbstractStock = Map.copyOf(state.world().inventoryTotals());
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		var first = prepare();
		assertEquals(SettlementMaterialCrafting.Recipe.PLANKS, first.recipe());
		assertTrue(finish(first));
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
		assertFalse(finish(first));
		var second = prepare();
		assertEquals(SettlementMaterialCrafting.Recipe.CRAFTING_TABLE, second.recipe());
		assertTrue(finish(second));
		assertFalse(finish(second));
		assertNull(prepare());
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(0, stock.countItem(Items.OAK_PLANKS));
		assertEquals(1, stock.countItem(Items.CRAFTING_TABLE));
		assertEquals(2, events(EventType.CRAFT_COMPLETED));
		assertEquals(2, events(EventType.SKILL_PRACTICED));
		assertEquals(0, events(EventType.EXTERNAL_IMPORT));
		assertEquals(initialAbstractStock, state.world().inventoryTotals());
		for (ResourceType resource : ResourceType.values()) {
			assertEquals(0L, state.world().inventoryTotals().get(resource).longValue(), resource.name());
		}
		assertTrue(data.getCraftingStation(settlement).isEmpty());
		assertTrue(data.getConstructionJob(settlement).isEmpty());
		state.world().assertConservation();
	}

	@Test void competingResidentsCannotReuseOldRecipeOrMakeASecondTable() {
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 8));
		var alice = prepare();
		var bob = SettlementStarterCrafting.prepare(data, settlement, BOB, stock, () -> true);
		assertTrue(finish(alice));
		assertFalse(SettlementStarterCrafting.finish(bob, data, settlement, BOB, stock));
		assertEquals(7, stock.countItem(Items.OAK_LOG));
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
		alice = prepare();
		bob = SettlementStarterCrafting.prepare(data, settlement, BOB, stock, () -> true);
		assertTrue(finish(alice));
		assertFalse(SettlementStarterCrafting.finish(bob, data, settlement, BOB, stock));
		assertEquals(1, stock.countItem(Items.CRAFTING_TABLE));
		assertEquals(2, events(EventType.CRAFT_COMPLETED));
	}

	@Test void namedFinishedTableStopsProductionButNamedIngredientsAreNotConsumable() {
		stock.setItem(0, named(Items.OAK_LOG, 4));
		stock.setItem(1, named(Items.OAK_PLANKS, 4));
		assertNull(prepare());
		assertTrue(needsLog());
		stock.setItem(2, new ItemStack(Items.OAK_LOG));
		assertNotNull(prepare());
		stock.setItem(3, named(Items.CRAFTING_TABLE, 1));
		assertNull(prepare());
		assertFalse(needsLog());
		assertEquals(0, events(EventType.CRAFT_COMPLETED));
	}

	@Test void anyStationMarkerAndAnySavedJobBlockEvenWhenUnusable() {
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		var ticket = prepare();
		for (BlockPos pos : List.of(BlockPos.ZERO, new BlockPos(12000, 90, -12000))) {
			data.setCraftingStation(settlement, pos);
			assertFalse(SettlementStarterCrafting.eligible(data, settlement));
			assertNull(prepare());
			assertFalse(needsLog());
		}
		assertFalse(finish(ticket));
		// A separate fixture snapshot with no marker tests all job states, including compatible starter jobs.
		CompoundTag saved = data.save(new CompoundTag(), null);
		saved.remove("CraftingStations");
		data = SettlementSavedData.load(saved, null);
		settlement = data.find(BlockPos.ZERO).orElseThrow();
		assertTrue(SettlementStarterCrafting.eligible(data, settlement));
		for (var job : List.of(ConstructionJob.start(starter, BlockPos.ZERO),
			new ConstructionJob(starter.id(), BlockPos.ZERO, 0),
			new ConstructionJob(starter.id(), BlockPos.ZERO, 0, "changed"),
			new ConstructionJob(ConstructionPlanner.STONE_COTTAGE, BlockPos.ZERO, 999, "missing"))) {
			data.setConstructionJob(settlement, job);
			assertNull(prepare());
			assertFalse(needsLog());
		}
	}

	@Test void enoughHousingAndMissingOrUnsupportedBlueprintBlock() throws Exception {
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		var ticket = prepare();
		data.addHousingBed(settlement, BlockPos.ZERO);
		data.addHousingBed(settlement, BlockPos.ZERO.east());
		assertNull(prepare());
		assertFalse(finish(ticket));
		data.removeHousingBed(settlement, BlockPos.ZERO);
		for (ConstructionPlan changed : List.of(
			new ConstructionPlan(starter.id(), List.of()),
			new ConstructionPlan(starter.id(), List.of(new ConstructionInstruction(BlockPos.ZERO.east(), Blocks.CRAFTING_TABLE.defaultBlockState(), Items.CRAFTING_TABLE, true))),
			new ConstructionPlan(starter.id(), List.of(new ConstructionInstruction(BlockPos.ZERO, Blocks.CRAFTING_TABLE.defaultBlockState(), Items.CRAFTING_TABLE, false))),
			new ConstructionPlan(starter.id(), List.of(new ConstructionInstruction(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, true))))) {
			plans.set(null, Map.of(starter.id(), starter));
			ticket = prepare();
			assertNotNull(ticket);
			plans.set(null, Map.of(starter.id(), changed));
			assertFalse(finish(ticket));
			assertNull(prepare());
		}
		plans.set(null, Map.of());
		assertNull(prepare());
		assertFalse(needsLog());
		assertEquals(1, stock.countItem(Items.OAK_LOG));
	}

	@Test void ticketPinsDataActorMemberStoreAndLiveBindingAndFailureConsumesIt() {
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		var ticket = prepare();
		var loaded = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		assertFalse(SettlementStarterCrafting.valid(ticket, loaded, loaded.find(BlockPos.ZERO).orElseThrow(), ALICE));
		assertFalse(SettlementStarterCrafting.valid(ticket, data, settlement, BOB));
		var other = data.getOrCreate(new BlockPos(1000, 0, 0), "Other", 0);
		assertFalse(SettlementStarterCrafting.valid(ticket, data, other, ALICE));
		assertNull(SettlementStarterCrafting.prepare(data, settlement, UUID.randomUUID(), stock, () -> true));
		data.setStockpile(settlement, data.getStockpile(settlement).orElseThrow());
		assertFalse(finish(ticket));
		ticket = prepare();
		data.setStockpile(settlement, BlockPos.ZERO.west());
		assertFalse(finish(ticket));
		ticket = prepare();
		data.removeMember(ALICE);
		data.registerMember(settlement, ALICE, 0);
		assertFalse(finish(ticket));
		AtomicBoolean live = new AtomicBoolean(true);
		ticket = SettlementStarterCrafting.prepare(data, settlement, ALICE, stock, live::get);
		live.set(false);
		assertFalse(finish(ticket));
		live.set(true);
		assertFalse(finish(ticket));
		assertNull(SettlementStarterCrafting.prepare(data, settlement, ALICE, stock, () -> false));
		ticket = prepare();
		assertFalse(SettlementStarterCrafting.finish(ticket, data, settlement, ALICE, null));
		assertFalse(finish(ticket));
		assertEquals(1, stock.countItem(Items.OAK_LOG));
		assertEquals(0, events(EventType.CRAFT_COMPLETED));
		assertEquals(0, events(EventType.SKILL_PRACTICED));
	}

	@Test void publicAbstractLogsExportOncePrivateAndHouseholdLogsAreUntouched() {
		var person = state.residentId(ALICE).orElseThrow();
		var privateOwner = StorageOwner.person(person);
		var householdOwner = StorageOwner.household(state.world().person(person).householdId());
		state.world().importExternalBatch(ResourceType.WOOD, 3, privateOwner);
		state.world().importExternalBatch(ResourceType.WOOD, 5, householdOwner);
		assertNull(prepare());
		assertTrue(needsLog());
		state.world().importExternalBatch(ResourceType.WOOD, 1, state.campOwner());
		assertFalse(needsLog());
		assertTrue(finish(prepare()));
		assertTrue(finish(prepare()));
		assertEquals(1, stock.countItem(Items.CRAFTING_TABLE));
		assertEquals(1, events(EventType.EXTERNAL_EXPORT));
		assertEquals(3, events(EventType.EXTERNAL_IMPORT));
		assertEquals(8L, state.world().inventoryTotals().get(ResourceType.WOOD).longValue());
		assertEquals(2, events(EventType.CRAFT_COMPLETED));
		assertEquals(2, events(EventType.SKILL_PRACTICED));
		state.world().assertConservation();
	}

	@Test void featureSwitchesGateOnlyAbstractExportsAndPreserveThoseBatches() {
		state.world().importExternalBatch(ResourceType.WOOD, 1, state.campOwner());
		for (boolean survival : List.of(false, true)) for (boolean production : List.of(false, true)) {
			if (survival && production) continue;
			stock.clearContent();
			state.setSurvivalEnabled(survival, 0);
			state.setProductionEnabled(production);
			assertNull(prepare());
			assertTrue(needsLog());
			stock.setItem(0, new ItemStack(Items.OAK_LOG));
			assertTrue(finish(prepare()));
			assertTrue(finish(prepare()));
			assertEquals(1, stock.countItem(Items.CRAFTING_TABLE));
			assertEquals(1L, state.world().inventoryTotals().get(ResourceType.WOOD).longValue());
			state.world().assertConservation();
		}
		assertEquals(0, events(EventType.EXTERNAL_EXPORT));
	}

	@Test void realStockWorksWithoutSimulationAndWithoutSkills() {
		SettlementSavedData plain = new SettlementSavedData();
		Settlement camp = plain.getOrCreate(BlockPos.ZERO, "Plain", 0);
		plain.registerMember(camp, ALICE, 0);
		plain.setStockpile(camp, BlockPos.ZERO.east());
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		for (int batch = 0; batch < 2; batch++) {
			var ticket = SettlementStarterCrafting.prepare(plain, camp, ALICE, stock, () -> true);
			assertNotNull(ticket);
			assertTrue(SettlementStarterCrafting.finish(ticket, plain, camp, ALICE, stock));
		}
		assertEquals(1, stock.countItem(Items.CRAFTING_TABLE));
		assertTrue(plain.simulationOf(camp).isEmpty());
		stock.clearContent();
		stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
		state.setSkillsEnabled(false);
		assertTrue(finish(prepare()));
		assertEquals(1, events(EventType.CRAFT_COMPLETED));
		assertEquals(0, events(EventType.SKILL_PRACTICED));
	}

	@Test void capacityAndSlotRestrictionsNeverPartiallyConsumeIngredients() {
		stock = new SimpleContainer(1);
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 2));
		assertNull(prepare());
		assertEquals(2, stock.countItem(Items.OAK_LOG));
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		assertTrue(finish(prepare()));
		assertTrue(finish(prepare()));
		AtomicBoolean allow = new AtomicBoolean(true);
		stock = new SimpleContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return allow.get(); }
		};
		stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
		var ticket = prepare();
		allow.set(false);
		assertFalse(finish(ticket));
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
		allow.set(true);
		assertFalse(finish(ticket));
		assertEquals(2, events(EventType.CRAFT_COMPLETED));
		assertEquals(2, events(EventType.SKILL_PRACTICED));
	}

	@Test void needsLogUsesOnlyOnePublicBatchAndValidEligibility() {
		assertTrue(needsLog());
		assertFalse(SettlementStarterCrafting.needsLog(data, settlement, null));
		stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
		assertTrue(needsLog());
		stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
		assertFalse(needsLog());
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		assertFalse(needsLog());
		stock.clearContent();
		state.world().importExternalBatch(ResourceType.WOOD, 1, state.campOwner());
		assertFalse(needsLog());
		var unmarked = data.getOrCreate(new BlockPos(2000, 0, 0), "Unmarked", 0);
		assertFalse(SettlementStarterCrafting.needsLog(data, unmarked, stock));
		assertNull(SettlementStarterCrafting.prepare(data, unmarked, ALICE, stock, () -> true));
	}

	@Test void freshDoubleContainerWrapperKeepsTheSameLiveStockAndSupportsBothBatches() {
		var left = new SimpleContainer(9);
		var right = new SimpleContainer(9);
		left.setItem(0, new ItemStack(Items.OAK_LOG));
		for (int batch = 0; batch < 2; batch++) {
			var ticket = SettlementStarterCrafting.prepare(data, settlement, ALICE,
				new CompoundContainer(left, right), () -> true);
			assertNotNull(ticket);
			assertTrue(SettlementStarterCrafting.finish(ticket, data, settlement, ALICE,
				new CompoundContainer(left, right)));
		}
		assertEquals(1, left.countItem(Items.CRAFTING_TABLE) + right.countItem(Items.CRAFTING_TABLE));
	}

	@Test void exportDisabledAfterPreparingConsumesTicketButNotAbstractLog() {
		state.world().importExternalBatch(ResourceType.WOOD, 1, state.campOwner());
		var ticket = prepare();
		assertNotNull(ticket);
		state.setProductionEnabled(false);
		assertFalse(finish(ticket));
		state.setProductionEnabled(true);
		assertFalse(finish(ticket));
		assertEquals(1L, state.world().inventoryTotals().get(ResourceType.WOOD).longValue());
		assertEquals(0, events(EventType.EXTERNAL_EXPORT));
		assertEquals(0, events(EventType.CRAFT_COMPLETED));
		assertEquals(0, events(EventType.SKILL_PRACTICED));
		state.world().assertConservation();
	}

	@Test void perItemStackLimitCanBlockFourPlankOutputWithoutConsumingLog() {
		stock = new SimpleContainer(1) {
			@Override public int getMaxStackSize(ItemStack stack) { return 1; }
		};
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		assertNull(prepare());
		assertEquals(1, stock.countItem(Items.OAK_LOG));
		assertEquals(0, events(EventType.CRAFT_COMPLETED));
	}

	@Test void validationDoesNotReadContainerEveryTick() {
		AtomicBoolean mayRead = new AtomicBoolean(true);
		stock = new SimpleContainer(9) {
			@Override public ItemStack getItem(int slot) { assertTrue(mayRead.get()); return super.getItem(slot); }
		};
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		var ticket = prepare();
		mayRead.set(false);
		assertTrue(SettlementStarterCrafting.valid(ticket, data, settlement, ALICE));
	}

	private SettlementStarterCrafting.Ticket prepare() {
		return SettlementStarterCrafting.prepare(data, settlement, ALICE, stock, () -> true);
	}
	private boolean finish(SettlementStarterCrafting.Ticket ticket) {
		return SettlementStarterCrafting.finish(ticket, data, settlement, ALICE, stock);
	}
	private boolean needsLog() { return SettlementStarterCrafting.needsLog(data, settlement, stock); }
	private long events(EventType type) {
		return state.world().ledger().events().stream().filter(event -> event.type() == type).count();
	}
	private static ItemStack named(net.minecraft.world.item.Item item, int count) {
		ItemStack stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
		return stack;
	}
}
