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

/** AST/source wiring only; live movement and server behavior remain integration coverage. */
class SettlementWorkbenchGoalWiringTest {
	@Test
	void planksUseOnlyTheNewRouteAndOtherRecipesKeepTheirRoute() throws Exception {
		var methods = methods();
		String choose = methods.get("chooseAction");
		assertBefore(choose, "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.PLANKS", "returnAction.CRAFT_MATERIALS;");
		assertTrue(choose.contains("workbenchTicket=prepareWorkbenchCrafting()"));
		assertTrue(choose.contains("returnworkbenchTicket==null?null:Action.COLLECT_PLANK_MATERIALS;"));
		assertBefore(methods.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.PLANKS", "SettlementMaterialCrafting.craft(");
		assertTrue(methods.get("chooseTarget").contains("?workbenchTicket.store():workbenchTicket.station()"));
	}

	@Test
	void arrivalAndLiveValidationPrecedePickupAndTimingOnlyStartsAtTheTable() throws Exception {
		var methods = methods();
		String tick = methods.get("tickWorkbenchCrafting");
		assertBefore(tick, "!isTargetValid()", "SettlementStockpile.getStockpile(");
		assertBefore(tick, "if(!atCargoStore())", "SettlementStockpile.getStockpile(");
		assertBefore(tick, "SettlementWorkbenchCrafting.pickup(", "?Action.WORKBENCH_DOORS:Action.WORKBENCH_PLANKS;");
		assertBefore(tick, "target=workbenchTicket.station();progress=0;travelTicks=0;return;", "if(++progress<duration)return;");
		assertTrue(tick.contains("workDuration(CRAFT_TICKS,VillagerPersonality.WorkType.CRAFT)"));
		assertTrue(tick.contains("SettlementWorkbenchCrafting.finish("));
		assertTrue(tick.contains("()->isTargetValid()&&atCargoStore()"));
		assertTrue(methods.get("start").contains("tickWorkbenchCrafting()"));
		assertTrue(methods.get("tick").contains("if(isWorkbenchAction()){tickWorkbenchCrafting();return;}"));
		assertTrue(methods.get("tickStoreTravel").contains("this::navigationSearchRegionLoaded"));
	}

	@Test
	void ticketPinsWorldIdentityAndDemandWithoutRequiringAnotherInputLog() throws Exception {
		var methods = methods();
		String prepare = methods.get("prepareWorkbenchCrafting");
		for (String gate : List.of("SettlementCraftingStore.capture(level,store)", "binding.valid()",
			"level.getEntity(villager.getUUID())==villager", "foragePositionAccessible(level,station)",
			"level.getBlockState(station).equals(stationState)", "SettlementMembershipClaims.resolve(level,villager.getUUID())",
			"demands.equals(ConstructionPlanner.materialDemands(data,camp))")) assertTrue(prepare.contains(gate), gate);
		String demand = methods.get("plankDemandStillNeeded");
		assertFalse(demand.contains("chooseMaterialRecipe"));
		assertFalse(demand.contains("chooseRecipe"));
		assertFalse(demand.contains("Items.OAK_LOG"));
		assertTrue(demand.contains("demand.getOrDefault(Items.OAK_PLANKS,0)>planks"));
		assertTrue(demand.contains("recipe.ingredients().getOrDefault(Items.OAK_PLANKS,0)>planks"));
	}

	@Test
	void interruptionDropsAuthorityNotCargoAndPendingWorkAvoidsTheReturnLoop() throws Exception {
		var methods = methods();
		assertTrue(methods.get("canUse").contains("cancelWorkbenchCrafting()"));
		assertTrue(methods.get("stop").contains("cancelWorkbenchCrafting()"));
		String cancel = methods.get("cancelWorkbenchCrafting");
		assertTrue(cancel.contains("SettlementWorkbenchCrafting.cancel(workbenchTicket)"));
		assertFalse(cancel.contains("getInventory"));
		assertTrue(methods.get("canContinueToUse").contains("!isWorkbenchAction()&&settlement!=null&&hasReturnCargo()"));
		assertTrue(methods.get("tickWorkbenchCrafting").contains("shouldEat()||meetingTicket!=null||emergencyForage()"));
		assertTrue(methods.get("hasReturnCargo").contains("SettlementWorkbenchCargo.hasReturnable(villager.getInventory(),camp.id())"));
		for (String method : List.of("canCraftSticks", "craftSticks", "withdrawOneLog"))
			assertFalse(methods.containsKey(method), "Legacy remote stick crafting must be removed");
	}

	@Test
	void allLegacyMaterialConsumersAndToolsRespectMarkerQuarantine() throws Exception {
		var methods = methods();
		for (String method : List.of("canGrindSmoothStone", "grindSmoothStone"))
			assertTrue(methods.get(method).contains("!SettlementWorkbenchCargo.isMarked(stack)"));
		for (String method : List.of("availableItems", "withdrawItems")) {
			assertFalse(methods.get(method).contains(".countItem("));
			assertTrue(methods.get(method).contains("countCraftingItems("));
		}
		assertTrue(methods.get("withdrawItems").contains("transferCraftingItems(stockpile,villager.getInventory(),item,requested)"));
		assertFalse(methods.get("withdrawItems").contains("SettlementStockpile.transferItem"));
		for (String method : List.of("countCraftingItems", "transferCraftingItems", "removeItems", "isPolishedPickaxe"))
			assertTrue(methods.get(method).contains("SettlementWorkbenchCargo.isMarked(stack)"));
		assertTrue(methods.get("hasStonePickaxe").contains("if(SettlementWorkbenchCargo.isMarked(hand)||SettlementShearingCargo.isMarked(hand)||SettlementShearsLoan.isMarked(hand))returnfalse;"));
		assertTrue(methods.get("hasStonePickaxe").contains("stack.is(Items.STONE_PICKAXE)&&!SettlementWorkbenchCargo.isMarked(stack)&&!SettlementShearingCargo.isMarked(stack)&&!SettlementShearsLoan.isMarked(stack)"));
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
