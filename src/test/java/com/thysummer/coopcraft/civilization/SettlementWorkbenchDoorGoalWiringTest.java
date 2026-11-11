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

/** Source wiring checks; real navigation remains integration coverage. */
class SettlementWorkbenchDoorGoalWiringTest {
	@Test
	void doorsChoosePhysicalWorkBeforeLegacyCraftingAndNeverFallBack() throws Exception {
		var methods = methods();
		String choose = methods.get("chooseAction");
		assertBefore(choose, "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.DOOR", "returnAction.CRAFT_MATERIALS;");
		assertTrue(choose.contains("returnworkbenchTicket==null?null:Action.COLLECT_DOOR_MATERIALS;"));
		for (String recipe : List.of("PLANKS", "DOOR"))
			assertBefore(methods.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe." + recipe,
				"SettlementMaterialCrafting.craft(");
		String prepare = methods.get("prepareWorkbenchCrafting");
		assertTrue(prepare.contains("SettlementMaterialCrafting.Reciperecipe=currentMaterialRecipe;"));
		assertTrue(prepare.contains("SettlementWorkbenchCrafting.prepare(data,camp,villager.getUUID(),villager.getInventory(),recipe,"));
	}

	@Test
	void bothDoorPhasesShareValidationCancellationAndCargoPreemptionGates() throws Exception {
		var methods = methods();
		assertTrue(methods.get("isCollectWorkbenchAction").contains("action==Action.COLLECT_DOOR_MATERIALS"));
		assertTrue(methods.get("isWorkbenchAction").contains("isCollectWorkbenchAction()"));
		assertTrue(methods.get("isWorkbenchAction").contains("action==Action.WORKBENCH_DOORS"));
		assertTrue(methods.get("chooseTarget").contains("isCollectWorkbenchAction()?workbenchTicket.store():workbenchTicket.station()"));
		String valid = methods.get("isTargetValid");
		assertTrue(valid.contains("target.equals(isCollectWorkbenchAction()?workbenchTicket.store():workbenchTicket.station())"));
		assertTrue(valid.contains("workbenchTicket.collected()==!isCollectWorkbenchAction()"));
		assertTrue(valid.contains("workbenchTicket.recipe()=="));
		assertTrue(valid.contains("action==Action.COLLECT_DOOR_MATERIALS||action==Action.WORKBENCH_DOORS"));
		assertTrue(valid.contains("SettlementWorkbenchCrafting.valid("));
		assertTrue(methods.get("canContinueToUse").contains("!isWorkbenchAction()&&settlement!=null&&hasReturnCargo()"));
		assertTrue(methods.get("cancelWorkbenchCrafting").contains("if(isWorkbenchAction())"));
		for (String method : List.of("canUse", "stop", "canContinueToUse"))
			assertTrue(methods.get(method).contains("cancelWorkbenchCrafting()"));
	}

	@Test
	void doorInputsTravelToTheTableBeforeTimedConversionAndProductsUseCargoReturn() throws Exception {
		var methods = methods();
		String tick = methods.get("tickWorkbenchCrafting");
		assertBefore(tick, "!isTargetValid()", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "if(!atCargoStore())", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "if(isCollectWorkbenchAction())", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "SettlementWorkbenchCrafting.pickup(", "workbenchTicket.recipe()==SettlementMaterialCrafting.Recipe.DOOR?Action.WORKBENCH_DOORS:Action.WORKBENCH_PLANKS");
		assertBefore(tick, "target=workbenchTicket.station();progress=0;travelTicks=0;return;", "if(++progress<duration)return;");
		assertBefore(tick, "if(++progress<duration)return;", "SettlementWorkbenchCrafting.finish(");
		assertTrue(tick.contains("workDuration(CRAFT_TICKS,VillagerPersonality.WorkType.CRAFT)"));
		assertTrue(tick.contains("SettlementSimulationService.craftingWorkDuration("));
		assertTrue(tick.contains("()->isTargetValid()&&atCargoStore()"));
		assertTrue(tick.contains("shouldEat()||meetingTicket!=null||emergencyForage()"));
		assertTrue(methods.get("start").contains("tickWorkbenchCrafting()"));
		assertTrue(methods.get("tick").contains("if(isWorkbenchAction()){tickWorkbenchCrafting();return;}"));
		assertTrue(methods.get("hasReturnCargo").contains("SettlementWorkbenchCargo.hasReturnable(villager.getInventory(),camp.id())"));
	}

	@Test
	void liveWorldAndPlanGatesRemainPinnedWhileDoorDemandIgnoresAlreadyWithdrawnPlanks() throws Exception {
		var methods = methods();
		String prepare = methods.get("prepareWorkbenchCrafting");
		for (String gate : List.of("SettlementCraftingStore.capture(level,store)", "binding.valid()",
			"SettlementSavedData.get(level)==data", "settlement==camp", "villager.level()==level",
			"villager.isAlive()", "!villager.isRemoved()", "!villager.isBaby()", "!villager.isSleeping()",
			"!villager.isTrading()", "!villager.isNoAi()", "!villager.isPassenger()",
			"level.getEntity(villager.getUUID())==villager", "foragePositionAccessible(level,station)",
			"level.getBlockState(station).equals(stationState)", "SettlementMembershipClaims.resolve(level,villager.getUUID())",
			"java.util.Objects.equals(job,data.getConstructionJob(camp).orElse(null))",
			"projects.equals(ConstructionPlanner.projects(data,camp))",
			"plans.equals(planIds.stream().map(BlueprintManager::get).toList())",
			"demands.equals(ConstructionPlanner.materialDemands(data,camp))")) assertTrue(prepare.contains(gate), gate);
		assertTrue(prepare.contains("recipe==SettlementMaterialCrafting.Recipe.PLANKS?plankDemandStillNeeded(level,data,camp,demands):doorDemandStillNeeded(level,data,camp,demands)"));
		String demand = methods.get("doorDemandStillNeeded");
		assertTrue(demand.contains("SettlementMaterialCrafting.countPlain(stockpile,Items.OAK_DOOR)"));
		assertTrue(demand.contains("demand.getOrDefault(Items.OAK_DOOR,0)>doors"));
		for (String forbidden : List.of("OAK_PLANKS", "ingredients()", "chooseMaterialRecipe", "chooseRecipe", "availablePlain", "canCraft"))
			assertFalse(demand.contains(forbidden), forbidden);
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
