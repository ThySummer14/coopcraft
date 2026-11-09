package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.SettlementSimulationService.CraftingAttempt;
import com.thysummer.coopcraft.civilization.SettlementSimulationService.CraftingCompletion;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.WorkType;
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

/** Service contract tests. Container interruption coverage lives in its own integration-style test. */
class SettlementCraftingTraceTest {
	private static final UUID ALICE = new UUID(7, 1);
	private static final UUID BOB = new UUID(7, 2);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void everyCraftingPathFiltersOldExportsAndUnrelatedActorRecipeActivityAndType() {
		List<String> recipes = new java.util.ArrayList<>(List.of("stone_pickaxe", "sticks", "polished_stone_pickaxe", "smooth_stone"));
		for (SettlementMaterialCrafting.Recipe recipe : SettlementMaterialCrafting.Recipe.values()) {
			recipes.add(recipe.id());
		}
		for (String recipe : recipes) {
			Fixture f = fixture(true, false);
			f.export(ALICE, recipe, "craft");
			CraftingAttempt attempt = f.begin(ALICE, recipe);
			f.export(BOB, recipe, "craft");
			f.export(ALICE, "other_recipe", "craft");
			f.export(ALICE, recipe, "build");
			f.state.world().importExternalBatch(ResourceType.WOOD, 1, f.state.campOwner(),
				List.of(f.person(ALICE)), Map.of("recipe", recipe, "activity", "craft"));
			long first = f.export(ALICE, recipe, "craft");
			long second = f.export(ALICE, recipe, "craft");
			CraftingCompletion completed = f.complete(ALICE, attempt);
			assertEquals(List.of(first, second), f.event(completed.eventId()).causalLinks(), recipe);
			assertEquals("current_attempt_direct_exports", f.event(completed.eventId()).context().get("provenanceScope"));
		}
	}

	@Test
	void failedAttemptAndOtherWorkersCompletionCannotLeakIntoRetry() {
		Fixture f = fixture(true, false);
		f.begin(ALICE, "sticks"); // Abandoned after export, deliberately never completed.
		long failed = f.export(ALICE, "sticks", "craft");
		CraftingCompletion bob = f.complete(BOB, f.begin(BOB, "sticks"));
		assertTrue(f.event(bob.eventId()).causalLinks().isEmpty());
		CraftingAttempt retry = f.begin(ALICE, "sticks");
		long fresh = f.export(ALICE, "sticks", "craft");
		CraftingCompletion alice = f.complete(ALICE, retry);
		assertEquals(List.of(fresh), f.event(alice.eventId()).causalLinks());
		assertFalse(f.event(alice.eventId()).causalLinks().contains(failed));
	}

	@Test
	void preexistingRealInventoryHasUnknownProvenanceAndNoDirectCauses() {
		Fixture f = fixture(true, false);
		f.export(ALICE, "sticks", "craft");
		CraftingCompletion completed = f.complete(ALICE, f.begin(ALICE, "sticks"));
		SimulationEvent event = f.event(completed.eventId());
		assertTrue(event.causalLinks().isEmpty());
		assertEquals("unknown", event.context().get("preexistingInventoryProvenance"));
		assertEquals("minecraft:stick", event.context().get("outputItem"));
		assertEquals("4", event.context().get("outputCount"));
	}

	@Test
	void completionIsSingleUseAndSkillsLinkOnlyItsReceipt() {
		for (boolean skills : List.of(false, true)) {
			Fixture f = fixture(true, skills);
			CraftingAttempt attempt = f.begin(ALICE, "sticks");
			f.export(ALICE, "sticks", "craft");
			double before = f.state.world().person(f.person(ALICE)).skill(WorkType.CRAFT);
			CraftingCompletion completed = f.complete(ALICE, attempt);
			List<SimulationEvent> snapshot = f.events();
			double after = f.state.world().person(f.person(ALICE)).skill(WorkType.CRAFT);
			assertTrue(SettlementSimulationService.recordCrafting(f.data, f.settlement, ALICE, attempt).isEmpty());
			assertEquals(snapshot, f.events());
			assertEquals(after, f.state.world().person(f.person(ALICE)).skill(WorkType.CRAFT));
			if (skills) {
				assertTrue(after > before);
				assertEquals(EventType.SKILL_PRACTICED, snapshot.getLast().type());
				assertEquals(List.of(completed.eventId()), snapshot.getLast().causalLinks());
			} else {
				assertEquals(before, after);
				assertEquals(completed.eventId(), snapshot.getLast().eventId());
			}
		}
	}

