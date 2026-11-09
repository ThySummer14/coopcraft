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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementConstructionSitesCommandTest {
	private static final ResourceLocation HOUSE = ConstructionPlanner.STONE_COTTAGE;
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void readOnlySitesRouteIsAvailableWithoutOperatorPermissionAndTakesNoCoordinates() throws Exception {
		var calls = new ArrayList<String>();
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			calls.add(action + ":" + option); return 1;
		}));
		for (int level : List.of(0, 1, 2)) assertEquals(1, dispatcher.execute("coop settlement construction sites", level));
		assertEquals(List.of("construction:sites", "construction:sites", "construction:sites"), calls);
		for (String suffix : List.of("1 2 3", "force", "clear", "confirm")) {
			assertThrows(CommandSyntaxException.class,
				() -> dispatcher.execute("coop settlement construction sites " + suffix, 2));
		}
		assertEquals(3, calls.size());
	}

	@Test void noPendingHousingProducesAnExplicitEmptySurveyMessage() {
		var lines = SettlementCommands.constructionSiteMessages(List.of());
		assertEquals(1, lines.size());
		assertEquals(key("construction_sites_none"), contents(lines.getFirst()).getKey());
	}

	@Test void firstPassingOriginIsReportedWithoutClaimingMaterialsOrNavigation() {
		var candidates = List.of(result("volume_occupied", 6, new BlockPos(7, 65, 0)),
			result("ready", 12, null), result("ready", 18, null));
		var copy = List.copyOf(candidates);
		var lines = SettlementCommands.constructionSiteMessages(candidates);
		assertEquals(2, lines.size());
		var ready = contents(lines.getFirst());
		assertEquals(key("construction_sites_ready"), ready.getKey());
		assertArrayEquals(new Object[] {HOUSE.toString(), "12, 64, 0"}, ready.getArgs());
		assertEquals(key("construction_sites_scope"), contents(lines.getLast()).getKey());
		assertEquals(copy, candidates);
		assertThrows(UnsupportedOperationException.class, () -> lines.add(Component.literal("mutation")));
	}

	@Test void unavailableBlueprintDoesNotInventAnInspectedCandidateCount() {
		var lines = SettlementCommands.constructionSiteMessages(List.of(result("plan_unavailable", 0, null)));
		assertEquals(2, lines.size());
		assertEquals(key("construction_sites_plan_unavailable"), contents(lines.getFirst()).getKey());
		assertArrayEquals(new Object[] {HOUSE.toString()}, contents(lines.getFirst()).getArgs());
	}

	@Test void blockedCandidatesAreGroupedInOrderWithFirstOriginAndExactFailurePosition() {
		var lines = SettlementCommands.constructionSiteMessages(List.of(
			result("foundation_unsupported", 6, new BlockPos(7, 63, 0)),
			result("plan_unavailable", 12, null),
			result("foundation_unsupported", 18, new BlockPos(18, 63, 0))));
		assertEquals(4, lines.size());
		assertArrayEquals(new Object[] {HOUSE.toString(), 3}, contents(lines.getFirst()).getArgs());
		var first = contents(lines.get(1));
		assertEquals(key("construction_sites_reason"), first.getKey());
		assertEquals(2, first.getArgs()[0]);
		assertEquals(key("construction_site_foundation_unsupported"), contents((Component) first.getArgs()[1]).getKey());
		assertEquals("6, 64, 0", first.getArgs()[2]);
		assertEquals("7, 63, 0", first.getArgs()[3]);
		assertEquals(key("construction_unknown"), contents((Component) contents(lines.get(2)).getArgs()[3]).getKey());
	}

	@Test void differentBlueprintsNeverShareAReadySiteOrFailureCount() {
		var second = ResourceLocation.parse("coopcraft:future_house");
		var lines = SettlementCommands.constructionSiteMessages(List.of(result("ready", 6, null),
			new ConstructionSite.Result(second, new BlockPos(12, 64, 0), "unsupported_layout", null)));
		assertEquals(4, lines.size());
		assertEquals(HOUSE.toString(), contents(lines.getFirst()).getArgs()[0]);
		assertArrayEquals(new Object[] {second.toString(), 1}, contents(lines.get(1)).getArgs());
	}

	@Test void savedJobSkipsSurveyWithoutAWorldAndSurvivesSaveReloadUnchanged() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Existing house", 0);
		var job = new ConstructionJob(HOUSE, new BlockPos(6, 0, 0), 3);
		data.setConstructionJob(settlement, job);
		var before = data.save(new CompoundTag(), null); data.setDirty(false);
		assertTrue(ConstructionSystem.inspectNewHousingSites(null, data, settlement).isEmpty());
		assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
		assertFalse(data.isDirty()); assertEquals(before, data.save(new CompoundTag(), null));
		var restored = SettlementSavedData.load(before, null);
		assertEquals(before, restored.save(new CompoundTag(), null));
		assertTrue(ConstructionSystem.inspectNewHousingSites(null, restored, restored.find(BlockPos.ZERO).orElseThrow()).isEmpty());
	}

	@Test void everySurveyMessageAndFailureReasonHasBilingualArgumentParity() throws Exception {
		var counts = new java.util.LinkedHashMap<>(Map.of("construction_sites_existing", 0,
			"construction_sites_none", 0, "construction_sites_ready", 2, "construction_sites_blocked", 2,
			"construction_sites_reason", 4, "construction_sites_scope", 0, "construction_sites_plan_unavailable", 1));
		for (String reason : List.of("plan_unavailable", "unsupported_layout", "out_of_bounds", "not_ticking",
			"foundation_unsupported", "volume_occupied", "entrance_obstructed", "entrance_unsupported"))
			counts.put("construction_site_" + reason, 0);
		for (String language : List.of("en_us", "zh_cn")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				counts.forEach((name, count) -> {
					assertTrue(json.has(key(name)), language + ":" + name);
					String text = json.get(key(name)).getAsString(); assertFalse(text.isBlank());
					assertEquals(count, text.split("%s", -1).length - 1);
				});
			}
		}
	}

	private static ConstructionSite.Result result(String reason, int x, BlockPos failure) {
		return new ConstructionSite.Result(HOUSE, new BlockPos(x, 64, 0), reason, failure);
	}
	private static String key(String suffix) { return "coopcraft.command.settlement." + suffix; }
	private static TranslatableContents contents(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents());
	}
}
