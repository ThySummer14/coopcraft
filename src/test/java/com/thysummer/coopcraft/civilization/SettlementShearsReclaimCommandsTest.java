package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Command grammar, pure fail-closed policy and production wiring; no game/server is started. */
class SettlementShearsReclaimCommandsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void inspectionIsReadOnlyAndReleaseRequiresTwoExplicitIdsAndOperatorPermission() throws Exception {
		String record = UUID.randomUUID().toString(), camp = UUID.randomUUID().toString();
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(permission -> permission >= 2, (context, action, option) -> {
			assertEquals("cargo", action);
			assertEquals(record, StringArgumentType.getString(context, "cargo_record"));
			if (option.equals("shears")) return 1;
			assertEquals("reclaim-shears", option);
			assertEquals(camp, StringArgumentType.getString(context, "cargo_settlement"));
			return 2;
		}));
		for (int permission : List.of(0, 1, 2))
			assertEquals(1, dispatcher.execute("coop settlement cargo shears " + record, permission));
		for (int permission : List.of(0, 1)) assertThrows(CommandSyntaxException.class,
			() -> dispatcher.execute("coop settlement cargo reclaim-shears " + record + " " + camp, permission));
		assertEquals(2, dispatcher.execute("coop settlement cargo reclaim-shears " + record + " " + camp, 2));
		for (String command : List.of("shears", "shears " + record + " " + camp, "reclaim-shears",
			"reclaim-shears " + record, "reclaim-shears " + record + " " + camp + " force", "shears @e"))
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement cargo " + command, 2));
	}

	@Test void bothUuidArgumentsUseTheExistingFullCanonicalUuidPolicy() {
		String id = "abcdefab-1234-5678-9012-abcdefabcdef";
		assertEquals(UUID.fromString(id), SettlementShearsReclaimCommands.parseId(id));
		assertEquals(UUID.fromString(id), SettlementShearsReclaimCommands.parseId(id.toUpperCase(java.util.Locale.ROOT)));
		assertNull(SettlementShearsReclaimCommands.parseId(null));
		for (String invalid : List.of("", "1-1-1-1-1", "@e", "../record", id + " ", " " + id, "{" + id + "}"))
			assertNull(SettlementShearsReclaimCommands.parseId(invalid));
	}

	@Test void aRealOnlineLivingPlayerAndBothPermissionsAreRequiredForRelease() {
		assertTrue(SettlementShearsReclaimCommands.playerAllowed(true, true, true, true, true, false, false));
		assertTrue(SettlementShearsReclaimCommands.playerAllowed(false, false, false, true, true, false, false));
		assertFalse(SettlementShearsReclaimCommands.playerAllowed(true, false, true, true, true, false, false));
		assertFalse(SettlementShearsReclaimCommands.playerAllowed(true, true, false, true, true, false, false));
		for (boolean mutation : List.of(false, true)) {
			assertFalse(SettlementShearsReclaimCommands.playerAllowed(mutation, true, true, false, true, false, false));
			assertFalse(SettlementShearsReclaimCommands.playerAllowed(mutation, true, true, true, false, false, false));
			assertFalse(SettlementShearsReclaimCommands.playerAllowed(mutation, true, true, true, true, true, false));
			assertFalse(SettlementShearsReclaimCommands.playerAllowed(mutation, true, true, true, true, false, true));
		}
	}

	@Test void bothLoadedValidLocationsAndTheExactThreeBlockBoundaryAreRequired() {
		assertTrue(SettlementShearsReclaimCommands.localArrival(true, true, 0));
		assertTrue(SettlementShearsReclaimCommands.localArrival(true, true, 9));
		assertFalse(SettlementShearsReclaimCommands.localArrival(false, true, 0));
		assertFalse(SettlementShearsReclaimCommands.localArrival(true, false, 0));
		for (double distance : new double[] {-1, 9.0001, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
			assertFalse(SettlementShearsReclaimCommands.localArrival(true, true, distance));
	}

	@Test void evenUniqueOrOrphanedCurrentMembershipBlocksReleaseAndUnknownReadsFailClosed() {
		assertFalse(SettlementShearsReclaimCommands.noClaims(null));
		for (var status : SettlementMembershipClaims.Status.values()) {
			boolean expected = status == SettlementMembershipClaims.Status.NONE;
			assertEquals(expected, SettlementShearsReclaimCommands.noClaims(
				new SettlementMembershipClaims.Resolution(status, List.of())));
		}
		assertFalse(SettlementShearsReclaimCommands.noClaims(new SettlementMembershipClaims.Resolution(
			SettlementMembershipClaims.Status.NONE, List.of(new SettlementMembershipClaims.Claim(null, null, null, null)))));
		assertEquals(32, SettlementShearsReclaimCommands.MAX_DIMENSIONS);
		for (int count = 1; count <= 32; count++) assertTrue(SettlementShearsReclaimCommands.withinDimensionLimit(count));
		for (int count : List.of(-1, 0, 33, Integer.MAX_VALUE)) assertFalse(SettlementShearsReclaimCommands.withinDimensionLimit(count));
	}

	@Test void dispatchSeparatesBothNewCommandsBeforeOrdinaryCargoAndNearestSettlementSelection() throws Exception {
		var execute = named(methods("SettlementCommands"), "execute");
		String body = execute.toString();
		assertTrue(body.indexOf("SettlementShearsReclaimCommands.execute") < body.indexOf("SettlementCargoCommands.execute"));
		assertTrue(body.indexOf("SettlementShearsReclaimCommands.execute") < body.indexOf("nearest(data.all(), player.position())"));
		assertTrue(body.contains("option.equals(\"shears\") || option.equals(\"reclaim-shears\")"));
		assertEquals(1, calls(execute, "SettlementCargoCommands.execute").size());
	}

	@Test void theOnlyReleaseUsesMainInventoryAndRevalidatesCapturedIdentityAtTransferBoundaries() throws Exception {
		var methods = methods("SettlementShearsReclaimCommands");
		var execute = named(methods, "execute");
		var transfer = calls(execute, "recovery.reclaimShears");
		assertEquals(1, transfer.size());
		assertEquals(5, transfer.getFirst().getArguments().size());
		assertEquals("new SettlementCargoCommands.MainInventory(player.getInventory())", transfer.getFirst().getArguments().get(3).toString());
		assertTrue(transfer.getFirst().getArguments().get(4).toString().contains(
			"authority(source, player, level, recovery, data, camp, recordId, snapshot.deceasedId(), position)"));
		String entry = execute.toString();
		assertTrue(entry.indexOf("!atRecord(level, player, position)") < entry.indexOf("recovery.reclaimShears"));
		assertTrue(entry.indexOf("!borrowerAbsent(level, data, snapshot.deceasedId())") < entry.indexOf("recovery.reclaimShears"));
		assertTrue(entry.contains("!source.hasPermission(2) || !player.hasPermissions(2)"));
		assertEquals(2, calls(execute, "parseId").size());
		var authority = named(methods, "authority");
		for (String guard : List.of("actualPlayer(source, player, level, true)", "atRecord(level, player, position)",
			"SettlementCargoRecovery.get(level) != recovery", "!recovery.available()", "SettlementSavedData.get(level) != data",
			"!data.canonicalSettlement(camp)", "current.deceasedId().equals(deceasedId)", "current.position().equals(position)",
			"borrowerAbsent(level, data, deceasedId)", "catch (RuntimeException"))
			assertTrue(authority.toString().contains(guard), guard);
		String actor = named(methods, "actualPlayer").toString();
		for (String guard : List.of("isSameThread()", "source.getEntity() == player", "player.level() == level",
			"source.getLevel() == level", "source.getPosition().equals(player.position())", "source.hasPermission(2)",
			"player.hasPermissions(2)", "getPlayer(player.getUUID()) == player", "player.connection.isAcceptingMessages()"))
			assertTrue(actor.contains(guard), guard);
	}

	@Test void identityLookupIsBoundedAndDoesNotLoadChunksOrUseAbsenceAsEvidence() throws Exception {
		var methods = methods("SettlementShearsReclaimCommands");
		var absent = named(methods, "borrowerAbsent");
		String body = absent.toString();
		assertEquals(1, calls(absent, "currentLevel.getServer().getAllLevels").size());
		assertTrue(body.indexOf("withinDimensionLimit(++visited)") < body.indexOf("SettlementSavedData.get(level)"));
		for (String guard : List.of("data != currentData", "foundCurrent", "level.getEntity(deceasedId)",
			"!entity.isRemoved() && entity.isAlive()", "SettlementMembershipClaims.resolve(sources, currentLevel.dimension(), deceasedId)",
			"catch (RuntimeException")) assertTrue(body.contains(guard), guard);
		String available = named(methods, "available").toString();
		for (String guard : List.of("!level.isOutsideBuildHeight(position)", "level.getWorldBorder().isWithinBounds(position)",
			"level.isLoaded(position)", "level.isPositionEntityTicking(position)")) assertTrue(available.contains(guard), guard);
		String arrival = named(methods, "atRecord").toString();
		assertTrue(arrival.contains("available(level, player.blockPosition())"));
		assertTrue(arrival.contains("available(level, position)"));
		String all = methods.toString();
		for (String forbidden : List.of(".getChunk(", ".getStockpile(", ".getMainHandItem(", ".getOffhandItem(",
			".setItem(", ".spawnAtLocation(", "SettlementShearsLoan.unmark(", "SettlementStockpile.insert(", ".teleportTo("))
			assertFalse(all.contains(forbidden), forbidden);
		var inspect = named(methods, "inspect");
		assertEquals(1, calls(inspect, "recovery.reclaimableShears").size());
		assertTrue(calls(inspect, "recovery.reclaimShears").isEmpty());
		assertTrue(calls(inspect, "borrowerAbsent").isEmpty());
		assertTrue(calls(inspect, "atRecord").isEmpty());
	}

	@Test void allNewMessagesAreAvailableInBothLanguages() throws Exception {
		for (String language : List.of("zh_cn", "en_us")) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(stream);
				var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String key : List.of("player_unavailable", "actual_position", "none", "scope", "settlement_invalid",
					"not_at_place", "borrower_present", "no_capacity", "invalid", "changed", "row", "camp_unavailable", "hint", "recovered"))
					assertFalse(json.get("coopcraft.command.settlement.cargo_shears_" + key).getAsString().isBlank(), key);
				for (String key : List.of("row", "recovered"))
					for (String argument : List.of("%1$s", "%2$s", "%3$s"))
						assertTrue(json.get("coopcraft.command.settlement.cargo_shears_" + key).getAsString().contains(argument), key + argument);
				for (String argument : List.of("%1$s", "%2$s"))
					assertTrue(json.get("coopcraft.command.settlement.cargo_shears_camp_unavailable").getAsString().contains(argument));
				assertTrue(json.get("coopcraft.command.settlement.cargo_shears_hint").getAsString().contains("%1$s"));
			}
		}
	}

	private static MethodTree named(List<MethodTree> methods, String name) {
		return methods.stream().filter(method -> method.getName().contentEquals(name)).findFirst().orElseThrow();
	}
	private static List<MethodTree> methods(String name) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var files = manager.getJavaFileObjects(Path.of("src/main/java/com/thysummer/coopcraft/civilization", name + ".java").toFile());
			var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, files);
			List<MethodTree> result = new ArrayList<>();
			for (var unit : task.parse()) new TreeScanner<Void, Void>() {
				@Override public Void visitMethod(MethodTree tree, Void unused) { result.add(tree); return super.visitMethod(tree, unused); }
			}.scan(unit, null);
			assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
			return result;
		}
	}
	private static List<MethodInvocationTree> calls(Tree tree, String name) {
		List<MethodInvocationTree> result = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
				if (call.getMethodSelect().toString().equals(name)) result.add(call);
				return super.visitMethodInvocation(call, unused);
			}
		}.scan(tree, null);
		return result;
	}
}
