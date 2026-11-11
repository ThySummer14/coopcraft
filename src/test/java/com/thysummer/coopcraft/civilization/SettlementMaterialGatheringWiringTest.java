package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source integration checks, not live navigation, loot-table or entity tests. */
class SettlementMaterialGatheringWiringTest {
	@Test void selectionRunsOnceWithoutReservingAndFinalChoiceClaimsSharedRegistry() throws Exception {
		var methods = methods("VillagerWorkGoal");
		assertEquals(1, calls(named(methods, "chooseAction"), "chooseGatherTarget").size());
		assertTrue(calls(named(methods, "chooseTarget"), "chooseGatherTarget").isEmpty());
		assertTrue(named(methods, "chooseTarget").toString().contains("return selectedGatherTarget"));
		var choose = named(methods, "chooseGatherTarget");
		assertEquals(1, calls(choose, "SettlementMaterialGathering.candidates").size());
		assertTrue(calls(choose, "materialRegistry.claim").isEmpty());
		var claim = named(methods, "claimGatherTarget");
		assertEquals(1, calls(claim, "SettlementGathering.validTarget").size());
		assertEquals(1, calls(claim, "SettlementMaterialGathering.prepare").size());
		assertEquals(1, calls(claim, "materialRegistry.claim").size());
		assertEquals(1, calls(named(methods, "canUse"), "claimGatherTarget").size());
		assertTrue(named(methods, "nearestResource").toString().contains("manual != null ? manual : nearestAutomaticMaterial(logs)"));
		String order = choose.toString();
		assertTrue(order.indexOf("needsConstructionLogs()") < order.indexOf("role == WorkRole.WOODCUTTER"));
		assertTrue(order.indexOf("role == WorkRole.MINER") < order.indexOf("needsSticks()"));
		assertTrue(order.indexOf("needsSticks()") < order.indexOf("needsCobblestone()"));
		assertTrue(order.indexOf("needsCobblestone()") < order.indexOf("hasStonePickaxe()"));
	}

	@Test void directTicksContinuationNavigationAndHarvestRecheckGatherBeforeDoingWork() throws Exception {
		var methods = methods("VillagerWorkGoal");
		var tick = named(methods, "tick");
		List<IfTree> gates = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitIf(IfTree tree, Void unused) {
				if (tree.getCondition().toString().contains("action == Action.GATHER")
					&& !calls(tree.getCondition(), "isTargetValid").isEmpty()) gates.add(tree);
				return super.visitIf(tree, unused);
			}
		}.scan(tick, null);
		assertEquals(1, gates.size());
		assertEquals(1, calls(gates.getFirst().getThenStatement(), "stop").size());
		assertTrue(tick.toString().indexOf("action == Action.GATHER") < tick.toString().indexOf("navigateToTarget()"));
		for (String name : List.of("canContinueToUse", "navigateToTarget", "gather"))
			assertFalse(calls(named(methods, name), "isTargetValid").isEmpty(), name);
		var valid = named(methods, "isTargetValid");
		assertEquals(1, calls(valid, "materialRegistry.valid").size());
		assertEquals(1, calls(valid, "SettlementMaterialGathering.valid").size());
		assertEquals(1, calls(valid, "SettlementGathering.validTarget").size());
		var gather = named(methods, "gather");
		assertEquals(1, calls(gather, "SettlementMaterialGathering.harvest").size());
		assertEquals(1, calls(gather, "SettlementGathering.harvest").size());
		assertTrue(gather.toString().contains("INTERACTION_DISTANCE_SQUARED"));
	}

	@Test void releaseCoversEarlyReturnStopPreemptionCompletionAndExceptions() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "releaseMaterialLease").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "releaseMaterialLease").size());
		TryTree gather = (TryTree) named(methods, "gather").getBody().getStatements().getFirst();
		assertEquals(1, calls(gather.getFinallyBlock(), "releaseMaterialLease").size());
		String canUse = named(methods, "canUse").toString();
		assertTrue(canUse.indexOf("releaseMaterialLease()") < canUse.indexOf("if (action == Action.GATHER)"));
		assertTrue(canUse.contains("action = null"));
	}

	@Test void automaticNavigationRetainsLoadedSearchRegionNodesAndConfiguredDiagnosticDoesNotScan() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("navigateTo", "navigateToTarget"))
			assertTrue(named(methods, name).toString().contains("materialTicket != null"));
		assertEquals(1, calls(named(methods, "navigateTo"), "navigationSearchRegionLoaded").size());
		assertFalse(calls(named(methods, "navigateTo"), "foragePositionAccessible").isEmpty());
		assertFalse(calls(named(methods, "navigateToTarget"), "constructionPathIsTicking").isEmpty());
		var status = named(methods, "status");
		assertTrue(status.toString().contains("data.resourcesOf(current).isEmpty() && data.gatheringAreaOf(current).isEmpty()"));
		assertTrue(calls(status, "SettlementMaterialGathering.candidates").isEmpty());
		assertTrue(calls(status, "materialRegistry.claim").isEmpty());
	}

	@Test void actualLootAndCommitUsePinnedAuthorityAndOnlyCarriedPractice() throws Exception {
		var methods = methods("SettlementMaterialGathering");
		var pure = methods.stream().filter(method -> method.getName().contentEquals("harvest")
			&& method.getParameters().getFirst().getType().toString().equals("Ticket")).findFirst().orElseThrow();
		assertEquals(1, calls(pure, "SettlementHarvestCommit.commit").size());
		var commit = calls(pure, "SettlementHarvestCommit.commit").getFirst();
		var mutation = (LambdaExpressionTree) commit.getArguments().get(2);
		assertEquals(1, calls(mutation, "valid").size());
		assertEquals(1, calls(mutation, "destroy.test").size());
		assertEquals(1, calls(pure, "SettlementSimulationService.recordCarriedProduction").size());
		assertTrue(calls(pure, "SettlementSimulationService.recordProduction").isEmpty());
		var server = methods.stream().filter(method -> method.getName().contentEquals("harvest")
			&& method.getParameters().getFirst().getType().toString().equals("ServerLevel")).findFirst().orElseThrow();
		assertEquals(1, calls(server, "Block.getDrops").size());
		assertEquals(1, calls(server, "registry.valid").size());
		assertEquals(1, calls(server, "level.destroyBlock").size());
		assertTrue(calls(server, "villager.spawnAtLocation").isEmpty());
		var probe = methods.stream().filter(method -> method.getName().contentEquals("probe") && method.getParameters().size() == 5)
			.findFirst().orElseThrow();
		assertEquals(1, calls(probe, "SettlementObservationClaims.capture").size());
		assertTrue(probe.toString().contains("level.getEntity(villager.getUUID()) == villager"));
		assertTrue(probe.toString().contains("!villager.isBaby()"));
		assertEquals(1, calls(probe, "SettlementSavedData.get").size());
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
