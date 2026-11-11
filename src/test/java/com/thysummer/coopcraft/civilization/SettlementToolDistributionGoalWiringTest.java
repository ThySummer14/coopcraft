package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Parses production wiring without constructing a world or executing movement. */
class SettlementToolDistributionGoalWiringTest {
	@Test
	void availablePublicToolPrecedesManufacturingAndMissingToolFallsThrough() throws Exception {
		var m = methods();
		String choose = m.get("chooseAction");
		assertBefore(choose, "prepareShearsLoan(false)", "preparePublicPickaxeCollection()");
		assertTrue(choose.contains("if(preparePublicPickaxeCollection())returnAction.COLLECT_STONE_PICKAXE;"));
		for (String later : List.of("prepareStarterCrafting()", "prepareFirstPickaxeCrafting()", "prepareToolStickCrafting()", "chooseMaterialRecipe()"))
			assertBefore(choose, "preparePublicPickaxeCollection()", later);
		assertTrue(choose.contains("if(prepareFirstPickaxeCrafting())returnAction.COLLECT_PICKAXE_MATERIALS;"));
		assertFalse(choose.contains("SettlementToolDistribution.finish"));
		String use = m.get("canUse");
		for (String earlier : List.of("shouldEat()", "prepareChildCare()", "prepareCargoReturn()", "prepareTeaching(true)", "prepareAutonomousMeeting(true)", "prepareProposalConversation(true)"))
			assertBefore(use, earlier, "chooseAction()");
	}

	@Test
	void selectionPinsPhysicalStoreAndLiveBodyNeedWithoutWorkbench() throws Exception {
		var m = methods(); String prepare = m.get("preparePublicPickaxeCollection");
		for (String gate : List.of("publicPickaxeCooldown.ready(", "firstPickaxeDemandStillNeeded()", "hasReturnCargo()", "hasTeachingCustody()",
			"shouldEat()", "emergencyForage()", "SettlementCraftingStore.capture(level,store)", "binding.valid()",
			"SettlementSavedData.get(level)==data", "settlement==camp", "villager.level()==level",
			"data.memberIdentity(villager.getUUID())==member", "state.foodRequestModeVersion()==modeVersion",
			"publicPickaxeBodyReady(level)", "foragePositionAccessible(level,villager.blockPosition())",
			"foragePositionAccessible(level,store)", "data.getStockpile(camp).filter(store::equals).isPresent()", "meetingTicket==null",
			"villager::getOffhandItem", "this::firstPickaxeDemandStillNeeded", "villager.getMainHandItem()")) assertTrue(prepare.contains(gate), gate);
		for (String forbidden : List.of("getCraftingStation", "hasCraftingStation", "station()", "SettlementToolDistribution.finish", "target="))
			assertFalse(prepare.contains(forbidden), forbidden);
		String body = m.get("publicPickaxeBodyReady");
		for (String gate : List.of("level.getEntity(villager.getUUID())==villager", "villager.isAlive()", "!villager.isRemoved()",
			"!villager.isBaby()", "!villager.isSleeping()", "!villager.isTrading()", "!villager.isNoAi()", "!villager.isPassenger()",
			"villager.getHealth()>=villager.getMaxHealth()", "villager.hurtTime==0", "MemoryModuleType.NEAREST_HOSTILE", "Activity.PANIC"))
			assertTrue(body.contains(gate), gate);
		for (String forbidden : List.of("simulationOf", "residentBodyReady", "Person", "skillsEnabled")) assertFalse(body.contains(forbidden), forbidden);
		String demand = m.get("firstPickaxeDemandStillNeeded");
		assertTrue(demand.contains("SettlementWorkbenchToolEquipment.needsFirstPickaxe(villager.getInventory(),villager.getMainHandItem(),villager.getOffhandItem())"));
	}

