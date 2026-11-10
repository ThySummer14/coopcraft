package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

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
		assertEquals(List.of("autonomy", "hold"), completions.getList().stream().map(suggestion -> suggestion.getText()).toList());
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

	@Test
	void actualFeedbackComponentsHaveMatchingBilingualArgumentsIncludingNestedReasons() throws Exception {
		var discussion = new NormSystem.MeetingResult("ready", "proposal", 10, 2, 0, "");
		var effects = new SettlementMeetingFeedback.Effects(true, true, 3, 2, false, true, false);
		var allMessages = new ArrayList<Component>();
		for (String rule : List.of(Norm.NEED_FIRST_COMMON_STORE, Norm.CARE_FIRST_COMMON_STORE)) {
			var agenda = new SettlementMeetingFeedback.Agenda(rule, 2, rule.equals(Norm.NEED_FIRST_COMMON_STORE) ? 3 : 0, true);
			var snapshot = new SettlementMeetingFeedback.Snapshot(Optional.of(agenda), effects, List.of());
			var messages = SettlementMeetingFeedback.messages(new SettlementMeetings.Result("ready", 0, Long.MIN_VALUE, "", discussion, snapshot));
			allMessages.addAll(messages);
			assertTrue(messages.stream().noneMatch(component -> key(component).contains("tally")), "Preview cannot predict votes");
			var change = messages.stream().filter(component -> key(component).endsWith("meeting_parameter_change")).findFirst().orElseThrow();
			Object[] args = ((TranslatableContents) change.getContents()).getArgs();
			assertEquals(agenda.currentCap(), args[0]);
			assertEquals(2, args[2]);
			assertEquals((long) agenda.proposedCap() - agenda.currentCap(), args[3]);
		}
		var held = new NormSystem.MeetingResult("held", "proposal", 10, 2, 1, "deliberating", 1);
		for (String reason : List.of("insufficient-participation", "insufficient-support", "household-opposition", "insufficient-supervision", "deliberating")) {
			var snapshot = new SettlementMeetingFeedback.Snapshot(Optional.empty(), effects, List.of(
				new SimulationEventDetail("coopcraft.simulation.detail.meeting_tally", "2/10; 1/1"),
				new SimulationEventDetail("coopcraft.simulation.detail.meeting_deliberating", "coopcraft.simulation.reason." + reason)));
			var messages = SettlementMeetingFeedback.messages(new SettlementMeetings.Result("held", 0, 0, "", held, snapshot));
			allMessages.addAll(messages);
			Component reasonLine = messages.stream().filter(component -> key(component).endsWith("meeting_deliberating")).findFirst().orElseThrow();
			Component reasonArgument = assertInstanceOf(Component.class, ((TranslatableContents) reasonLine.getContents()).getArgs()[0]);
			assertEquals("coopcraft.simulation.reason." + reason, key(reasonArgument));
		}
		for (var variant : List.of(
			new SettlementMeetingFeedback.Effects(false, true, 3, 0, false, false, false),
			new SettlementMeetingFeedback.Effects(true, false, 3, 0, false, false, false),
			new SettlementMeetingFeedback.Effects(true, true, 2, 0, true, false, true))) {
			allMessages.addAll(SettlementMeetingFeedback.messages(new SettlementMeetings.Result("held", 0, 0, "", held,
				new SettlementMeetingFeedback.Snapshot(Optional.empty(), variant, List.of()))));
		}
		var adoption = SettlementMeetingFeedback.messages(new SettlementMeetings.Result("held", 0, 0, "", held,
			new SettlementMeetingFeedback.Snapshot(Optional.empty(), effects, List.of(
				new SimulationEventDetail("coopcraft.simulation.detail.meeting_adopted", "0 → 2")))));
		allMessages.addAll(adoption);
		var change = adoption.stream().filter(component -> key(component).endsWith("meeting_frozen_change")).findFirst().orElseThrow();
		assertEquals("coopcraft.command.settlement.meeting_unestablished",
			key(assertInstanceOf(Component.class, ((TranslatableContents) change.getContents()).getArgs()[0])),
			"A previously unestablished norm must not claim the real baseline was zero");
		for (String locale : List.of("en_us", "zh_cn")) {
			var json = translations(locale);
			for (Component component : allMessages) assertComponentArguments(json, locale, component);
		}
	}

	private static String key(Component component) {
		return component.getContents() instanceof TranslatableContents translated ? translated.getKey() : "";
	}

	private static void assertComponentArguments(com.google.gson.JsonObject json, String locale, Component component) {
		if (component.getContents() instanceof TranslatableContents translated) {
			assertArguments(json, locale, translated.getKey(), translated.getArgs().length);
			for (Object argument : translated.getArgs()) {
				if (argument instanceof Component child) assertComponentArguments(json, locale, child);
			}
		}
		for (Component sibling : component.getSiblings()) assertComponentArguments(json, locale, sibling);
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
