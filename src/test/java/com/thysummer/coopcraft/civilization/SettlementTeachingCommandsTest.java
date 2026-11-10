package com.thysummer.coopcraft.civilization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure scene policy and actual command grammar; no running server or GameTest. */
class SettlementTeachingCommandsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void grammarRequiresExplicitPairAndHoldRequiresOperator() throws Exception {
		String teacher = UUID.randomUUID().toString(), learner = UUID.randomUUID().toString();
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(op -> op >= 2, (ctx, action, operation) -> {
			assertEquals("teaching", action);
			if (operation.isEmpty()) return 1;
			assertEquals(teacher, StringArgumentType.getString(ctx, "teacher_uuid"));
			assertEquals(learner, StringArgumentType.getString(ctx, "learner_uuid"));
			return operation.equals("hold") ? 3 : 2;
		}));
		assertEquals(1, dispatcher.execute("coop settlement teaching", 0));
		for (int op : List.of(0, 1, 2)) assertEquals(2, dispatcher.execute("coop settlement teaching inspect " + teacher + " " + learner, op));
		for (int op : List.of(0, 1)) assertThrows(CommandSyntaxException.class,
			() -> dispatcher.execute("coop settlement teaching hold " + teacher + " " + learner, op));
		assertEquals(3, dispatcher.execute("coop settlement teaching hold " + teacher + " " + learner, 2));
		for (String suffix : List.of("inspect", "hold", "hold " + teacher, "inspect " + teacher,
			"hold " + teacher + " " + learner + " force", "confirm", "auto"))
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement teaching " + suffix, 2));
	}

	@Test void exactUuidPolicyRejectsSelectorsAndAbbreviations() {
		String id = "abcdefab-1234-5678-9012-abcdefabcdef";
		assertEquals(UUID.fromString(id), SettlementTeachingCommands.parseId(id));
		assertEquals(UUID.fromString(id), SettlementTeachingCommands.parseId(id.toUpperCase(java.util.Locale.ROOT)));
		assertNull(SettlementTeachingCommands.parseId(null));
		for (String bad : List.of("@e", "@s", "1-1-1-1-1", "", " " + id, id + " ")) assertNull(SettlementTeachingCommands.parseId(bad));
	}

	@Test void playerGateRequiresBothPermissionsAndRealLivingOnlineIdentity() {
		assertTrue(SettlementTeachingScene.playerAllowed(false, false, false, true, true, false, false));
		assertTrue(SettlementTeachingScene.playerAllowed(true, true, true, true, true, false, false));
		assertFalse(SettlementTeachingScene.playerAllowed(true, false, true, true, true, false, false));
		assertFalse(SettlementTeachingScene.playerAllowed(true, true, false, true, true, false, false));
		for (boolean mutation : List.of(false, true)) {
			assertFalse(SettlementTeachingScene.playerAllowed(mutation, true, true, false, true, false, false));
			assertFalse(SettlementTeachingScene.playerAllowed(mutation, true, true, true, false, false, false));
			assertFalse(SettlementTeachingScene.playerAllowed(mutation, true, true, true, true, true, false));
			assertFalse(SettlementTeachingScene.playerAllowed(mutation, true, true, true, true, false, true));
		}
	}

	@Test void fourBlockInclusiveBoundaryRejectsNonfiniteAndNegativeDistances() {
		assertTrue(SettlementTeachingScene.near(0));
		assertTrue(SettlementTeachingScene.near(16));
		for (double distance : new double[] {-1, 16.00001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
			assertFalse(SettlementTeachingScene.near(distance));
		assertEquals(16, SettlementTeachingCommands.MAX_CANDIDATES);
	}

	@Test void residentGateRejectsEveryUrgentOrUnavailableState() {
		assertTrue(eligible(-1));
		for (int gate = 0; gate < 10; gate++) assertFalse(eligible(gate), "gate " + gate);
		assertFalse(SettlementTeachingScene.residentAllowed(true, false, false, Double.NaN, 20, false, false, false, false, false, false));
	}
	private static boolean eligible(int bad) {
		return SettlementTeachingScene.residentAllowed(bad != 0, bad == 1, bad == 2, bad == 3 ? 19 : 20, 20,
			bad == 4, bad == 5, bad == 6, bad == 7, bad == 8, bad == 9);
	}

	@Test void commitRechecksLiveSceneAndNeitherReadNorHoldTouchesInventoryOrGoals() throws Exception {
		String commands = source("SettlementTeachingCommands"), scene = source("SettlementTeachingScene");
		assertTrue(commands.contains("SettlementCraftTeaching.teach(data, camp, teacherId, learnerId, () -> scene.authority(source, player))"));
		assertTrue(scene.contains("status(source, player, true).equals(\"ready\")"));
		for (String required : List.of("source.getPosition().equals(player.position())", "source.getLevel() == level",
			"level.getEntity(villager.getUUID()) != villager", "SettlementMembershipClaims.resolve", "stationToken != data.craftingStationBindingToken(camp)",
			"data.simulationOf(camp).orElse(null) != state", "level.isLoaded(pos)", "level.isPositionEntityTicking(pos)",
			"data.protectedAreas().contains(station)", "state.lastSurvivalDay()", "AgeBand.ADULT", "AgeBand.ELDER")) assertTrue(scene.contains(required), required);
		for (String forbidden : List.of("getInventory(", "getChunk(", "getOrCreate", "getNavigation(", "setItem(", "removeItem(", "setSkill(", "setCraftingStation(")) {
			assertFalse(commands.contains(forbidden), forbidden);
			assertFalse(scene.contains(forbidden), forbidden);
		}
	}
	private static String source(String name) throws Exception {
		return Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/" + name + ".java"));
	}
}
