package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementRationsCommandTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private SettlementCommands.RationRow row(int id, OptionalLong nutrition) {
		return new SettlementCommands.RationRow(Component.literal("Resident " + id),
			new SettlementRations.Snapshot(new UUID(0, id), 7L, 5, 3, 2, nutrition, 61));
	}

	private TranslatableContents translation(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}

	@Test
	void ordinaryPlayersRouteRationsToReadHandlerWhileWritesStillRequireOp2() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		var actions = new ArrayList<String>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			actions.add(action);
			assertEquals("", option);
			return 1;
		}));
		for (int level : List.of(0, 1, 2)) {
			assertEquals(1, dispatcher.execute("coop settlement rations", level));
		}
		assertEquals(List.of("rations", "rations", "rations"), actions);
		for (int level : List.of(0, 1)) {
			for (String command : List.of("simulation survival true", "import stone 1", "export stone 1")) {
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement " + command, level));
			}
		}
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement rations reset", 2));
		var completions = dispatcher.getCompletionSuggestions(dispatcher.parse("coop settlement ", 0)).get();
		assertTrue(completions.getList().stream().anyMatch(suggestion -> suggestion.getText().equals("rations")));
	}

	@Test
	void rowPreservesNameUnitsNutritionHungerAndCoreDay() {
		var row = row(1, OptionalLong.of(18));
		var message = translation(SettlementCommands.rationMessage(row));
		assertEquals("coopcraft.command.settlement.rations_row", message.getKey());
		assertArrayEquals(new Object[] {row.name(), 3, 5, 2, 18L, 61, 7L}, message.getArgs());
		assertEquals(18L, row.snapshot().foodNutritionReceived().orElseThrow());
	}

	@Test
	void incompleteHistoryIsUnknownButKnownZeroRemainsZero() {
		var unknown = translation(SettlementCommands.rationMessage(row(1, OptionalLong.empty()))).getArgs();
		assertEquals("coopcraft.command.settlement.rations_unknown", translation((Component) unknown[4]).getKey());
		var known = translation(SettlementCommands.rationMessage(row(1, OptionalLong.of(0)))).getArgs();
		assertEquals(0L, known[4]);
	}

	@Test
	void filtersDeadAndOutOfRangeResidentsWithInclusive64BlockBoundary() {
		Vec3 center = new Vec3(0.5, 64.5, 0.5);
		assertTrue(SettlementCommands.rationVisible(true, center.add(64, 0, 0), center));
		assertFalse(SettlementCommands.rationVisible(true, center.add(64.001, 0, 0), center));
		assertFalse(SettlementCommands.rationVisible(false, center, center));
	}

	@Test
	void displayHasStableUuidOrderAnd32RowLimitWithoutMutatingInput() {
		var rows = new ArrayList<SettlementCommands.RationRow>();
		for (int i = 1; i <= 40; i++) rows.add(row(i, OptionalLong.of(0)));
		Collections.reverse(rows);
		var original = List.copyOf(rows);
		var visible = SettlementCommands.visibleRationRows(rows);
		assertEquals(32, visible.size());
		assertEquals(new UUID(0, 1), visible.getFirst().snapshot().villagerId());
		assertEquals(new UUID(0, 32), visible.getLast().snapshot().villagerId());
		assertEquals(original, rows);
		assertThrows(UnsupportedOperationException.class, () -> visible.clear());
		assertTrue(SettlementCommands.visibleRationRows(List.of()).isEmpty());
	}

	@Test
	void allRationKeysExistInBothLanguagesWithCorrectArgumentCounts() throws Exception {
		Map<String, Integer> keys = Map.of("rations_disabled", 0, "rations_header", 3,
			"rations_explanation", 0, "rations_row", 7, "rations_unknown", 0, "rations_truncated", 2);
		for (String language : List.of("zh_cn", "en_us")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				keys.forEach((key, count) -> {
					String fullKey = "coopcraft.command.settlement." + key;
					assertTrue(json.has(fullKey), language + ": " + fullKey);
					String value = json.get(fullKey).getAsString();
					assertFalse(value.isBlank());
					assertEquals(count.intValue(), value.split("%s", -1).length - 1, fullKey);
				});
			}
		}
	}
}
