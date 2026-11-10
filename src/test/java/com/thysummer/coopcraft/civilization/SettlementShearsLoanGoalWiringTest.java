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
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source structure checks complement deterministic loan authority tests, not live navigation tests. */
class SettlementShearsLoanGoalWiringTest {
	@Test void borrowingIsSelectedBeforePickaxeEquipOrCraftAndRequiresSafeRecoveryAndNoCargo() throws Exception {
		var methods = methods("VillagerWorkGoal"); String choose = named(methods, "chooseAction").toString();
		assertTrue(choose.indexOf("prepareShearsLoan(false)") < choose.indexOf("canCraftPickaxe()"));
		assertTrue(choose.indexOf("prepareShearsLoan(false)") < choose.indexOf("canCraftSticks()"));
		String prepare = named(methods, "prepareShearsLoan").toString();
		assertTrue(prepare.contains("!returning")); assertTrue(prepare.contains("SettlementCargoRecovery.get(level).available()"));
		assertTrue(prepare.contains("hasReturnCargo()")); assertTrue(prepare.contains("ConstructionSupply.hasAny(villager.getInventory())"));
		assertTrue(prepare.contains("SettlementShearsLoan.borrowable(stack)")); assertTrue(prepare.contains("binding.valid()"));
		assertEquals(1, calls(named(methods, "prepareShearsLoan"), "SettlementShearing.prepareLoan").size());
		assertTrue(calls(named(methods, "prepareShearsLoan"), "hasStonePickaxe").isEmpty());
		assertTrue(calls(named(methods, "prepareShearsLoan"), "villager.setItemInHand").isEmpty());
	}

	@Test void physicalArrivalAndLiveAuthorityGateBothTransfersAndOnlyRealBorrowStartsASession() throws Exception {
		var methods = methods("VillagerWorkGoal"); var tick = named(methods, "tickShearsLoan"); String source = tick.toString();
		assertTrue(source.indexOf("!atCargoStore()") < source.indexOf("SettlementStockpile.getStockpile"));
		assertTrue(source.indexOf("SettlementShearsLoan.borrow") < source.indexOf("SettlementShearing.prepare"));
		assertTrue(source.indexOf("if (!borrowed)") < source.indexOf("SettlementShearing.prepare"));
		assertTrue(source.indexOf("SettlementShearsLoan.returnFromHand") < source.indexOf("SettlementShearsLoan.borrow"));
		assertTrue(source.contains("SettlementShearing.returnLoanAuthority(ticket) && atCargoStore()"));
		assertTrue(source.contains("isTargetValid() && atCargoStore()"));
		assertEquals(1, calls(tick, "SettlementShearsLoan.borrow").size());
		assertEquals(1, calls(tick, "SettlementShearsLoan.returnFromHand").size());
		assertEquals(1, calls(tick, "SettlementShearing.prepare").size());
		assertEquals(1, calls(named(methods, "start"), "tickShearsLoan").size());
		assertEquals(1, calls(named(methods, "tick"), "tickShearsLoan").size());
		assertTrue(calls(tick, "SettlementShearing.finish").isEmpty());
		assertTrue(calls(tick, "villager.setItemInHand").isEmpty());
	}

	@Test void stopReloadAndCancelledSessionOnlyReturnCustodyAndNeverRemotelyTransfer() throws Exception {
		var methods = methods("VillagerWorkGoal"); var cancel = named(methods, "cancelShearing");
		assertEquals(1, calls(cancel, "SettlementShearing.cancelLoan").size());
		assertTrue(cancel.toString().contains("shearsLoanTicket = null"));
		for (String name : List.of("stop", "cancelShearing", "cancelShearingOnUnload", "canUse")) {
			assertTrue(calls(named(methods, name), "SettlementShearsLoan.borrow").isEmpty());
			assertTrue(calls(named(methods, name), "SettlementShearsLoan.returnFromHand").isEmpty());
		}
		String use = named(methods, "canUse").toString();
		assertTrue(use.indexOf("prepareShearsLoan(true)") < use.indexOf("chooseAction()"));
		for (String name : List.of("tick", "canContinueToUse")) {
			String source = named(methods, name).toString();
			assertTrue(source.contains("hasAnyShearsLoan() && action != Action.SHEAR_SHEEP && action != Action.RETURN_SHEARS && action != Action.EAT"), name);
		}
		assertTrue(named(methods, "isTargetValid").toString().contains("action != Action.SHEAR_SHEEP && action != Action.EAT"));
		assertTrue(use.indexOf("if (shouldEat())") < use.indexOf("prepareShearsLoan(true)"));
	}

