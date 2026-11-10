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

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Source wiring checks only: these do not claim live navigation or world-placement coverage. */
class ConstructionSupplyGoalWiringTest {
	@Test
	void transferRunsOnlyAfterArrivalAndRechecksLiveAuthority() throws Exception {
		Map<String, String> methods = methods();
		String tick = methods.get("tickBuildSupply");
		assertBefore(tick, "if(!isTargetValid())", "SettlementStockpile.getStockpile(");
		assertBefore(tick, "if(!atCargoStore())", "SettlementStockpile.getStockpile(");
		assertTrue(tick.contains("()->isTargetValid()&&atCargoStore()"));
		assertTrue(tick.contains("ConstructionSupply.pickup(store,villager.getInventory()"));
		assertTrue(tick.contains("ConstructionSupply.returnUnused(villager.getInventory(),store"));
		assertTrue(tick.contains("Objects.equals(rawJob,settlementData().getConstructionJob(settlement).orElse(null))"));
		assertTrue(tick.contains("Objects.equals(returnJob,returnSupplyJob())"));
	}

	@Test
	void preparePinsIdentityBindingAndUniqueMembershipWhilePickupPinsTheExactStep() throws Exception {
		Map<String, String> methods = methods();
		String prepare = methods.get("prepareBuildSupply");
		assertTrue(prepare.contains("SettlementCraftingStore.capture(level,store)"));
		assertTrue(prepare.contains("data.stockpileBindingToken(camp)==storeToken"));
		assertTrue(prepare.contains("data.memberIdentity(villager.getUUID())==member"));
		assertTrue(prepare.contains("level.getEntity(villager.getUUID())==villager"));
		assertTrue(prepare.contains("data.getStockpile(camp).filter(store::equals).isPresent()&&binding.valid()"));
		assertEquals(2, prepare.split("SettlementMembershipClaims.resolve", -1).length - 1);
		String current = methods.get("currentSupplyTargetValid");
		assertTrue(current.contains("!ConstructionSupply.hasAny(villager.getInventory())"));
		assertTrue(current.contains("getConstructionJob(settlement).filter(currentBuildTarget.job()::equals).isPresent()"));
		assertTrue(current.contains("ConstructionSystem.nextBuildTarget((ServerLevel)villager.level(),settlementData(),settlement,villager)"));
		assertTrue(current.contains(".filter(currentBuildTarget::equals).isPresent()"));
	}

	@Test
	void interruptionPreservesPhysicalCargoAndNoWarehouseDoesNotEnableOrdinaryWork() throws Exception {
		Map<String, String> methods = methods();
		String stop = methods.get("stop");
		assertTrue(stop.contains("supplyAuthorization=null"));
		assertFalse(stop.contains("getInventory"));
		assertFalse(stop.contains("ConstructionSupply"));
		assertTrue(methods.get("chooseAction").startsWith("{if(settlementData().getStockpile(settlement).isEmpty())returnnull;"));
		assertTrue(methods.get("findWorkSettlement").contains("ConstructionSupply.hasAny(villager.getInventory())"));
		String lookup = methods.get("findWorkSettlement");
		// Javac's AST printer may parenthesize an inferred single lambda parameter.
		assertTrue(lookup.contains(".filter(next->!next.instruction().consume()).isPresent()")
			|| lookup.contains(".filter((next)->!next.instruction().consume()).isPresent()"));
		assertTrue(methods.get("tickBuildSupply").contains("if(hasReturnableSupply())deferBuildSupply()"));
		assertTrue(methods.get("tickStoreTravel").contains("cargoApproach.attempt("));
		assertTrue(methods.get("tickStoreTravel").contains("this::navigationSearchRegionLoaded"));
	}

