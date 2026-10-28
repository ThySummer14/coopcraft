package com.thysummer.coopcraft.civilization;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionPlanTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void loadsCottageWithOneMaterialPerBlockAndMultipartObject() throws Exception {
		try (InputStream input = ConstructionPlanTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/stone_cottage.json"
		)) {
			JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			ConstructionPlan plan = ConstructionPlan.fromJson(ConstructionSystem.STONE_COTTAGE, json);

			assertEquals(ConstructionSystem.STONE_COTTAGE, plan.id());
			assertEquals(85, plan.instructions().size());
			assertEquals(85, plan.instructions().stream().map(ConstructionInstruction::offset).distinct().count());
			assertEquals(Map.of(
				Items.COBBLESTONE, 50,
				Items.OAK_PLANKS, 29,
				Items.OAK_DOOR, 1,
				Items.WHITE_BED, 1,
				Items.TORCH, 2
			), plan.materials());
			assertEquals(2, plan.instructions().stream().filter(part -> part.item() == Items.OAK_DOOR).count());
			assertEquals(2, plan.instructions().stream().filter(part -> part.item() == Items.WHITE_BED).count());
			assertEquals(1, plan.instructions().stream().filter(part -> part.item() == Items.OAK_DOOR && part.consume()).count());
			assertEquals(1, plan.instructions().stream().filter(part -> part.item() == Items.WHITE_BED && part.consume()).count());
			assertEquals(List.of(true, false), plan.instructions().stream().filter(part -> part.item() == Items.OAK_DOOR).map(ConstructionInstruction::consume).toList());
			assertEquals(List.of(true, false), plan.instructions().stream().filter(part -> part.item() == Items.WHITE_BED).map(ConstructionInstruction::consume).toList());
			assertEquals(List.of(79, 80, 81, 82), plan.instructions().stream()
				.filter(part -> part.item() == Items.OAK_DOOR || part.item() == Items.WHITE_BED)
				.map(part -> plan.instructions().indexOf(part))
				.toList());
		}
	}
}
