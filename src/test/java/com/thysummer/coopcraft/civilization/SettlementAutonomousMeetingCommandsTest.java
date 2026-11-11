package com.thysummer.coopcraft.civilization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementAutonomousMeetingCommandsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualTreeOffersReadToAllAndRequiresOpTwoForWrites() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(op -> op >= 2, (ctx, action, operation) -> {
			assertEquals("meeting_autonomy", action);
			return switch (operation) { case "" -> 1; case "on" -> 2; case "off" -> 3; default -> 0; };
		}));
		for (int op : List.of(0, 1, 2)) assertEquals(1, dispatcher.execute("coop settlement meeting autonomy", op));
		for (int op : List.of(0, 1)) for (String value : List.of("on", "off"))
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement meeting autonomy " + value, op));
		assertEquals(2, dispatcher.execute("coop settlement meeting autonomy on", 2));
		assertEquals(3, dispatcher.execute("coop settlement meeting autonomy off", 2));
		for (String value : List.of("true", "false", "reset", "on force"))
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement meeting autonomy " + value, 2));
	}

	@Test void queriesAndOffNeverCreateStateAndOnNeverEnablesDependencies() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Council", 0);
		data.setDirty(false);
		assertEquals("off", SettlementAutonomousMeetingCommands.apply(data, camp, ""));
		assertEquals("off", SettlementAutonomousMeetingCommands.apply(data, camp, "off"));
		assertEquals("needs_modes", SettlementAutonomousMeetingCommands.apply(data, camp, "on"));
		assertTrue(data.simulationOf(camp).isEmpty());
		assertFalse(data.isDirty());
		var state = data.getOrCreateSimulation(camp);
		data.setDirty(false);
		assertEquals("needs_modes", SettlementAutonomousMeetingCommands.apply(data, camp, "on"));
		state.setSurvivalEnabled(true, 0);
		assertEquals("needs_modes", SettlementAutonomousMeetingCommands.apply(data, camp, "on"));
		assertFalse(state.normsEnabled());
		assertFalse(data.isDirty());
		state.setNormsEnabled(true);
		assertEquals("on", SettlementAutonomousMeetingCommands.apply(data, camp, "on"));
		assertTrue(data.isDirty());
		data.setDirty(false);
		var before = state.save();
		assertEquals("on", SettlementAutonomousMeetingCommands.apply(data, camp, ""));
		assertEquals("on", SettlementAutonomousMeetingCommands.apply(data, camp, "on"));
		assertFalse(data.isDirty());
		assertEquals(before, state.save());
		state.setSurvivalEnabled(false, 0);
		assertEquals("paused", SettlementAutonomousMeetingCommands.inspect(data, camp));
		assertTrue(state.autonomousMeetingsEnabled());
		assertFalse(data.isDirty());
		assertEquals("off", SettlementAutonomousMeetingCommands.apply(data, camp, "off"));
		assertTrue(data.isDirty());
		assertFalse(state.autonomousMeetingsEnabled());
	}

	@Test void unsupportedFormatIsReportedWithoutClearingOrDirtying() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Council", 0);
		var state = data.getOrCreateSimulation(camp);
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		var saved = data.save(new CompoundTag(), null);
		var raw = new CompoundTag(); raw.putInt("Version", 82); raw.putBoolean("Enabled", true);
		saved.getList("Simulations", Tag.TAG_COMPOUND).getCompound(0).put("AutonomousMeetings", raw);
		var restored = SettlementSavedData.load(saved, null);
		var restoredCamp = restored.all().getFirst();
		restored.setDirty(false);
		var before = restored.save(new CompoundTag(), null);
		for (String operation : List.of("", "on", "off")) {
			assertEquals("blocked_format", SettlementAutonomousMeetingCommands.apply(restored, restoredCamp, operation));
			assertFalse(restored.isDirty());
			assertEquals(before, restored.save(new CompoundTag(), null));
		}
	}

	@Test void productionHandlerChecksBothPermissionsAndActualPlayerBeforeApplying() throws Exception {
		String command = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementAutonomousMeetingCommands.java"));
		String wiring = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementCommands.java"));
		assertTrue(command.contains("!source.hasPermission(2) || !player.hasPermissions(2)"));
		assertTrue(command.contains("SettlementTeachingScene.actualPlayer(source, player, player.serverLevel(), mutation)"));
		assertTrue(wiring.contains("SettlementAutonomousMeetingCommands.execute(source, player, data, settlement, option)"));
		assertTrue(command.indexOf("SettlementTeachingScene.actualPlayer") < command.indexOf("String result = apply"));
		assertFalse(command.contains("getOrCreateSimulation"));
	}
}
