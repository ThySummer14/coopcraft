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

/** Structural wiring checks only; these do not establish actual Minecraft entity behavior. */
class SettlementProposalConversationGoalWiringTest {
	@Test void failedScanBackoffUsesActualTicksAndHandlesClockBoundaries() {
		var cooldown = new VillagerWorkGoal.ProposalConversationCooldown();
		assertFalse(cooldown.attempt(-1));
		assertTrue(cooldown.attempt(100));
		assertFalse(cooldown.attempt(100));
		assertFalse(cooldown.attempt(299));
		assertTrue(cooldown.attempt(300));
		assertTrue(cooldown.attempt(20)); // Rewound clock restarts the interval.
		assertFalse(cooldown.attempt(219));
		assertTrue(cooldown.attempt(220));
		assertTrue(cooldown.attempt(Long.MAX_VALUE - 200));
		assertFalse(cooldown.attempt(Long.MAX_VALUE - 1));
		assertTrue(cooldown.attempt(Long.MAX_VALUE));
		assertFalse(cooldown.attempt(Long.MAX_VALUE));
	}

	@Test void selectionPreservesJobsDutiesAndExistingSocialInvitations() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String select = named(methods, "canUse").toString();
		for (String first : List.of("action != null && isTargetValid()", "hasAnyShearsLoan()", "shouldEat()",
			"Action.ATTEND_MEETING", "prepareChildCare()", "prepareSowing()", "prepareCargoReturn()",
			"prepareAutonomousMeeting(false)", "prepareTeaching(false)"))
			assertTrue(select.indexOf(first) >= 0 && select.indexOf(first) < select.indexOf("prepareProposalConversation(false)"), first);
		assertTrue(select.indexOf("prepareProposalConversation(false)") < select.indexOf("searchCooldown > 0"));
		for (String first : List.of("prepareBuildSupply(true)", "prepareAutonomousMeeting(true)", "prepareTeaching(true)"))
			assertTrue(select.indexOf(first) < select.indexOf("prepareProposalConversation(true)"), first);
		String prepare = named(methods, "prepareProposalConversation").toString();
		assertTrue(prepare.indexOf("SettlementProposalConversation.current") < prepare.indexOf("SettlementProposalConversation.poll"));
		assertTrue(prepare.contains("session == null && allowSearch && proposalConversationCooldown.attempt(villager.level().getGameTime())"));
		assertTrue(prepare.indexOf("SettlementProposalConversation.current") < prepare.indexOf("proposalConversationCooldown.attempt"));
		for (String boundary : List.of("stop", "cancelProposalConversation", "start"))
			assertFalse(named(methods, boundary).toString().contains("proposalConversationCooldown"), boundary);
		assertTrue(select.contains("if (proposalConversationSession != null)"));
		assertTrue(select.indexOf("action != null && isTargetValid()") < select.indexOf("if (proposalConversationSession != null)"));
		assertTrue(prepare.indexOf("action =") < prepare.indexOf("SettlementProposalConversation.join"));
		for (String gate : List.of("!state.survivalEnabled()", "!state.normsEnabled()", "readyForProposalConversation(villager)"))
			assertTrue(prepare.indexOf(gate) < prepare.indexOf("SettlementProposalConversation.current"));
	}

	@Test void inspectionExcludesOtherReservationsAndDoesNotInvokePeerLifecycle() throws Exception {
		var methods = methods("VillagerWorkGoal");
		MethodTree ready = named(methods, "readyForProposalConversation");
		for (String gate : List.of("goal.action != null", "goal.isProposalConversationAction()", "goal.foodTicket != null",
			"goal.meetingTicket != null", "goal.cargoTicket != null", "goal.teachingSession != null", "goal.autonomousMeetingSession != null",
			"goal.childCareTicket != null", "goal.childCareLease != null", "goal.sowingTicket != null", "goal.materialLease != null",
			"goal.forageLease != null", "goal.workbenchTicket != null", "goal.hasAnyShearsLoan()", "goal.hasReturnCargo()",
			"goal.hasTeachingCustody()", "getNavigation().isDone()", "getMainHandItem().isEmpty()", "getOffhandItem().isEmpty()",
			"data.teachingSessions().find", "data.councilSessions().claimed", "data.childCareClaims().claimed"))
			assertTrue(ready.toString().contains(gate), gate);
		for (String forbidden : List.of("goal.canUse", "goal.canContinueToUse", "goal.stop", "SettlementProposalConversation.valid"))
			assertTrue(calls(ready, forbidden).isEmpty(), forbidden);
		for (String name : List.of("readyForTeaching", "readyForAutonomousMeeting", "readyForChildCareRecipient", "sowingInterrupted"))
			assertFalse(calls(named(methods, name), "SettlementProposalConversation.claimed").isEmpty(), name);
		assertTrue(named(methods, "childCareInterrupted").toString().contains("sowingInterrupted()"));
	}

	@Test void rolesAreVisibleAndTicksNeverTravelOrGrantKnowledgeDirectly() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String prepare = named(methods, "prepareProposalConversation").toString();
		for (String role : List.of("session.speaker == villager", "Action.TELL_PROPOSAL", "Action.HEAR_PROPOSAL", "target = session.station"))
			assertTrue(prepare.contains(role));
		String tick = named(methods, "tickProposalConversation").toString();
		assertTrue(tick.indexOf("!proposalConversationValid()") < tick.indexOf("SettlementProposalConversation.pulse"));
		assertTrue(tick.lastIndexOf("!proposalConversationValid()") > tick.indexOf("SettlementProposalConversation.pulse"));
		for (String required : List.of("session.listener", "session.speaker", "getNavigation().stop()", "getLookControl().setLookAt(other"))
			assertTrue(tick.contains(required), required);
		for (String forbidden : List.of("moveTo", "createPath", "teleport", "++progress", "NormSystem", "shareProposal", "Knowledge"))
			assertFalse(tick.contains(forbidden), forbidden);
		assertTrue(named(methods, "status").toString().contains("proposalConversationSession.phase()"));
		assertTrue(named(methods, "status").toString().contains("action.id()"));
		assertTrue(named(methods, "status").toString().contains("phase.equals(\"closed\") || phase.equals(\"shared\") ? \"idle\""));
		assertTrue(named(methods, "requiresUpdateEveryTick").toString().contains("return true"));
	}

	@Test void stopExceptionsInvalidationAndUnloadReleasePendingAndJoinedPairs() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelProposalConversation").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "cancelProposalConversation").size());
		for (String boundary : List.of("canUse", "canContinueToUse"))
			assertFalse(calls(named(methods, boundary), "cancelProposalConversation").isEmpty(), boundary);
		assertEquals(1, calls(named(methods, "cancelProposalConversation"), "SettlementProposalConversation.cancel").size());
		assertEquals(1, calls(named(methods, "cancelProposalConversation"), "SettlementProposalConversation.cancelOnUnload").size());
		assertEquals(1, calls(named(methods, "cancelShearingOnUnload"), "SettlementProposalConversation.cancelOnUnload").size());
		assertEquals(1, calls(named(methods, "isTargetValid"), "proposalConversationValid").size());
		for (String boundary : List.of("start", "tick"))
			assertEquals(1, calls(named(methods, boundary), "tickProposalConversation").size());
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
