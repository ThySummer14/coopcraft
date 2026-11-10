package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

import static com.thysummer.coopcraft.civilization.SettlementGatheringPreview.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementGatheringCommandsTest {
	private static final SettlementGatheringPreview.Boundary BORDER = (x, z) -> true;
	private static final String DIMENSION = "minecraft:overworld";

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualGrammarRestrictsAllMutationsToOperatorsAndBoundsRadius() throws Exception {
		var calls = new ArrayList<String>();
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			assertEquals("gathering", action);
			calls.add(option);
			if (option.startsWith("preview_")) assertTrue(Set.of(1, 4, 8).contains(IntegerArgumentType.getInteger(context, "radius")));
			return 1;
		}));
		var node = dispatcher.getRoot().getChild("coop").getChild("settlement").getChild("gathering");
		for (int permission : List.of(0, 1, 2)) assertEquals(1, dispatcher.execute("coop settlement gathering", permission));
		for (String operation : List.of("preview wood 1", "preview stone 8", "preview both 4", "confirm", "cancel", "revoke")) {
			for (int permission : List.of(0, 1)) {
				assertFalse(node.getChild(operation.split(" ")[0]).canUse(permission));
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement gathering " + operation, permission));
			}
			assertEquals(1, dispatcher.execute("coop settlement gathering " + operation, 2));
		}
		for (String invalid : List.of("preview", "preview wood 0", "preview stone -1", "preview both 9", "preview wood 2147483648",
			"preview both 1.5", "preview wood 4 0 0", "confirm 1", "preview berry 4", "preview 4", "force")) {
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement gathering " + invalid, 2));
		}
		assertEquals(9, calls.size(), "Rejected commands must never reach a mutation handler");
	}

	@Test void statusReportsAbsenceAndExactAreaReadOnlyWithoutCreatingSimulation() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		assertEquals("none", suffix(SettlementGatheringCommands.statusMessages(data, camp, DIMENSION).getFirst()));
		assertEquals("summary_none", suffix(SettlementGatheringCommands.summaryMessage(data, camp)));
		var owner = UUID.randomUUID();
		data.permitGatheringArea(camp, owner, new BlockPos(2, 200, -4), 5, SettlementGatheringAreas.Mode.STONE);
		data.setDirty(false);
		CompoundTag before = data.save(new CompoundTag(), null);
		var lines = SettlementGatheringCommands.statusMessages(data, camp, DIMENSION);
		assertEquals("summary_configured", suffix(SettlementGatheringCommands.summaryMessage(data, camp)));
		assertArrayEquals(new Object[] {"Home", owner.toString()}, translation(lines.getFirst()).getArgs());
		assertArrayEquals(new Object[] {DIMENSION, 2, -4, 5, -3, -9, 7, 1}, translation(lines.get(2)).getArgs());
		assertEquals("mode_stone", suffix(lines.get(1)));
		assertEquals(before, data.save(new CompoundTag(), null));
		assertFalse(data.isDirty());
		assertTrue(data.simulationOf(camp).isEmpty());
	}

	@Test void unsupportedPermitOrProtectionDataIsNeverReportedAsUsable() {
		var original = new SettlementSavedData();
		var camp = original.getOrCreate(BlockPos.ZERO, "Home", 0);
		original.permitGatheringArea(camp, UUID.randomUUID(), BlockPos.ZERO, 4, SettlementGatheringAreas.Mode.WOOD);
		for (String key : List.of("GatheringAreas", "PlayerProtectedAreas")) {
			CompoundTag saved = original.save(new CompoundTag(), null);
			saved.putString(key, "unsupported");
			var data = SettlementSavedData.load(saved, null);
			var loadedCamp = data.find(BlockPos.ZERO).orElseThrow();
			CompoundTag before = data.save(new CompoundTag(), null);
			var lines = SettlementGatheringCommands.statusMessages(data, loadedCamp, DIMENSION);
			assertEquals("summary_unavailable", suffix(SettlementGatheringCommands.summaryMessage(data, loadedCamp)));
			assertTrue(lines.stream().map(SettlementGatheringCommandsTest::suffix).anyMatch(
				name -> name.equals(key.equals("GatheringAreas") ? "preserved_unsupported" : "protection_unavailable")));
			assertFalse(data.canGatherMaterial(loadedCamp, BlockPos.ZERO, SettlementResourceObservations.Kind.OAK_LOG));
			assertEquals(before, data.save(new CompoundTag(), null));
		}
	}

	@Test void bothUnsupportedSubtreesAreVisibleWithoutMutation() {
		var original = new SettlementSavedData();
		original.getOrCreate(BlockPos.ZERO, "Home", 0);
		var saved = original.save(new CompoundTag(), null);
		saved.putString("GatheringAreas", "future");
		saved.putString("PlayerProtectedAreas", "future");
		var data = SettlementSavedData.load(saved, null);
		var camp = data.find(BlockPos.ZERO).orElseThrow();
		var before = data.save(new CompoundTag(), null);
		assertEquals(List.of("preserved_unsupported", "protection_unavailable", "scope"),
			SettlementGatheringCommands.statusMessages(data, camp, DIMENSION).stream()
				.map(SettlementGatheringCommandsTest::suffix).toList());
		assertEquals(before, data.save(new CompoundTag(), null));
	}

	@Test void confirmationUsesFrozenCoordinatesAndWritesAtMostOnce() {
		var previews = new SettlementGatheringPreview();
		var actor = actor(); var camp = camp();
		var mutable = new BlockPos.MutableBlockPos(3, 72, -5);
		var draft = previews.preview(actor, camp, mutable, 4, SettlementGatheringAreas.Mode.BOTH, null, 10, BORDER).draft();
		mutable.set(20, 99, 20);
		AtomicInteger writes = new AtomicInteger();
		SettlementGatheringPreview.Writer writer = frozen -> {
			writes.incrementAndGet();
			assertEquals(new BlockPos(3, 72, -5), frozen.center());
			assertEquals(4, frozen.radius()); assertEquals(SettlementGatheringAreas.Mode.BOTH, frozen.mode()); assertSame(camp, frozen.settlement());
			return new SettlementGatheringAreas.PermitResult(SettlementGatheringAreas.Status.CREATED, area(camp));
		};
		var result = previews.confirm(actor, 11, camp, null, BORDER, writer);
		assertEquals(CONFIRMED, result.preview().status());
		assertSame(draft, result.preview().draft());
		assertEquals(NO_PREVIEW, previews.confirm(actor, 12, camp, null, BORDER, writer).preview().status());
		assertEquals(1, writes.get());
	}

	@Test void changingNearestCampOrPermitGenerationInvalidatesConfirmation() {
		var previews = new SettlementGatheringPreview(); var actor = actor(); var camp = camp();
		var previous = area(camp);
		previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, previous, 0, BORDER);
		assertEquals(CAMP_CHANGED, previews.confirm(actor, 1, camp(), previous, BORDER, neverWrite()).preview().status());
		for (var current : new SettlementGatheringAreas.Area[] {null, area(camp)}) {
			previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, previous, 0, BORDER);
			assertEquals(AREA_CHANGED, previews.confirm(actor, 1, camp, current, BORDER, neverWrite()).preview().status());
		}
		previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 0, BORDER);
		assertEquals(AREA_CHANGED, previews.confirm(actor, 1, camp, previous, BORDER, neverWrite()).preview().status());
		assertEquals(0, previews.size());
	}

	@Test void previewsAndInspectionNeverSlideTheFixedExpiry() {
		var previews = new SettlementGatheringPreview(); var actor = actor(); var camp = camp();
		previews.preview(actor, camp, BlockPos.ZERO, 1, SettlementGatheringAreas.Mode.BOTH, null, 10, BORDER);
		assertEquals(PREVIEW, previews.inspect(actor, 6009).status());
		assertEquals(10, previews.preview(actor, camp, BlockPos.ZERO.east(), 2, SettlementGatheringAreas.Mode.BOTH, null, 6009, BORDER).draft().createdTick());
		assertEquals(EXPIRED, previews.confirm(actor, 6010, camp, null, BORDER, neverWrite()).preview().status());
		previews.preview(actor, camp, BlockPos.ZERO, 1, SettlementGatheringAreas.Mode.BOTH, null, 10, BORDER);
		assertEquals(EXPIRED, previews.inspect(actor, 9).status(), "A clock reset also fails closed");
	}

	@Test void disconnectRespawnDimensionChangeAndLifecycleCleanupDiscardConsent() {
		var previews = new SettlementGatheringPreview(); var actor = actor(); var camp = camp();
		var changedActors = List.of(new SettlementGatheringPreview.Actor(actor.id(), new Object(), DIMENSION, true),
			new SettlementGatheringPreview.Actor(actor.id(), actor.identity(), DIMENSION, false),
			new SettlementGatheringPreview.Actor(actor.id(), actor.identity(), "minecraft:the_nether", true));
		for (var changed : changedActors) {
			previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 0, BORDER);
			assertEquals(changed.dimension().equals(DIMENSION) ? PLAYER_UNAVAILABLE : DIMENSION_CHANGED,
				previews.confirm(changed, 1, camp, null, BORDER, neverWrite()).preview().status());
			assertEquals(NO_PREVIEW, previews.inspect(actor, 1).status());
		}
		previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 0, BORDER);
		previews.forget(actor.id());
		assertEquals(NO_PREVIEW, previews.inspect(actor, 1).status());
		previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 0, BORDER);
		previews.clear();
		assertEquals(0, previews.size());
	}

	@Test void fixedCapacityAllowsReplacingOnePreviewAndReclaimsExpiredEntries() {
		var previews = new SettlementGatheringPreview(); var camp = camp();
		var first = actor();
		previews.preview(first, camp, BlockPos.ZERO, 1, SettlementGatheringAreas.Mode.BOTH, null, 0, BORDER);
		for (int i = 1; i < SettlementGatheringPreview.MAX_PREVIEWS; i++) {
			assertEquals(PREVIEW, previews.preview(actor(), camp, BlockPos.ZERO, 1, SettlementGatheringAreas.Mode.BOTH, null, 0, BORDER).status());
		}
		assertEquals(256, previews.size());
		assertEquals(SESSION_LIMIT, previews.preview(actor(), camp, BlockPos.ZERO, 1, SettlementGatheringAreas.Mode.BOTH, null, 1, BORDER).status());
		assertEquals(PREVIEW, previews.preview(first, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 1, BORDER).status());
		assertEquals(256, previews.size());
		assertEquals(PREVIEW, previews.preview(actor(), camp, BlockPos.ZERO, 1, SettlementGatheringAreas.Mode.BOTH, null, 6000, BORDER).status());
		assertEquals(1, previews.size());
	}


	@Test void replacementChangesModeWithoutExtendingDeadlineAndRechecksBounds() {
		var previews = new SettlementGatheringPreview(); var actor = actor(); var camp = camp();
		previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.WOOD, null, 10, BORDER);
		var draft = previews.preview(actor, camp, BlockPos.ZERO, 3, SettlementGatheringAreas.Mode.STONE, null, 30, BORDER).draft();
		assertEquals(10, draft.createdTick());
		assertEquals(SettlementGatheringAreas.Mode.STONE, draft.mode());
		var messages = SettlementGatheringCommands.previewMessages(draft, 30);
		assertEquals(List.of("preview", "mode_stone", "range", "preview_new", "scope", "confirm_help"),
			messages.stream().map(SettlementGatheringCommandsTest::suffix).toList());
		assertArrayEquals(new Object[] {299L}, translation(messages.getLast()).getArgs());
		assertEquals(OUT_OF_BOUNDS, previews.confirm(actor, 31, camp, null, (x, z) -> x != -3 || z != 3, neverWrite()).preview().status());
		previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 40, BORDER);
		assertEquals(INVALID, previews.preview(actor, camp, BlockPos.ZERO, 9, SettlementGatheringAreas.Mode.BOTH, null, 41, BORDER).status());
		assertEquals(NO_PREVIEW, previews.inspect(actor, 41).status());
		previews.preview(actor, camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 50, BORDER);
		previews.preview(actor(), camp, BlockPos.ZERO, 2, SettlementGatheringAreas.Mode.BOTH, null, 50, BORDER);
		previews.forgetSettlement(DIMENSION, camp.id());
		assertEquals(0, previews.size(), "A create/revoke round trip cannot restore stale previews");
	}
	private static Settlement camp() { return new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Home", 0); }
	private static SettlementGatheringPreview.Actor actor() { return new SettlementGatheringPreview.Actor(UUID.randomUUID(), new Object(), DIMENSION, true); }
	private static SettlementGatheringAreas.Area area(Settlement camp) { return new SettlementGatheringAreas.Area(UUID.randomUUID(), camp.id(), UUID.randomUUID(), 0, 0, 2, SettlementGatheringAreas.Mode.BOTH); }
	private static SettlementGatheringPreview.Writer neverWrite() { return draft -> { fail("Rejected preview must not write"); return null; }; }
	private static TranslatableContents translation(Component line) { return assertInstanceOf(TranslatableContents.class, line.getContents()); }
	private static String suffix(Component line) { return translation(line).getKey().substring("coopcraft.command.settlement.gathering_".length()); }
}
