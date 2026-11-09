package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.thysummer.coopcraft.net.ResidentDialogPayload;
import net.minecraft.network.chat.Component;

/** Read-only window projections. A meeting receipt and today's allocation are different observations. */
final class ResidentMeetingView {
	private ResidentMeetingView() {}

	record Presentation(Component hint, boolean canHold) {}

	static Presentation present(SettlementMeetings.Result result) {
		try {
			return new Presentation(ResidentDialogPayload.copyBoundedComponent(gathering(result)), reviewable(result));
		} catch (RuntimeException tooLargeOrInvalid) {
			// Do not truncate a proposal, cost or consequence while leaving its action enabled.
			return new Presentation(message("gathering_unreviewable"), false);
		}
	}

	static boolean reviewable(SettlementMeetings.Result result) {
		String id = result.discussion().proposalId();
		return result.status().equals("ready") && result.feedback().agenda().isPresent()
			&& !id.isBlank() && id.length() <= 64 && id.codePoints().noneMatch(Character::isISOControl);
	}

	static Component gathering(SettlementMeetings.Result result) {
		List<Component> lines = new ArrayList<>();
		lines.add(message("gathering_title"));
		lines.add(message("gathering_cost"));
		lines.add(result.status().equals("ready")
			? message(reviewable(result) ? "gathering_ready" : "gathering_unreviewable") : status(result));
		lines.add(command("meeting_day", result.coreDay(), command(result.lastMeetingDay() == result.coreDay()
			? "meeting_used" : "meeting_unused")));
		if (!result.location().isEmpty()) lines.add(command("meeting_location", result.location()));
		if (!result.discussion().proposalId().isEmpty() && !result.status().equals("invalid_proposal")) {
			lines.add(command("meeting_summary", result.discussion().participants(),
				result.discussion().adultCount(), result.discussion().round()));
		}
		lines.addAll(SettlementMeetingFeedback.messages(result));
		var joined = Component.empty();
		for (Component line : lines) {
			if (!joined.getSiblings().isEmpty()) joined.append("\n");
			joined.append(line);
		}
		return joined;
	}

	/** Freeze the exact successful result once. Refreshing must not reinterpret the old meeting. */
	static List<Component> receipt(SettlementMeetings.Result result) {
		if (!result.held()) return List.of();
		List<Component> lines = new ArrayList<>();
		lines.add(message("receipt_title"));
		lines.add(command("meeting_day", result.coreDay(), command("meeting_used")));
		lines.add(command("meeting_location", result.location()));
		lines.add(command("meeting_summary", result.discussion().participants(),
			result.discussion().adultCount(), result.discussion().round()));
		lines.add(command("meeting_outcome", Component.translatable("coopcraft.simulation.meeting." + result.discussion().outcome())));
		lines.addAll(SettlementMeetingFeedback.messages(result));
		return List.copyOf(lines);
	}

	static Component status(SettlementMeetings.Result result) {
		return command("meeting_" + result.status());
	}

	/** Existing saved allocation, not a delivery or a promise that the current world day is settled. */
	static List<Component> rations(SettlementSavedData data, Settlement settlement, UUID resident, Component name, long gameDay) {
		var state = data.simulationOf(settlement).orElse(null);
		if (!data.all().contains(settlement) || !data.isMember(settlement, resident) || state == null || !state.survivalEnabled())
			return List.of();
		var row = SettlementRations.inspect(data, settlement).stream()
			.filter(snapshot -> snapshot.villagerId().equals(resident)).findFirst().orElse(null);
		if (row == null) return List.of();
		List<Component> lines = new ArrayList<>();
		lines.add(message("ration_title"));
		if (gameDay < 0 || !SettlementMeetings.dayReady(gameDay, state.lastSurvivalDay()))
			lines.add(command("meeting_day_pending"));
		lines.add(SettlementCommands.rationMessage(new SettlementCommands.RationRow(name, row)));
		lines.add(command("rations_explanation"));
		SettlementFoodAccess.inspect(data, settlement, resident, gameDay).ifPresent(access -> {
			lines.add(message("quota_refusal", access.coreDay(), access.refusalEventId(),
				access.refusedAllocatedUnits(), access.refusedCap()));
			lines.add(message("quota_refusal_context", access.refusedHunger(), access.refusedAvailableNutrition()));
			lines.add(message("quota_retry_" + access.status().name().toLowerCase(Locale.ROOT)));
		});
		return List.copyOf(lines);
	}

	private static Component message(String suffix, Object... arguments) {
		return Component.translatable("coopcraft.resident_dialog." + suffix, arguments);
	}
	private static Component command(String suffix, Object... arguments) {
		return Component.translatable("coopcraft.command.settlement." + suffix, arguments);
	}
}
