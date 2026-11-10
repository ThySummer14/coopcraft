package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.SettlementForagingPreview.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementForagingCommandsTest {
	private static final SettlementForagingPreview.Boundary BORDER = (x, z) -> true;
	private static final String DIMENSION = "minecraft:overworld";

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualGrammarRestrictsAllMutationsToOperatorsAndBoundsRadius() throws Exception {
		var calls = new ArrayList<String>();
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			assertEquals("foraging", action);
			calls.add(option);
			if (option.equals("preview")) assertTrue(Set.of(1, 16).contains(IntegerArgumentType.getInteger(context, "radius")));
			return 1;
		}));
		var node = dispatcher.getRoot().getChild("coop").getChild("settlement").getChild("foraging");
		for (int permission : List.of(0, 1, 2)) assertEquals(1, dispatcher.execute("coop settlement foraging", permission));
		for (String operation : List.of("preview 1", "preview 16", "confirm", "cancel", "revoke")) {
			for (int permission : List.of(0, 1)) {
				assertFalse(node.getChild(operation.split(" ")[0]).canUse(permission));
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement foraging " + operation, permission));
			}
			assertEquals(1, dispatcher.execute("coop settlement foraging " + operation, 2));
		}
		for (String invalid : List.of("preview", "preview 0", "preview -1", "preview 17", "preview 2147483648",
			"preview 1.5", "preview 4 0 0", "confirm 1", "preview wood 4", "authorize berry-1", "force")) {
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement foraging " + invalid, 2));
		}
		assertEquals(8, calls.size(), "Rejected commands must never reach a mutation handler");
	}

	@Test void statusReportsAbsenceAndExactAreaReadOnlyWithoutCreatingSimulation() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		assertEquals("none", suffix(SettlementForagingCommands.statusMessages(data, camp, DIMENSION).getFirst()));
		assertEquals("summary_none", suffix(SettlementForagingCommands.summaryMessage(data, camp)));
		var owner = UUID.randomUUID();
		data.permitForagingArea(camp, owner, new BlockPos(2, 200, -4), 5);
		data.setDirty(false);
		CompoundTag before = data.save(new CompoundTag(), null);
		var lines = SettlementForagingCommands.statusMessages(data, camp, DIMENSION);
		assertEquals("summary_configured", suffix(SettlementForagingCommands.summaryMessage(data, camp)));
		assertArrayEquals(new Object[] {"Home", owner.toString()}, translation(lines.getFirst()).getArgs());
		assertArrayEquals(new Object[] {DIMENSION, 2, -4, 5, -3, -9, 7, 1}, translation(lines.get(1)).getArgs());
		assertEquals(before, data.save(new CompoundTag(), null));
		assertFalse(data.isDirty());
		assertTrue(data.simulationOf(camp).isEmpty());
	}

	@Test void unsupportedPermitOrProtectionDataIsNeverReportedAsUsable() {
		var original = new SettlementSavedData();
		var camp = original.getOrCreate(BlockPos.ZERO, "Home", 0);
		original.permitForagingArea(camp, UUID.randomUUID(), BlockPos.ZERO, 4);
		for (String key : List.of("ForagingAreas", "PlayerProtectedAreas")) {
			CompoundTag saved = original.save(new CompoundTag(), null);
			saved.putString(key, "unsupported");
			var data = SettlementSavedData.load(saved, null);
			var loadedCamp = data.find(BlockPos.ZERO).orElseThrow();
			CompoundTag before = data.save(new CompoundTag(), null);
			var lines = SettlementForagingCommands.statusMessages(data, loadedCamp, DIMENSION);
			assertEquals("summary_unavailable", suffix(SettlementForagingCommands.summaryMessage(data, loadedCamp)));
			assertTrue(lines.stream().map(SettlementForagingCommandsTest::suffix).anyMatch(
				name -> name.equals(key.equals("ForagingAreas") ? "preserved_unsupported" : "protection_unavailable")));
			assertFalse(data.canHarvestBerries(loadedCamp, BlockPos.ZERO));
			assertEquals(before, data.save(new CompoundTag(), null));
		}
	}

	@Test void confirmationUsesFrozenCoordinatesAndWritesAtMostOnce() {
		var previews = new SettlementForagingPreview();
		var actor = actor(); var camp = camp();
		var mutable = new BlockPos.MutableBlockPos(3, 72, -5);
		var draft = previews.preview(actor, camp, mutable, 4, null, 10, BORDER).draft();
		mutable.set(20, 99, 20);
		AtomicInteger writes = new AtomicInteger();
		SettlementForagingPreview.Writer writer = frozen -> {
			writes.incrementAndGet();
			assertEquals(new BlockPos(3, 72, -5), frozen.center());
			assertEquals(4, frozen.radius()); assertSame(camp, frozen.settlement());
			return new SettlementForagingAreas.PermitResult(SettlementForagingAreas.Status.CREATED, area(camp));
		};
		var result = previews.confirm(actor, 11, camp, null, BORDER, writer);
		assertEquals(CONFIRMED, result.preview().status());
		assertSame(draft, result.preview().draft());
		assertEquals(NO_PREVIEW, previews.confirm(actor, 12, camp, null, BORDER, writer).preview().status());
		assertEquals(1, writes.get());
	}

	@Test void changingNearestCampOrPermitGenerationInvalidatesConfirmation() {
		var previews = new SettlementForagingPreview(); var actor = actor(); var camp = camp();
		var previous = area(camp);
		previews.preview(actor, camp, BlockPos.ZERO, 2, previous, 0, BORDER);
		assertEquals(CAMP_CHANGED, previews.confirm(actor, 1, camp(), previous, BORDER, neverWrite()).preview().status());
		for (var current : new SettlementForagingAreas.Area[] {null, area(camp)}) {
			previews.preview(actor, camp, BlockPos.ZERO, 2, previous, 0, BORDER);
			assertEquals(AREA_CHANGED, previews.confirm(actor, 1, camp, current, BORDER, neverWrite()).preview().status());
		}
		previews.preview(actor, camp, BlockPos.ZERO, 2, null, 0, BORDER);
		assertEquals(AREA_CHANGED, previews.confirm(actor, 1, camp, previous, BORDER, neverWrite()).preview().status());
		assertEquals(0, previews.size());
	}

	@Test void previewsAndInspectionNeverSlideTheFixedExpiry() {
		var previews = new SettlementForagingPreview(); var actor = actor(); var camp = camp();
		previews.preview(actor, camp, BlockPos.ZERO, 1, null, 10, BORDER);
		assertEquals(PREVIEW, previews.inspect(actor, 6009).status());
		assertEquals(10, previews.preview(actor, camp, BlockPos.ZERO.east(), 2, null, 6009, BORDER).draft().createdTick());
		assertEquals(EXPIRED, previews.confirm(actor, 6010, camp, null, BORDER, neverWrite()).preview().status());
		previews.preview(actor, camp, BlockPos.ZERO, 1, null, 10, BORDER);
		assertEquals(EXPIRED, previews.inspect(actor, 9).status(), "A clock reset also fails closed");
	}

	@Test void disconnectRespawnDimensionChangeAndLifecycleCleanupDiscardConsent() {
		var previews = new SettlementForagingPreview(); var actor = actor(); var camp = camp();
		var changedActors = List.of(new SettlementForagingPreview.Actor(actor.id(), new Object(), DIMENSION, true),
			new SettlementForagingPreview.Actor(actor.id(), actor.identity(), DIMENSION, false),
			new SettlementForagingPreview.Actor(actor.id(), actor.identity(), "minecraft:the_nether", true));
		for (var changed : changedActors) {
			previews.preview(actor, camp, BlockPos.ZERO, 2, null, 0, BORDER);
			assertEquals(changed.dimension().equals(DIMENSION) ? PLAYER_UNAVAILABLE : DIMENSION_CHANGED,
				previews.confirm(changed, 1, camp, null, BORDER, neverWrite()).preview().status());
			assertEquals(NO_PREVIEW, previews.inspect(actor, 1).status());
		}
		previews.preview(actor, camp, BlockPos.ZERO, 2, null, 0, BORDER);
		previews.forget(actor.id());
		assertEquals(NO_PREVIEW, previews.inspect(actor, 1).status());
		previews.preview(actor, camp, BlockPos.ZERO, 2, null, 0, BORDER);
		previews.clear();
		assertEquals(0, previews.size());
	}

	@Test void fixedCapacityAllowsReplacingOnePreviewAndReclaimsExpiredEntries() {
		var previews = new SettlementForagingPreview(); var camp = camp();
		var first = actor();
		previews.preview(first, camp, BlockPos.ZERO, 1, null, 0, BORDER);
		for (int i = 1; i < SettlementForagingPreview.MAX_PREVIEWS; i++) {
			assertEquals(PREVIEW, previews.preview(actor(), camp, BlockPos.ZERO, 1, null, 0, BORDER).status());
		}
		assertEquals(256, previews.size());
		assertEquals(SESSION_LIMIT, previews.preview(actor(), camp, BlockPos.ZERO, 1, null, 1, BORDER).status());
		assertEquals(PREVIEW, previews.preview(first, camp, BlockPos.ZERO, 2, null, 1, BORDER).status());
		assertEquals(256, previews.size());
		assertEquals(PREVIEW, previews.preview(actor(), camp, BlockPos.ZERO, 1, null, 6000, BORDER).status());
		assertEquals(1, previews.size());
	}

	@Test void allFourWorldBorderCornersAreCheckedAtPreviewAndAgainAtConfirm() {
		var previews = new SettlementForagingPreview(); var actor = actor(); var camp = camp();
		var corners = new ArrayList<String>();
		previews.preview(actor, camp, BlockPos.ZERO, 4, null, 0, (x, z) -> { corners.add(x + "," + z); return true; });
		assertEquals(Set.of("-4,-4", "-4,4", "4,-4", "4,4"), Set.copyOf(corners));
		assertEquals(OUT_OF_BOUNDS, previews.confirm(actor, 1, camp, null,
			(x, z) -> x != -4 || z != 4, neverWrite()).preview().status());
		assertEquals(OUT_OF_BOUNDS, previews.preview(actor, camp, BlockPos.ZERO, 4, null, 0,
			(x, z) -> x != 4 || z != -4).status());
		assertEquals(0, previews.size());
	}

	@Test void invalidReplacementCancelsOldPreviewAndSharesSavedDataRangeRules() {
		var previews = new SettlementForagingPreview(); var actor = actor(); var camp = camp();
		for (int radius : List.of(0, 17, Integer.MAX_VALUE)) {
			previews.preview(actor, camp, BlockPos.ZERO, 1, null, 0, BORDER);
			assertEquals(INVALID, previews.preview(actor, camp, BlockPos.ZERO, radius, null, 1, BORDER).status());
			assertEquals(NO_PREVIEW, previews.inspect(actor, 1).status());
		}
		assertEquals(INVALID, previews.preview(actor, camp, new BlockPos(63, 0, 0), 1, null, 0, BORDER).status());
		assertEquals(PREVIEW, previews.preview(actor, camp, new BlockPos(62, 1000, 0), 1, null, 0, BORDER).status());
		assertEquals(CANCELLED, previews.cancel(actor.id()).status());
		assertEquals(NO_PREVIEW, previews.cancel(actor.id()).status());
	}

	@Test void rejectedOrThrowingWritesAndSettlementInvalidationCannotReuseConsent() {
		var previews = new SettlementForagingPreview(); var actor = actor(); var camp = camp();
		previews.preview(actor, camp, BlockPos.ZERO, 2, null, 0, BORDER);
		var result = previews.confirm(actor, 1, camp, null, BORDER,
			draft -> new SettlementForagingAreas.PermitResult(SettlementForagingAreas.Status.LIMIT, null));
		assertEquals(SettlementForagingAreas.Status.LIMIT, result.saved().status());
		assertEquals(NO_PREVIEW, previews.inspect(actor, 1).status());
		previews.preview(actor, camp, BlockPos.ZERO, 2, null, 0, BORDER);
		assertThrows(IllegalStateException.class, () -> previews.confirm(actor, 1, camp, null, BORDER,
			draft -> { throw new IllegalStateException("Interrupted save"); }));
		assertEquals(NO_PREVIEW, previews.inspect(actor, 1).status());
		previews.preview(actor, camp, BlockPos.ZERO, 2, null, 0, BORDER);
		previews.preview(actor(), camp, BlockPos.ZERO, 2, null, 0, BORDER);
		previews.forgetSettlement("minecraft:the_nether", camp.id());
		assertEquals(2, previews.size());
		previews.forgetSettlement(DIMENSION, camp.id());
		assertEquals(0, previews.size(), "A create/revoke round trip must not revive previews from before the change");
	}

	@Test void previewDisclosesExactScopeAndEveryFeedbackHasBothTranslations() throws Exception {
		var previews = new SettlementForagingPreview(); var actor = actor(); var camp = camp();
		var draft = previews.preview(actor, camp, new BlockPos(2, 72, -4), 5, null, 10, BORDER).draft();
		var messages = SettlementForagingCommands.previewMessages(draft, 30);
		assertEquals(List.of("preview", "range", "preview_new", "scope", "confirm_help"),
			messages.stream().map(SettlementForagingCommandsTest::suffix).toList());
		assertArrayEquals(new Object[] {299L}, translation(messages.getLast()).getArgs());
		var expected = new java.util.LinkedHashMap<>(Map.of("area", 2, "none", 1, "range", 8, "preview", 2, "confirm_help", 1));
		for (var status : SettlementForagingPreview.Status.values()) expected.putIfAbsent(status.name().toLowerCase(java.util.Locale.ROOT), 0);
		for (var status : SettlementForagingAreas.Status.values()) expected.putIfAbsent(status.name().toLowerCase(java.util.Locale.ROOT), 0);
		for (String key : List.of("scope", "help", "preview_new", "preview_replace", "protection_unavailable", "invalid_saved_range",
			"summary_none", "summary_configured", "summary_unavailable", "permission", "actual_dimension")) expected.put(key, 0);
		for (String language : List.of("en_us", "zh_cn")) {
			try (var input = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(input);
				var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var entry : expected.entrySet()) {
					String key = "coopcraft.command.settlement.foraging_" + entry.getKey();
					assertTrue(json.has(key), language + ": " + key);
					String text = json.get(key).getAsString();
					assertFalse(text.isBlank());
					assertEquals(entry.getValue().intValue(), text.split("%s", -1).length - 1, key);
				}
				String scope = json.get("coopcraft.command.settlement.foraging_scope").getAsString();
				for (String term : language.equals("en_us") ? List.of("sweet berries", "player-planted", "underground", "all heights", "protection", "wood", "stone", "UNKNOWN")
					: List.of("甜浆果", "玩家种植", "地下", "全部高度", "保护", "砍木", "挖石", "UNKNOWN")) assertTrue(scope.contains(term), term);
			}
		}
	}

	private static Settlement camp() { return new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Home", 0); }
	private static SettlementForagingPreview.Actor actor() { return new SettlementForagingPreview.Actor(UUID.randomUUID(), new Object(), DIMENSION, true); }
	private static SettlementForagingAreas.Area area(Settlement camp) { return new SettlementForagingAreas.Area(UUID.randomUUID(), camp.id(), UUID.randomUUID(), 0, 0, 2); }
	private static SettlementForagingPreview.Writer neverWrite() { return draft -> { fail("Rejected preview must not write"); return null; }; }
	private static TranslatableContents translation(Component line) { return assertInstanceOf(TranslatableContents.class, line.getContents()); }
	private static String suffix(Component line) { return translation(line).getKey().substring("coopcraft.command.settlement.foraging_".length()); }
}
