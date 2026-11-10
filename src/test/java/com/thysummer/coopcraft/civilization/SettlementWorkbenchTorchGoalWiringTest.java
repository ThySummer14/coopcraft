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

/** Source/AST wiring checks only; world navigation remains unverified. */
class SettlementWorkbenchTorchGoalWiringTest {
	@Test
	void bothFuelRecipesChoosePhysicalPickupAndCannotReachLegacyCrafting() throws Exception {
		var methods = methods();
		String choose = methods.get("chooseAction");
		for (String recipe : List.of("TORCH_COAL", "TORCH_CHARCOAL")) {
			assertBefore(choose, "currentMaterialRecipe==SettlementMaterialCrafting.Recipe." + recipe, "returnAction.CRAFT_MATERIALS;");
			assertBefore(methods.get("craftMaterials"), "currentMaterialRecipe==SettlementMaterialCrafting.Recipe." + recipe,
				"SettlementMaterialCrafting.craft(");
			assertTrue(methods.get("prepareWorkbenchCrafting").contains("recipe!=SettlementMaterialCrafting.Recipe." + recipe));
		}
		assertTrue(choose.contains("returnworkbenchTicket==null?null:Action.COLLECT_TORCH_MATERIALS;"));
		assertTrue(methods.get("chooseMaterialRecipe").contains("SettlementMaterialCrafting.chooseRecipe(stockpile,state,demand)"));
		assertTrue(methods.get("constructionMaterialDemands").contains("ConstructionPlanner.materialDemands(settlementData(),settlement)"));
	}

	@Test
	void sharedActionsPinTheExactSelectedFuelAndRouteBothPhases() throws Exception {
		var methods = methods();
		assertTrue(methods.get("isCollectWorkbenchAction").contains("action==Action.COLLECT_TORCH_MATERIALS"));
		assertTrue(methods.get("isWorkbenchAction").contains("action==Action.WORKBENCH_TORCHES"));
		String valid = methods.get("isTargetValid");
		assertTrue(valid.contains("target.equals(isCollectWorkbenchAction()?workbenchTicket.store():workbenchTicket.station())"));
		assertTrue(valid.contains("workbenchTicket.collected()==!isCollectWorkbenchAction()"));
		assertTrue(valid.contains("||isTorchRecipe(currentMaterialRecipe)"));
		assertTrue(valid.contains("workbenchTicket.recipe()==((action==Action.COLLECT_TORCH_MATERIALS||action==Action.WORKBENCH_TORCHES)?currentMaterialRecipe:"));
		assertTrue(valid.contains("SettlementWorkbenchCrafting.valid("));
		assertTrue(methods.get("chooseTarget").contains("isCollectWorkbenchAction()?workbenchTicket.store():workbenchTicket.station()"));
		assertTrue(methods.get("tickWorkbenchCrafting").contains("isTorchRecipe(workbenchTicket.recipe())?Action.WORKBENCH_TORCHES"));
	}

	@Test
	void pickedUpIngredientsDoNotInvalidateOutputDemandOrRetargetFuel() throws Exception {
		var methods = methods();
		String demand = methods.get("torchDemandStillNeeded");
		assertTrue(demand.contains("SettlementMaterialCrafting.countPlain(stockpile,Items.TORCH)"));
		assertTrue(demand.contains("demand.getOrDefault(Items.TORCH,0)>torches"));
		for (String forbidden : List.of("COAL", "CHARCOAL", "STICK", "chooseRecipe", "availablePlain"))
			assertFalse(demand.contains(forbidden));
		var trees = methodTrees("VillagerWorkGoal");
		var prepare = onlyCall(trees.get("prepareWorkbenchCrafting"), "SettlementWorkbenchCrafting.prepare");
		assertEquals("recipe", compact(prepare.getArguments().get(4)));
		var gate = assertInstanceOf(LambdaExpressionTree.class, prepare.getArguments().get(6));
		assertEquals(List.of("demands", "SettlementStockpile.getStockpile(level,data,camp)"),
			onlyCall(gate, "torchDemandStillNeeded").getArguments().stream().map(SettlementWorkbenchTorchGoalWiringTest::compact).toList());
		for (String fragment : List.of("java.util.Objects.equals(job,data.getConstructionJob(camp).orElse(null))",
			"projects.equals(ConstructionPlanner.projects(data,camp))", "plans.equals(planIds.stream().map(BlueprintManager::get).toList())",
			"demands.equals(ConstructionPlanner.materialDemands(data,camp))"))
			assertTrue(methods.get("prepareWorkbenchCrafting").contains(fragment));
	}

	@Test
	void timedConversionRequiresArrivalAndExistingPriorityAndCancellationGates() throws Exception {
		var methods = methods();
		String tick = methods.get("tickWorkbenchCrafting");
		assertBefore(tick, "!isTargetValid()", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "if(!atCargoStore())", "SettlementWorkbenchCrafting.pickup(");
		assertBefore(tick, "target=workbenchTicket.station();progress=0;travelTicks=0;return;", "if(++progress<duration)return;");
		assertBefore(tick, "if(++progress<duration)return;", "SettlementWorkbenchCrafting.finish(");
		assertTrue(tick.contains("shouldEat()||meetingTicket!=null||emergencyForage()"));
		var trees = methodTrees("VillagerWorkGoal");
		for (String operation : List.of("pickup", "finish")) {
			var call = onlyCall(trees.get("tickWorkbenchCrafting"), "SettlementWorkbenchCrafting." + operation);
			assertEquals("workbenchTicket", compact(call.getArguments().getFirst()));
			assertEquals("isTargetValid()&&atCargoStore()", compact(assertInstanceOf(LambdaExpressionTree.class,
				call.getArguments().getLast()).getBody()));
		}
		for (String method : List.of("canUse", "start", "tick", "stop", "canContinueToUse"))
			assertTrue(methods.get(method).contains("cancelWorkbenchCrafting()"));
		assertTrue(methods.get("canContinueToUse").contains("!isWorkbenchAction()&&settlement!=null&&hasReturnCargo()"));
		assertTrue(methods.get("tickStoreTravel").contains("(isWorkbenchAction()||isShearsLoanAction()||action==Action.COLLECT_STONE_PICKAXE)&&!workbenchPathInBounds(level)"));
		assertTrue(methods.get("tickStoreTravel").contains("this::navigationSearchRegionLoaded"));
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
