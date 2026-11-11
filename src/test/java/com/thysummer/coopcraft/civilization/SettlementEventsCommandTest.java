package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementEventsCommandTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private TranslatableContents translation(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}

	@Test
	void actualTreeSupportsReadPermissionDefaultAndBoundsButRejectsOtherArguments() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			assertEquals("events", action);
			return SettlementCommands.eventCount(context, option);
		}));
		for (int level : List.of(0, 1, 2)) {
			assertEquals(5, dispatcher.execute("coop settlement events", level));
			for (int count : List.of(1, 5, 12)) {
				assertEquals(count, dispatcher.execute("coop settlement events " + count, level));
			}
			for (String invalid : List.of("0", "-1", "13", "2147483648", "1.5", "all", "1 2")) {
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement events " + invalid, level));
			}
		}
		for (int level : List.of(0, 1)) {
			for (String write : List.of("simulation survival true", "import stone 1", "export stone 1")) {
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement " + write, level));
			}
			var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("coop settlement ev", level)).get();
			assertEquals(List.of("events"), suggestions.getList().stream().map(s -> s.getText()).toList());
		}
	}

	@Test
	void absentAndEmptySimulationReadDoNotCreateStateOrDirtySave() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Empty", 0);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		for (int i = 0; i < 3; i++) {
			var lines = SettlementEvents.messages(null, data, settlement, 5);
			assertEquals(1, lines.size());
			assertEquals("coopcraft.command.settlement.events_empty", translation(lines.getFirst()).getKey());
		}
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
		data.getOrCreateSimulation(settlement);
		before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		assertEquals("coopcraft.command.settlement.events_empty",
			translation(SettlementEvents.messages(null, data, settlement, 12).getFirst()).getKey());
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
		for (int invalid : List.of(-1, 0, 13, Integer.MAX_VALUE)) {
			assertThrows(IllegalArgumentException.class, () -> SettlementEvents.messages(null, data, settlement, invalid));
		}
	}

	@Test
	void disabledHistoryUsesBoundedChronologicalTailAndRepeatedReadsPreserveEntireSave() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "History", 0);
		var state = data.getOrCreateSimulation(settlement);
		state.setSurvivalEnabled(true, 0);
		for (int i = 1; i <= 20; i++) state.world().importExternalBatch(ResourceType.STONE, i, state.campOwner());
		state.setSurvivalEnabled(false, 24000);
		var before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		for (int count : List.of(1, 5, 12)) {
			var expected = state.world().ledger().recentEvents(count);
			var lines = SettlementEvents.messages(null, data, settlement, count);
			assertEquals(count + 1, lines.size());
			assertEquals(lines, SettlementEvents.messages(null, data, settlement, count));
			for (int i = 0; i < count; i++) {
				var args = translation(lines.get(i + 1)).getArgs();
				assertEquals(expected.get(i).eventId(), args[0]);
				assertEquals(expected.get(i).day(), args[1]);
				assertEquals("coopcraft.simulation.event.EXTERNAL_IMPORT", translation((Component) args[2]).getKey());
				assertEquals("coopcraft.simulation.resource.STONE", translation((Component) args[3]).getKey());
				assertEquals(expected.get(i).outputs().getFirst().quantity(), args[4]);
			}
		}
		assertFalse(state.survivalEnabled());
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
	}

	@Test
	void directCausesAreBoundedAndAllSixRecordedRefusalDetailsRemainLocalized() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Refusal", 0);
		var event = new SimulationEvent(8, 4, EventType.SHARE_REFUSED, List.of("resident"), List.of(), List.of(),
			Map.of("reason", "daily-unit-cap-exhausted", "allocatedUnits", "3", "cap", "3",
				"allocationBasis", "item_units", "hunger", "46", "neededNutrition", "46", "availableNutrition", "360"),
			List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L));
		var info = Civilization.toSimulationEventInfo(null, data, settlement, event);
		var row = SettlementEvents.row(event, info);
		var causes = (Component) translation(row).getArgs()[5];
		assertEquals(Component.literal("#1, #2, #3, #4").getContents(), causes.getContents());
		assertArrayEquals(new Object[] {3}, translation(causes.getSiblings().getFirst()).getArgs());
		assertEquals(6, info.details().size());
		assertEquals(12, row.getSiblings().size());
		for (int i = 0; i < 6; i++) {
			var rendered = translation(row.getSiblings().get(i * 2 + 1));
			assertEquals(info.details().get(i).label(), rendered.getKey());
			var value = (Component) rendered.getArgs()[0];
			if (i == 1) assertEquals(info.details().get(i).value(), translation(value).getKey());
			else assertEquals(info.details().get(i).value(), value.getString());
		}
		var fourCauses = new SimulationEvent(5, 0, EventType.EXTERNAL_IMPORT, List.of(), List.of(), List.of(),
			Map.of(), List.of(1L, 2L, 3L, 4L));
		assertEquals("#1, #2, #3, #4", SettlementEvents.causes(fourCauses).getString());
		assertTrue(SettlementEvents.causes(fourCauses).getSiblings().isEmpty());
		var noCauses = new SimulationEvent(1, 0, EventType.EXTERNAL_IMPORT, List.of(), List.of(), List.of(), Map.of(), List.of());
		assertEquals("coopcraft.command.settlement.events_none", translation(SettlementEvents.causes(noCauses)).getKey());
	}

	@Test
	void interruptedMealEventUsesFrozenDailyTotalRatherThanTheLastMealSize() {
		var f = SettlementFoodRestockTest.fixture(1);
		SettlementFoodRestockTest.eat(f);
		f.state().world().importExternalBatch(ResourceType.FISH, 1, f.state().campOwner());
		SettlementFoodRestockTest.eat(f);
		var event = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.COMMON_MEAL_INTERRUPTED).toList().getLast();
		assertEquals("1", event.context().get("allocatedUnits"));
		assertEquals("2", event.context().get("dailyAllocatedUnits"));
		var expected = Civilization.toSimulationEventInfo(null, f.data(), f.settlement(), event);
		assertTrue(expected.details().stream().anyMatch(d -> d.label().equals("coopcraft.simulation.detail.allocation_units") && d.value().equals("2/3")));
		assertTrue(expected.details().stream().anyMatch(d -> d.value().equals("coopcraft.simulation.reason.common-food-exhausted")));
		f.state().world().importExternalBatch(ResourceType.FISH, 1, f.state().campOwner());
		SettlementFoodRestockTest.eat(f);
		var before = SettlementFoodRestockTest.save(f); f.data().setDirty(false);
		assertEquals(expected, Civilization.toSimulationEventInfo(null, f.data(), f.settlement(), event));
		assertEquals(before, SettlementFoodRestockTest.save(f)); assertFalse(f.data().isDirty());
		f.state().world().assertConservation();
	}

	@Test
	void everyLedgerEventAndInterruptedMealReasonHaveBothLanguageNames() throws Exception {
		for (String language : List.of("zh_cn", "en_us")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				for (EventType type : EventType.values()) {
					String key = "coopcraft.simulation.event." + type.name();
					assertTrue(json.has(key), key); assertFalse(json.get(key).getAsString().isBlank(), key);
				}
				String reason = "coopcraft.simulation.reason.common-food-exhausted";
				assertTrue(json.has(reason)); assertFalse(json.get(reason).getAsString().isBlank());
			}
		}
	}

	@Test
	void newTranslationsExistWithMatchingArgumentCounts() throws Exception {
		var keys = Map.of("events_empty", 1, "events_header", 2, "events_none", 0, "events_row", 6, "events_more_causes", 1);
		for (String language : List.of("zh_cn", "en_us")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				keys.forEach((key, count) -> {
					String value = json.get("coopcraft.command.settlement." + key).getAsString();
					assertFalse(value.isBlank());
					assertEquals(count.intValue(), value.split("%s", -1).length - 1);
				});
			}
		}
	}
}
