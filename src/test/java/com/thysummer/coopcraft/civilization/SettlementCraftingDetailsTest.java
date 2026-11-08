package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCraftingDetailsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void completedCraftShowsRecordedScopeUnknownInventoryAndOutputEvenWithoutCauses() {
		for (List<Long> causes : List.of(List.<Long>of(), List.of(1L))) {
			var event = event(EventType.CRAFT_COMPLETED, context(), causes);
			var info = project(event);
			assertEquals(expected(), info.details());
			var row = SettlementEvents.row(event, info);
			assertEquals(10, row.getSiblings().size());
			for (int i = 0; i < info.details().size(); i++) {
				var rendered = translation(row.getSiblings().get(i * 2 + 1));
				assertEquals(info.details().get(i).label(), rendered.getKey());
				var value = (Component) rendered.getArgs()[0];
				if (i >= 2 && i <= 3) assertEquals(info.details().get(i).value(), translation(value).getKey());
				else assertEquals(info.details().get(i).value(), value.getString());
			}
		}
	}

	@Test
	void oldOrIncompleteCompletionNeverGuessesScopeInventoryOrHistoricalProductFromRecipe() {
		assertEquals(List.of(detail("actor", "resident"), detail("recipe", "block.minecraft.oak_planks")),
			project(event(EventType.CRAFT_COMPLETED, Map.of("recipe", "material_planks"), List.of())).details());
		for (String key : List.of("provenanceScope", "preexistingInventoryProvenance", "outputItem", "outputCount")) {
			var context = context();
			context.remove(key);
			var details = project(event(EventType.CRAFT_COMPLETED, context, List.of())).details();
			String omitted = key.equals("provenanceScope") ? "crafting_evidence"
				: key.equals("preexistingInventoryProvenance") ? "preexisting_inventory" : "crafting_output";
			assertFalse(details.stream().anyMatch(d -> d.label().equals(detail(omitted, "unused").label())));
			assertEquals(4, details.size());
		}
	}

	@Test
	void knownRecipeDoesNotReplaceTheRecordedHistoricalOutput() {
		var context = context();
		context.put("recipe", "material_planks");
		var details = project(event(EventType.CRAFT_COMPLETED, context, List.of())).details();
		assertEquals(detail("recipe", "block.minecraft.oak_planks"), details.get(1));
		assertEquals(detail("crafting_output", "minecraft:birch_planks × 12"), details.get(4));
	}

	@Test
	void unknownScopeAndUnrelatedEventsDoNotClaimDirectExportEvidence() {
		var context = context();
		context.put("provenanceScope", "complete_history");
		context.put("preexistingInventoryProvenance", "known");
		assertEquals(List.of(expected().get(0), expected().get(1), expected().get(4)),
			project(event(EventType.CRAFT_COMPLETED, context, List.of(1L))).details());
		assertEquals(expected().subList(0, 2), project(event(EventType.EXTERNAL_IMPORT, context(), List.of())).details());
	}

	@Test
	void malformedHistoricalOutputsAreOmittedWithoutLosingEvidenceScope() {
		for (String count : List.of("", " ", "0", "-1", "1.5", "many", "2147483648")) {
			var context = context();
			context.put("outputCount", count);
			assertEquals(expected().subList(0, 4), project(event(EventType.CRAFT_COMPLETED, context, List.of())).details());
		}
		for (String item : List.of("", " ", "a".repeat(96))) {
			var context = context();
			context.put("outputItem", item);
			assertEquals(expected().subList(0, 4), project(event(EventType.CRAFT_COMPLETED, context, List.of())).details());
		}
	}

	@Test
	void craftingDetailsHavePriorityAndStayInsideExistingSixDetailLimit() {
		var context = context();
		context.put("item", "extra");
		context.put("player", "extra player");
		context.put("reason", "extra reason");
		var details = project(event(EventType.CRAFT_COMPLETED, context, List.of())).details();
		assertEquals(SettlementOverviewPayload.MAX_EVENT_DETAILS, details.size());
		assertEquals(expected(), details.subList(0, 5));
		assertEquals(detail("item", "extra"), details.get(5));
		context.put("normId", "extra_rule");
		details = project(event(EventType.CRAFT_COMPLETED, context, List.of())).details();
		assertEquals(6, details.size());
		assertEquals(expected().subList(2, 5), details.subList(3, 6));
	}

	@Test
	void repeatedProjectionDoesNotCreateStateOrMutateSaveAndExistingLedger() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Crafting", 0);
		var event = event(EventType.CRAFT_COMPLETED, context(), List.of());
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		Civilization.toSimulationEventInfo(null, data, settlement, event);
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
		var state = data.getOrCreateSimulation(settlement);
		state.world().importExternalBatch(ResourceType.STONE, 2, state.campOwner());
		before = data.save(new CompoundTag(), null);
		var ledger = List.copyOf(state.world().ledger().events());
		data.setDirty(false);
		for (int i = 0; i < 3; i++) {
			SettlementEvents.row(event, Civilization.toSimulationEventInfo(null, data, settlement, event));
			SettlementEvents.messages(null, data, settlement, 5);
		}
		assertEquals(ledger, state.world().ledger().events());
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
	}

	@Test
	void bilingualLabelsAndTranslatedValuesExistWithMatchingArguments() throws Exception {
		for (String language : List.of("zh_cn", "en_us")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var detail : expected().subList(2, 5)) {
					String label = json.get(detail.label()).getAsString();
					assertFalse(label.isBlank());
					assertEquals(1, label.split("%s", -1).length - 1);
					if (detail.value().startsWith("coopcraft.")) {
						String value = json.get(detail.value()).getAsString();
						assertFalse(value.isBlank());
						assertFalse(value.contains("%s"));
					}
				}
			}
		}
	}

	private static Map<String, String> context() {
		// An unknown historical recipe with a different recorded output must remain historical fact.
		return new HashMap<>(Map.of("recipe", "historical_recipe", "provenanceScope", "current_attempt_direct_exports",
			"preexistingInventoryProvenance", "unknown", "outputItem", "minecraft:birch_planks", "outputCount", "12"));
	}

	private static List<SimulationEventDetail> expected() {
		return List.of(detail("actor", "resident"), detail("recipe", "historical_recipe"),
			detail("crafting_evidence", "coopcraft.simulation.crafting.current_attempt_direct_exports"),
			detail("preexisting_inventory", "coopcraft.simulation.crafting.provenance_unknown"),
			detail("crafting_output", "minecraft:birch_planks × 12"));
	}

	private static SimulationEvent event(EventType type, Map<String, String> context, List<Long> causes) {
		return new SimulationEvent(2, 1, type, List.of("resident"), List.of(), List.of(), context, causes);
	}

	private static SettlementOverviewPayload.SimulationEventInfo project(SimulationEvent event) {
		var data = new SettlementSavedData();
		return Civilization.toSimulationEventInfo(null, data, data.getOrCreate(BlockPos.ZERO, "Crafting", 0), event);
	}

	private static SimulationEventDetail detail(String label, String value) {
		return new SimulationEventDetail("coopcraft.simulation.detail." + label, value);
	}

	private static TranslatableContents translation(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}
}
