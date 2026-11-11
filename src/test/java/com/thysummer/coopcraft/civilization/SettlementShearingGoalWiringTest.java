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
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source wiring and pure repath policy only; no world, server, live navigation or shearing is run. */
class SettlementShearingGoalWiringTest {
	@Test void repathTracksMovingTargetsWithoutRebuildingEveryUnchangedTick() {
		BlockPos origin = new BlockPos(4, 64, 4);
		assertTrue(VillagerWorkGoal.shearingRepathRequired(null, origin, false, 0));
		assertFalse(VillagerWorkGoal.shearingRepathRequired(origin, origin, false, 9));
		assertTrue(VillagerWorkGoal.shearingRepathRequired(origin, origin, false, 10));
		assertTrue(VillagerWorkGoal.shearingRepathRequired(origin, origin.east(), false, 0));
		assertTrue(VillagerWorkGoal.shearingRepathRequired(origin, origin.above(), false, 0));
		assertTrue(VillagerWorkGoal.shearingRepathRequired(origin, origin, true, 0));
		assertFalse(VillagerWorkGoal.shearingRepathRequired(origin, null, true, 99));
	}

	@Test void selectionCapturesOneSessionAndRechecksLiveBedDemand() throws Exception {
		var methods = methods("VillagerWorkGoal");
		var choice = named(methods, "chooseAction");
		assertEquals(1, calls(choice, "SettlementShearing.prepare").size());
		var prepare = calls(choice, "SettlementShearing.prepare").getFirst();
		var demand = prepare.getArguments().get(4);
		assertEquals(1, calls(demand, "woolDemandStillNeeded").size());
		assertEquals(1, calls(demand, "ConstructionPlanner.materialDemands").size());
		assertTrue(choice.toString().contains("!hasReturnCargo() && shearingCooldown.ready"));
		assertTrue(choice.toString().indexOf("SettlementShearing.prepare") < choice.toString().indexOf("chooseMaterialRecipe()"));
		assertTrue(calls(named(methods, "chooseTarget"), "SettlementShearing.prepare").isEmpty());
		assertTrue(calls(named(methods, "status"), "SettlementShearing.prepare").isEmpty());
		assertEquals(1, calls(named(methods, "isTargetValid"), "SettlementShearing.valid").size());
		assertEquals(1, calls(named(methods, "hasReturnCargo"), "SettlementShearingCargo.hasReturnable").size());
	}

	@Test void dedicatedTickRequiresLiveIdentityReachSightAndLoadedPathBeforeCompletion() throws Exception {
		var methods = methods("VillagerWorkGoal");
		assertEquals(1, calls(named(methods, "start"), "tickShearing").size());
		assertEquals(1, calls(named(methods, "tick"), "tickShearing").size());
		var tick = named(methods, "tickShearing");
		String source = tick.toString();
		assertEquals(2, calls(tick, "isTargetValid").size());
		assertEquals(1, calls(tick, "shearingSession.position").size());
		assertEquals(1, calls(tick, "villager.getLookControl().setLookAt").size());
		assertEquals(1, calls(tick, "villager.hasLineOfSight").size());
		assertTrue(source.indexOf("navigationSearchRegionLoaded()") < source.indexOf("villager.hasLineOfSight(sheep)"));
		assertTrue(source.indexOf("villager.hasLineOfSight(sheep)") < source.indexOf("++progress"));
		assertTrue(source.indexOf("++progress") < source.indexOf("SettlementShearing.finish"));
		assertTrue(source.contains("progress = 0"));
		assertTrue(source.contains("++travelTicks >= SHEARING_MAX_TRAVEL_TICKS"));
		assertTrue(source.indexOf("navigationSearchRegionLoaded()") < source.indexOf("villager.getNavigation().createPath"));
		assertFalse(calls(tick, "constructionPathIsTicking").isEmpty());
		assertFalse(calls(tick, "workbenchPathInBounds").isEmpty());
		assertTrue(calls(tick, "foragePositionAccessible").stream()
			.anyMatch(call -> call.getArguments().get(1).toString().equals("path.getNodePos(index)")));
		assertEquals(1, calls(tick, "SettlementShearing.finish").size());
		assertTrue(calls(tick, "villager.setItemInHand").isEmpty());
		assertTrue(calls(tick, "sheep.shear").isEmpty());
		assertTrue(calls(tick, "villager.spawnAtLocation").isEmpty());
	}

	@Test void allExitsReleaseSessionWithoutMovingOrReplacingHeldItems() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelShearing").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "cancelShearing").size());
		assertFalse(calls(named(methods, "canContinueToUse"), "cancelShearing").isEmpty());
		assertEquals(1, calls(named(methods, "cancelShearing"), "SettlementShearing.cancel").size());
		assertEquals(1, calls(named(methods, "cancelShearingOnUnload"), "goal.cancelShearing").size());
		assertEquals(1, calls(named(methods("Civilization"), "register"), "VillagerWorkGoal.cancelShearingOnUnload").size());
		List<TryTree> completion = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitTry(TryTree tree, Void unused) {
				if (!calls(tree.getBlock(), "SettlementShearing.finish").isEmpty()) completion.add(tree);
				return super.visitTry(tree, unused);
			}
		}.scan(named(methods, "tickShearing"), null);
		assertEquals(1, completion.size());
		assertEquals(1, calls(completion.getFirst().getFinallyBlock(), "stop").size());
		assertTrue(calls(named(methods, "cancelShearing"), "villager.setItemInHand").isEmpty());
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
