package com.thysummer.coopcraft.civilization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProposalConversationEventDetailsTest {
	private static final String SPEAKER = "00000000-0000-0000-0000-000000000001";
	private static final String LISTENER = "00000000-0000-0000-0000-000000000002";
	private static final List<String> ACTORS = List.of("minecraft-person:" + SPEAKER, "minecraft-person:" + LISTENER);

	private static Map<String, String> context() {
		var context = new LinkedHashMap<String, String>();
		context.putAll(Map.of("source", "minecraft_proposal_conversation", "kind", "NORM_PROPOSAL",
			"informationId", "notice-1", "proposalId", "proposal-1", "sourceEventId", "2",
			"confidence", "1.0", "receivedConfidence", "0.85", "hoursPerParticipant", "1",
			"costTiming", "next_settled_day"));
		context.putAll(Map.of("campId", "camp-1", "settlement", "settlement-1",
			"speakerUuid", SPEAKER, "listenerUuid", LISTENER));
		return context;
	}

	private static SimulationEvent event(Map<String, String> context) {
		return event(EventType.INFORMATION_SHARED, ACTORS, List.of(), List.of(), context, List.of(2L));
	}

	private static SimulationEvent event(EventType type, List<String> actors, List<ResourceFlow> inputs,
		List<ResourceFlow> outputs, Map<String, String> context, List<Long> causes) {
		return new SimulationEvent(10, 7, type, actors, inputs, outputs, context, causes);
	}

	private static void rejects(SimulationEvent event) {
		assertTrue(ProposalConversationEventDetails.project(event, id -> id).isEmpty());
	}

	@Test void frozenNoticeRendersSixHistoricalDetailsWithoutProposalState() {
		var context = context();
		var event = event(context);
		context.put("proposalId", "later-edited");
		var details = ProposalConversationEventDetails.project(event, id -> id.equals(ACTORS.get(0)) ? "Ada" : "Bram");
		assertEquals(6, details.size());
		assertTrue(details.size() <= SettlementOverviewPayload.MAX_EVENT_DETAILS);
		assertEquals("coopcraft.simulation.detail.conversation_speaker", details.get(0).label());
		assertEquals(List.of("Ada", "Bram", "proposal-1", "2", "0.85",
			"coopcraft.simulation.conversation_cost.next_settled_day"), details.stream().map(d -> d.value()).toList());
		assertThrows(UnsupportedOperationException.class, details::clear);
		assertEquals(details, ProposalConversationEventDetails.project(event,
			id -> id.equals(ACTORS.get(0)) ? "Ada" : "Bram"));
	}

	@Test void everyRequiredFieldMustBePresentAndValid() {
		for (String key : context().keySet()) {
			var missing = context(); missing.remove(key); rejects(event(missing));
			var blank = context(); blank.put(key, ""); rejects(event(blank));
			var nulled = context(); nulled.put(key, null); rejects(event(nulled));
		}
		for (var badValue : List.of(Map.entry("kind", "RUMOR"), Map.entry("hoursPerParticipant", "2"),
			Map.entry("hoursPerParticipant", "1.0"), Map.entry("costTiming", "immediate"),
			Map.entry("speakerUuid", LISTENER), Map.entry("listenerUuid", "not-a-uuid"),
			Map.entry("sourceEventId", "0"), Map.entry("sourceEventId", "2.0"),
			Map.entry("sourceEventId", "9999999999999999999"), Map.entry("sourceEventId", "3"))) {
			var invalid = context(); invalid.put(badValue.getKey(), badValue.getValue()); rejects(event(invalid));
		}
	}

	@Test void confidenceMustBeFiniteReliableAndExactlyPropagated() {
		for (String key : List.of("confidence", "receivedConfidence")) {
			for (String value : List.of("NaN", "Infinity", "-Infinity", "-0.1", "1.1", "false", "0")) {
				var invalid = context(); invalid.put(key, value); rejects(event(invalid));
			}
		}
		var weak = context(); weak.put("confidence", "0.49");
		weak.put("receivedConfidence", Double.toString(.49 * .85)); rejects(event(weak));
		var mismatched = context(); mismatched.put("receivedConfidence", "0.8"); rejects(event(mismatched));
		var threshold = context(); threshold.put("confidence", "0.5"); threshold.put("receivedConfidence", "0.425");
		assertEquals(6, ProposalConversationEventDetails.project(event(threshold), id -> id).size());
	}

	@Test void actorsCauseAndNoResourceFlowsAreRequired() {
		for (List<String> actors : List.of(List.<String>of(), List.of(ACTORS.get(0)),
			List.of(ACTORS.get(0), ACTORS.get(0)), List.of("", ACTORS.get(1)),
			List.of(ACTORS.get(1), ACTORS.get(0)), List.of(ACTORS.get(0), ACTORS.get(1), "extra"))) {
			rejects(event(EventType.INFORMATION_SHARED, actors, List.of(), List.of(), context(), List.of(2L)));
		}
		for (List<Long> causes : List.of(List.<Long>of(), List.of(3L), List.of(2L, 3L)))
			rejects(event(EventType.INFORMATION_SHARED, ACTORS, List.of(), List.of(), context(), causes));
		var flow = new ResourceFlow("batch", ResourceType.values()[0], 1, 0, "a", "b");
		rejects(event(EventType.INFORMATION_SHARED, ACTORS, List.of(flow), List.of(), context(), List.of(2L)));
		rejects(event(EventType.INFORMATION_SHARED, ACTORS, List.of(), List.of(flow), context(), List.of(2L)));
	}

	@Test void unmarkedLegacyEventsAndOtherEventTypesRemainOutsideThisProjection() {
		assertFalse(ProposalConversationEventDetails.isConversation(null));
		rejects(null);
		var legacy = context(); legacy.remove("source");
		assertFalse(ProposalConversationEventDetails.isConversation(event(legacy))); rejects(event(legacy));
		var unrelated = event(EventType.NORM_PROPOSED, ACTORS, List.of(), List.of(), context(), List.of(2L));
		assertFalse(ProposalConversationEventDetails.isConversation(unrelated)); rejects(unrelated);
		assertTrue(ProposalConversationEventDetails.project(event(context()), null).isEmpty());
	}

	@Test void displayedValuesAreBoundedAndNullNamesDoNotLeak() {
		var longId = context(); longId.put("proposalId", "p".repeat(200));
		var details = ProposalConversationEventDetails.project(event(longId), id -> "n".repeat(200));
		assertEquals(96, details.get(0).value().length());
		assertEquals(96, details.get(2).value().length());
		assertTrue(details.stream().allMatch(d -> d.value().length() <= 96));
		assertEquals("?", ProposalConversationEventDetails.project(event(context()), id -> null).get(0).value());
	}

	@Test void overviewReturnsMarkedProjectionBeforeAnyMutableProposalLookup() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/Civilization.java"));
		int method = source.indexOf("static SettlementOverviewPayload.SimulationEventInfo toSimulationEventInfo(");
		int dispatch = source.indexOf("if (ProposalConversationEventDetails.isConversation(event))", method);
		int close = source.indexOf("\n\t\t}", dispatch);
		assertTrue(method >= 0 && dispatch > method && close > dispatch);
		String branch = source.substring(dispatch, close);
		assertTrue(branch.contains("return new SettlementOverviewPayload.SimulationEventInfo("));
		assertTrue(branch.contains("ProposalConversationEventDetails.project(event, actor -> residentName(level, actor))"));
		assertTrue(close < source.indexOf("SettlementSimulationState state =", method));
		assertTrue(close < source.indexOf("state.world().proposal(", method));
	}
}
