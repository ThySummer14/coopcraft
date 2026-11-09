package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Face-to-face information only. Never creates state, invites, teaches or casts a vote. */
final class VillagerMeetingInquiry {
	private static final String PREFIX = "coopcraft.interaction.meeting.inquiry.";

	private VillagerMeetingInquiry() {}

	/** At most four short lines about the same earliest open proposal, regardless of knowledge. */
	static List<Component> inspect(SettlementSavedData data, Settlement settlement, UUID residentId, long gameDay) {
		if (data == null || settlement == null) return List.of();
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.normsEnabled()) return List.of();
		if (!data.all().contains(settlement) || residentId == null || !data.isMember(settlement, residentId)) {
			return List.of(message("unavailable"));
		}
		if (gameDay < 0 || !SettlementMeetings.dayReady(gameDay, state.lastSurvivalDay())) {
			return List.of(message("day_pending"));
		}
		String personId = state.residentId(residentId).orElse(null);
		Person resident = state.world().people().stream()
			.filter(person -> person.id().equals(personId) && person.campId().equals(state.campOwner().id()))
			.findFirst().orElse(null);
		if (resident == null) return List.of(message("unavailable"));
		NormProposal proposal = state.world().proposalsForCamp(state.campOwner().id()).stream()
			.filter(candidate -> candidate.status() == NormProposal.Status.OPEN)
			.min(Comparator.comparingLong(NormProposal::proposedDay).thenComparing(NormProposal::proposalId)).orElse(null);
		if (proposal == null) return List.of(message("none"));
		// Normal generated IDs fit comfortably; do not display an ambiguous truncated identity.
		if (proposal.proposalId().length() > 64 || proposal.proposalId().codePoints().anyMatch(Character::isISOControl)) {
			return List.of(message("unavailable"));
		}
		NormSystem.ProposalInquiry inquiry = state.norms().inspectProposal(resident, proposal.proposalId()).orElse(null);
		if (inquiry == null) return List.of(message("unavailable"));
		List<Component> lines = new ArrayList<>();
		Component parameter = message(Norm.CARE_FIRST_COMMON_STORE.equals(inquiry.normId()) ? "care_bonus" : "cap",
			inquiry.allocationUnitCap());
		lines.add(message("proposal", inquiry.proposalId(),
			Component.translatable("coopcraft.simulation.norm." + inquiry.normId()), parameter));
		lines.add(message("knowledge", message(inquiry.known() ? "yes" : "no"),
			message(inquiry.hasReliableInformation() ? "yes" : "no"), introduction(inquiry)));
		if (inquiry.opinion().isPresent()) {
			NormSystem.ProposalOpinion opinion = inquiry.opinion().orElseThrow();
			lines.add(message("opinion", message("position_" + opinion.position().name().toLowerCase(Locale.ROOT))));
			lines.add(message("factors", factors(opinion)));
		} else {
			lines.add(message(inquiry.known() ? "undecidable" : "unknown"));
		}
		return List.copyOf(lines);
	}

	private static Component introduction(NormSystem.ProposalInquiry inquiry) {
		if (inquiry.canIntroduce()) return message("introduce_ready");
		if (!inquiry.decisionCapable()) return message("introduce_unavailable");
		if (inquiry.remainingSocialHours() == 0) return message("introduce_exhausted");
		return message(inquiry.known() ? "introduce_no_source" : "introduce_unknown");
	}

	private static Component factors(NormSystem.ProposalOpinion opinion) {
		List<NormSystem.ProposalFactor> prominent = opinion.factors().stream()
			.filter(factor -> factor.contribution() != 0)
			.sorted(Comparator.comparingDouble((NormSystem.ProposalFactor factor) -> Math.abs(factor.contribution())).reversed()
				.thenComparing(NormSystem.ProposalFactor::kind))
			.limit(3).toList();
		if (prominent.isEmpty()) return message("factors_none");
		MutableComponent result = Component.empty();
		for (NormSystem.ProposalFactor factor : prominent) {
			if (!result.getSiblings().isEmpty()) result.append(message("factor_separator"));
			result.append(message("factor_" + factor.kind().name().toLowerCase(Locale.ROOT)));
		}
		return result;
	}

	private static Component message(String key, Object... arguments) {
		return Component.translatable(PREFIX + key, arguments);
	}
}
