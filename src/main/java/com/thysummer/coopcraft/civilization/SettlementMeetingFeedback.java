package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;

import net.minecraft.network.chat.Component;

/** Read-only command explanations. An allowance or core qualification is not a physical delivery. */
final class SettlementMeetingFeedback {
	private SettlementMeetingFeedback() {}

	record Agenda(String normId, int proposedCap, int currentCap, boolean defaultValue) {}
	record Effects(boolean survivalEnabled, boolean normsEnabled, int baseCap, int careExtraCap,
		boolean baseActive, boolean careActive, boolean needFirstEnforced) {}
	record Snapshot(Optional<Agenda> agenda, Effects effects, List<SimulationEventDetail> completedDetails) {
		Snapshot { completedDetails = List.copyOf(completedDetails); }
	}

	static Snapshot inspect(SettlementSimulationState state, NormSystem.MeetingResult discussion) {
		return new Snapshot(Optional.ofNullable(agenda(state, discussion)), effects(state), completed(state, discussion));
	}

	static Agenda agenda(SettlementSimulationState state, NormSystem.MeetingResult discussion) {
		if (state == null || discussion.proposalId().isEmpty() || discussion.status().equals("invalid_proposal")) return null;
		var proposal = state.world().proposal(discussion.proposalId()).orElse(null);
		if (proposal == null || !proposal.campId().equals(state.campOwner().id()) || !knownRule(proposal.normId())
			|| proposal.proposedDay() > state.world().day() || proposal.rounds() != discussion.round()
			|| (!discussion.held() && proposal.status() != NormProposal.Status.OPEN)) return null;
		// originEventId is the grievance/shortage cause, not the NORM_PROPOSED message.
		if (state.world().ledger().event(proposal.originEventId()).isEmpty()) return null;
		var current = state.world().norm(proposal.campId(), proposal.normId()).filter(Norm::isActive);
		int fallback = proposal.normId().equals(Norm.NEED_FIRST_COMMON_STORE) ? SettlementSimulationState.DAILY_FOOD_UNIT_CAP : 0;
		return new Agenda(proposal.normId(), proposal.allocationUnitCap(), current.map(Norm::allocationUnitCap).orElse(fallback), current.isEmpty());
	}

	static Effects effects(SettlementSimulationState state) {
		if (state == null) return new Effects(false, false, SettlementSimulationState.DAILY_FOOD_UNIT_CAP, 0, false, false, false);
		String camp = state.campOwner().id();
		var base = state.world().norm(camp, Norm.NEED_FIRST_COMMON_STORE).filter(Norm::isActive);
		var care = state.world().norm(camp, Norm.CARE_FIRST_COMMON_STORE).filter(Norm::isActive);
		boolean norms = state.normsEnabled();
		return new Effects(state.survivalEnabled(), norms,
			norms ? base.map(Norm::allocationUnitCap).orElse(SettlementSimulationState.DAILY_FOOD_UNIT_CAP) : SettlementSimulationState.DAILY_FOOD_UNIT_CAP,
			norms ? care.map(Norm::allocationUnitCap).orElse(0) : 0,
			norms && base.isPresent(), norms && care.isPresent(),
			state.survivalEnabled() && norms && state.norms().isNeedFirstEnforced(camp));
	}

	/** Exact receipt only: legacy results and unknown/malformed frozen evidence have no fallback. */
	static List<SimulationEventDetail> completed(SettlementSimulationState state, NormSystem.MeetingResult discussion) {
		if (state == null || !discussion.held() || discussion.deliberationEventId() <= 0) return List.of();
		var event = state.world().ledger().event(discussion.deliberationEventId()).orElse(null);
		var proposal = state.world().proposal(discussion.proposalId()).orElse(null);
		if (event == null || proposal == null || !proposal.campId().equals(state.campOwner().id())
			|| event.type() != EventType.NORM_DELIBERATED || event.day() != state.world().day()
			|| !discussion.proposalId().equals(event.context().get("proposalId"))
			|| !proposal.normId().equals(event.context().get("normId"))
			|| !Integer.toString(discussion.round()).equals(event.context().get("round"))
			|| !Integer.toString(discussion.participants()).equals(event.context().get("participants"))
			|| !Integer.toString(discussion.adultCount()).equals(event.context().get("adultCount"))
			|| !discussion.outcome().equals(event.context().get("meetingOutcome"))) return List.of();
		return SettlementMeetingDetails.project(event);
	}

	static List<Component> messages(SettlementMeetings.Result result) {
		List<Component> lines = new ArrayList<>();
		Snapshot snapshot = result.feedback();
		if (!result.held()) snapshot.agenda().ifPresent(agenda -> {
			lines.add(message("meeting_rule_agenda", result.discussion().proposalId(), Component.translatable("coopcraft.simulation.norm." + agenda.normId()),
				message(agenda.normId().equals(Norm.NEED_FIRST_COMMON_STORE) ? "meeting_parameter_base" : "meeting_parameter_care")));
			lines.add(message("meeting_parameter_change", agenda.currentCap(),
				message(agenda.defaultValue() ? "meeting_default_value" : "meeting_active_value"), agenda.proposedCap(),
				(long) agenda.proposedCap() - agenda.currentCap()));
			lines.add(message("meeting_thresholds"));
		});
		if (result.held()) {
			if (snapshot.completedDetails().isEmpty()) lines.add(message("meeting_receipt_unavailable"));
			else {
				lines.add(message("meeting_receipt_reference", result.discussion().deliberationEventId(), result.discussion().proposalId(), result.discussion().round()));
				for (SimulationEventDetail detail : snapshot.completedDetails()) {
					// The normal command already shows location, attendance and round. Keep the receipt compact.
					if (detail.label().endsWith(".rule") || detail.label().endsWith(".meeting_tally")) {
						lines.add(Component.translatable(detail.label(), localize(detail.value())));
					} else if (detail.label().endsWith(".meeting_adopted") || detail.label().endsWith(".meeting_revised")) {
						String[] caps = detail.value().split(" → ");
						lines.add(message("meeting_frozen_change", caps[0].equals("0") ? message("meeting_unestablished") : Component.literal(caps[0]), caps[1]));
					} else if (detail.label().endsWith(".meeting_deliberating") || detail.label().endsWith(".meeting_rejected")
						|| detail.label().endsWith(".meeting_expired")) {
						lines.add(Component.translatable(detail.label(), localize(detail.value())));
					}
				}
			}
		}
		Effects effects = snapshot.effects();
		if (!effects.survivalEnabled()) lines.add(message("meeting_effects_survival_off"));
		else if (!effects.normsEnabled()) lines.add(message("meeting_effects_norms_off", effects.baseCap()));
		else {
			lines.add(message("meeting_effects_caps", effects.baseCap(),
				message(effects.baseActive() ? "meeting_active_value" : "meeting_default_value"), effects.careExtraCap(),
				message(effects.careActive() ? "meeting_active_value" : "meeting_default_value")));
			lines.add(message(effects.needFirstEnforced() ? "meeting_order_qualified" : "meeting_order_unqualified"));
		}
		lines.add(message("meeting_compare_hint"));
		return List.copyOf(lines);
	}

	private static boolean knownRule(String id) {
		return id.equals(Norm.NEED_FIRST_COMMON_STORE) || id.equals(Norm.CARE_FIRST_COMMON_STORE);
	}
	private static Component localize(String value) {
		return value.startsWith("coopcraft.") ? Component.translatable(value) : Component.literal(value);
	}
	private static Component message(String key, Object... args) {
		return Component.translatable("coopcraft.command.settlement." + key, args);
	}
}
