package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static com.thysummer.coopcraft.civilization.SettlementShearingPreview.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementShearingCommandsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static Settlement camp() { return new Settlement(UUID.randomUUID(), BlockPos.ZERO, "Home", 0); }
	private static SettlementShearingPreview.Actor actor() { return new SettlementShearingPreview.Actor(UUID.randomUUID(), new Object(), "minecraft:overworld", true); }
	private static SettlementShearingPreview.Target target() { return new SettlementShearingPreview.Target(UUID.randomUUID(), new Object(), true); }

	@Test void actualGrammarAndPermissions() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> {
			assertEquals("shearing", action); return 1;
		}));
		String id = UUID.randomUUID().toString();
		for (int permission : List.of(0, 1, 2)) {
			assertEquals(1, dispatcher.execute("coop settlement shearing", permission));
			assertEquals(1, dispatcher.execute("coop settlement shearing nearby", permission));
		}
		for (String command : List.of("preview " + id, "confirm", "cancel", "revoke " + id)) {
			for (int permission : List.of(0, 1)) assertThrows(CommandSyntaxException.class,
				() -> dispatcher.execute("coop settlement shearing " + command, permission));
			assertEquals(1, dispatcher.execute("coop settlement shearing " + command, 2));
		}
		for (String command : List.of("preview", "revoke", "confirm " + id, "nearby 9", "preview @e", "preview 0 0 0", "force"))
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement shearing " + command, 2));
		assertEquals(UUID.fromString(id), SettlementShearingCommands.parseUuid(id));
		for (String invalid : List.of("1-1-1-1-1", "@e", "white", ""))
			assertThrows(IllegalArgumentException.class, () -> SettlementShearingCommands.parseUuid(invalid));
	}
	@Test void confirmationConsumesAndRevalidatesIdentityAndEligibility() {
		var sessions = new SettlementShearingPreview(); var actor = actor(); var camp = camp();
		var target = target(); Object data = new Object(), revision = new Object();
		var writes = new AtomicInteger();
		sessions.preview(actor, data, camp, revision, target, 10);
		var result = sessions.confirm(actor, data, camp, revision, 11, id -> target, draft -> { writes.incrementAndGet(); return null; });
		assertEquals(CONFIRMED, result.preview().status());
		assertEquals(NO_PREVIEW, sessions.confirm(actor, data, camp, revision, 11, id -> target, draft -> { fail(); return null; }).preview().status());
		assertEquals(1, writes.get());
		for (var changed : List.of(new SettlementShearingPreview.Target(target.id(), new Object(), true),
			new SettlementShearingPreview.Target(target.id(), target.identity(), false))) {
			sessions.preview(actor, data, camp, revision, target, 10);
			assertNotEquals(CONFIRMED, sessions.confirm(actor, data, camp, revision, 11, id -> changed, draft -> { fail(); return null; }).preview().status());
			assertEquals(0, sessions.size());
		}
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(TARGET_CHANGED, sessions.confirm(actor, data, camp, revision, 11, id -> null, draft -> { fail(); return null; }).preview().status());
	}
	@Test void lifetimeSessionAndAbaGuards() {
		var sessions = new SettlementShearingPreview(); var actor = actor(); var camp = camp();
		var target = target(); Object data = new Object(), revision = new Object();
		for (long now : List.of(9L, 6010L)) {
			sessions.preview(actor, data, camp, revision, target, 10);
			assertEquals(EXPIRED, sessions.inspect(actor, now).status());
		}
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(PLAYER_UNAVAILABLE, sessions.inspect(new SettlementShearingPreview.Actor(actor.id(), new Object(), actor.dimension(), true), 11).status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(DIMENSION_CHANGED, sessions.inspect(new SettlementShearingPreview.Actor(actor.id(), actor.identity(), "other", true), 11).status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(DATA_CHANGED, sessions.confirm(actor, data, camp, new Object(), 11, id -> { fail(); return null; }, draft -> { fail(); return null; }).preview().status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(DATA_CHANGED, sessions.confirm(actor, new Object(), camp, revision, 11, id -> target, draft -> { fail(); return null; }).preview().status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(CAMP_CHANGED, sessions.confirm(actor, data, camp(), revision, 11, id -> target, draft -> { fail(); return null; }).preview().status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(INVALID_TARGET, sessions.preview(actor, data, camp, revision, null, 11).status());
		assertEquals(NO_PREVIEW, sessions.cancel(actor.id()).status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(CANCELLED, sessions.cancel(actor.id()).status());
	}
	@Test void boundedSessionsAndLifecycleClear() {
		var sessions = new SettlementShearingPreview(); Object data = new Object(), revision = new Object(); var camp = camp();
		for (int i = 0; i < 256; i++) assertEquals(PREVIEW, sessions.preview(actor(), data, camp, revision, target(), 0).status());
		assertEquals(SESSION_LIMIT, sessions.preview(actor(), data, camp, revision, target(), 0).status());
		assertEquals(PREVIEW, sessions.preview(actor(), data, camp, revision, target(), 6000).status());
		assertEquals(1, sessions.size()); sessions.clear(); assertEquals(0, sessions.size());
	}
	@Test void resolverAndWriterExceptionsConsumePreviewAndRefreshDoesNotExtendTtl() {
		var sessions = new SettlementShearingPreview(); var actor = actor(); var camp = camp();
		var target = target(); Object data = new Object(), revision = new Object();
		sessions.preview(actor, data, camp, revision, target, 10);
		assertEquals(10, sessions.preview(actor, data, camp, revision, target, 5000).draft().createdTick());
		assertEquals(EXPIRED, sessions.inspect(actor, 6010).status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertThrows(IllegalStateException.class, () -> sessions.confirm(actor, data, camp, revision, 11,
			id -> { throw new IllegalStateException("resolver"); }, draft -> { fail(); return null; }));
		assertEquals(NO_PREVIEW, sessions.inspect(actor, 11).status());
		sessions.preview(actor, data, camp, revision, target, 10);
		assertThrows(IllegalStateException.class, () -> sessions.confirm(actor, data, camp, revision, 11,
			id -> target, draft -> { throw new IllegalStateException("writer"); }));
		assertEquals(NO_PREVIEW, sessions.inspect(actor, 11).status());
	}
	@Test void productionWiringUsesActualPlayerAndLoadedTargetWithoutAutomaticWork() throws Exception {
		String source = java.nio.file.Files.readString(java.nio.file.Path.of(
			"src/main/java/com/thysummer/coopcraft/civilization/SettlementShearingCommands.java"));
		for (String guard : List.of("!source.hasPermission(2) || !player.hasPermissions(2)",
			"source.getLevel() != level", "!source.getPosition().equals(player.position())",
			"level.getServer().getPlayerList().getPlayer(player.getUUID()) == player",
			"level.getEntity(id)", "sheep.level() == level", "!sheep.isRemoved()", "sheep.isAlive()", "!sheep.isBaby()",
			"sheep.getColor() == DyeColor.WHITE", "sheep.distanceToSqr(player) <= 64", "dx * dx + dz * dz <= 4096",
			"available(level, player.blockPosition())", "available(level, sheep.blockPosition())",
			"level.isLoaded(pos)", "level.isPositionEntityTicking(pos)",
			"withinBorder(level, sheep.getBoundingBox())", "!protectedBox(data.protectedAreas(), sheep.getBoundingBox())",
			"AFTER_PLAYER_CHANGE_WORLD", "AFTER_RESPAWN", "DISCONNECT", "SERVER_STOPPED"))
			assertTrue(source.contains(guard), guard);
		for (String forbidden : List.of(".shear(", ".spawnAtLocation(", ".hurtAndBreak(", ".getChunk(", ".readyForShearing("))
			assertFalse(source.contains(forbidden), forbidden);
		String revoke = source.substring(source.indexOf("if (operation.equals(\"revoke\"))"), source.indexOf("if (operation.equals(\"preview\"))"));
		assertFalse(revoke.contains("target(")); assertFalse(revoke.contains("getEntity("));
		assertTrue(revoke.contains("data.revokeShearing(camp, id)"));
	}
	@Test void protectionChecksWholeBoxIncludingTouchAndUnknownData() {
		var protection = new PlayerProtectedAreas().add(UUID.randomUUID(), "Home", 1, 1, 2, 2).value();
		assertTrue(SettlementShearingCommands.protectedBox(protection, new AABB(.8, 0, .8, 1.2, 1, 1.2)));
		assertTrue(SettlementShearingCommands.protectedBox(protection, new AABB(3, 0, 1, 4, 1, 2)));
		assertFalse(SettlementShearingCommands.protectedBox(protection, new AABB(3.01, 0, 1, 4, 1, 2)));
		var unknown = PlayerProtectedAreas.load(net.minecraft.nbt.StringTag.valueOf("future"), null);
		assertTrue(SettlementShearingCommands.protectedBox(unknown, new AABB(100, 0, 100, 101, 1, 101)));
	}
	@Test void storedStatusAndRevokeNeedNoEntityAndDoNotCreateSimulation() {
		var data = new SettlementSavedData(); var camp = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		UUID id = UUID.randomUUID(); data.permitShearing(camp, UUID.randomUUID(), id); data.setDirty(false);
		CompoundTag before = data.save(new CompoundTag(), null);
		assertEquals(4, SettlementShearingCommands.statusMessages(data, camp).size());
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
		assertTrue(data.simulationOf(camp).isEmpty());
		assertEquals(SettlementShearingPermits.Status.REMOVED, data.revokeShearing(camp, id));
	}
}
