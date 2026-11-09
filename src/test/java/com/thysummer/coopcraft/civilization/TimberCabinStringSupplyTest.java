package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real material, job and ledger services; no server, Goal or world placement is run. */
class TimberCabinStringSupplyTest {
	private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000055");
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void eitherPublishedCabinCanConsumeStringInsteadOfWoolAndKeepExactLeftovers() throws Exception {
		for (String name : new String[] {"timber_porch_cabin_v1", "timber_side_porch_cabin_v1"}) {
			ConstructionPlan plan = plan(name);
			SimpleContainer stock = new SimpleContainer(27);
			stock.setItem(0, new ItemStack(Items.OAK_LOG, 59));
			stock.setItem(1, new ItemStack(Items.STRING, 24));
			stock.setItem(2, new ItemStack(Items.COAL));
			int woolBatches = 0;
			for (int batches = 0; ; batches++) {
				assertTrue(batches < 100, "One cabin's production must terminate");
				var recipe = SettlementMaterialCrafting.chooseRecipe(stock, null, plan.materials());
				if (recipe.isEmpty()) break;
				if (recipe.get() == SettlementMaterialCrafting.Recipe.WOOL) woolBatches++;
				assertTrue(SettlementMaterialCrafting.craft(stock, null, recipe.get(), ACTOR, () -> {}));
			}
			assertEquals(6, woolBatches);
			assertEquals(0, stock.countItem(Items.STRING));
			assertEquals(0, stock.countItem(Items.WHITE_WOOL));
			assertTrue(ConstructionMaterials.hasAll(stock, null, plan));
			assertTrue(SettlementWoolSupply.inspect(plan.materials(), stock).isEmpty());
			ConstructionJob job = ConstructionJob.start(plan, BlockPos.ZERO);
			for (ConstructionInstruction instruction : plan.instructions()) {
				job = ConstructionJob.load(job.save());
				assertEquals("ready", job.compatibilityBlocker(plan));
				assertTrue(ConstructionMaterials.place(stock, null, instruction.item(), instruction.consume(),
					job, ACTOR, () -> fail("No abstract export"), () -> true));
				job = job.advance();
			}
			assertEquals(plan.instructions().size(), job.nextIndex());
			assertEquals(2, stock.countItem(Items.OAK_PLANKS));
			assertEquals(2, stock.countItem(Items.OAK_DOOR));
			assertEquals(2, stock.countItem(Items.TORCH));
			assertEquals(7, stock.countItem(Items.STICK));
			assertEquals(0, stock.countItem(Items.WHITE_BED));
			assertEquals(0, stock.countItem(Items.OAK_LOG));
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null,
				ConstructionPlanner.remainingMaterials(plan, job.nextIndex())).isEmpty());
		}
	}

	@Test
	void physicalWoolBatchUsesExistingCraftAndSkillEventsWithoutDuplicateAbstractProducts() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "String workshop", 0);
		data.registerMember(settlement, ACTOR, 0);
		var state = data.getOrCreateSimulation(settlement);
		state.mapResident(ACTOR, false, 0);
		state.setSurvivalEnabled(true, 0);
		state.setProductionEnabled(true);
		state.setSkillsEnabled(true);
		var initial = Map.copyOf(state.world().inventoryTotals());
		var stock = new SimpleContainer(3);
		stock.setItem(0, new ItemStack(Items.STRING, 4));
		stock.setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
		var recipe = SettlementMaterialCrafting.chooseRecipe(stock, state, Map.of(Items.WHITE_BED, 1)).orElseThrow();
		assertEquals(SettlementMaterialCrafting.Recipe.WOOL, recipe);
		var attempt = SettlementSimulationService.beginCrafting(data, settlement, ACTOR,
			recipe.id(), recipe.output(), recipe.count());
		assertTrue(SettlementMaterialCrafting.craft(stock, state, recipe, ACTOR, data::setDirty));
		assertTrue(SettlementSimulationService.recordCrafting(data, settlement, ACTOR, attempt).isPresent());
		assertTrue(SettlementSimulationService.recordCrafting(data, settlement, ACTOR, attempt).isEmpty());
		assertEquals(1, stock.countItem(Items.WHITE_WOOL));
		assertEquals(0, stock.countItem(Items.STRING));
		assertEquals(initial, state.world().inventoryTotals());
		assertEquals(1, state.world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).count());
		assertEquals(1, state.world().ledger().events().stream().filter(e -> e.type() == EventType.SKILL_PRACTICED).count());
		assertEquals(0, state.world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_IMPORT).count());
		state.world().assertConservation();
	}

	private static ConstructionPlan plan(String name) throws Exception {
		try (var input = TimberCabinStringSupplyTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json")) {
			assertNotNull(input);
			return ConstructionPlan.fromJson(ResourceLocation.fromNamespaceAndPath("coopcraft", name),
				JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject());
		}
	}
}
