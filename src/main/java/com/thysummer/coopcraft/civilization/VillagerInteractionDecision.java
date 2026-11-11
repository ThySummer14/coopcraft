package com.thysummer.coopcraft.civilization;

public final class VillagerInteractionDecision {
	public static final int FOOD_RESERVE_UNITS_PER_MEMBER = 8;
	private static final int TRUST_THRESHOLD = 10;

	private VillagerInteractionDecision() {
	}

	public static Decision decide(
		int satiety,
		int residentCount,
		long communalFoodUnits,
		int trustTowardPlayer,
		int sociability,
		boolean supportedFood
	) {
		Decision decision = decide(satiety, new SettlementReserves.Snapshot(FoodReserveTarget.NORMAL, residentCount, false,
			java.util.OptionalLong.of(communalFoodUnits), (long) Math.max(1, residentCount) * FOOD_RESERVE_UNITS_PER_MEMBER),
			trustTowardPlayer, sociability, supportedFood);
		// Compatibility for old callers and their historical event vocabulary.
		return new Decision(decision.accepted(), switch (decision.reason()) {
			case "reserve_target_met" -> "village_has_food";
			case "below_reserve_target" -> "low_communal_food";
			default -> decision.reason();
		});
	}

	public static Decision decide(int satiety, SettlementReserves.Snapshot reserves,
		int trustTowardPlayer, int sociability, boolean supportedFood) {
		if (satiety < 0 || satiety > 100 || trustTowardPlayer < 0 || trustTowardPlayer > 100
			|| sociability < 0 || sociability > 100 || reserves.current().orElse(0) < 0) {
			throw new IllegalArgumentException("Interaction inputs must be within their valid ranges");
		}
		if (!supportedFood) {
			return new Decision(false, "unsupported_food");
		}
		if (satiety >= SettlementNeeds.FULL_AT) {
			return new Decision(false, "already_full");
		}
		if (reserves.current().isEmpty()) return new Decision(false, "no_stockpile");
		boolean secure = reserves.current().getAsLong() >= reserves.goal();
		int acceptAt = 50 + sociability / 5;
		if (secure && satiety >= acceptAt && trustTowardPlayer < TRUST_THRESHOLD) {
			return new Decision(false, "reserve_target_met");
		}
		if (trustTowardPlayer >= TRUST_THRESHOLD && satiety >= 60) {
			return new Decision(true, "remembers_help");
		}
		if (satiety <= SettlementNeeds.HUNGRY_AT) {
			return new Decision(true, "hungry");
		}
		if (!secure) {
			return new Decision(true, "below_reserve_target");
		}
		return new Decision(true, "welcomed");
	}

	public record Decision(boolean accepted, String reason) {
		public Decision {
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("Interaction decision needs a reason");
			}
		}
	}
}
