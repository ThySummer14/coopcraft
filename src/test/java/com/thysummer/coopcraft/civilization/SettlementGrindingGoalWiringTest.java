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
class SettlementGrindingGoalWiringTest {
	@Test void grindingMustCollectAtWarehouseThenWorkAtRegisteredStonecutter() throws Exception {
		var methods = methods();
		String choose = methods.get("chooseAction");
		assertTrue(choose.contains("currentMaterialRecipe=SettlementMaterialCrafting.Recipe.SMOOTH_STONE;workbenchTicket=prepareWorkbenchCrafting();"));
		assertTrue(choose.contains("returnAction.COLLECT_GRINDING_MATERIALS;"));
		assertTrue(methods.get("isCollectWorkbenchAction").contains("action==Action.COLLECT_GRINDING_MATERIALS"));
		assertTrue(methods.get("isWorkbenchAction").contains("action==Action.GRIND_SMOOTH_STONE"));
		assertTrue(methods.get("prepareWorkbenchCrafting").contains("grinding?data.getGrindingStation(camp):data.getCraftingStation(camp)"));
		assertTrue(methods.get("prepareWorkbenchCrafting").contains("stationState.is(grinding?Blocks.STONECUTTER:Blocks.CRAFTING_TABLE)"));
		assertTrue(methods.get("prepareWorkbenchCrafting").contains("ItemStack.matches(grindingTool,villager.getMainHandItem())"));
		assertTrue(methods.get("isTargetValid").contains("action==Action.COLLECT_GRINDING_MATERIALS||action==Action.GRIND_SMOOTH_STONE"));
		String tick = methods.get("tickWorkbenchCrafting");
		assertBefore(tick, "if(!atCargoStore())", "SettlementWorkbenchCrafting.pickup(");
		assertTrue(tick.contains("workbenchTicket.recipe()==SettlementMaterialCrafting.Recipe.SMOOTH_STONE?Action.GRIND_SMOOTH_STONE"));
		assertBefore(tick, "if(++progress<duration)return;", "SettlementWorkbenchCrafting.finish(");
		for (String forbidden : List.of("recordCraftedProduct", "withdrawItems", "SettlementPersonalCrafting")) assertFalse(tick.contains(forbidden));
		assertTrue(methods.get("hasReturnCargo").contains("SettlementWorkbenchCargo.hasReturnable"));
		assertBefore(methods.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.SMOOTH_STONE", "SettlementMaterialCrafting.craft(");
		assertFalse(methods.containsKey("grindSmoothStone"));
	}

	@Test void demandOnlyRechecksPolishingShortageAndNeverEquipsOrRequiresAnotherCobble() throws Exception {
		var methods = methods();
		String demand = methods.get("smoothStoneDemandStillNeeded");
		for (String forbidden : List.of("setItem", "hasStonePickaxe", "COBBLESTONE", "withdraw", "craft(")) assertFalse(demand.contains(forbidden));
		assertTrue(demand.contains("availableItems(Items.SMOOTH_STONE)<3"));
		for (String guard : List.of("SettlementWorkbenchCargo.isMarked", "SettlementShearingCargo.isMarked", "SettlementShearsLoan.isMarked", "SettlementToolUpgrade.supportsPolishing", "!isPolishedPickaxe")) assertTrue(demand.contains(guard));
		assertTrue(methods.get("canGrindSmoothStone").contains("SettlementMaterialCrafting.availablePlain("));
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
