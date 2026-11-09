package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VillagerReserveDecisionTest {
	@Test void targetBoundariesAreExactInBothAuthorities() {
		for (boolean survival : List.of(false, true)) {
			for (FoodReserveTarget target : FoodReserveTarget.values()) {
				long goal = 3L * target.pointsPerMember() * (survival ? 5 : 1);
				for (int delta : List.of(-1, 0, 1)) {
					var snapshot = new SettlementReserves.Snapshot(target, 3, survival, OptionalLong.of(goal + delta), goal);
					var decision = VillagerInteractionDecision.decide(80, snapshot, 0, 100, true);
					assertEquals(delta < 0, decision.accepted());
					assertEquals(delta < 0 ? "below_reserve_target" : "reserve_target_met", decision.reason());
				}
			}
		}
	}

	@Test void fullnessTrustHungerAndPersonalityKeepTheirPriorityAndBoundaries() {
		var secure = snapshot(24);
		var deficit = snapshot(23);
		assertDecision(false, "unsupported_food", VillagerInteractionDecision.decide(100, deficit, 100, 100, false));
		assertDecision(false, "already_full", VillagerInteractionDecision.decide(95, deficit, 100, 100, true));
		assertDecision(true, "remembers_help", VillagerInteractionDecision.decide(94, secure, 10, 0, true));
		assertDecision(false, "reserve_target_met", VillagerInteractionDecision.decide(80, secure, 9, 100, true));
		assertDecision(true, "remembers_help", VillagerInteractionDecision.decide(60, deficit, 10, 0, true));
		assertDecision(true, "below_reserve_target", VillagerInteractionDecision.decide(59, deficit, 10, 0, true));
		assertDecision(true, "hungry", VillagerInteractionDecision.decide(35, secure, 0, 0, true));
		assertDecision(true, "welcomed", VillagerInteractionDecision.decide(36, secure, 0, 0, true));
		for (int sociability : List.of(0, 50, 100)) {
			int threshold = 50 + sociability / 5;
			assertDecision(true, "welcomed", VillagerInteractionDecision.decide(threshold - 1, secure, 0, sociability, true));
			assertDecision(false, "reserve_target_met", VillagerInteractionDecision.decide(threshold, secure, 0, sociability, true));
		}
		var unknown = new SettlementReserves.Snapshot(FoodReserveTarget.NORMAL, 3, false, OptionalLong.empty(), 24);
		assertDecision(false, "no_stockpile", VillagerInteractionDecision.decide(30, unknown, 100, 100, true));
	}

	@Test void oldSignatureRetainsHistoricalReasons() {
		assertDecision(false, "village_has_food", VillagerInteractionDecision.decide(80, 3, 24, 0, 50, true));
		assertDecision(true, "low_communal_food", VillagerInteractionDecision.decide(80, 3, 23, 0, 50, true));
		assertDecision(false, "village_has_food", VillagerInteractionDecision.decide(80, 0, 8, 0, 50, true));
	}

	@Test void bilingualGiftReasonsExistAndKeepMessageArgumentCounts() throws Exception {
		for (String locale : List.of("en_us", "zh_cn")) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
				assertNotNull(stream);
				var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
					java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				for (String reason : List.of("reserve_target_met", "below_reserve_target", "already_full", "remembers_help",
					"hungry", "welcomed", "no_stockpile", "unsupported_food", "transfer_failed", "stockpile_full",
					"village_has_food", "low_communal_food")) {
					String key = "coopcraft.interaction.reason." + reason;
					assertTrue(json.has(key), locale + ": " + key);
					assertFalse(json.get(key).getAsString().isBlank());
					assertFalse(json.get(key).getAsString().contains("%"), "Reasons need no formatting arguments");
				}
				for (String detail : List.of("target", "points", "nutrition")) {
					String key = "coopcraft.simulation.detail.gift_reserve_" + detail;
					assertTrue(json.has(key), locale + ": " + key);
					assertFalse(json.get(key).getAsString().isBlank());
				}
				assertEquals(3, json.get("coopcraft.interaction.accepted").getAsString().split("%s", -1).length - 1);
				assertEquals(2, json.get("coopcraft.interaction.refused").getAsString().split("%s", -1).length - 1);
			}
		}
	}

	@Test void historyProjectionUsesOnlyCompleteValidGiftEvidence() {
		for (var type : List.of(com.thysummer.coopcraft.civilization.simulation.EventType.GIFT_RECEIVED,
			com.thysummer.coopcraft.civilization.simulation.EventType.GIFT_REFUSED)) {
			for (String target : List.of("normal", "high")) {
				for (String unit : List.of("points", "nutrition")) {
					var evidence = new java.util.HashMap<>(java.util.Map.of("targetId", target, "unit", unit,
						"currentBefore", "000", "goalBefore", Long.toString(Long.MAX_VALUE)));
					var details = GiftReserveDetails.project(event(type, evidence));
					assertEquals(2, details.size());
					assertEquals("coopcraft.simulation.detail.gift_reserve_target", details.get(0).label());
					assertEquals("coopcraft.command.settlement.reserves_" + target, details.get(0).value());
					assertEquals("coopcraft.simulation.detail.gift_reserve_" + unit, details.get(1).label());
					assertEquals("0/" + Long.MAX_VALUE, details.get(1).value());
					for (String key : List.of("targetId", "unit", "currentBefore", "goalBefore")) {
						var missing = new java.util.HashMap<>(evidence); missing.remove(key);
						assertTrue(GiftReserveDetails.project(event(type, missing)).isEmpty());
					}
					for (String key : List.of("currentBefore", "goalBefore")) {
						for (String invalid : List.of("", "-1", "+1", " 1", "1 ", "1.0", "NaN", "١", "9223372036854775808", "00000000000000000000")) {
							var changed = new java.util.HashMap<>(evidence); changed.put(key, invalid);
							assertTrue(GiftReserveDetails.project(event(type, changed)).isEmpty(), key + ": " + invalid);
						}
					}
					for (String key : List.of("targetId", "unit")) {
						for (String invalid : List.of("", "HIGH", "future", " high", "NUTRITION")) {
							var changed = new java.util.HashMap<>(evidence); changed.put(key, invalid);
							assertTrue(GiftReserveDetails.project(event(type, changed)).isEmpty());
						}
					}
					assertTrue(GiftReserveDetails.project(event(com.thysummer.coopcraft.civilization.simulation.EventType.SHARE_REQUEST, evidence)).isEmpty());
				}
			}
			assertTrue(GiftReserveDetails.project(event(type, java.util.Map.of())).isEmpty());
		}
	}

	private static com.thysummer.coopcraft.civilization.simulation.SimulationEvent event(
		com.thysummer.coopcraft.civilization.simulation.EventType type, java.util.Map<String, String> context) {
		return new com.thysummer.coopcraft.civilization.simulation.SimulationEvent(1, 0, type,
			List.of(), List.of(), List.of(), context, List.of());
	}

	private static SettlementReserves.Snapshot snapshot(long current) {
		return new SettlementReserves.Snapshot(FoodReserveTarget.NORMAL, 3, false, OptionalLong.of(current), 24);
	}
	private static void assertDecision(boolean accepted, String reason, VillagerInteractionDecision.Decision decision) {
		assertEquals(accepted, decision.accepted()); assertEquals(reason, decision.reason());
	}
}
