package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPlayersTest {
	@Test
	void observationUsesStableMachineReadableIds() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(-4, 65, 9), "河湾 \"一号\"", 0);

		JsonObject json = AiPlayers.observationJson(
			data, settlement, true, 20, 7, List.of("common_food_shortage"), "food"
		);

		assertEquals(1, json.get("version").getAsInt());
		assertEquals(settlement.id().toString(), json.get("settlement_id").getAsString());
		assertEquals("河湾 \"一号\"", json.get("settlement_name").getAsString());
		assertEquals("coopcraft:primitive", json.get("era").getAsString());
		assertEquals("new_camp", json.get("origin").getAsString());
		assertEquals("coopcraft:common_stockpile", json.get("next_era_milestone").getAsString());
		assertEquals("primitive", json.get("settlement_scale").getAsString());
		assertEquals(20, json.get("stockpile").getAsJsonObject().get("food_points").getAsInt());
		assertEquals("common_food_shortage", json.get("public_affairs").getAsJsonArray().get(0).getAsString());
		assertEquals("food", json.get("next_step").getAsString());
		assertEquals(-4, json.get("center").getAsJsonObject().get("x").getAsInt());
	}

	@Test
	void finalEraHasNoFakeNextMilestone() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		data.setEra(settlement, CivilizationEras.byId(CivilizationEras.INTELLIGENT));

		JsonObject json = AiPlayers.observationJson(data, settlement, false, 0, 0, List.of(), "observe");

		assertTrue(json.get("next_era_milestone").isJsonNull());
	}
}