	@Test void loanTravelSharesLoadedBoundsAndSafeStoreApproachChecks() throws Exception {
		var methods = methods("VillagerWorkGoal"); String travel = named(methods, "tickStoreTravel").toString();
		assertTrue(travel.contains("isWorkbenchAction() || isShearsLoanAction()"));
		assertTrue(travel.contains("workbenchPathInBounds(level)")); assertTrue(travel.contains("foragePositionAccessible(level, path.getNodePos(i))"));
		assertTrue(travel.contains("this::navigationSearchRegionLoaded")); assertTrue(travel.contains("constructionPathIsTicking()"));
		assertTrue(travel.contains("shearsLoanCooldown.defer(level.getGameTime())"));
		assertEquals(1, calls(named(methods, "atCargoStore"), "SettlementCargoApproach.arrived").size());
	}

	@Test void candidateLookupNeverCreatesAToolOrClaimsALeaseAndReturnDoesNotRequirePermit() throws Exception {
		var methods = methods("SettlementShearing"); var candidate = named(methods, "hasBorrowCandidate");
		assertTrue(calls(candidate, "data.shearingLeases().claim").isEmpty());
		assertTrue(calls(candidate, "probe.mainHand().set").isEmpty());
		List<NewClassTree> allocations = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitNewClass(NewClassTree node, Void unused) { allocations.add(node); return super.visitNewClass(node, unused); }
		}.scan(candidate, null);
		assertTrue(allocations.isEmpty());
		assertTrue(named(methods, "borrowerAllowed").toString().contains("actorAllowed(data, camp, actor, probe, false)"));
		assertTrue(named(methods, "borrowerAllowed").toString().contains("SettlementShearsLoan::isMarked"));
		assertTrue(calls(named(methods, "returnLoanAuthority"), "hasBorrowCandidate").isEmpty());
		assertTrue(calls(named(methods, "returnLoanAuthority"), "actorAllowed").isEmpty());
		assertFalse(named(methods, "returnLoanAuthority").toString().contains("demand"));
		assertTrue(named(methods, "loanIdentityCurrent").toString().contains("foodRequestModeVersion() == t.modeVersion"));
		assertTrue(named(methods, "loanIdentityCurrent").toString().contains("age < LoanTicket.MAX_AGE"));
	}

	@Test void mainHandClearsOnlyThroughEquipmentGuardAndBagReturnDoesNotEquip() throws Exception {
		var methods = methods("VillagerWorkGoal");
		assertEquals(1, calls(named(methods, "mainShearsHand"), "SettlementShearsEquipment.clear").size());
		var bagReturn = named(methods, "returnShearsHand");
		assertTrue(calls(bagReturn, "villager.setItemInHand").isEmpty());
		assertEquals(1, calls(bagReturn, "bag.setItem").size());
		assertTrue(bagReturn.toString().contains("SettlementShearsLoan.ownedBy"));
		var shearingMethods = methods("SettlementShearing");
		assertTrue(shearingMethods.stream().anyMatch(method -> !calls(method, "SettlementShearsEquipment.allowClear").isEmpty()));
	}

	@Test void loanMarkersAreExcludedFromLegacyCraftingAndAutomaticPickaxeEquip() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("craftSticks", "canCraftSticks", "canGrindSmoothStone", "grindSmoothStone", "withdrawOneLog",
			"isPolishedPickaxe", "hasStonePickaxe", "countCraftingItems", "transferCraftingItems", "removeItems")) {
			assertFalse(calls(named(methods, name), "SettlementShearsLoan.isMarked").isEmpty(), name);
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
