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
class SettlementPolishingGoalWiringTest {
	@Test void polishingCollectsWholePublicBatchThenWorksAtStonecutter() throws Exception {
		var m = methods();
		assertTrue(m.get("chooseAction").contains("workbenchTicket=preparePolishingCrafting();"));
		assertTrue(m.get("chooseAction").contains("returnAction.COLLECT_POLISHING_MATERIALS;"));
		assertTrue(m.get("isCollectWorkbenchAction").contains("action==Action.COLLECT_POLISHING_MATERIALS"));
		assertTrue(m.get("isWorkbenchAction").contains("action==Action.POLISH_PICKAXE"));
		String prepare = m.get("preparePolishingCrafting");
		for (String gate : List.of("data.getGrindingStation(camp)", "stationState.is(Blocks.STONECUTTER)",
			"binding.valid()", "villager.level()==level", "level.getEntity(villager.getUUID())==villager",
			"!villager.isBaby()", "!villager.isNoAi()", "SettlementMembershipClaims.resolve(",
			"SettlementWorkbenchCrafting.preparePolishing(", "this::polishingDemandStillNeeded"))
			assertTrue(prepare.contains(gate), gate);
		String tick = m.get("tickWorkbenchCrafting");
		assertBefore(tick, "if(!atCargoStore())", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "if(++progress<duration)return;", "SettlementWorkbenchCrafting.finishPolishing(");
		assertTrue(tick.contains("action==Action.POLISH_PICKAXE?SettlementWorkbenchCrafting.finishPolishing("));
		assertTrue(tick.contains("if(finished&&action==Action.WORKBENCH_PICKAXE)"));
		assertTrue(m.get("isTargetValid").contains("action==Action.COLLECT_POLISHING_MATERIALS||action==Action.POLISH_PICKAXE"));
		assertFalse(m.containsKey("craftPolishedPickaxe"));
	}

	@Test void validationNeverEquipsOrConsumesPersonalMaterialsOrRequiresSecondBatch() throws Exception {
		var m = methods();
		String demand = m.get("polishingDemandStillNeeded"), selection = m.get("canPolishPickaxe");
		for (String forbidden : List.of("hasStonePickaxe", "setItem", "withdraw", "availableItems")) {
			assertFalse(demand.contains(forbidden)); assertFalse(selection.contains(forbidden));
		}
		for (String forbidden : List.of("SMOOTH_STONE", "STICK", "availablePlain", "getStockpile")) assertFalse(demand.contains(forbidden));
		assertTrue(selection.contains("SettlementMaterialCrafting.availablePlain(store,state,Items.SMOOTH_STONE)>=3"));
		assertTrue(selection.contains("SettlementMaterialCrafting.availablePlain(store,state,Items.STICK)>=2"));
		assertBefore(m.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.POLISH_PICKAXE", "SettlementMaterialCrafting.craft(");
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
