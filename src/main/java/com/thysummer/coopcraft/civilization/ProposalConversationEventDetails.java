package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;

/** Frozen notice evidence. Names label recorded actors; no present proposal or vote is consulted. */
final class ProposalConversationEventDetails {
	private ProposalConversationEventDetails() { }

	static boolean isConversation(SimulationEvent event) {
		return event != null && event.type() == EventType.INFORMATION_SHARED
			&& "minecraft_proposal_conversation".equals(event.context().get("source"));
	}

	static List<SimulationEventDetail> project(SimulationEvent event, Function<String, String> residentName) {
		if (!isConversation(event) || residentName == null || event.actors().size() != 2
			|| event.actors().get(0).equals(event.actors().get(1))
			|| !event.inputs().isEmpty() || !event.outputs().isEmpty()) return List.of();
		var context = event.context();
		Double source = confidence(context.get("confidence"));
		Double received = confidence(context.get("receivedConfidence"));
		Long cause = positiveId(context.get("sourceEventId"));
		if (!"NORM_PROPOSAL".equals(context.get("kind"))
			|| !"1".equals(context.get("hoursPerParticipant"))
			|| !"next_settled_day".equals(context.get("costTiming"))
			|| source == null || source < .5 || received == null
			|| Double.compare(received, source * .85) != 0
			|| cause == null || !event.causalLinks().equals(List.of(cause))
			|| !nonblank(context.get("proposalId")) || !nonblank(context.get("informationId"))
			|| !nonblank(context.get("campId")) || !nonblank(context.get("settlement"))
			|| !matchesActor(context.get("speakerUuid"), event.actors().get(0))
			|| !matchesActor(context.get("listenerUuid"), event.actors().get(1))) return List.of();
		return List.of(
			detail("speaker", residentName.apply(event.actors().get(0))),
			detail("listener", residentName.apply(event.actors().get(1))),
			detail("proposal", context.get("proposalId")),
			detail("source", cause.toString()),
			detail("confidence", Double.toString(received)),
			detail("cost", "coopcraft.simulation.conversation_cost.next_settled_day"));
	}

	private static boolean nonblank(String value) { return value != null && !value.isBlank(); }

	private static boolean matchesActor(String uuid, String actor) {
		if (uuid == null || uuid.length() != 36) return false;
		try { return UUID.fromString(uuid).toString().equals(uuid) && actor.equals("minecraft-person:" + uuid); }
		catch (IllegalArgumentException invalid) { return false; }
	}

	private static Long positiveId(String value) {
		if (value == null || !value.matches("[1-9][0-9]{0,18}")) return null;
		try { return Long.valueOf(value); }
		catch (NumberFormatException invalid) { return null; }
	}

	private static Double confidence(String value) {
		if (value == null || value.length() > 32) return null;
		try {
			double number = Double.parseDouble(value);
			return Double.isFinite(number) && number >= 0 && number <= 1 ? number : null;
		} catch (NumberFormatException invalid) { return null; }
	}

	private static SimulationEventDetail detail(String key, String value) {
		String shown = value == null || value.isBlank() ? "?" : value;
		if (shown.length() > 96) shown = shown.substring(0, 95) + "…";
		return new SimulationEventDetail("coopcraft.simulation.detail.conversation_" + key, shown);
	}
}
