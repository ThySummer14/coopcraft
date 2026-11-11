package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;

/** Frozen meeting evidence only. Current names may label recorded actor IDs, never invent attendance. */
final class SettlementMeetingDetails {
	private SettlementMeetingDetails() {}

	static boolean isMeeting(SimulationEvent event) {
		return event.context().containsKey("meeting")
			&& Set.of(EventType.NORM_DELIBERATED, EventType.NORM_ADOPTED, EventType.NORM_REVISED,
				EventType.NORM_REJECTED).contains(event.type());
	}

	static List<SimulationEventDetail> project(SimulationEvent event) {
		return project(event, actors -> String.join(", ", actors));
	}

	static List<SimulationEventDetail> project(SimulationEvent event, Function<List<String>, String> actorNames) {
		if (!isMeeting(event) || !"common-store-v1".equals(event.context().get("meeting"))) return List.of();
		var context = event.context();
		String location = context.get("location");
		String norm = context.get("normId");
		String outcome = context.get("meetingOutcome");
		Integer adults = count(context.get("adultCount"));
		Integer participants = count(context.get("participants"));
		Integer support = count(context.get("supportCount"));
		Integer oppose = count(context.get("opposeCount"));
		Integer round = count(context.get("round"));
		if (location == null || location.isBlank() || location.length() > 96 || norm == null
			|| !(norm.equals("need-first-common-store") || norm.equals("care-first-common-store"))
			|| outcome == null || !Set.of("deliberating", "adopted", "revised", "rejected", "expired").contains(outcome)
			|| adults == null || participants == null || support == null || oppose == null || round == null
			|| participants < 2 || participants > adults || participants != event.actors().size()
			|| event.actors().stream().distinct().count() != participants || round < 1
			|| event.actors().stream().anyMatch(String::isBlank) || (long) support + oppose > participants
			|| !compatibleOutcome(event.type(), outcome)) return List.of();
		String result;
		if (outcome.equals("adopted") || outcome.equals("revised")) {
			Integer previous = count(context.get("previousCap"));
			Integer current = count(context.get("newCap"));
			if (previous == null || current == null || current <= 0) return List.of();
			result = previous + " → " + current;
		} else {
			String reason = context.get("reason");
			if (reason == null || !Set.of("insufficient-participation", "insufficient-support", "household-opposition",
				"insufficient-supervision", "deliberating").contains(reason)) return List.of();
			result = "coopcraft.simulation.reason." + reason;
		}
		String names = actorNames.apply(event.actors());
		if (names == null || names.isBlank()) return List.of();
		if (names.length() > 96) names = names.substring(0, 95) + "…";
		return List.of(detail("rule", "coopcraft.simulation.norm." + norm), detail("meeting_location", location),
			detail("meeting_attendees", names), detail("meeting_tally", participants + "/" + adults + "; " + support + "/" + oppose),
			detail("meeting_round_cost", round.toString()), detail("meeting_" + outcome, result));
	}

	private static boolean compatibleOutcome(EventType type, String outcome) {
		return switch (type) {
			case NORM_DELIBERATED -> true;
			case NORM_ADOPTED -> outcome.equals("adopted");
			case NORM_REVISED -> outcome.equals("revised");
			case NORM_REJECTED -> outcome.equals("rejected") || outcome.equals("expired");
			default -> false;
		};
	}

	private static SimulationEventDetail detail(String label, String value) {
		return new SimulationEventDetail("coopcraft.simulation.detail." + label, value);
	}

	private static Integer count(String value) {
		if (value == null || !value.matches("[0-9]{1,10}")) return null;
		try { return Integer.valueOf(value); }
		catch (NumberFormatException invalid) { return null; }
	}
}
