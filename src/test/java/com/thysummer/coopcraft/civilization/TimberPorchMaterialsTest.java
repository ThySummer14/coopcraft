package com.thysummer.coopcraft.civilization;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure material/job contract tests: placement callbacks do not claim to test a Minecraft world. */
class TimberPorchMaterialsTest {
	private static final ResourceLocation BLUEPRINT = ResourceLocation.parse("coopcraft:timber_porch_cabin_v1");
	private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000061");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void actualBlueprintHas151PaidInstructionsAndThreeFreeContinuations() throws Exception {
		ConstructionPlan plan = plan();
		assertEquals(154, plan.instructions().size());
		assertEquals(151, plan.instructions().stream().filter(ConstructionInstruction::consume).count());
		assertEquals(Map.of(Items.OAK_PLANKS, 122, Items.OAK_LOG, 24, Items.OAK_DOOR, 1,
			Items.WHITE_BED, 2, Items.TORCH, 2), plan.materials());
		assertEquals(plan.materials(), ConstructionPlanner.remainingMaterials(plan, 0));
	}

	@Test
	void coalAndCharcoalPrepareAllMaterialsWithoutSpendingStructuralLogs() throws Exception {
		for (Item fuel : new Item[] { Items.COAL, Items.CHARCOAL }) {
			ConstructionPlan plan = plan();
			SimpleContainer stock = rawStock(6, fuel);
			Map<SettlementMaterialCrafting.Recipe, Integer> batches = craftUntilBlocked(stock, null, plan);
			assertPrepared(stock, null, plan);
			assertEquals(Map.of(SettlementMaterialCrafting.Recipe.PLANKS, 34,
				SettlementMaterialCrafting.Recipe.STICKS, 1, SettlementMaterialCrafting.Recipe.DOOR, 1,
				SettlementMaterialCrafting.Recipe.BED, 2,
				fuel == Items.COAL ? SettlementMaterialCrafting.Recipe.TORCH_COAL
					: SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL, 1), batches);
			assertEquals(24, stock.countItem(Items.OAK_LOG));
			assertEquals(0, stock.countItem(Items.WHITE_WOOL));
			assertEquals(0, stock.countItem(fuel));
		}
	}

	@Test
	void sequentialConsumptionSurvivesEveryCheckpointAndFailedPlacement() throws Exception {
		ConstructionPlan plan = plan();
		SimpleContainer stock = rawStock(6, Items.COAL);
		craftUntilBlocked(stock, null, plan);
		assertPrepared(stock, null, plan);
		ConstructionJob job = ConstructionJob.start(plan, new BlockPos(12, 64, -9));
		Map<Item, Integer> consumed = new HashMap<>();
		AtomicInteger callbacks = new AtomicInteger();
		int freeParts = 0;
		for (ConstructionInstruction instruction : plan.instructions()) {
			// Includes checkpoints between paid and free halves; only the job is serialized here.
			ConstructionJob restored = ConstructionJob.load(job.save());
			assertEquals(job, restored);
			assertEquals("ready", restored.compatibilityBlocker(plan));
			job = restored;
			Map<Item, Integer> before = inventory(stock);
			assertFalse(ConstructionMaterials.place(stock, null, instruction.item(), instruction.consume(),
				job, ACTOR, () -> fail("Real stock must not export"), () -> {
					callbacks.incrementAndGet();
					return false;
				}));
			assertEquals(before, inventory(stock), "Failed placement must not consume any item");
			assertEquals(restored, job, "The caller must not advance a failed instruction");
			assertTrue(ConstructionMaterials.place(stock, null, instruction.item(), instruction.consume(),
				job, ACTOR, () -> fail("Real stock must not export"), () -> {
					assertEquals(before, inventory(stock), "Consume only after successful placement");
					callbacks.incrementAndGet();
					return true;
				}));
			if (instruction.consume()) {
				consumed.merge(instruction.item(), 1, Integer::sum);
				Map<Item, Integer> expected = new HashMap<>(before);
				expected.compute(instruction.item(), (item, count) -> count == 1 ? null : count - 1);
				assertEquals(expected, inventory(stock));
			} else {
				freeParts++;
				assertEquals(before, inventory(stock), "Door upper/bed head is free even without stock");
			}
			job = job.advance();
			assertEquals(151 - consumed.values().stream().mapToInt(Integer::intValue).sum(),
				ConstructionPlanner.remainingMaterials(plan, job.nextIndex()).values().stream()
					.mapToInt(Integer::intValue).sum());
		}
		assertEquals(plan.materials(), consumed);
		assertEquals(3, freeParts);
		assertEquals(308, callbacks.get());
		assertEquals(154, job.nextIndex());
		assertEquals(job, ConstructionJob.load(job.save()));
		assertEquals("invalid_index", job.compatibilityBlocker(plan));
		assertEquals(Map.of(Items.OAK_PLANKS, 2, Items.OAK_DOOR, 2, Items.TORCH, 2, Items.STICK, 7), inventory(stock));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null,
			ConstructionPlanner.remainingMaterials(plan, job.nextIndex())).isEmpty());
	}

	@Test
	void missingWoolCannotCreateSecondBedOrAdvanceItsPaidInstruction() throws Exception {
		ConstructionPlan plan = plan();
		SimpleContainer stock = rawStock(5, Items.COAL);
		craftUntilBlocked(stock, null, plan);
		assertFalse(ConstructionMaterials.hasAll(stock, null, plan));
		assertEquals(1, stock.countItem(Items.WHITE_BED));
		assertEquals(2, stock.countItem(Items.WHITE_WOOL));
		assertStopsAtMissingItem(stock, plan, Items.WHITE_BED);
	}

	@Test
	void missingFuelCannotCreateTorchesOrAdvanceTheirPaidInstruction() throws Exception {
		ConstructionPlan plan = plan();
		SimpleContainer stock = rawStock(6, null);
		craftUntilBlocked(stock, null, plan);
		assertFalse(ConstructionMaterials.hasAll(stock, null, plan));
		assertEquals(0, stock.countItem(Items.TORCH));
		assertEquals(8, stock.countItem(Items.STICK));
		assertStopsAtMissingItem(stock, plan, Items.TORCH);
	}

	@Test
	void abstractWoodExportsOnlyCraftInputsAndRetainsFailedStructuralPlacement() throws Exception {
		ConstructionPlan plan = plan();
		SettlementSimulationState state = SettlementSimulationState.create(
			new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Timber materials", 0));
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.world().importExternalBatch(ResourceType.WOOD, 59, state.campOwner());
		SimpleContainer stock = rawStock(6, Items.COAL);
		stock.removeItemType(Items.OAK_LOG, 59);
		craftUntilBlocked(stock, state, plan);
		assertPrepared(stock, state, plan);
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(24, abstractWood(state));
		int logIndex = 0;
		while (plan.instructions().get(logIndex).item() != Items.OAK_LOG) logIndex++;
		ConstructionJob job = new ConstructionJob(plan.id(), BlockPos.ZERO, logIndex, plan.fingerprint());
		AtomicInteger dirty = new AtomicInteger();
		assertFalse(ConstructionMaterials.place(stock, state, Items.OAK_LOG, true, job, ACTOR,
			dirty::incrementAndGet, () -> false));
		assertEquals(1, stock.countItem(Items.OAK_LOG));
		assertEquals(23, abstractWood(state));
		assertEquals(24, ConstructionMaterials.available(stock, state, Items.OAK_LOG));
		assertEquals(job, ConstructionJob.load(job.save()));
		assertTrue(ConstructionMaterials.place(stock, state, Items.OAK_LOG, true,
			ConstructionJob.load(job.save()), ACTOR, dirty::incrementAndGet, () -> true));
		assertEquals(1, dirty.get(), "Retry must reuse the exported unit");
		assertEquals(0, stock.countItem(Items.OAK_LOG));
		assertEquals(23, abstractWood(state));
		state.world().assertConservation();
	}

	private static ConstructionPlan plan() throws Exception {
		try (InputStream input = TimberPorchMaterialsTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/timber_porch_cabin_v1.json")) {
			assertNotNull(input);
			return ConstructionPlan.fromJson(BLUEPRINT,
				JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject());
		}
	}

	private static SimpleContainer rawStock(int wool, Item fuel) {
		SimpleContainer stock = new SimpleContainer(27);
		assertEquals(59, SettlementStockpile.insert(stock, new ItemStack(Items.OAK_LOG, 59)));
		assertEquals(wool, SettlementStockpile.insert(stock, new ItemStack(Items.WHITE_WOOL, wool)));
		if (fuel != null) assertEquals(1, SettlementStockpile.insert(stock, new ItemStack(fuel)));
		return stock;
	}

	private static Map<SettlementMaterialCrafting.Recipe, Integer> craftUntilBlocked(
		SimpleContainer stock, SettlementSimulationState state, ConstructionPlan plan
	) {
		Map<SettlementMaterialCrafting.Recipe, Integer> batches = new EnumMap<>(SettlementMaterialCrafting.Recipe.class);
		Map<Item, Integer> demand = ConstructionPlanner.remainingMaterials(plan, 0);
		for (int attempts = 0; attempts < 100; attempts++) {
			var recipe = SettlementMaterialCrafting.chooseRecipe(stock, state, demand);
			if (recipe.isEmpty()) return batches;
			assertTrue(SettlementMaterialCrafting.craft(stock, state, recipe.get(), ACTOR, () -> {}));
			batches.merge(recipe.get(), 1, Integer::sum);
		}
		fail("Demand-driven production must terminate within 100 batches");
		return batches;
	}

	private static void assertPrepared(SimpleContainer stock, SettlementSimulationState state, ConstructionPlan plan) {
		assertTrue(ConstructionMaterials.hasAll(stock, state, plan));
		assertEquals(124, stock.countItem(Items.OAK_PLANKS));
		assertEquals(3, stock.countItem(Items.OAK_DOOR));
		assertEquals(2, stock.countItem(Items.WHITE_BED));
		assertEquals(4, stock.countItem(Items.TORCH));
		assertEquals(7, stock.countItem(Items.STICK));
	}

	private static void assertStopsAtMissingItem(SimpleContainer stock, ConstructionPlan plan, Item missing) {
		ConstructionJob job = ConstructionJob.start(plan, BlockPos.ZERO);
		for (ConstructionInstruction instruction : plan.instructions()) {
			Map<Item, Integer> before = inventory(stock);
			AtomicInteger callbacks = new AtomicInteger();
			boolean placed = ConstructionMaterials.place(stock, null, instruction.item(), instruction.consume(),
				job, ACTOR, () -> fail("No abstract resources"), () -> {
					callbacks.incrementAndGet();
					return true;
				});
			if (!placed) {
				assertEquals(missing, instruction.item());
				assertTrue(instruction.consume());
				assertEquals(0, callbacks.get(), "Missing material must block before placement");
				assertEquals(before, inventory(stock));
				assertEquals(job, ConstructionJob.load(job.save()));
				assertEquals("ready", job.compatibilityBlocker(plan));
				return;
			}
			job = job.advance();
		}
		fail("Incomplete supply must not finish the blueprint");
	}

	private static Map<Item, Integer> inventory(SimpleContainer stock) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			ItemStack stack = stock.getItem(slot);
			if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
		}
		return counts;
	}

	private static long abstractWood(SettlementSimulationState state) {
		return state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == ResourceType.WOOD).mapToLong(batch -> batch.quantity()).sum();
	}
}
