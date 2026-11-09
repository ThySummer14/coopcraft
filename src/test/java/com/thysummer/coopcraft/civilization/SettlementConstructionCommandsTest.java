package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementConstructionCommandsTest {
	private static final String FINGERPRINT = "v1-" + "0123456789abcdef".repeat(4);
	private static final List<String> STATUSES = List.of("adopted", "no_job", "not_legacy", "plan_unavailable",
		"invalid_index", "fingerprint_mismatch", "prefix_unloaded", "prefix_mismatch");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void actualTreeRoutesReadAndFingerprintAndEnforcesOperatorPermission() throws Exception {
		var calls = new ArrayList<String>();
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			assertEquals("construction", action);
			calls.add(option);
			if (option.equals("adopt")) {
				assertEquals(FINGERPRINT, SettlementCommands.adoptionFingerprint(context));
			}
			return 1;
		}));
		for (int level : List.of(0, 1, 2)) {
			assertEquals(1, dispatcher.execute("coop settlement construction", level));
		}
		var adopt = dispatcher.getRoot().getChild("coop").getChild("settlement")
			.getChild("construction").getChild("adopt");
		assertNotNull(adopt);
		// Raw Brigadier completion is not Minecraft's permission-filtered client command tree.
		// Verify the actual node requirement and execution gate, not suggestion visibility.
		for (int level : List.of(0, 1)) {
			assertFalse(adopt.canUse(level));
			assertThrows(CommandSyntaxException.class,
				() -> dispatcher.execute("coop settlement construction adopt " + FINGERPRINT, level));
			assertEquals(List.of("", "", ""), calls, "Rejected adoption must not dispatch a handler");
		}
		assertTrue(adopt.canUse(2));
		assertEquals(1, dispatcher.execute("coop settlement construction adopt " + FINGERPRINT, 2));
		assertEquals(List.of("", "", "", "adopt"), calls);
		for (String suffix : List.of("adopt", "adopt \"" + FINGERPRINT + "\"", "adopt " + FINGERPRINT + " 1 2 3", "cancel", "force", "adopt job-id " + FINGERPRINT)) {
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement construction " + suffix, 2));
		}
		assertEquals(4, calls.size(), "Invalid grammar must not dispatch any mutation");
	}

	@Test
	void fingerprintValidationRejectsMalformedDigestWithoutNormalization() {
		assertTrue(SettlementCommands.validFingerprint(FINGERPRINT));
		assertTrue(SettlementCommands.validFingerprint("v1-" + "a".repeat(64)));
		assertFalse(SettlementCommands.validFingerprint(null));
		for (String malformed : List.of("", "v2-" + "a".repeat(64), "v1-" + "A".repeat(64),
			"v1-" + "g".repeat(64), "v1-" + "a".repeat(63), "v1-" + "a".repeat(65),
			FINGERPRINT + "\n", " " + FINGERPRINT, FINGERPRINT + " ", "a".repeat(64))) {
			assertFalse(SettlementCommands.validFingerprint(malformed), malformed);
		}
	}

	private static TranslatableContents translation(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}

	@Test
	void savedSnapshotShowsUnknownFingerprintsAndNeverChangesJob() {
		var id = ResourceLocation.fromNamespaceAndPath("coopcraft", "stone_cottage");
		var job = new ConstructionJob(id, new BlockPos(3, 65, -7), 2);
		var before = job.save();
		var contents = translation(SettlementCommands.savedConstructionMessage(job, null));
		assertEquals("coopcraft.command.settlement.construction_saved", contents.getKey());
		var args = contents.getArgs();
		assertEquals(id.toString(), args[0]);
		assertEquals("3, 65, -7", args[1]);
		assertEquals(2, args[2]);
		for (int i : List.of(3, 4)) {
			assertEquals("coopcraft.command.settlement.construction_unknown", translation((Component) args[i]).getKey());
		}
		assertEquals("coopcraft.interaction.blocker.construction_plan_unavailable", translation((Component) args[5]).getKey());
		assertEquals(before, job.save());
	}

	@Test
	void savedSnapshotShowsBothSignaturesWithoutAcceptingChangedOrLegacyPlans() {
		var id = ResourceLocation.fromNamespaceAndPath("coopcraft", "stone_cottage");
		var instruction = new ConstructionInstruction(BlockPos.ZERO,
			net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(),
			net.minecraft.world.item.Items.COBBLESTONE, true);
		var plan = new ConstructionPlan(id, List.of(instruction));
		for (String signature : List.of("", FINGERPRINT, plan.fingerprint())) {
			var job = new ConstructionJob(id, BlockPos.ZERO, 0, signature);
			var before = job.save();
			var args = translation(SettlementCommands.savedConstructionMessage(job, plan)).getArgs();
			if (signature.isEmpty()) {
				assertEquals("coopcraft.command.settlement.construction_unknown", translation((Component) args[3]).getKey());
			} else {
				assertEquals(signature, args[3]);
			}
			assertEquals(plan.fingerprint(), args[4]);
			String reason = signature.isEmpty() ? "plan_unverified"
				: signature.equals(plan.fingerprint()) ? "ready" : "plan_changed";
			assertEquals(reason.equals("ready") ? "coopcraft.command.settlement.construction_compatible"
				: "coopcraft.interaction.blocker.construction_" + reason, translation((Component) args[5]).getKey());
			assertEquals(before, job.save());
		}
	}

	@Test
	void everyAdoptionResultExplainsStatusAndOptionalFailurePosition() {
		for (String status : STATUSES) {
			var contents = translation(SettlementCommands.adoptionMessage(new ConstructionPlanAdoption.Result(status, null)));
			assertEquals("coopcraft.command.settlement.construction_adopt_" + status, contents.getKey());
		}
		var contents = translation(SettlementCommands.adoptionMessage(
			new ConstructionPlanAdoption.Result("prefix_mismatch", new BlockPos(4, 70, -9))));
		assertEquals("coopcraft.command.settlement.construction_adopt_position", contents.getKey());
		assertEquals("coopcraft.command.settlement.construction_adopt_prefix_mismatch",
			translation((Component) contents.getArgs()[0]).getKey());
		assertEquals("4, 70, -9", contents.getArgs()[1]);
	}

	@Test
	void pairBlockersAreRenderedReadOnlyAndHaveBothTranslations() throws Exception {
		var statuses = List.of("pair_invalid", "target_out_of_bounds", "pair_out_of_bounds",
			"pair_not_ticking", "pair_occupied", "pair_changed",
			"completion_beds_unloaded", "completion_beds_out_of_bounds");
		for (String status : statuses) {
			var snapshot = new ConstructionReadiness(ConstructionPlanner.TIMBER_SIDE_PORCH_CABIN,
				99, 154, new BlockPos(10, 65, 3), status);
			var args = translation(SettlementCommands.constructionMessage(snapshot)).getArgs();
			assertEquals(99, args[1]);
			assertEquals(154, args[2]);
			assertEquals("10, 65, 3", args[3]);
			assertEquals("coopcraft.interaction.blocker.construction_" + status,
				translation((Component) args[4]).getKey());
			for (String language : List.of("en_us", "zh_cn")) {
				try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
					assertNotNull(input);
					var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
					String text = json.get("coopcraft.interaction.blocker.construction_" + status).getAsString();
					assertFalse(text.isBlank());
					assertFalse(text.contains("%s"));
				}
			}
		}
	}

	@Test
	void allConstructionMessagesHaveBothTranslationsAndCorrectArgumentCounts() throws Exception {
		for (String language : List.of("en_us", "zh_cn")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				var counts = new java.util.LinkedHashMap<>(Map.of("construction_saved", 6,
					"construction_materials_blocked", 2, "construction_adopt_help", 0, "construction_compatible", 0,
					"construction_adopt_invalid_fingerprint", 0, "construction_adopt_position", 2));
				STATUSES.forEach(status -> counts.put("construction_adopt_" + status, 0));
				counts.forEach((name, count) -> {
					String key = "coopcraft.command.settlement." + name;
					assertTrue(json.has(key), language + ": " + key);
					String text = json.get(key).getAsString();
					assertFalse(text.isBlank());
					assertEquals(count, text.split("%s", -1).length - 1, key);
				});
			}
		}
	}
}
