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

class SettlementChildCareGoalWiringTest {
	@Test void careDoesNotDisplaceOwnFoodChildrenOrValidWork() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String use = named(methods, "canUse").toString();
		int care = use.indexOf("prepareChildCare()");
		for (String earlier : List.of("action != null && isTargetValid()", "shouldEat()", "meetingTicket != null", "villager.isBaby()", "hasAnyShearsLoan()"))
			assertTrue(use.indexOf(earlier) >= 0 && use.indexOf(earlier) < care, earlier);
		assertTrue(care < use.indexOf("prepareSowing()"));
		assertTrue(care < use.indexOf("prepareCargoReturn()"));
		assertTrue(use.contains("childCareCooldown.ready"));
		assertTrue(named(methods, "canContinueToUse").toString().contains("action != Action.FEED_CHILD"));
		String prepare = named(methods, "prepareChildCare").toString();
		assertTrue(prepare.indexOf("childCareCooldown.defer") < prepare.indexOf("childCareInterrupted()"));
		assertEquals(1, calls(named(methods, "prepareChildCare"), "SettlementChildCare.candidates").size());
		assertTrue(calls(named(methods, "prepareChildCare"), "SettlementStockpile.getStockpile").isEmpty());
	}

	@Test void recipientInspectionIsReadOnlyAndDoesNotReserveEating() throws Exception {
		var methods = methods("VillagerWorkGoal");
		var gate = named(methods, "readyForChildCareRecipient");
		String text = gate.toString();
		for (String check : List.of("goal.action != null", "goal.foodTicket != null", "goal.meetingTicket != null",
			"goal.hasAnyShearsLoan()", "goal.hasTeachingCustody()", "data.teachingSessions().find", "data.councilSessions().claimed"))
			assertTrue(text.contains(check), check);
		for (String forbidden : List.of("goal.stop", "goal.canUse", "goal.tick", "goal.start", "goal.cancelMeetingInvitation", "goal.shouldEat"))
			assertTrue(calls(gate, forbidden).isEmpty(), forbidden);
		assertEquals(1, calls(named(methods, "childCareInterrupted"), "sowingInterrupted").size());
		assertTrue(calls(named(methods, "childCareInterrupted"), "hasReturnCargo").isEmpty());
		assertEquals(1, calls(named(methods, "childCareValid"), "SettlementChildCare.valid").size());
	}

	@Test void contactClockRejectsGapsAndDoesNotCountDuplicateCalls() {
		assertEquals(1, VillagerWorkGoal.childCareContactStep(100, 101));
		assertEquals(0, VillagerWorkGoal.childCareContactStep(100, 100));
		assertEquals(-1, VillagerWorkGoal.childCareContactStep(100, 102));
		assertEquals(-1, VillagerWorkGoal.childCareContactStep(100, 99));
		assertEquals(-1, VillagerWorkGoal.childCareContactStep(Long.MAX_VALUE, Long.MIN_VALUE));
	}

	@Test void fortyContinuousContactTicksAndFinalFootingAreRequired() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("start", "tick"))
			assertEquals(1, calls(named(methods, name), "tickChildCare").size());
		var tick = named(methods, "tickChildCare");
		String text = tick.toString();
		assertTrue(text.indexOf("childCareValid()") < text.indexOf("++progress"));
		assertTrue(text.contains("++progress < CHILD_CARE_TICKS"));
		assertTrue(text.indexOf("childCareContactStep(lastChildCareContactTick, now)") < text.indexOf("++progress"));
		assertTrue(text.contains("step < 0") && text.contains("step == 0"));
		assertTrue(text.indexOf("lastChildCareContactTick = now") < text.indexOf("++progress"));
		assertTrue(text.contains("progress > 0"));
		assertTrue(text.indexOf("progress > 0") < text.indexOf("++travelTicks"));
		assertTrue(text.contains("SettlementCargoApproach.MAX_TRAVEL_TICKS"));
		assertTrue(text.contains("this::navigationSearchRegionLoaded"));
		assertEquals(2, calls(tick, "sowingPathSafe").size());
		assertEquals(1, calls(tick, "childCareApproach.attempt").size());
		var finish = calls(tick, "SettlementChildCare.finish").getFirst();
		assertEquals(1, calls(finish.getArguments().get(2), "childCareValid").size());
		assertEquals(1, calls(finish.getArguments().get(2), "atChildCareRecipient").size());
		assertTrue(text.indexOf("SettlementChildCare.finish") < text.indexOf("villager.swing"));
		var arrival = named(methods, "atChildCareRecipient");
		assertEquals(1, calls(arrival, "SettlementChildCare.contactReady").size());
		assertTrue(calls(arrival, "villager.hasLineOfSight").isEmpty(), "Goal must use the full shared contact predicate");
		var service = methods("SettlementChildCare");
		var wrapper = named(service, "contactReady");
		assertEquals(1, calls(wrapper, "valid").size());
		assertEquals(1, calls(wrapper, "ticket.contact.getAsBoolean").size());
		assertTrue(wrapper.toString().indexOf("valid(ticket, giver)") < wrapper.toString().indexOf("ticket.contact.getAsBoolean()"));
		var contact = named(service, "contact");
		String contactText = contact.toString();
		assertEquals(1, calls(contact, "SettlementCargoApproach.arrived").size());
		assertTrue(contactText.indexOf("giver.distanceToSqr(child) > 6.25") < contactText.indexOf("BlockPos.betweenClosed"));
		assertTrue(contactText.contains("BlockPos.betweenClosed(min, max)"));
		for (String axis : List.of("X", "Y", "Z")) {
			assertTrue(contactText.contains("Math.min(from.get" + axis + "(), to.get" + axis + "()) - 1"));
			assertTrue(contactText.contains("Math.max(from.get" + axis + "(), to.get" + axis + "()) + 1"));
		}
		for (String check : List.of("probe.state(initial).isAir()", "probe.state(initial.above()).isAir()",
			"ConstructionSite.safeSupport(probe.state(initial.below()))", "SettlementTeachingScene.available"))
			assertTrue(contactText.indexOf(check) >= 0 && contactText.indexOf(check) < contactText.indexOf("giver.hasLineOfSight"), check);
		assertEquals(1, calls(contact, "giver.hasLineOfSight").size());
		assertEquals(1, calls(contact, "child.hasLineOfSight").size());
		assertTrue(calls(tick, "navigateToTarget").isEmpty());
	}

	@Test void lifecycleCancelsTicketsAndReleasesClaimsWithoutInventoryWrites() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelChildCare").size(), name);
		}
		assertEquals(1, calls(named(methods, "stop"), "cancelChildCare").size());
		assertEquals(1, calls(named(methods, "cancelShearingOnUnload"), "goal.cancelChildCare").size());
		assertEquals(1, calls(named(methods, "cancelChildCare"), "childCareRegistry.release").size());
		assertEquals(1, calls(named(methods, "cancelChildCare"), "SettlementChildCare.cancel").size());
		assertEquals(1, calls(named(methods, "childCareValid"), "childCareRegistry.active").size());
		assertEquals(1, calls(named(methods, "isTargetValid"), "childCareValid").size());
		for (String method : List.of("prepareChildCare", "cancelChildCare", "tickChildCare", "readyForChildCareRecipient", "status")) {
			String text = named(methods, method).toString();
			for (String forbidden : List.of("removeItem(", ".shrink(", "setItem(", "setSatiety(", "setBlock(")) assertFalse(text.contains(forbidden));
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