	@Test
	void paidDoorAndBedBuildsUseCarriedSupplyInsideTheReadinessAndLiveAuthorityGuards() throws Exception {
		MethodTree build = methodTrees("ConstructionSystem").get("buildNext");
		assertNotNull(build);
		String source = compact(build.getBody());
		IfTree placementGate = placementGate(build);
		String gate = compact(placementGate);
		assertBefore(source, "if(current==null||!current.equals(target.job())){returnfalse;}", gate);
		assertBefore(source, "if(!current.compatibilityBlocker(plan).equals(\"ready\")"
			+ "||!plan.instructions().get(current.nextIndex()).equals(target.instruction())"
			+ "||!current.origin().offset(target.instruction().offset()).equals(target.position())){returnfalse;}", gate);
		assertBefore(source, "if(instruction.consume()&&ConstructionSupply.supported(instruction.item())"
			+ "&&!supplyWorkerReady(level,data,settlement,villager))returnfalse;", gate);

		var negated = assertInstanceOf(UnaryTree.class, unwrapped(placementGate.getCondition()));
		var readiness = assertInstanceOf(MethodInvocationTree.class, unwrapped(negated.getExpression()));
		assertEquals(List.of("current", "plan", "stepProbe(level,data)"),
			readiness.getArguments().subList(0, 3).stream().map(ConstructionSupplyGoalWiringTest::compact).toList());
		var placement = assertInstanceOf(LambdaExpressionTree.class, readiness.getArguments().get(3));
		var body = assertInstanceOf(BlockTree.class, placement.getBody());
		IfTree supplyBranch = body.getStatements().stream()
			.filter(IfTree.class::isInstance).map(IfTree.class::cast)
			.filter(branch -> compact(unwrapped(branch.getCondition())).equals(
				"instruction.consume()&&ConstructionSupply.supported(instruction.item())"))
			.findFirst().orElseThrow();
		var supplyBody = assertInstanceOf(BlockTree.class, supplyBranch.getThenStatement());
		assertEquals(1, supplyBody.getStatements().size(), "The paid branch must return its physical-item result");
		var returned = assertInstanceOf(ReturnTree.class, supplyBody.getStatements().getFirst());
		var supply = assertInstanceOf(MethodInvocationTree.class, returned.getExpression());
		assertEquals("ConstructionSupply.place", compact(supply.getMethodSelect()));
		assertEquals(List.of("villager.getInventory()", "level.dimension().location()", "settlement.id()",
			"villager.getUUID()", "current", "instruction"),
			supply.getArguments().subList(0, 6).stream().map(ConstructionSupplyGoalWiringTest::compact).toList());
		assertEquals(8, supply.getArguments().size());
		var authority = assertInstanceOf(LambdaExpressionTree.class, supply.getArguments().get(6));
		assertEquals("supplyWorkerReady(level,data,settlement,villager)"
			+ "&&data.getConstructionJob(settlement).filter(current::equals).isPresent()"
			+ "&&current.compatibilityBlocker(BlueprintManager.get(current.blueprintId())).equals(\"ready\")"
			+ "&&villager.distanceToSqr(target.position().getCenter())<=ConstructionApproach.REACH_SQUARED"
			+ "&&!villager.getBoundingBox().intersects(newAABB(target.position()))"
			+ "&&ConstructionStepReadiness.inspect(current,plan,stepProbe(level,data),true).ready()", compact(authority.getBody()));
		var worldPlacement = assertInstanceOf(LambdaExpressionTree.class, supply.getArguments().get(7));
		assertTrue(compact(worldPlacement.getBody()).startsWith("modificationAllowed.getAsBoolean()&&level.setBlock(target.position(),instruction.state(),"));
		assertFalse(compact(supplyBranch).contains("ConstructionMaterials.place"));
		assertFalse(compact(supplyBranch).contains("loadedStockpile"));
		assertBefore(compact(body), compact(supplyBranch), "ConstructionMaterials.place(");
		assertTrue(source.contains("()->!ConstructionProtection.isBlocked(plan,current.origin(),"
			+ "(minX,minZ,maxX,maxZ)->modificationBlocked(level,data,minX,minZ,maxX,maxZ))"));
		assertBefore(source, "if(!modificationAllowed.getAsBoolean())returnfalse;", gate);
		assertTrue(compact(body).contains("villager.getUUID(),data::setDirty,modificationAllowed,"));
		assertEquals(2, compact(body).split("modificationAllowed.getAsBoolean\\(\\)&&level.setBlock", -1).length - 1);
	}