	@Test
	void transferOnlyAtPinnedWarehouseWithoutCraftingOrMaterialSpend() throws Exception {
		var m = methods(); String tick = m.get("tickPublicPickaxeCollection");
		assertBefore(tick, "!isTargetValid()", "SettlementToolDistribution.finish(");
		assertBefore(tick, "if(!atCargoStore())", "SettlementToolDistribution.finish(");
		assertTrue(tick.contains("tickStoreTravel(false);return;"));
		assertTrue(tick.contains("()->publicPickaxeTicket==ticket&&action==Action.COLLECT_STONE_PICKAXE&&ticket.store().equals(target)&&atCargoStore()"));
		// finish consumes its ticket before this callback; do not reenter valid() here.
		assertFalse(tick.contains("()->publicPickaxeTicket==ticket&&isTargetValid()"));
		for (String forbidden : List.of("recordCraft", "beginCraft", "withdraw", "newItemStack", "SettlementMaterialCrafting", "tickWorkbenchCrafting"))
			assertFalse(tick.contains(forbidden), forbidden);
		assertTrue(m.get("chooseTarget").contains("publicPickaxeTicket==null?null:publicPickaxeTicket.store()"));
		assertTrue(m.get("isTargetValid").contains("target.equals(publicPickaxeTicket.store())"));
		assertTrue(m.get("isTargetValid").contains("SettlementToolDistribution.valid(publicPickaxeTicket,settlementData(),settlement,villager.getUUID(),villager.getInventory())"));
		assertTrue(m.get("start").contains("tickPublicPickaxeCollection()"));
		assertTrue(m.get("tick").contains("tickPublicPickaxeCollection();return;"));
	}

	@Test
	void cancellationAndBoundedPathFailuresNeverRetainClaimsOrBlockProduction() throws Exception {
		var m = methods();
		assertBefore(m.get("preparePublicPickaxeCollection"), "publicPickaxeCooldown.defer(", "SettlementToolDistribution.prepare(");
		assertTrue(m.get("preparePublicPickaxeCollection").contains("returnpublicPickaxeTicket!=null;"));
		String travel = m.get("tickStoreTravel");
		assertTrue(travel.contains("isWorkbenchAction()||isShearsLoanAction()||action==Action.COLLECT_STONE_PICKAXE"));
		assertTrue(travel.contains("!workbenchPathInBounds(level)"));
		assertTrue(travel.contains("!foragePositionAccessible(level,path.getNodePos(i))"));
		assertTrue(travel.contains("if(action==Action.COLLECT_STONE_PICKAXE)publicPickaxeCooldown.defer(level.getGameTime())"));
		assertTrue(m.get("canContinueToUse").contains("publicPickaxeCooldown.defer(villager.level().getGameTime());cancelPublicPickaxeCollection();returnfalse;"));
		assertTrue(m.get("stop").contains("cancelPublicPickaxeCollection()"));
		assertFalse(m.get("stop").contains("publicPickaxeCooldown="));
		String cancel = m.get("cancelPublicPickaxeCollection");
		assertTrue(cancel.contains("SettlementToolDistribution.cancel(publicPickaxeTicket);publicPickaxeTicket=null;"));
		assertTrue(cancel.contains("action=null;target=null;"));
		assertFalse(cancel.contains("getInventory"));
	}

	private static String compact(Tree tree) {
		assertNotNull(tree);
		return tree.toString().replaceAll("\\s+", "");
	}

	private static int requiredIndex(String source, String expected) {
		assertNotNull(source);
		int index = source.indexOf(expected);
		assertTrue(index >= 0, "Missing source fragment: " + expected);
		return index;
	}

	private static void assertBefore(String source, String earlier, String later) {
		assertTrue(requiredIndex(source, earlier) < requiredIndex(source, later),
			"Expected " + earlier + " before " + later);
	}

	private static Map<String, String> methods() throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		methodTrees("VillagerWorkGoal").forEach((name, method) -> result.put(name, compact(method.getBody())));
		return result;
	}

	private static Map<String, MethodTree> methodTrees(String typeName) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		Path source = Path.of("src/main/java/com/thysummer/coopcraft/civilization/" + typeName + ".java");
		try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
				List.of("-proc:none"), null, files.getJavaFileObjects(source));
			var unit = task.parse().iterator().next();
			assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
				diagnostics.getDiagnostics().toString());
			ClassTree type = assertInstanceOf(ClassTree.class, unit.getTypeDecls().getFirst());
			Map<String, MethodTree> result = new LinkedHashMap<>();
			for (var member : type.getMembers()) {
				if (member instanceof MethodTree method && method.getBody() != null)
					result.put(method.getName().toString(), method);
			}
			return result;
		}
	}
}
