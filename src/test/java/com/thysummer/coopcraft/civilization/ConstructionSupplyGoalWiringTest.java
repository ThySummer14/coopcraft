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
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Source wiring checks only: these do not claim live navigation or world-placement coverage. */
class ConstructionSupplyGoalWiringTest {
	@Test
	void transferRunsOnlyAfterArrivalAndRechecksLiveAuthority() throws Exception {
		Map<String, String> methods = methods();
		String tick = methods.get("tickBuildSupply");
		assertTrue(tick.indexOf("if(!isTargetValid())") < tick.indexOf("SettlementStockpile.getStockpile("));
		assertTrue(tick.indexOf("if(!atCargoStore())") < tick.indexOf("SettlementStockpile.getStockpile("));
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
		assertTrue(methods.get("findWorkSettlement").contains(".filter(next->!next.instruction().consume()).isPresent()"));
		assertTrue(methods.get("tickBuildSupply").contains("if(hasReturnableSupply())deferBuildSupply()"));
		assertTrue(methods.get("tickStoreTravel").contains("cargoApproach.attempt("));
		assertTrue(methods.get("tickStoreTravel").contains("this::navigationSearchRegionLoaded"));
	}

	private static Map<String, String> methods() throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		Path source = Path.of("src/main/java/com/thysummer/coopcraft/civilization/VillagerWorkGoal.java");
		try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
				List.of("-proc:none"), null, files.getJavaFileObjects(source));
			var unit = task.parse().iterator().next();
			assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
				diagnostics.getDiagnostics().toString());
			ClassTree type = assertInstanceOf(ClassTree.class, unit.getTypeDecls().getFirst());
			Map<String, String> result = new LinkedHashMap<>();
			for (var member : type.getMembers()) {
				if (member instanceof MethodTree method && method.getBody() != null)
					result.put(method.getName().toString(), method.getBody().toString().replaceAll("\\s+", ""));
			}
			return result;
		}
	}
}