	@Test
	void realWorldProtectionIsCombinedWithBusinessDataForEveryConstructionEntry() throws Exception {
		var methods = methodTrees("ConstructionSystem");
		assertEquals("{returndata.isResidentModificationBlocked(minX,minZ,maxX,maxZ)"
			+ "||SettlementSavedData.get(level).isResidentModificationBlocked(minX,minZ,maxX,maxZ);}",
			compact(methods.get("modificationBlocked").getBody()));
		assertTrue(compact(methods.get("stepProbe").getBody()).contains("returnConstructionSystem.modificationBlocked(level,data,minX,minZ,maxX,maxZ);"));
		assertTrue(compact(methods.get("inspect").getBody()).contains("returnConstructionSystem.modificationBlocked(level,data,minX,minZ,maxX,maxZ);"));
		assertTrue(compact(methods.get("siteProbe").getBody()).contains(
			"returnSettlementSavedData.get(level).isResidentModificationBlocked(minX,minZ,maxX,maxZ);"));
		assertTrue(compact(methods.get("protectedSiteProbe").getBody()).contains(
			"returndata.isResidentModificationBlocked(minX,minZ,maxX,maxZ)||probe.modificationBlocked(minX,minZ,maxX,maxZ);"));
		assertTrue(compact(methods.get("canPlace").getBody()).contains("guarded=protectedSiteProbe(data,probe)"));
		assertTrue(compact(methods.get("canPlace").getBody()).contains("guarded::modificationBlocked"));
		assertTrue(compact(methods.get("chooseOrigin").getBody()).contains("canPlace(data,plan,origin,siteProbe(level))"));
	}

	@Test
	void constructionAdvancesOrCompletesOnlyAfterThePlacementFailureReturn() throws Exception {
		MethodTree build = methodTrees("ConstructionSystem").get("buildNext");
		assertNotNull(build);
		IfTree placementGate = placementGate(build);
		var statements = build.getBody().getStatements();
		int gateIndex = statements.indexOf(placementGate);
		assertTrue(gateIndex >= 0);
		for (int index = 0; index <= gateIndex; index++) {
			String statement = compact(statements.get(index));
			assertFalse(statement.contains("data.advanceConstruction("));
			assertFalse(statement.contains("data.completeConstruction("));
		}
		var completion = assertInstanceOf(IfTree.class, statements.get(gateIndex + 1));
		assertEquals("current.nextIndex()+1>=plan.instructions().size()", compact(unwrapped(completion.getCondition())));
		assertTrue(compact(completion.getThenStatement()).contains("data.completeConstruction(settlement);"));
		assertEquals("{data.advanceConstruction(settlement);}", compact(completion.getElseStatement()));
		String source = compact(build.getBody());
		for (String mutation : List.of("data.completeConstruction(", "data.advanceConstruction(")) {
			int first = requiredIndex(source, mutation);
			assertEquals(-1, source.indexOf(mutation, first + mutation.length()), "Only one " + mutation + " call is allowed");
		}
		assertEquals("returntrue;", compact(statements.getLast()));
	}

	private static IfTree placementGate(MethodTree build) {
		IfTree gate = build.getBody().getStatements().stream()
			.filter(IfTree.class::isInstance).map(IfTree.class::cast)
			.filter(branch -> unwrapped(branch.getCondition()) instanceof UnaryTree negated
				&& negated.getKind() == Tree.Kind.LOGICAL_COMPLEMENT
				&& unwrapped(negated.getExpression()) instanceof MethodInvocationTree call
				&& compact(call.getMethodSelect()).equals("placeIfReady"))
			.findFirst().orElseThrow();
		assertEquals("returnfalse;", compact(gate.getThenStatement()));
		assertNull(gate.getElseStatement());
		return gate;
	}

	private static Tree unwrapped(Tree tree) {
		while (tree instanceof ParenthesizedTree parentheses) tree = parentheses.getExpression();
		return tree;
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
