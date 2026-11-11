package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
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

class SettlementCraftingAttemptIntegrationTest {
	private static final UUID ALICE = new UUID(11, 1);
	private static final UUID BOB = new UUID(11, 2);
	private static final SettlementMaterialCrafting.Recipe CAMPFIRE = SettlementMaterialCrafting.Recipe.CAMPFIRE_COAL;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void interruptedExportsConsumedByAnotherResidentNeverBecomeLaterDirectCauses() {
		for (boolean skills : List.of(false, true)) {
			interruptedExportsConsumedByAnotherResident(false, skills);
		}
	}

	@Test
	void fullSavedDataReloadDoesNotReconnectFailedAttemptToLaterCompletion() {
		for (boolean skills : List.of(false, true)) {
			interruptedExportsConsumedByAnotherResident(true, skills);
		}
	}

	private static void interruptedExportsConsumedByAnotherResident(boolean reload, boolean skills) {
		Fixture f = fixture(skills);
		f.supply(ResourceType.WOOD, 3);
		f.supply(ResourceType.STICK, 3);
		AtomicBoolean allow = new AtomicBoolean(true);
		SimpleContainer stock = new SimpleContainer(9) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return allow.get(); }
		};
		stock.setItem(0, new ItemStack(Items.COAL));
		var failedAttempt = begin(f, ALICE, CAMPFIRE);
		assertNotNull(failedAttempt);
		assertFalse(SettlementMaterialCrafting.craft(stock, f.state(), CAMPFIRE, ALICE, () -> allow.set(false)));
		assertEquals(3, stock.countItem(Items.OAK_LOG));
		assertEquals(0, stock.countItem(Items.STICK));
		assertEquals(1, stock.countItem(Items.COAL));
		assertEquals(0, stock.countItem(Items.CAMPFIRE));
		assertEquals(0, f.quantity(ResourceType.WOOD));
		assertEquals(3, f.quantity(ResourceType.STICK));
		assertEquals(0, events(f, EventType.CRAFT_COMPLETED).size());
		assertEquals(0, events(f, EventType.SKILL_PRACTICED).size());
		List<Long> failedExports = exportIds(f);
		assertEquals(1, failedExports.size());
		f.state().world().assertConservation();

		if (reload) {
			CompoundTag saved = f.data().save(new CompoundTag(), null);
			SettlementSavedData loaded = SettlementSavedData.load(saved, null);
			f = new Fixture(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
			assertEquals(failedExports, exportIds(f));
			assertEquals(saved, loaded.save(new CompoundTag(), null));
			assertEquals(3, stock.countItem(Items.OAK_LOG));
			f.state().world().assertConservation();
		}
		allow.set(true);
		for (int batch = 0; batch < 3; batch++) {
			SimulationEvent bobCompletion = craftAndComplete(f, stock, BOB, SettlementMaterialCrafting.Recipe.PLANKS);
			assertTrue(bobCompletion.causalLinks().isEmpty());
		}
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(12, stock.countItem(Items.OAK_PLANKS));
		assertEquals(1, stock.countItem(Items.COAL));
		assertEquals(3, f.quantity(ResourceType.STICK));

		// Independent player input: no abstract transfer or fabricated source evidence.
		assertEquals(3, SettlementStockpile.insert(stock, new ItemStack(Items.OAK_LOG, 3)));
		SimulationEvent completed = craftAndComplete(f, stock, ALICE, CAMPFIRE);
		List<Long> newExports = exportIds(f).stream().filter(id -> !failedExports.contains(id)).toList();
		assertEquals(1, newExports.size());
		assertEquals(newExports, completed.causalLinks());
		// Only direct causes are relevant: a remaining batch may legitimately retain older ancestry.
		assertTrue(completed.causalLinks().stream().noneMatch(failedExports::contains));
		assertUnknownRealInputs(completed);
		assertEquals(1, stock.countItem(Items.CAMPFIRE));
		assertEquals(12, stock.countItem(Items.OAK_PLANKS));
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(0, stock.countItem(Items.STICK));
		assertEquals(0, stock.countItem(Items.COAL));
		assertEquals(0, f.quantity(ResourceType.WOOD));
		assertEquals(0, f.quantity(ResourceType.STICK));
		assertEquals(4, events(f, EventType.CRAFT_COMPLETED).size());
		if (!skills) assertTrue(events(f, EventType.SKILL_PRACTICED).isEmpty());
		f.state().world().assertConservation();
	}

	@Test
	void everyBatchExportedDuringTheCurrentAttemptIsADirectCause() {
		Fixture f = fixture(false);
		for (int batch = 0; batch < 3; batch++) {
			f.supply(ResourceType.WOOD, 1);
			f.supply(ResourceType.STICK, 1);
		}
		SimpleContainer stock = new SimpleContainer(9);
		stock.setItem(0, new ItemStack(Items.COAL));
		SimulationEvent completed = craftAndComplete(f, stock, ALICE, CAMPFIRE);
		assertEquals(6, exportIds(f).size());
		assertEquals(exportIds(f), completed.causalLinks());
		assertUnknownRealInputs(completed);
		assertEquals(1, stock.countItem(Items.CAMPFIRE));
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(0, stock.countItem(Items.STICK));
		assertEquals(0, stock.countItem(Items.COAL));
		assertEquals(0, f.quantity(ResourceType.WOOD));
		assertEquals(0, f.quantity(ResourceType.STICK));
		f.state().world().assertConservation();
	}

	@Test
	void entirelyRealInputsHaveNoCausesAndNewAttemptCannotReuseSuccessfulExports() {
		Fixture f = fixture(true);
		f.supply(ResourceType.WOOD, 3);
		f.supply(ResourceType.STICK, 3);
		SimpleContainer stock = new SimpleContainer(9);
		stock.setItem(0, new ItemStack(Items.COAL));
		SimulationEvent first = craftAndComplete(f, stock, ALICE, CAMPFIRE);
		assertEquals(exportIds(f), first.causalLinks());
		assertEquals(2, first.causalLinks().size());
		CAMPFIRE.ingredients().forEach((item, count) ->
			assertEquals(count.intValue(), SettlementStockpile.insert(stock, new ItemStack(item, count))));
		SimulationEvent second = craftAndComplete(f, stock, ALICE, CAMPFIRE);
		assertTrue(second.causalLinks().isEmpty());
		assertUnknownRealInputs(second);
		assertEquals(2, stock.countItem(Items.CAMPFIRE));
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(0, stock.countItem(Items.STICK));
		assertEquals(0, stock.countItem(Items.COAL));
		assertEquals(0, f.quantity(ResourceType.WOOD));
		assertEquals(0, f.quantity(ResourceType.STICK));
		assertEquals(2, exportIds(f).size());
		f.state().world().assertConservation();
	}

	private static SettlementSimulationService.CraftingAttempt begin(Fixture f, UUID actor, SettlementMaterialCrafting.Recipe recipe) {
		return SettlementSimulationService.beginCrafting(f.data(), f.settlement(), actor, recipe.id(), recipe.output(), recipe.count());
	}

	private static SimulationEvent craftAndComplete(Fixture f, SimpleContainer stock, UUID actor, SettlementMaterialCrafting.Recipe recipe) {
		var attempt = begin(f, actor, recipe);
		assertTrue(SettlementMaterialCrafting.craft(stock, f.state(), recipe, actor, f.data()::setDirty));
		var completion = SettlementSimulationService.recordCrafting(f.data(), f.settlement(), actor, attempt).orElseThrow();
		return f.state().world().ledger().event(completion.eventId()).orElseThrow();
	}

	private static void assertUnknownRealInputs(SimulationEvent completed) {
		assertEquals("unknown", completed.context().get("preexistingInventoryProvenance"));
		assertEquals("current_attempt_direct_exports", completed.context().get("provenanceScope"));
		assertEquals("minecraft:campfire", completed.context().get("outputItem"));
		assertEquals("1", completed.context().get("outputCount"));
	}

	private static List<SimulationEvent> events(Fixture f, EventType type) {
		return f.state().world().ledger().events().stream().filter(event -> event.type() == type).toList();
	}

	private static List<Long> exportIds(Fixture f) {
		return events(f, EventType.EXTERNAL_EXPORT).stream().map(SimulationEvent::eventId).toList();
	}

	private static Fixture fixture(boolean skills) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crafting attempts", 0);
		var state = data.getOrCreateSimulation(settlement);
		for (UUID actor : List.of(ALICE, BOB)) {
			data.registerMember(settlement, actor, 0);
			state.mapResident(actor, false, 0);
		}
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.setSkillsEnabled(skills);
		return new Fixture(data, settlement);
	}

	private record Fixture(SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state() { return data.simulationOf(settlement).orElseThrow(); }
		void supply(ResourceType resource, long quantity) {
			state().world().importExternalBatch(resource, quantity, state().campOwner());
		}
		long quantity(ResourceType resource) { return state().world().inventoryTotals().getOrDefault(resource, 0L); }
	}
}
