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

class SettlementFoodSharingGoalWiringTest {
	@Test void selectionPreservesWorkAndOwnEatingBeforeWarehouseIndependentSharing() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String use = named(methods, "canUse").toString();
		assertTrue(use.indexOf("action != null && isTargetValid()") < use.indexOf("prepareFoodSharing()"));
		assertTrue(use.lastIndexOf("preparePersonalMeal()") < use.indexOf("prepareFoodSharing()"));
		assertTrue(use.indexOf("prepareFoodSharing()") < use.indexOf("findWorkSettlement()"));
		assertTrue(use.contains("if (foodSharingSession != null) cancelFoodSharing()"));
		String prepare = named(methods, "prepareFoodSharing").toString();
		assertTrue(prepare.indexOf("readyForFoodSharing") < prepare.indexOf("SettlementFoodSharingScene.current"));
		assertTrue(prepare.indexOf("SettlementFoodSharingScene.current") < prepare.indexOf("foodSharingCooldown.attempt"));
		assertFalse(prepare.contains("getStockpile"));
		assertFalse(prepare.contains("findWorkSettlement"));
		assertTrue(prepare.contains("SettlementFoodDeliveryScene.pollRequest(villager, data, camp)"));
		assertTrue(named(methods, "isFoodDeliveryAction").toString().contains("Action.DELIVER_FOOD || action == Action.REQUEST_FOOD"));
	}
	@Test void allLifecyclePathsDispatchSharingBeforeWarehouseOrNavigation() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String continuation = named(methods, "canContinueToUse").toString();
		assertTrue(continuation.indexOf("foodSharingValid()") < continuation.indexOf("hasAnyShearsLoan()"));
		assertTrue(continuation.indexOf("foodSharingValid()") < continuation.indexOf("findWorkSettlement()"));
		String tick = named(methods, "tick").toString();
		assertTrue(tick.indexOf("tickFoodSharing()") < tick.indexOf("navigateToTarget()"));
		assertTrue(named(methods, "isTargetValid").toString().contains("isFoodSharingAction()"));
		assertEquals(1, calls(named(methods, "start"), "tickFoodSharing").size());
		assertEquals(1, calls(named(methods, "stop"), "cancelFoodSharing").size());
		assertEquals(1, calls(named(methods, "cancelShearingOnUnload"), "goal.cancelFoodSharing").size());
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelFoodSharing").size());
		}
	}
	@Test void readinessBlocksCustodyAndCompetingWorkButNotOrdinarySurplus() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String ready = named(methods, "readyForFoodSharing").toString();
		for (String token : List.of("goal.action != null", "!goal.isFoodDeliveryAction()", "personalMealTicket", "meetingTicket", "childCareLease", "materialLease",
			"forageLease", "hasAnyShearsLoan", "hasTeachingCustody", "ConstructionSupply.hasAny", "SettlementCargo.hasCargo",
			"SettlementFoodAge.isMarked", "SettlementWorkbenchCargo.isMarked", "getMainHandItem", "getOffhandItem", "teachingSessions", "councilSessions", "childCareClaims"))
			assertTrue(ready.contains(token), token);
		assertFalse(ready.contains("hasReturnCargo"));
		for (String name : List.of("readyForTeaching", "readyForAutonomousMeeting", "readyForProposalConversation", "readyForChildCareRecipient"))
			assertTrue(named(methods, name).toString().contains("SettlementFoodSharingScene.claimed"), name);
	}
	@Test void productionScenePinsBothRealBagsAndPositionsBeforeBoundedReservation() throws Exception {
		var methods = methods("SettlementFoodSharingScene");
		String poll = named(methods, "poll").toString();
		for (String token : List.of("++checked > 16", "registry.find", "SettlementFoodSharing.prepare", "giver.getInventory() == fromBag",
			"recipient.getInventory() == toBag", "giver.position().equals(from)", "recipient.position().equals(to)", "registry.reserve")) assertTrue(poll.contains(token), token);
		assertTrue(poll.indexOf("registry.find") < poll.indexOf("registry.reserve"));
		String body = named(methods, "bodyReady").toString();
		assertTrue(body.contains("!resident.isBaby()")); assertTrue(body.contains("!resident.isVehicle()"));
		assertTrue(body.contains("SettlementPersonalMeal.bodyReady"));
		String contact = named(methods, "contact").toString();
		assertTrue(contact.contains("2.5 * 2.5"));
		assertTrue(contact.indexOf("SettlementTeachingScene.available") < contact.indexOf("hasLineOfSight"));
	}
	@Test void completionConsumesRegistryThenTransfersAndOnlyThenSwings() throws Exception {
		var methods = methods("SettlementFoodSharingScene");
		String pulse = named(methods, "pulse").toString();
		assertTrue(pulse.indexOf("foodSharingSessions().pulse") < pulse.indexOf("SettlementFoodSharing.finish"));
		assertTrue(pulse.contains("s.pair.completed() && goalsMatch(s)"));
		assertTrue(pulse.indexOf("SettlementFoodSharing.finish") < pulse.indexOf("s.giver.swing"));
		assertFalse(pulse.contains(".active("));
		for (String name : List.of("poll", "pulse", "bodyReady")) {
			String method = named(methods, name).toString();
			for (String forbidden : List.of("getStockpile", "setSatiety", "SettlementTeachingScene.person", "recordPractice", "navigateTo")) assertFalse(method.contains(forbidden), forbidden);
		}
	}
	@Test void onlySuccessfulCompletionRecordsBeforeAnimationAndOptionalUiUsesSpareSlots() throws Exception {
		String pulse = named(methods("SettlementFoodSharingScene"), "pulse").toString();
		assertTrue(pulse.contains("s.level.getGameTime()"));
		assertTrue(pulse.contains("VillagerInteraction.nameOf(s.giver).getString()"));
		assertTrue(pulse.indexOf("if (completion != null)") < pulse.indexOf("s.data.rememberFoodSharing"));
		assertTrue(pulse.indexOf("SettlementFoodSharing.finish") < pulse.indexOf("s.data.rememberFoodSharing"));
		assertTrue(pulse.indexOf("s.data.rememberFoodSharing") < pulse.indexOf("s.giver.swing"));
		assertEquals(1, calls(named(methods("SettlementFoodSharingScene"), "pulse"), "s.data.rememberFoodSharing").size());
		String inspect = named(methods("VillagerInteraction"), "inspectLines").toString();
		assertTrue(inspect.contains("SettlementFoodSharingMemories.inspect"));
		assertFalse(named(methods("VillagerInteraction"), "inspectCoreLines").toString().contains("SettlementFoodSharingMemories"));
		String prepare = named(methods("ResidentDialogService"), "prepare").toString();
		assertTrue(prepare.contains("VillagerInteraction.inspectCoreLines"));
		assertTrue(prepare.indexOf("ResidentMeetingView.rations") < prepare.indexOf("withOptionalMemories"));
	}

	@Test void historySentenceHasMatchingBilingualPlaceholders() throws Exception {
		for (String language : List.of("zh_cn", "en_us")) {
			String raw = java.nio.file.Files.readString(Path.of("src/main/resources/assets/coopcraft/lang", language + ".json"));
			var root = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
			String text = root.get("coopcraft.interaction.food_sharing.memory").getAsString();
			assertEquals(5, text.split("%s", -1).length - 1);
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
