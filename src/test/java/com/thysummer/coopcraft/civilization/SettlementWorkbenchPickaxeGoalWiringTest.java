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

/** Static Goal wiring only; no world navigation is executed by these checks. */
class SettlementWorkbenchPickaxeGoalWiringTest {
	@Test
	void firstToolUsesPublicPickupAndHasNoLegacyRemoteMutationEntry() throws Exception {
		var methods = methods();
		String choose = methods.get("chooseAction");
		assertTrue(choose.contains("if(prepareFirstPickaxeCrafting())returnAction.COLLECT_PICKAXE_MATERIALS;"));
		assertBefore(choose, "prepareShearsLoan(false)", "prepareFirstPickaxeCrafting()");
		assertBefore(choose, "prepareFirstPickaxeCrafting()", "prepareToolStickCrafting()");
		String prepare = methods.get("prepareFirstPickaxeCrafting");
		assertTrue(prepare.contains("hasReturnCargo()||hasTeachingCustody()"));
		assertTrue(prepare.contains("SettlementMaterialCrafting.availablePlain(store,state,Items.COBBLESTONE)<3"));
		assertTrue(prepare.contains("SettlementMaterialCrafting.availablePlain(store,state,Items.STICK)<2"));
		assertTrue(prepare.contains("currentMaterialRecipe=SettlementMaterialCrafting.Recipe.STONE_PICKAXE"));
		assertTrue(prepare.contains("workbenchTicket=prepareWorkbenchCrafting()"));
		for (String legacy : List.of("craftPickaxe", "canCraftPickaxe")) assertFalse(methods.containsKey(legacy));
		assertFalse(prepare.contains("role"));
		assertFalse(prepare.contains("withdraw"));
		assertBefore(methods.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.STONE_PICKAXE", "SettlementMaterialCrafting.craft(");
	}

	@Test
	void liveDemandDoesNotReRequireWarehouseIngredientsOrConstructionProjects() throws Exception {
		var methods = methods();
		String demand = methods.get("firstPickaxeDemandStillNeeded");
		assertTrue(demand.contains("SettlementWorkbenchToolEquipment.needsFirstPickaxe(villager.getInventory(),villager.getMainHandItem(),villager.getOffhandItem())"));
		for (String gate : List.of("hasAnyShearsLoan()", "ConstructionSupply.hasAny(", "childCareTicket!=null",
			"sowingTicket!=null", "teachingSession!=null", "autonomousMeetingSession!=null", "proposalConversationSession!=null",
			"meetingTicket!=null", "SettlementCargo.hasCargo(", "SettlementShearingCargo.isMarked(", "SettlementFoodAge.isMarked("))
			assertTrue(demand.contains(gate), gate);
		for (String forbidden : List.of("availablePlain", "countPlain", "chooseRecipe", "getStockpile", "ConstructionPlanner", "role"))
			assertFalse(demand.contains(forbidden), forbidden);
		String prepare = methods.get("prepareWorkbenchCrafting");
		// Grinding has a separate first branch; first-tool demand must still bypass construction demand.
		assertTrue(prepare.contains("()->grinding?smoothStoneDemandStillNeeded():"
			+ "recipe==SettlementMaterialCrafting.Recipe.STONE_PICKAXE?firstPickaxeDemandStillNeeded():"));
		for (String gate : List.of("binding.valid()", "level.getBlockState(station).equals(stationState)",
			"!villager.isNoAi()", "!shouldEat()", "meetingTicket==null", "!emergencyForage()", "SettlementMembershipClaims.resolve("))
			assertTrue(prepare.contains(gate), gate);
	}

	@Test
	void physicalActionsPinLocationsAndFinishBeforeSafeEquipment() throws Exception {
		var methods = methods();
		assertTrue(methods.get("isCollectWorkbenchAction").contains("action==Action.COLLECT_PICKAXE_MATERIALS"));
		assertTrue(methods.get("isWorkbenchAction").contains("action==Action.WORKBENCH_PICKAXE"));
		String valid = methods.get("isTargetValid");
		assertTrue(valid.contains("target.equals(isCollectWorkbenchAction()?workbenchTicket.store():workbenchTicket.station())"));
		assertTrue(valid.contains("workbenchTicket.collected()==!isCollectWorkbenchAction()"));
		assertTrue(valid.contains("(action==Action.COLLECT_PICKAXE_MATERIALS||action==Action.WORKBENCH_PICKAXE)?SettlementMaterialCrafting.Recipe.STONE_PICKAXE:"));
		String tick = methods.get("tickWorkbenchCrafting");
		assertBefore(tick, "!isTargetValid()", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "if(!atCargoStore())", "SettlementWorkbenchCrafting.pickup(");
		assertTrue(tick.contains("workbenchTicket.recipe()==SettlementMaterialCrafting.Recipe.STONE_PICKAXE?Action.WORKBENCH_PICKAXE"));
		assertBefore(tick, "target=workbenchTicket.station();progress=0;travelTicks=0;return;", "if(++progress<duration)return;");
		assertBefore(tick, "if(++progress<duration)return;", "booleanfinished=SettlementWorkbenchCrafting.finish(");
		assertBefore(tick, "if(finished&&action==Action.WORKBENCH_PICKAXE)", "SettlementWorkbenchToolEquipment.equipFinishedPickaxe(");
		assertTrue(tick.contains("villager.getInventory(),()->isTargetValid()&&atCargoStore()"));
		assertTrue(tick.contains("villager::getOffhandItem,()->action==Action.WORKBENCH_PICKAXE&&target.equals(workbenchTicket.station())&&atCargoStore()"));
		assertFalse(tick.contains("newItemStack(Items.STONE_PICKAXE"));
		assertTrue(methods.get("cancelWorkbenchCrafting").contains("SettlementWorkbenchCrafting.cancel(workbenchTicket)"));
		assertFalse(methods.get("cancelWorkbenchCrafting").contains("getInventory"));
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
