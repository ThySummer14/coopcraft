package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementSowingGoalWiringTest {
	@Test void surplusSowingPrecedesFoodReturnWithItsOwnBackoff() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String use = named(methods, "canUse").toString();
		assertTrue(use.indexOf("prepareSowing()") < use.indexOf("prepareCargoReturn()"));
		assertTrue(use.contains("sowingCooldown.ready"));
		var prepare = named(methods, "prepareSowing");
		assertEquals(1, calls(prepare, "SettlementSowing.hasSeed").size());
		assertTrue(calls(prepare, "shouldForage").isEmpty());
		assertTrue(calls(prepare, "SettlementStockpile.getStockpile").isEmpty());
		assertTrue(named(methods, "canContinueToUse").toString().contains("action != Action.SOW_POTATO"));
		assertFalse(calls(named(methods, "sowingValid"), "sowingInterrupted").isEmpty());
		String interrupt = named(methods, "sowingInterrupted").toString();
		for (String check : List.of("shouldEat()", "meetingTicket != null", "emergencyForage()", "hasAnyShearsLoan()",
			"hasTeachingCustody()", "SettlementCargo.hasCargo", "SettlementCargoDelivery.hasMarkedHarvestFood"))
			assertTrue(interrupt.contains(check), check);
		assertTrue(calls(named(methods, "sowingInterrupted"), "hasReturnCargo").isEmpty());
		var interrupted = named(methods, "sowingInterrupted");
		assertEquals(1, calls(interrupted, "settlementData().teachingSessions().find").size());
		assertEquals(1, calls(interrupted, "settlementData().councilSessions().claimed").size());
		assertTrue(calls(interrupted, "sowingValid").isEmpty());
		assertTrue(calls(interrupted, "isTargetValid").isEmpty());
		assertEquals(2, calls(interrupted, "SettlementCargo.isMaterial").size());
		assertTrue(calls(interrupted, "SettlementCargo.isMaterial").stream()
			.anyMatch(call -> call.getArguments().getFirst().toString().equals("villager.getMainHandItem()")));
		assertTrue(calls(interrupted, "SettlementCargo.isMaterial").stream()
			.anyMatch(call -> call.getArguments().getFirst().toString().equals("villager.getOffhandItem()")));
	}

	@Test void adjacentNavigationAndFinalArrivalAreRequired() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("start", "tick"))
			assertEquals(1, calls(named(methods, name), "tickSowing").size());
		var tick = named(methods, "tickSowing");
		assertTrue(tick.toString().indexOf("sowingValid()") < tick.toString().indexOf("++progress"));
		assertEquals(2, calls(tick, "SettlementCargoApproach.arrived").size());
		assertEquals(1, calls(tick, "sowingApproach.attempt").size());
		assertTrue(tick.toString().contains("this::navigationSearchRegionLoaded"));
		var finish = calls(tick, "SettlementSowing.finish").getFirst();
		assertEquals(1, calls(finish.getArguments().get(2), "SettlementCargoApproach.arrived").size());
		assertEquals(1, calls(finish.getArguments().get(2), "sowingValid").size());
		assertTrue(calls(tick, "navigateToTarget").isEmpty());
		String path = named(methods, "sowingPathSafe").toString();
		assertTrue(path.indexOf("foragePositionAccessible") < path.indexOf("level.getBlockState"));
		assertTrue(path.contains("Blocks.FARMLAND"));
		assertTrue(tick.toString().contains("SettlementCargoApproach.MAX_TRAVEL_TICKS"));
	}

	@Test void lifecycleReleasesTheSingleCellClaimEvenOnExceptions() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelSowing").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "cancelSowing").size());
		assertEquals(1, calls(named(methods, "cancelShearingOnUnload"), "goal.cancelSowing").size());
		assertEquals(1, calls(named(methods, "cancelSowing"), "sowingRegistry.release").size());
		assertEquals(1, calls(named(methods, "prepareSowing"), "registry.claim").size());
		assertEquals(1, calls(named(methods, "sowingValid"), "sowingRegistry.valid").size());
		assertTrue(calls(named(methods, "status"), "SettlementSowing.prepare").isEmpty());
	}

	@Test void harvestGoalRetainsTheCapturedPermission() throws Exception {
		var methods = methods("VillagerWorkGoal");
		assertEquals(1, calls(named(methods, "findRipeBerryBush"), "SettlementCropHarvest.capturePermission").size());
		var valid = calls(named(methods, "isTargetValid"), "SettlementCropHarvest.validTarget").getFirst();
		assertEquals("cropPermission", valid.getArguments().getLast().toString());
		var harvest = calls(named(methods, "forage"), "SettlementCropHarvest.harvest").getFirst();
		assertEquals("cropPermission", harvest.getArguments().getLast().toString());
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
