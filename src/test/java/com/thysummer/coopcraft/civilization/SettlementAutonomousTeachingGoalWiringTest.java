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

/** Static lifecycle and navigation evidence; deliberately does not start a server. */
class SettlementAutonomousTeachingGoalWiringTest {
	@Test void selectsOnlyAtSafeBoundariesAndKeepsIndependentBackoff() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String select = named(methods, "canUse").toString();
		assertTrue(select.indexOf("action != null && isTargetValid()") < select.indexOf("releaseMaterialLease()"));
		assertTrue(select.indexOf("hasAnyShearsLoan()") < select.indexOf("prepareTeaching(false)"));
		assertTrue(select.indexOf("shouldEat()") < select.indexOf("prepareTeaching(false)"));
		assertTrue(select.indexOf("Action.ATTEND_MEETING") < select.indexOf("prepareTeaching(false)"));
		assertTrue(select.indexOf("prepareCargoReturn()") < select.indexOf("prepareTeaching(false)"));
		assertTrue(select.indexOf("prepareTeaching(false)") < select.indexOf("searchCooldown > 0"));
		assertTrue(select.indexOf("prepareBuildSupply(true)") < select.indexOf("prepareTeaching(true)"));
		assertTrue(select.indexOf("prepareTeaching(true)") < select.indexOf("ConstructionSystem.nextBuildTarget"));
		String prepare = named(methods, "prepareTeaching").toString();
		assertTrue(prepare.indexOf("SettlementAutonomousTeaching.current") < prepare.indexOf("SettlementAutonomousTeaching.poll"));
		assertTrue(prepare.contains("allowSearch && teachingCooldown.ready"));
		assertTrue(prepare.indexOf("action = Action.ATTEND_LESSON") < prepare.indexOf("SettlementAutonomousTeaching.join"));
		assertFalse(named(methods, "stop").toString().contains("teachingCooldown ="));
	}

	@Test void teacherInspectionCannotProbeLifecycleOrIgnoreAnyCustody() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String ready = named(methods, "readyForTeaching").toString();
		for (String gate : List.of("goal.action != null", "goal.foodTicket != null", "goal.meetingTicket != null",
			"goal.hasAnyShearsLoan()", "goal.hasReturnCargo()", "goal.hasTeachingCustody()", "SettlementTeachingScene.residentBodyReady")) assertTrue(ready.contains(gate), gate);
		assertTrue(calls(named(methods, "readyForTeaching"), "goal.canUse").isEmpty());
		assertTrue(calls(named(methods, "readyForTeaching"), "goal.canContinueToUse").isEmpty());
		String purpose = named(methods, "teachingPurpose").toString();
		for (String marker : List.of("ConstructionSupply", "SettlementWorkbenchCargo", "SettlementShearingCargo", "SettlementShearsLoan", "SettlementFoodAge"))
			assertTrue(purpose.contains(marker + ".isMarked"), marker);
		String custody = named(methods, "hasTeachingCustody").toString();
		assertTrue(custody.contains("getMainHandItem")); assertTrue(custody.contains("getOffhandItem")); assertTrue(custody.contains("hasAnyMatching"));
	}

	@Test void realArrivalRequiresSeparateVisibleActorsAndBoundedSafeNavigation() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String tick = named(methods, "tickTeaching").toString();
		assertTrue(tick.indexOf("!teachingValid()") < tick.indexOf("SettlementAutonomousTeaching.pulse"));
		assertTrue(tick.contains("arrived && separate"));
		assertTrue(tick.contains("villager.hasLineOfSight(other) && other.hasLineOfSight(villager)"));
		assertTrue(tick.contains("partner.teachingPathTarget"));
		assertTrue(tick.contains("candidate.equals(other.blockPosition())"));
		assertTrue(tick.indexOf("this::navigationSearchRegionLoaded") < tick.indexOf("createPath"));
		assertTrue(tick.contains("!teachingPathSafe(level, path)"));
		assertTrue(tick.contains("teachingPathSafe(level, villager.getNavigation().getPath())"));
		assertTrue(tick.contains("SettlementCargoApproach.MAX_TRAVEL_TICKS"));
		assertTrue(named(methods, "atTeachingStation").toString().contains("SettlementCargoApproach.arrived"));
		assertTrue(named(methods, "teachingProbe").toString().contains("teachingCell(level, pos)"));
		String path = named(methods, "teachingPathSafe").toString();
		assertTrue(path.contains("pos.above()")); assertTrue(path.contains("pos.below()"));
		assertTrue(named(methods, "teachingCell").toString().contains("protectedAreas().contains(position)"));
		for (String forbidden : List.of("setItemInHand", "spawnAtLocation", "SettlementCraftTeaching.teach", "++progress")) assertFalse(tick.contains(forbidden));
		assertTrue(named(methods, "requiresUpdateEveryTick").toString().contains("return true"));
	}

	@Test void everyLifecycleExitReleasesBothParticipantsAndUnloadDoesNotResume() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelTeaching").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "cancelTeaching").size());
		assertEquals(1, calls(named(methods, "cancelTeaching"), "SettlementAutonomousTeaching.cancel").size());
		assertEquals(1, calls(named(methods, "cancelTeaching"), "SettlementAutonomousTeaching.cancelOnUnload").size());
		assertEquals(1, calls(named(methods("Civilization"), "register"), "SettlementAutonomousTeaching.cancelOnUnload").size());
		assertEquals(1, calls(named(methods, "isTargetValid"), "teachingValid").size());
		assertEquals(1, calls(named(methods, "start"), "tickTeaching").size());
		assertEquals(1, calls(named(methods, "tick"), "tickTeaching").size());
		String status = named(methods, "status").toString();
		for (String label : List.of("attend_lesson", "lesson_waiting", "lesson_learning")) assertTrue(status.contains(label));
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