	@Test
	void explicitOlderCompletionWinsOverNewerSameRecipeAndCannotBeImportedTwice() {
		Fixture f = fixture(true, false);
		CraftingCompletion first = f.complete(ALICE, f.begin(ALICE, "sticks"));
		CraftingCompletion second = f.complete(ALICE, f.begin(ALICE, "sticks"));
		assertNotEquals(first.eventId(), second.eventId());
		SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.STICK, 8));
		assertEquals(4, f.importProduct(ALICE, inventory, first, 4));
		assertEquals(List.of(first.eventId()), f.events().getLast().causalLinks());
		List<SimulationEvent> snapshot = f.events();
		assertEquals(0, f.importProduct(ALICE, inventory, first, 4));
		assertEquals(4, inventory.countItem(Items.STICK));
		assertEquals(snapshot, f.events());
		assertEquals(4, f.importProduct(ALICE, inventory, second, 4));
		f.state.world().assertConservation();
	}

	@Test
	void partialProductImportAlsoConsumesWholeReceipt() {
		Fixture f = fixture(true, false);
		CraftingCompletion completion = f.complete(ALICE, f.begin(ALICE, "sticks"));
		SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.STICK, 4));
		assertEquals(2, f.importProduct(ALICE, inventory, completion, 2));
		List<SimulationEvent> after = f.events();
		assertEquals(0, f.importProduct(ALICE, inventory, completion, 2));
		assertEquals(2, inventory.countItem(Items.STICK));
		assertEquals(after, f.events());
	}

	@Test
	void invalidProductRequestsDoNotConsumeInventoryOrReceiptOrLedger() {
		Fixture f = fixture(true, false);
		CraftingCompletion completion = f.complete(ALICE, f.begin(ALICE, "sticks"));
		SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.STICK, 8), new ItemStack(Items.SMOOTH_STONE, 4));
		List<SimulationEvent> before = f.events();
		assertEquals(0, f.importProduct(ALICE, inventory, null, 4));
		assertEquals(0, f.importProduct(ALICE, null, completion, 4));
		assertEquals(0, f.importProduct(BOB, inventory, completion, 4));
		assertEquals(0, f.importProduct(ALICE, inventory, completion, 5));
		assertEquals(0, f.importProduct(ALICE, inventory, completion, 0));
		assertEquals(0, SettlementSimulationService.recordCraftedProduct(f.data, f.settlement, ALICE,
			inventory, completion, Items.SMOOTH_STONE, 4));
		f.state.setProductionEnabled(false);
		assertEquals(0, f.importProduct(ALICE, inventory, completion, 4));
		f.state.setProductionEnabled(true);
		Fixture other = fixture(true, false);
		assertEquals(0, other.importProduct(ALICE, inventory, completion, 4));
		assertEquals(before, f.events());
		assertTrue(other.events().isEmpty());
		assertEquals(8, inventory.countItem(Items.STICK));
		assertEquals(4, inventory.countItem(Items.SMOOTH_STONE));
		assertEquals(4, f.importProduct(ALICE, inventory, completion, 4));
	}

	@Test
	void zeroMoveConsumesReceiptBeforeLaterUnrelatedStockAppears() {
		Fixture f = fixture(true, false);
		CraftingCompletion completion = f.complete(ALICE, f.begin(ALICE, "sticks"));
		SimpleContainer inventory = new SimpleContainer(2);
		List<SimulationEvent> before = f.events();
		assertEquals(0, f.importProduct(ALICE, inventory, completion, 4));
		inventory.setItem(0, new ItemStack(Items.STICK, 4));
		assertEquals(0, f.importProduct(ALICE, inventory, completion, 4));
		assertEquals(4, inventory.countItem(Items.STICK));
		assertEquals(before, f.events());
	}

	@Test
	void completeSavedDataRoundTripPreservesEvidenceButInvalidatesEphemeralHandles() {
		Fixture f = fixture(true, true);
		CraftingAttempt attempt = f.begin(ALICE, "sticks");
		f.export(ALICE, "sticks", "craft");
		CraftingCompletion completion = f.complete(ALICE, attempt);
		CraftingAttempt pending = f.begin(ALICE, "sticks");
		CompoundTag tag = f.data.save(new CompoundTag(), null);
		SettlementSavedData loaded = SettlementSavedData.load(tag, null);
		Settlement settlement = loaded.find(BlockPos.ZERO).orElseThrow();
		Fixture restored = new Fixture(loaded, settlement, loaded.simulationOf(settlement).orElseThrow());
		assertEquals(f.events(), restored.events());
		assertEquals(tag, loaded.save(new CompoundTag(), null));
		assertTrue(SettlementSimulationService.recordCrafting(loaded, settlement, ALICE, pending).isEmpty());
		SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.STICK, 4));
		assertEquals(0, restored.importProduct(ALICE, inventory, completion, 4));
		assertEquals(4, inventory.countItem(Items.STICK));
		assertEquals(tag, loaded.save(new CompoundTag(), null));
	}

	@Test
	void disabledAttemptsNeverCreateStateAndSkillsOnlyStillReturnsReceipt() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "No simulation", 0);
		CraftingAttempt missing = SettlementSimulationService.beginCrafting(data, settlement, ALICE, "sticks", Items.STICK, 4);
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertTrue(SettlementSimulationService.recordCrafting(data, settlement, ALICE, missing).isEmpty());
		Fixture f = fixture(false, false);
		CraftingAttempt disabled = f.begin(ALICE, "sticks");
		f.state.setSkillsEnabled(true);
		assertTrue(SettlementSimulationService.recordCrafting(f.data, f.settlement, ALICE, disabled).isEmpty());
		CraftingCompletion completion = f.complete(ALICE, f.begin(ALICE, "sticks"));
		SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.STICK, 4));
		assertEquals(0, f.importProduct(ALICE, inventory, completion, 4));
		assertEquals(4, inventory.countItem(Items.STICK));
	}

	@Test
	void completionRejectsWrongActorMissingMembershipAndChangedSwitches() {
		Fixture f = fixture(true, false);
		CraftingAttempt attempt = f.begin(ALICE, "sticks");
		assertTrue(SettlementSimulationService.recordCrafting(f.data, f.settlement, BOB, attempt).isEmpty());
		f.state.setSurvivalEnabled(false, 0);
		assertTrue(SettlementSimulationService.recordCrafting(f.data, f.settlement, ALICE, attempt).isEmpty());
		assertTrue(f.events().isEmpty());
		f.state.setSurvivalEnabled(true, 0);
		Settlement another = f.data.getOrCreate(new BlockPos(1000, 0, 0), "Another", 0);
		f.data.removeMember(ALICE);
		f.data.registerMember(another, ALICE, 0);
		assertTrue(SettlementSimulationService.recordCrafting(f.data, f.settlement, ALICE, attempt).isEmpty());
		assertTrue(f.events().isEmpty());
	}

	private static Fixture fixture(boolean production, boolean skills) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Craft receipts", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (UUID actor : List.of(ALICE, BOB)) {
			data.registerMember(settlement, actor, 0);
			state.mapResident(actor, false, 100);
		}
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(production);
		state.setSkillsEnabled(skills);
		return new Fixture(data, settlement, state);
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		String person(UUID actor) { return state.residentId(actor).orElseThrow(); }
		List<SimulationEvent> events() { return state.world().ledger().events(); }
		SimulationEvent event(long id) { return state.world().ledger().event(id).orElseThrow(); }
		CraftingAttempt begin(UUID actor, String recipe) {
			return SettlementSimulationService.beginCrafting(data, settlement, actor, recipe, Items.STICK, 4);
		}
		CraftingCompletion complete(UUID actor, CraftingAttempt attempt) {
			return SettlementSimulationService.recordCrafting(data, settlement, actor, attempt).orElseThrow();
		}
		long export(UUID actor, String recipe, String activity) {
			var batch = state.world().importExternalBatch(ResourceType.WOOD, 1, state.campOwner());
			state.world().exportExternalBatch(state.campOwner(), batch.batchId(), 1,
				List.of(person(actor)), Map.of("recipe", recipe, "activity", activity));
			return events().getLast().eventId();
		}
		int importProduct(UUID actor, SimpleContainer inventory, CraftingCompletion completion, int inserted) {
			return SettlementSimulationService.recordCraftedProduct(data, settlement, actor, inventory, completion, Items.STICK, inserted);
		}
	}
}
