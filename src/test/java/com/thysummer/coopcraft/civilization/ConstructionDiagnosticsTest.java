package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionDiagnosticsTest {
	private static final List<String> REASONS = List.of("ready", "target_not_ticking", "target_occupied",
		"entity_obstructed", "stockpile_unavailable", "stockpile_full", "missing_material", "plan_unavailable", "invalid_index", "plan_unverified", "plan_changed", "invalid_job_id", "protected_area");
	private static final ResourceLocation BLUEPRINT = ResourceLocation.fromNamespaceAndPath("coopcraft", "stone_cottage");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private ConstructionReadiness snapshot(String blocker) {
		return new ConstructionReadiness(BLUEPRINT, 4, 12, new BlockPos(6, 64, -3), blocker);
	}

	private TranslatableContents translation(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}

	@Test
	void bothReadCommandsDispatchToTheirReadHandlers() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		var actions = new java.util.ArrayList<String>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			actions.add(action);
			assertEquals("", option);
			return 1;
		}));
		assertEquals(1, dispatcher.execute("coop settlement status", 0));
		assertEquals(1, dispatcher.execute("coop settlement materials", 0));
		assertEquals(List.of("status", "materials"), actions);
	}

	@Test
	void commandIncludesSavedProgressNextPositionAndEveryActualReason() {
		for (String reason : REASONS) {
			var readiness = snapshot(reason);
			var message = translation(SettlementCommands.constructionMessage(readiness));
			assertEquals("coopcraft.command.settlement.construction_progress", message.getKey());
			Object[] args = message.getArgs();
			assertEquals(BLUEPRINT.toString(), args[0]);
			assertEquals(4, args[1]);
			if (List.of("plan_unavailable", "plan_unverified", "plan_changed", "invalid_job_id").contains(reason)) {
				assertEquals("coopcraft.command.settlement.construction_unknown",
					translation((Component) args[2]).getKey());
			} else {
				assertEquals(12, args[2]);
			}
			assertEquals("6, 64, -3", args[3]);
			assertEquals("coopcraft.interaction.blocker.construction_" + reason,
				translation((Component) args[4]).getKey());
			assertEquals(snapshot(reason), readiness);
		}
	}

	@Test
	void invalidSnapshotDoesNotInventCoordinatesOrRepairSavedProgress() {
		var readiness = new ConstructionReadiness(BLUEPRINT, -1, 12, null, "invalid_index");
		var args = translation(SettlementCommands.constructionMessage(readiness)).getArgs();
		assertEquals(-1, args[1]);
		assertEquals("coopcraft.command.settlement.construction_unknown",
			translation((Component) args[3]).getKey());
		assertEquals(-1, readiness.nextIndex());
		assertNull(readiness.position());
	}

	@Test
	void idleResidentIsNotCalledBlockedWhenConstructionIsReady() {
		for (String reason : REASONS) {
			var status = VillagerWorkGoal.constructionStatus(snapshot(reason));
			assertEquals("idle", status.action());
			assertNull(status.target(), "A read-only status must not start movement or claim a build target");
			assertEquals(reason.equals("ready") ? null : "construction_" + reason, status.blocker());
		}
	}

	@Test
	void allDiagnosticKeysExistInBothLanguagesWithExpectedArgumentCount() throws Exception {
		for (String language : List.of("zh_cn", "en_us")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String reason : REASONS) {
					String key = "coopcraft.interaction.blocker.construction_" + reason;
					assertTrue(json.has(key), language + ": " + key);
					assertFalse(json.get(key).getAsString().isBlank());
				}
				var pattern = json.get("coopcraft.command.settlement.construction_progress").getAsString();
				assertEquals(5, pattern.split("%s", -1).length - 1);
				assertFalse(json.get("coopcraft.command.settlement.construction_unknown").getAsString().isBlank());
			}
		}
	}
}
