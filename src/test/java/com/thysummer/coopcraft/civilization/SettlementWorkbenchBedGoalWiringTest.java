package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source wiring checks; real navigation remains integration coverage. */
class SettlementWorkbenchBedGoalWiringTest {
	@Test
	void bedsChoosePhysicalWorkBeforeLegacyCraftingAndNeverFallBack() throws Exception {
		var methods = methods();
		String choose = methods.get("chooseAction");
		assertBefore(choose, "currentMaterialRecipe==SettlementMaterialCrafting.Recipe.BED", "returnAction.CRAFT_MATERIALS;");
		assertTrue(choose.contains("returnworkbenchTicket==null?null:Action.COLLECT_BED_MATERIALS;"));
		for (String recipe : List.of("PLANKS", "DOOR", "BED"))
			assertBefore(methods.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe." + recipe,
				"SettlementMaterialCrafting.craft(");
		String prepare = methods.get("prepareWorkbenchCrafting");
		assertTrue(prepare.contains("SettlementMaterialCrafting.Reciperecipe=currentMaterialRecipe;"));
		assertTrue(prepare.contains("SettlementWorkbenchCrafting.prepare(data,camp,villager.getUUID(),villager.getInventory(),recipe,"));
	}

	@Test
	void bothBedPhasesShareValidationCancellationAndCargoPreemptionGates() throws Exception {
		var methods = methods();
		assertTrue(methods.get("isCollectWorkbenchAction").contains("action==Action.COLLECT_BED_MATERIALS"));
		assertTrue(methods.get("isWorkbenchAction").contains("isCollectWorkbenchAction()"));
		assertTrue(methods.get("isWorkbenchAction").contains("action==Action.WORKBENCH_BEDS"));
		assertTrue(methods.get("chooseTarget").contains("isCollectWorkbenchAction()?workbenchTicket.store():workbenchTicket.station()"));
		String valid = methods.get("isTargetValid");
		assertTrue(valid.contains("target.equals(isCollectWorkbenchAction()?workbenchTicket.store():workbenchTicket.station())"));
		assertTrue(valid.contains("workbenchTicket.collected()==!isCollectWorkbenchAction()"));
		assertTrue(valid.contains("workbenchTicket.recipe()=="));
		assertTrue(valid.contains("(action==Action.COLLECT_BED_MATERIALS||action==Action.WORKBENCH_BEDS)?SettlementMaterialCrafting.Recipe.BED"));
		assertTrue(valid.contains("SettlementWorkbenchCrafting.valid("));
		assertTrue(methods.get("canContinueToUse").contains("!isWorkbenchAction()&&settlement!=null&&hasReturnCargo()"));
		assertTrue(methods.get("cancelWorkbenchCrafting").contains("if(isWorkbenchAction())"));
		for (String method : List.of("canUse", "start", "tick", "stop", "canContinueToUse"))
			assertTrue(methods.get(method).contains("cancelWorkbenchCrafting()"));
	}

	@Test
	void bedInputsTravelToTheTableBeforeTimedConversionAndProductsUseCargoReturn() throws Exception {
		var methods = methods();
		String tick = methods.get("tickWorkbenchCrafting");
		assertBefore(tick, "!isTargetValid()", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "if(!atCargoStore())", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "if(isCollectWorkbenchAction())", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "SettlementWorkbenchCrafting.pickup(", "workbenchTicket.recipe()==SettlementMaterialCrafting.Recipe.BED?Action.WORKBENCH_BEDS");
		assertBefore(tick, "target=workbenchTicket.station();progress=0;travelTicks=0;return;", "if(++progress<duration)return;");
		assertBefore(tick, "if(++progress<duration)return;", "SettlementWorkbenchCrafting.finish(");
		assertTrue(tick.contains("workDuration(CRAFT_TICKS,VillagerPersonality.WorkType.CRAFT)"));
		assertTrue(tick.contains("SettlementSimulationService.craftingWorkDuration("));
		assertTrue(tick.contains("()->isTargetValid()&&atCargoStore()"));
		assertTrue(tick.contains("shouldEat()||meetingTicket!=null||emergencyForage()"));
		assertTrue(methods.get("start").contains("tickWorkbenchCrafting()"));
		assertTrue(methods.get("tick").contains("if(isWorkbenchAction()){tickWorkbenchCrafting();return;}"));
		assertTrue(methods.get("hasReturnCargo").contains("SettlementWorkbenchCargo.hasReturnable(villager.getInventory(),camp.id())"));
		assertTrue(methods.get("tickStoreTravel").contains("isWorkbenchAction()&&!workbenchPathInBounds(level)"));
		assertTrue(methods.get("tickStoreTravel").contains("this::navigationSearchRegionLoaded"));
	}

