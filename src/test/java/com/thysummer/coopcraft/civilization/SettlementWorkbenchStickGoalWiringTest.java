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

/** Source wiring checks only; no server or navigation is executed. */
class SettlementWorkbenchStickGoalWiringTest {
	@Test void toolAndConstructionRoutesBothChooseWarehousePickup() throws Exception {
		var methods = methods();
		String choose = methods.get("chooseAction");
		assertTrue(choose.contains("if(prepareToolStickCrafting())returnAction.COLLECT_STICK_INPUTS;"));
		assertBefore(choose, "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.STICKS", "returnAction.CRAFT_MATERIALS;");
		assertTrue(choose.contains("returnworkbenchTicket==null?null:Action.COLLECT_STICK_INPUTS;"));
		assertTrue(methods.get("prepareToolStickCrafting").contains("Items.OAK_LOG)<1"));
		assertTrue(methods.get("prepareToolStickCrafting").contains("toolStickDemand=true"));
		assertBefore(methods.get("prepareWorkbenchCrafting"), ":toolSticks?toolStickDemandStillNeeded()", "java.util.Objects.equals(job,");
		for (String legacy : List.of("craftSticks", "canCraftSticks", "withdrawOneLog")) assertFalse(methods.containsKey(legacy));
		assertBefore(methods.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.STICKS", "SettlementMaterialCrafting.craft(");
	}

	@Test void bothStagesPinRecipeArrivalAndUseExistingCargoReturn() throws Exception {
		var methods = methods();
		assertTrue(methods.get("isCollectWorkbenchAction").contains("action==Action.COLLECT_STICK_INPUTS"));
		assertTrue(methods.get("isWorkbenchAction").contains("action==Action.CRAFT_STICKS_AT_WORKBENCH"));
		assertTrue(methods.get("isTargetValid").contains("(action==Action.COLLECT_STICK_INPUTS||action==Action.CRAFT_STICKS_AT_WORKBENCH)?SettlementMaterialCrafting.Recipe.STICKS"));
		String tick = methods.get("tickWorkbenchCrafting");
		assertBefore(tick, "if(!atCargoStore())", "SettlementWorkbenchCrafting.pickup(");
		assertTrue(tick.contains("workbenchTicket.recipe()==SettlementMaterialCrafting.Recipe.STICKS?Action.CRAFT_STICKS_AT_WORKBENCH"));
		assertBefore(tick, "if(++progress<duration)return;", "SettlementWorkbenchCrafting.finish(");
		assertFalse(tick.contains("recordCraftedProduct"));
		assertTrue(methods.get("hasReturnCargo").contains("SettlementWorkbenchCargo.hasReturnable"));
	}

	@Test void liveDemandDoesNotRequireWithdrawnLogsOrMutatePersonalTools() throws Exception {
		var methods = methods();
		String demand = methods.get("toolStickDemandStillNeeded");
		for (String forbidden : List.of("setItem", "setItemInHand", "hasStonePickaxe()", "OAK_LOG", "ConstructionPlanner"))
			assertFalse(demand.contains(forbidden), forbidden);
		String construction = methods.get("stickDemandStillNeeded");
		for (String forbidden : List.of("OAK_LOG", "chooseRecipe", "availablePlain")) assertFalse(construction.contains(forbidden));
		assertTrue(methods.get("prepareWorkbenchCrafting").contains("stickDemandStillNeeded(demands,SettlementStockpile.getStockpile(level,data,camp))"));
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
