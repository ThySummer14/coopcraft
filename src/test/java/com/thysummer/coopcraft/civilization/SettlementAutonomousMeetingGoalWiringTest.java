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

/** Council-specific AST guards, with no Minecraft server or runtime scene. */
class SettlementAutonomousMeetingGoalWiringTest {
	@Test void preservesJobsReturnsAndReservedTeachingBeforeStartingCouncil() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String select = named(methods, "canUse").toString();
		assertTrue(select.indexOf("action != null && isTargetValid()") < select.indexOf("prepareAutonomousMeeting(false)"));
		for (String urgent : List.of("hasAnyShearsLoan()", "shouldEat()", "Action.ATTEND_MEETING", "prepareCargoReturn()"))
			assertTrue(select.indexOf(urgent) < select.indexOf("prepareAutonomousMeeting(false)"), urgent);
		assertTrue(select.indexOf("prepareAutonomousMeeting(false)") < select.indexOf("searchCooldown > 0"));
		assertTrue(select.indexOf("prepareBuildSupply(true)") < select.indexOf("prepareAutonomousMeeting(true)"));
		assertTrue(select.indexOf("prepareAutonomousMeeting(true)") < select.indexOf("prepareTeaching(true)"));
		assertTrue(select.indexOf("prepareAutonomousMeeting(true)") < select.indexOf("ConstructionSystem.nextBuildTarget"));
		String prepare = named(methods, "prepareAutonomousMeeting").toString();
		assertTrue(prepare.indexOf("SettlementAutonomousMeetings.current") < prepare.indexOf("SettlementAutonomousMeetings.poll"));
		assertTrue(prepare.contains("allowSearch && autonomousMeetingCooldown.ready"));
		assertTrue(prepare.indexOf("action = Action.DISCUSS_COUNCIL") < prepare.indexOf("SettlementAutonomousMeetings.join"));
		assertFalse(named(methods, "stop").toString().contains("autonomousMeetingCooldown ="));
	}

	@Test void disabledOptionShortCircuitsBeforeCandidateInspectionWithoutCreatingState() throws Exception {
		String prepare = named(methods("VillagerWorkGoal"), "prepareAutonomousMeeting").toString();
		assertTrue(prepare.contains("simulationOf(settlement).orElse(null)"));
		for (String gate : List.of("state == null", "!state.autonomousMeetingsAvailable()", "!state.autonomousMeetingsEnabled()",
			"!state.survivalEnabled()", "!state.normsEnabled()")) {
			assertTrue(prepare.indexOf(gate) >= 0, gate);
			assertTrue(prepare.indexOf(gate) < prepare.indexOf("readyForAutonomousMeeting(villager)"), gate);
		}
		assertFalse(prepare.contains("ensureSimulation"));
	}

	@Test void readOnlyCandidateGateExcludesAllCustodyAndCrossReservations() throws Exception {
		var methods = methods("VillagerWorkGoal");
		MethodTree method = named(methods, "readyForAutonomousMeeting");
		String ready = method.toString();
		for (String gate : List.of("goal.action != null", "Action.DISCUSS_COUNCIL", "goal.foodTicket != null",
			"goal.meetingTicket != null", "goal.teachingSession != null", "goal.hasAnyShearsLoan()",
			"goal.hasReturnCargo()", "goal.hasTeachingCustody()", "getMainHandItem()", "getOffhandItem()",
			"data.teachingSessions().find", "SettlementTeachingScene.residentBodyReady")) assertTrue(ready.contains(gate), gate);
		assertTrue(calls(method, "goal.canUse").isEmpty());
		assertTrue(calls(method, "goal.canContinueToUse").isEmpty());
		assertFalse(ready.contains("SettlementAutonomousTeaching.valid"));
		assertFalse(ready.contains("councilSessions().claimed")); // Own claim must not invalidate eligibility.
		assertTrue(named(methods, "readyForTeaching").toString().contains("data.councilSessions().claimed"));
		String custody = named(methods, "hasTeachingCustody").toString();
		for (String slot : List.of("getInventory()", "getMainHandItem()", "getOffhandItem()")) assertTrue(custody.contains(slot));
		String purpose = named(methods, "teachingPurpose").toString();
		for (String marker : List.of("ConstructionSupply", "SettlementWorkbenchCargo", "SettlementShearingCargo", "SettlementShearsLoan", "SettlementFoodAge"))
			assertTrue(purpose.contains(marker + ".isMarked"), marker);
	}

	@Test void everyTickUsesRealParticipantsAndAdapterAuthorityWithoutTravelOrRewards() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String tick = named(methods, "tickAutonomousMeeting").toString();
		assertTrue(tick.indexOf("!autonomousMeetingValid()") < tick.indexOf("SettlementAutonomousMeetings.pulse"));
		assertTrue(tick.lastIndexOf("!autonomousMeetingValid()") > tick.indexOf("SettlementAutonomousMeetings.pulse"));
		assertTrue(tick.contains("session.participants.stream()"));
		assertTrue(tick.contains("getNavigation().stop()"));
		assertTrue(tick.contains("villager.hasLineOfSight(other) && other.hasLineOfSight(villager)"));
		assertTrue(tick.contains("getLookControl().setLookAt(other"));
		for (String forbidden : List.of("moveTo", "createPath", "teleport", "setItemInHand", "spawnAtLocation", "++progress", "NormSystem")) assertFalse(tick.contains(forbidden));
		assertTrue(named(methods, "autonomousMeetingValid").toString().contains("SettlementAutonomousMeetings.valid"));
		assertTrue(named(methods, "requiresUpdateEveryTick").toString().contains("return true"));
	}

	@Test void cancellationCoversStopInvalidationExceptionsAndUnload() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelAutonomousMeeting").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "cancelAutonomousMeeting").size());
		assertEquals(1, calls(named(methods, "cancelAutonomousMeeting"), "SettlementAutonomousMeetings.cancel").size());
		assertEquals(1, calls(named(methods, "cancelAutonomousMeeting"), "SettlementAutonomousMeetings.cancelOnUnload").size());
		assertEquals(1, calls(named(methods("Civilization"), "register"), "SettlementAutonomousMeetings.cancelOnUnload").size());
		assertEquals(1, calls(named(methods, "isTargetValid"), "autonomousMeetingValid").size());
		assertEquals(1, calls(named(methods, "start"), "tickAutonomousMeeting").size());
		assertEquals(1, calls(named(methods, "tick"), "tickAutonomousMeeting").size());
		String status = named(methods, "status").toString();
		for (String label : List.of("discuss_council", "council_waiting")) assertTrue(status.contains(label));
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