	@Test
	void liveWorldAndPlanGatesRemainPinnedWhileBedDemandIgnoresAlreadyWithdrawnIngredients() throws Exception {
		var methods = methods();
		String prepare = methods.get("prepareWorkbenchCrafting");
		for (String gate : List.of("SettlementCraftingStore.capture(level,store)", "binding.valid()",
			"SettlementSavedData.get(level)==data", "settlement==camp", "villager.level()==level",
			"villager.isAlive()", "!villager.isRemoved()", "!villager.isBaby()", "!villager.isSleeping()",
			"!villager.isTrading()", "!villager.isNoAi()", "!villager.isPassenger()",
			"level.getEntity(villager.getUUID())==villager", "foragePositionAccessible(level,station)",
			"ConstructionWorkshop.withinWorkRange(camp.center(),store)",
			"ConstructionWorkshop.withinWorkRange(camp.center(),station)",
			"level.getBlockState(station).equals(stationState)", "SettlementMembershipClaims.resolve(level,villager.getUUID())",
			"java.util.Objects.equals(job,data.getConstructionJob(camp).orElse(null))",
			"projects.equals(ConstructionPlanner.projects(data,camp))",
			"plans.equals(planIds.stream().map(BlueprintManager::get).toList())",
			"demands.equals(ConstructionPlanner.materialDemands(data,camp))")) assertTrue(prepare.contains(gate), gate);
		assertTrue(prepare.contains("recipe==SettlementMaterialCrafting.Recipe.BED?bedDemandStillNeeded(level,data,camp,demands)"));
		String demand = methods.get("bedDemandStillNeeded");
		assertTrue(demand.contains("ConstructionMaterials.available(stockpile,data.simulationOf(camp).orElse(null),Items.WHITE_BED)"));
		assertTrue(demand.contains("demand.getOrDefault(Items.WHITE_BED,0)>beds"));
		for (String forbidden : List.of("WHITE_WOOL", "OAK_PLANKS", "countPlain", "ingredients()", "chooseMaterialRecipe", "chooseRecipe", "availablePlain", "canCraft"))
			assertFalse(demand.contains(forbidden), forbidden);
	}

	@Test
	void pickupFinishAndTicketCallbacksAreWiredAtTheActualCallSites() throws Exception {
		var methods = methodTrees("VillagerWorkGoal");
		var prepare = onlyCall(methods.get("prepareWorkbenchCrafting"), "SettlementWorkbenchCrafting.prepare");
		assertEquals(7, prepare.getArguments().size());
		assertEquals("recipe", compact(prepare.getArguments().get(4)));
		var identity = assertInstanceOf(LambdaExpressionTree.class, prepare.getArguments().get(5));
		var demand = assertInstanceOf(LambdaExpressionTree.class, prepare.getArguments().get(6));
		for (String call : List.of("binding.valid", "SettlementMembershipClaims.belongsTo", "level.getEntity"))
			assertFalse(calls(identity, call).isEmpty(), call);
		assertEquals("level,data,camp,demands", onlyCall(demand, "bedDemandStillNeeded")
			.getArguments().stream().map(SettlementWorkbenchBedGoalWiringTest::compact)
			.collect(java.util.stream.Collectors.joining(",")));
		var availability = onlyCall(methods.get("bedDemandStillNeeded"), "ConstructionMaterials.available");
		assertEquals("Items.WHITE_BED", compact(availability.getArguments().get(2)));
		for (String operation : List.of("pickup", "finish")) {
			var call = onlyCall(methods.get("tickWorkbenchCrafting"), "SettlementWorkbenchCrafting." + operation);
			assertEquals("workbenchTicket", compact(call.getArguments().getFirst()));
			assertEquals("villager.getInventory()", compact(call.getArguments().get(4)));
			var authorization = assertInstanceOf(LambdaExpressionTree.class, call.getArguments().getLast());
			assertEquals("isTargetValid()&&atCargoStore()", compact(authorization.getBody()));
		}
		assertEquals("workbenchTicket", compact(onlyCall(methods.get("cancelWorkbenchCrafting"),
			"SettlementWorkbenchCrafting.cancel").getArguments().getFirst()));
		assertTrue(calls(methods.get("tickWorkbenchCrafting"), "SettlementMaterialCrafting.craft").isEmpty());
		assertTrue(calls(methods.get("cancelWorkbenchCrafting"), "villager.getInventory").isEmpty());
	}

	private static MethodInvocationTree onlyCall(Tree tree, String target) {
		var matches = calls(tree, target);
		assertEquals(1, matches.size(), target);
		return matches.getFirst();
	}

	private static List<MethodInvocationTree> calls(Tree tree, String target) {
		List<MethodInvocationTree> result = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
				if (compact(call.getMethodSelect()).equals(target)) result.add(call);
				return super.visitMethodInvocation(call, unused);
			}
		}.scan(tree, null);
		return result;
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
