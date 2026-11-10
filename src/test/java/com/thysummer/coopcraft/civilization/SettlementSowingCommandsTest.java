package com.thysummer.coopcraft.civilization;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SettlementSowingCommandsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void realCommandTreeHasReadOnlyQueryAndThreeAbsoluteIntegerCoordinates() throws Exception {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(p -> p >= 2, (ctx, action, option) -> {
			assertEquals("planting", action);
			if (!option.isEmpty()) { assertTrue(List.of("mark", "revoke").contains(option)); assertEquals(-1, IntegerArgumentType.getInteger(ctx, "x")); assertEquals(64, IntegerArgumentType.getInteger(ctx, "y")); assertEquals(2, IntegerArgumentType.getInteger(ctx, "z")); }
			return 1;
		}));
		for (int permission : List.of(0, 1, 2)) assertEquals(1, dispatcher.execute("coop settlement planting", permission));
		for (String op : List.of("mark", "revoke")) {
			for (int permission : List.of(0, 1)) assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement planting " + op + " -1 64 2", permission));
			assertEquals(1, dispatcher.execute("coop settlement planting " + op + " -1 64 2", 2));
			for (String invalid : List.of("", "1", "1 2", "~ ~ ~", "1 2.5 3", "1 2 3 4")) assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement planting " + op + " " + invalid, 2));
		}
	}
	@Test void statusDoesNotCreateSimulationOrDirtyData() {
		var data = new SettlementSavedData(); var camp = data.getOrCreate(BlockPos.ZERO, "Camp", 0); data.setDirty(false);
		assertTrue(data.simulationOf(camp).isEmpty()); assertFalse(SettlementSowingCommands.statusMessages(data, camp).isEmpty());
		assertTrue(data.simulationOf(camp).isEmpty()); assertFalse(data.isDirty());
		assertFalse(SettlementTeachingScene.playerAllowed(true, true, false, true, true, false, false));
		assertFalse(SettlementTeachingScene.playerAllowed(true, false, true, true, true, false, false));
		assertFalse(SettlementTeachingScene.playerAllowed(true, true, true, false, true, false, false));
	}
	@Test void actualHandlerChecksBeforeReadsAndDoesNotTouchBlocksOrSimulation() throws Exception {
		String code = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementSowingCommands.java"));
		assertTrue(code.indexOf("available(level, pos.below())") < code.indexOf("level.getBlockState(pos)"));
		assertTrue(code.indexOf("!data.protectedAreas().blocked()") < code.indexOf("level.getBlockState(pos)"));
		assertTrue(code.contains("operation.equals(\"mark\") && !markReady"));
		assertEquals(2, code.split("SettlementTeachingScene.actualPlayer", -1).length - 1);
		for (String forbidden : List.of("setBlock(", "destroyBlock(", "getChunk(", "ensureSimulation", "markResource")) assertFalse(code.contains(forbidden));
		String routing = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementCommands.java"));
		assertTrue(routing.indexOf("SettlementSowingCommands.execute(context, option)") < routing.indexOf("SettlementSavedData data = SettlementSavedData.get(level)"));
	}
}
