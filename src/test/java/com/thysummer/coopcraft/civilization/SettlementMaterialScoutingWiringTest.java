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

/** Source integration only: no assertion of live Minecraft navigation coverage. */
class SettlementMaterialScoutingWiringTest {
	@Test void selectionAndLifecycleUseDedicatedReadOnlyScoutingPath() throws Exception {
		var methods = methods("VillagerWorkGoal");
		assertEquals(1, calls(named(methods, "chooseAction"), "prepareScouting").size());
		assertEquals(1, calls(named(methods, "chooseAction"), "chooseGatherTarget").size());
		for (String name : List.of("start", "tick")) assertEquals(1, calls(named(methods, name), "tickScouting").size());
		for (String name : List.of("canContinueToUse", "isTargetValid", "prepareScouting", "tickScouting"))
			assertFalse(calls(named(methods, name), "scoutingValid").isEmpty(), name);
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelScouting").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "cancelScouting").size());
		assertEquals(1, calls(named(methods, "cancelScouting"), "villager.getNavigation().stop").size());
		assertEquals(1, calls(named(methods, "cancelScouting"), "scoutingCooldown.defer").size());
	}
	@Test void pathCreationIsGuardedBeforeAndAfterAndNeverUsesGenericMovementOrHarvest() throws Exception {
		var methods = methods("VillagerWorkGoal"); var tick = named(methods, "tickScouting");
		assertEquals(1, calls(tick, "navigationSearchRegionLoaded").size());
		assertEquals(1, calls(tick, "villager.getNavigation().createPath").size());
		assertEquals(1, calls(tick, "scoutingPathSafe").size());
		assertEquals(2, calls(tick, "scoutingAuthority").size());
		assertEquals(2, calls(tick, "scoutingValid").size());
		for (String forbidden : List.of("navigateTo", "navigateToTarget", "gather", "SettlementMaterialGathering.harvest", "level.destroyBlock", "data.recordResourceObservation"))
			assertTrue(calls(tick, forbidden).isEmpty(), forbidden);
		List<? extends com.sun.source.tree.StatementTree> statements = tick.getBody().getStatements();
		int preflight = -1, create = -1, safe = -1, move = -1;
		for (int i = 0; i < statements.size(); i++) {
			Tree statement = statements.get(i);
			if (!calls(statement, "navigationSearchRegionLoaded").isEmpty()) preflight = i;
			if (!calls(statement, "villager.getNavigation().createPath").isEmpty()) create = i;
			if (!calls(statement, "scoutingPathSafe").isEmpty()) safe = i;
			if (!calls(statement, "villager.getNavigation().moveTo").isEmpty()) move = i;
		}
		assertTrue(preflight >= 0 && preflight < create && create < safe && safe < move);
	}
	@Test void healthDemandMembershipProtectionCapacityAndCurrentPathAreRechecked() throws Exception {
		var methods = methods("VillagerWorkGoal");
		var body = named(methods, "scoutingBodyReady");
		for (String call : List.of("publicPickaxeBodyReady", "shouldEat", "emergencyForage", "hasReturnCargo", "hasTeachingCustody", "hasAnyShearsLoan"))
			assertFalse(calls(body, call).isEmpty(), call);
		assertTrue(calls(named(methods, "scoutingDemand"), "hasStonePickaxe").isEmpty());
		var authority = named(methods, "scoutingAuthority");
		for (String call : List.of("session.valid", "scoutingBodyReady", "scoutingDemand", "session.data.isResidentModificationBlocked", "probe.activeAdult", "SettlementSimulationService.hasGatherTime"))
			assertFalse(calls(authority, call).isEmpty(), call);
		var valid = named(methods, "scoutingValid");
		for (String call : List.of("SettlementMaterialScouting.safe", "SettlementMaterialScouting.onLeg", "chooseGatherTarget", "scoutingPathSafe", "villager.getNavigation().getPath"))
			assertFalse(calls(valid, call).isEmpty(), call);
		assertEquals(1, calls(named(methods, "prepareScouting"), "data.canObserveResource").size());
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
