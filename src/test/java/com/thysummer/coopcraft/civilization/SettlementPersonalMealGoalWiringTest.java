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

class SettlementPersonalMealGoalWiringTest {
	@Test void independentMealSelectionPrecedesWarehouseFilterButPreservesValidWork() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String use = named(methods, "canUse").toString();
		assertTrue(use.indexOf("action == Action.EAT && preparePersonalMeal()") < use.indexOf("action != null && isTargetValid()"));
		assertTrue(use.lastIndexOf("preparePersonalMeal()") < use.indexOf("findWorkSettlement()"));
		assertTrue(use.indexOf("action != null && isTargetValid()") < use.lastIndexOf("preparePersonalMeal()"));
		var prepare = named(methods, "preparePersonalMeal");
		assertTrue(prepare.toString().contains("satiety() > SettlementNeeds.HUNGRY_AT"));
		assertFalse(calls(prepare, "data.findSettlementForVillager").isEmpty());
		assertFalse(calls(prepare, "foodTicket.cancel").isEmpty());
		assertFalse(calls(prepare, "villager.getNavigation().stop").isEmpty());
		for (String forbidden : List.of("shouldEat", "findWorkSettlement", "SettlementNeeds.eat", "data.getStockpile"))
			assertTrue(calls(prepare, forbidden).isEmpty(), forbidden);
	}
	@Test void independentContinuationAndTickBypassBorrowedToolsReturnsAndNavigation() throws Exception {
		var methods = methods("VillagerWorkGoal");
		String continuation = named(methods, "canContinueToUse").toString();
		assertTrue(continuation.indexOf("action == Action.EAT_PERSONAL") < continuation.indexOf("hasAnyShearsLoan()"));
		assertTrue(continuation.indexOf("personalMealValid()") < continuation.indexOf("findWorkSettlement()"));
		String tick = named(methods, "tick").toString();
		assertTrue(tick.indexOf("tickPersonalMeal()") < tick.indexOf("hasAnyShearsLoan()"));
		assertTrue(tick.indexOf("tickPersonalMeal()") < tick.indexOf("navigateToTarget()"));
		String valid = named(methods, "isTargetValid").toString();
		assertTrue(valid.indexOf("personalMealValid()") < valid.indexOf("hasAnyShearsLoan()"));
		assertFalse(calls(named(methods, "start"), "tickPersonalMeal").isEmpty());
		assertTrue(calls(named(methods, "personalMealValid"), "findWorkSettlement").isEmpty());
	}
	@Test void consecutiveClockAndFinalTransactionKeepHandsPublicFoodAndEventsUntouched() throws Exception {
		var methods = methods("VillagerWorkGoal");
		var tick = named(methods, "tickPersonalMeal");
		assertEquals(1, calls(tick, "personalMealClock.frame").size());
		assertTrue(tick.toString().indexOf("frame == 0") < tick.toString().indexOf("SettlementPersonalMeal.finish"));
		assertEquals(1, calls(tick, "SettlementPersonalMeal.finish").size());
		assertEquals(1, calls(tick, "SettlementPersonalMeal.contextValid").size());
		for (var method : List.of(tick, named(methods, "preparePersonalMeal"), named(methods, "personalMealValid"))) {
			for (String forbidden : List.of("navigateToTarget", "villager.setItemInHand", "SettlementNeeds.eat", "queuePreparedMeal", "recordPractice"))
				assertTrue(calls(method, forbidden).isEmpty(), forbidden);
		}
		assertFalse(calls(named(methods, "tick"), "SettlementNeeds.eat").isEmpty(), "The legacy public action still exists");
	}
	@Test void stopUnloadAndEveryExceptionReleaseMealAndMeetingViewsRecognizeEating() throws Exception {
		var methods = methods("VillagerWorkGoal");
		assertEquals(1, calls(named(methods, "stop"), "cancelPersonalMeal").size());
		assertEquals(1, calls(named(methods, "cancelShearingOnUnload"), "goal.cancelPersonalMeal").size());
		for (String name : List.of("canUse", "canContinueToUse", "start", "tick")) {
			TryTree scoped = (TryTree) named(methods, name).getBody().getStatements().getFirst();
			assertEquals(1, calls(scoped.getCatches().getFirst(), "cancelPersonalMeal").size(), name);
		}
		assertTrue(named(methods, "status").toString().contains("Action.EAT_PERSONAL"));
		assertTrue(named(methods, "requestInvitation").toString().contains("Action.EAT_PERSONAL"));
	}
	@Test void bodyChecksActualWorldAndBodyWithoutOptionalPersonOrFullHealthGate() throws Exception {
		var methods = methods("SettlementPersonalMeal");
		String body = named(methods, "bodyReady").toString();
		for (String required : List.of("isSameThread", "SettlementSavedData.get", "getEntity", "hurtTime", "Activity.PANIC",
			"MemoryModuleType.HURT_BY", "MemoryModuleType.NEAREST_HOSTILE", "SettlementMembershipClaims", "boundedBody", "SettlementTeachingScene.available"))
			assertTrue(body.contains(required), required);
		for (String forbidden : List.of("getMaxHealth", "person(", "isBaby", "getStockpile", "protectedAreas", "isFaceSturdy"))
			assertFalse(body.contains(forbidden), forbidden);
		assertTrue(body.indexOf("boundedBody") < body.indexOf("BlockPos.betweenClosed"));
		assertTrue(body.indexOf("SettlementTeachingScene.available") < body.indexOf("getBlockState"));
		assertTrue(body.contains("getCollisionShape"));
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
