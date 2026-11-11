package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventInfo;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementMeetingDetailsTest {
	@Test
	void frozenMeetingDisplaysActualAttendeesDenominatorVotesRoundCostAndReasonWithinSixDetails() {
		SimulationEvent event = event(EventType.NORM_DELIBERATED, evidence("deliberating"));

		List<SimulationEventDetail> details = SettlementMeetingDetails.project(event, actors -> "Ada, Bo");

		assertEquals(List.of(
			detail("rule", "coopcraft.simulation.norm.need-first-common-store"),
			detail("meeting_location", "minecraft:overworld 1,64,1"),
			detail("meeting_attendees", "Ada, Bo"),
			detail("meeting_tally", "2/10; 1/1"),
			detail("meeting_round_cost", "2"),
			detail("meeting_deliberating", "coopcraft.simulation.reason.insufficient-participation")
		), details);
		assertThrows(UnsupportedOperationException.class, details::clear);
	}

	@Test
	void adoptionAndRevisionShowTheFrozenCapChange() {
		for (EventType type : List.of(EventType.NORM_DELIBERATED, EventType.NORM_ADOPTED, EventType.NORM_REVISED)) {
			String outcome = type == EventType.NORM_REVISED ? "revised" : "adopted";
			var evidence = evidence(outcome);
			evidence.put("previousCap", "3");
			evidence.put("newCap", "2");
			var details = SettlementMeetingDetails.project(event(type, evidence));
			assertEquals(6, details.size());
			assertEquals(detail("meeting_" + outcome, "3 → 2"), details.getLast());
			for (String key : List.of("previousCap", "newCap")) {
				var missing = new LinkedHashMap<>(evidence);
				missing.remove(key);
				assertTrue(SettlementMeetingDetails.project(event(type, missing)).isEmpty());
			}
		}
	}

	@Test
	void rejectedAndExpiredMeetingsKeepTheirRecordedOutcome() {
		for (String outcome : List.of("rejected", "expired")) {
			var details = SettlementMeetingDetails.project(event(EventType.NORM_REJECTED, evidence(outcome)));
			assertEquals(6, details.size());
			assertEquals(detail("meeting_" + outcome, "coopcraft.simulation.reason.insufficient-participation"), details.getLast());
		}
	}

	@Test
	void incompleteOrMalformedEvidenceDoesNotInventAHistory() {
		var valid = evidence("deliberating");
		for (String key : List.of("location", "normId", "meetingOutcome", "adultCount", "participants", "supportCount", "opposeCount", "round", "reason")) {
			var missing = new LinkedHashMap<>(valid);
			missing.remove(key);
			assertTrue(SettlementMeetingDetails.project(event(EventType.NORM_DELIBERATED, missing)).isEmpty(), key);
		}
		for (String key : List.of("adultCount", "participants", "supportCount", "opposeCount", "round")) {
			for (String bad : List.of("", "-1", "+2", " 2", "2 ", "2.0", "NaN", "١", "2147483648", "00000000000")) {
				var invalid = new LinkedHashMap<>(valid);
				invalid.put(key, bad);
				assertTrue(SettlementMeetingDetails.project(event(EventType.NORM_DELIBERATED, invalid)).isEmpty(), key + ": " + bad);
			}
		}
		for (var invalid : List.of(Map.entry("adultCount", "1"), Map.entry("participants", "1"),
			Map.entry("supportCount", "2"), Map.entry("round", "0"), Map.entry("location", "x".repeat(97)),
			Map.entry("normId", "future-rule"), Map.entry("meetingOutcome", "future-result"), Map.entry("reason", "future-reason"))) {
			var changed = new LinkedHashMap<>(valid);
			changed.put(invalid.getKey(), invalid.getValue());
			assertTrue(SettlementMeetingDetails.project(event(EventType.NORM_DELIBERATED, changed)).isEmpty(), invalid.toString());
		}
	}

	@Test
	void actorsAndEventTypeMustAgreeWithFrozenEvidence() {
		for (List<String> actors : List.of(List.<String>of(), List.of("one"), List.of("one", "one"), List.of("one", " "), List.of("one", "two", "three"))) {
			assertTrue(SettlementMeetingDetails.project(new SimulationEvent(1, 0, EventType.NORM_DELIBERATED,
				actors, List.of(), List.of(), evidence("deliberating"), List.of())).isEmpty());
		}
		assertTrue(SettlementMeetingDetails.project(event(EventType.SHARE_REFUSED, evidence("deliberating"))).isEmpty());
		assertTrue(SettlementMeetingDetails.project(event(EventType.NORM_ADOPTED, evidence("rejected"))).isEmpty());
		assertTrue(SettlementMeetingDetails.project(event(EventType.NORM_REJECTED, evidence("adopted"))).isEmpty());
		assertTrue(SettlementMeetingDetails.project(event(EventType.NORM_REVISED, evidence("adopted"))).isEmpty());
	}

	@Test
	void missingOldAndUnknownVersionEvidenceNeverMasqueradesAsAnOnsiteMeeting() {
		for (String version : List.of("", "common-store", "common-store-v2")) {
			var evidence = evidence("deliberating");
			if (version.isEmpty()) evidence.remove("meeting");
			else evidence.put("meeting", version);
			assertTrue(SettlementMeetingDetails.project(event(EventType.NORM_DELIBERATED, evidence)).isEmpty());
		}
	}

	@Test
	void markedMalformedAndUnknownVersionEventsNeverFallBackToLiveProposalOrNormDetails() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Frozen history", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		UUID actor = new UUID(28, 1);
		state.mapResident(actor, false, 50);
		String person = state.residentId(actor).orElseThrow();
		state.world().registerProposal(NormProposal.restore("proposal-test", state.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE,
			person, 0, 1, 4, 0, 1, NormProposal.Status.OPEN, Map.of(person, NormProposal.Position.SUPPORT)));
		state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, state.campOwner().id(), 4,
			Set.of(person), Set.of(), Set.of(person), 50, Norm.Status.ACTIVE, List.of()));
		for (String marker : List.of("common-store-v1", "common-store", "common-store-v999")) {
			var broken = evidence("deliberating");
			broken.put("meeting", marker);
			broken.put("proposalId", "proposal-test");
			broken.remove("adultCount");
			var info = Civilization.toSimulationEventInfo(null, data, settlement, event(EventType.NORM_DELIBERATED, broken));
			assertTrue(info.details().isEmpty(), "Marked broken history must not consult current proposal support: " + marker);
		}
	}

	@Test
	void meetingDetailsRoundTripThroughTheRealNetworkCodecAndRespectItsSixItemLimit() {
		var details = SettlementMeetingDetails.project(event(EventType.NORM_DELIBERATED, evidence("deliberating")));
		var info = new SimulationEventInfo(0, "NORM_DELIBERATED", "NONE", 0, details);
		SettlementOverviewPayload expected = new SettlementOverviewPayload("Meetings", 0, 0, 10, 10, 0,
			0, 0, 0, SocialOrganization.PRIMITIVE_COMMUNE, SettlementStage.PRIMITIVE, List.of(), List.of(info),
			List.of(), "food", CivilizationEras.PRIMITIVE, "coopcraft.milestone.common_stockpile", "new_camp");
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			SettlementOverviewPayload.STREAM_CODEC.encode(buffer, expected);
			assertEquals(expected, SettlementOverviewPayload.STREAM_CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
		var tooMany = new ArrayList<>(details);
		tooMany.add(detail("round", "3"));
		assertThrows(IllegalArgumentException.class, () -> new SimulationEventInfo(0, "NORM_DELIBERATED", "NONE", 0, tooMany));
	}

	@Test
	void longAttendeeNamesAreBoundedWithoutChangingFrozenParticipationCounts() {
		var details = SettlementMeetingDetails.project(event(EventType.NORM_DELIBERATED, evidence("deliberating")),
			actors -> "A".repeat(200));
		assertEquals(6, details.size());
		assertEquals(96, details.get(2).value().length());
		assertEquals("2/10; 1/1", details.get(3).value());
	}

	private static Map<String, String> evidence(String outcome) {
		return new LinkedHashMap<>(Map.ofEntries(Map.entry("meeting", "common-store-v1"),
			Map.entry("location", "minecraft:overworld 1,64,1"), Map.entry("normId", Norm.NEED_FIRST_COMMON_STORE),
			Map.entry("adultCount", "10"), Map.entry("participants", "2"), Map.entry("supportCount", "1"),
			Map.entry("opposeCount", "1"), Map.entry("round", "2"), Map.entry("meetingOutcome", outcome),
			Map.entry("reason", "insufficient-participation")));
	}

	private static SimulationEvent event(EventType type, Map<String, String> evidence) {
		return new SimulationEvent(1, 0, type, List.of("resident-1", "resident-2"), List.of(), List.of(), evidence, List.of());
	}

	private static SimulationEventDetail detail(String label, String value) {
		return new SimulationEventDetail("coopcraft.simulation.detail." + label, value);
	}
}
