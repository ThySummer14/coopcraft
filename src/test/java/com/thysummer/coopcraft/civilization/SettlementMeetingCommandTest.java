package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementMeetingCommandTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void ordinaryPlayersMayInspectAndOrganizeButCannotChooseAPositionOrChangeSimulationModes() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		var routed = new ArrayList<String>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			routed.add(action + ":" + option);
			return 1;
		}));
		for (int permission : List.of(0, 1, 2)) {
			assertEquals(1, dispatcher.execute("coop settlement meeting", permission));
			assertEquals(1, dispatcher.execute("coop settlement meeting hold", permission));
			for (String playerVote : List.of("vote", "support", "oppose", "adopt", "hold support")) {
				assertThrows(CommandSyntaxException.class,
					() -> dispatcher.execute("coop settlement meeting " + playerVote, permission));
			}
		}
		assertEquals(List.of("meeting:", "meeting:hold", "meeting:", "meeting:hold", "meeting:", "meeting:hold"), routed);
		for (int permission : List.of(0, 1)) {
			for (String command : List.of("simulation survival true", "simulation norms true")) {
				assertThrows(CommandSyntaxException.class,
					() -> dispatcher.execute("coop settlement " + command, permission));
			}
		}
		var completions = dispatcher.getCompletionSuggestions(dispatcher.parse("coop settlement meeting ", 0)).get();
		assertEquals(List.of("hold"), completions.getList().stream().map(suggestion -> suggestion.getText()).toList());
	}

	@Test
	void meetingCommandAndOutcomeTranslationsHaveExactlyTheArgumentsTheHandlersProvide() throws Exception {
		for (String locale : List.of("en_us", "zh_cn")) {
			var json = translations(locale);
			for (String status : List.of("disabled", "day_pending", "store_unavailable", "organizer_unavailable", "ready",
				"held", "already_held", "no_proposal", "no_informed_resident", "insufficient_participants", "invalid_proposal", "used", "unused")) {
				assertArguments(json, locale, "coopcraft.command.settlement.meeting_" + status, 0);
			}
			for (var entry : Map.of("day", 2, "summary", 3, "location", 1, "agenda", 1, "outcome", 1).entrySet()) {
				assertArguments(json, locale, "coopcraft.command.settlement.meeting_" + entry.getKey(), entry.getValue());
			}
			for (String outcome : List.of("deliberating", "adopted", "revised", "rejected", "expired")) {
				assertArguments(json, locale, "coopcraft.simulation.meeting." + outcome, 0);
			}
		}
	}

	@Test
	void everyMeetingDetailIncludesItsValuePlaceholderInBothLanguages() throws Exception {
		for (String locale : List.of("en_us", "zh_cn")) {
			var json = translations(locale);
			for (String label : List.of("rule", "meeting_location", "meeting_attendees", "meeting_tally", "meeting_round_cost",
				"meeting_deliberating", "meeting_adopted", "meeting_revised", "meeting_rejected", "meeting_expired")) {
				assertArguments(json, locale, "coopcraft.simulation.detail." + label, 1);
			}
		}
	}

	private static com.google.gson.JsonObject translations(String locale) throws Exception {
		try (var input = SettlementMeetingCommandTest.class.getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
			assertNotNull(input);
			return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static void assertArguments(com.google.gson.JsonObject json, String locale, String key, int count) {
		assertTrue(json.has(key), locale + ": " + key);
		String value = json.get(key).getAsString();
		assertFalse(value.isBlank(), locale + ": " + key);
		assertEquals(count, value.split("%s", -1).length - 1, locale + ": " + key);
	}
}
