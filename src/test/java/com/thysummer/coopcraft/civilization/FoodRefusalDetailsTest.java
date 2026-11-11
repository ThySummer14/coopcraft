package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodRefusalDetailsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void overviewUsesAllRecordedDetailsWithoutCreatingSimulationState() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Refusal", 0);
		data.setDirty(false);
		SimulationEvent event = event(EventType.SHARE_REFUSED, context());
		var overview = Civilization.toSimulationEventInfo(null, data, settlement, event);
		assertEquals(SettlementOverviewPayload.MAX_EVENT_DETAILS, overview.details().size());
		assertEquals(detail("actor", "resident"), overview.details().getFirst());
		assertEquals(FoodRefusalDetails.project(event), overview.details().subList(1, 6));
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertTrue(!data.isDirty());
	}

	@Test
	void recordedRefusalFitsExistingLimitAlongsideResident() {
		var details = FoodRefusalDetails.project(event(EventType.SHARE_REFUSED, context()));
		assertEquals(List.of(
			detail("reason", "coopcraft.simulation.reason.daily-unit-cap-exhausted"),
			detail("allocation_units", "3/3"),
			detail("hunger_at_refusal", "46"),
			detail("needed_nutrition", "46"),
			detail("available_nutrition", "360")
		), details);
		assertTrue(details.size() + 1 <= SettlementOverviewPayload.MAX_EVENT_DETAILS);
		assertTrue(details.stream().allMatch(d -> d.label().length() <= 96 && d.value().length() <= 96));
	}

	@Test
	void missingFieldsAreNotReconstructedFromOtherFacts() {
		var details = FoodRefusalDetails.project(event(EventType.SHARE_REFUSED,
			Map.of("reason", "daily-unit-cap-exhausted", "hunger", "46")));
		assertEquals(List.of(
			detail("reason", "coopcraft.simulation.reason.daily-unit-cap-exhausted"),
			detail("hunger_at_refusal", "46")
		), details);
	}

	@Test
	void unknownOrMissingBasisDoesNotClaimAnItemUnitLabel() {
		for (String basis : List.of("", "unknown", "nutrition")) {
			var context = context();
			if (basis.isEmpty()) {
				context.remove("allocationBasis");
			} else {
				context.put("allocationBasis", basis);
			}
			assertEquals(detail("allocation", "3/3"),
				FoodRefusalDetails.project(event(EventType.SHARE_REFUSED, context)).get(1));
		}
	}

	@Test
	void oldAndUnrelatedEventsKeepTheirExistingProjection() {
		assertTrue(FoodRefusalDetails.project(event(EventType.SHARE_REFUSED, Map.of())).isEmpty());
		assertTrue(FoodRefusalDetails.project(event(EventType.SHARE_REFUSED,
			Map.of("reason", "unknown"))).isEmpty());
		assertTrue(FoodRefusalDetails.project(event(EventType.SHARE, context())).isEmpty());
	}

	@Test
	void zeroStockAndDistinctNutritionArePreservedWithoutInventingRelief() {
		var context = context();
		context.put("hunger", "10");
		context.put("neededNutrition", "28");
		context.put("availableNutrition", "0");
		var details = FoodRefusalDetails.project(event(EventType.SHARE_REFUSED, context));
		assertEquals(detail("hunger_at_refusal", "10"), details.get(2));
		assertEquals(detail("needed_nutrition", "28"), details.get(3));
		assertEquals(detail("available_nutrition", "0"), details.get(4));
	}

	@Test
	void oversizedOrBlankHistoricalValuesAreOmittedWithoutChangingWireLimit() {
		var context = context();
		context.put("hunger", " ");
		context.put("neededNutrition", "1".repeat(97));
		context.put("availableNutrition", "1".repeat(96));
		var details = FoodRefusalDetails.project(event(EventType.SHARE_REFUSED, context));
		assertEquals(3, details.size());
		assertEquals(detail("available_nutrition", "1".repeat(96)), details.get(2));
	}

	private static Map<String, String> context() {
		return new HashMap<>(Map.of(
			"reason", "daily-unit-cap-exhausted", "allocationBasis", "item_units",
			"allocatedUnits", "3", "cap", "3", "hunger", "46",
			"neededNutrition", "46", "availableNutrition", "360"));
	}

	private static SimulationEvent event(EventType type, Map<String, String> context) {
		return new SimulationEvent(1, 0, type, List.of("resident", "camp"), List.of(), List.of(), context, List.of());
	}

	private static SimulationEventDetail detail(String label, String value) {
		return new SimulationEventDetail("coopcraft.simulation.detail." + label, value);
	}
}
