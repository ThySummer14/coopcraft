package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodDeliveryGoalWiringTest {
	@Test void deliveryHasSeparateMovementDispatchAndOriginalSharingRemainsStationary() throws Exception {
		var methods = methods("VillagerWorkGoal");
		for (String name : List.of("start", "tick")) {
			String body = named(methods, name).toString();
			assertTrue(body.indexOf("tickFoodDelivery()") < body.indexOf("tickFoodSharing()"));
		}
		assertTrue(named(methods, "canContinueToUse").toString().contains("foodDeliveryValid()"));
		assertTrue(named(methods, "isTargetValid").toString().contains("foodDeliveryValid()"));
		String prepare = named(methods, "prepareFoodSharing").toString();
		assertTrue(prepare.indexOf("foodSharingCooldown.attempt") < prepare.indexOf("SettlementFoodDeliveryScene.poll"));
		assertTrue(prepare.contains("action = Action.DELIVER_FOOD"));
		assertTrue(named(methods, "cancelFoodSharing").toString().contains("SettlementFoodDeliveryScene.cancel(foodDelivery)"));
	}
	@Test void recipientDoesNotJoinOrFreezeDuringTravelAndPromotionUsesExactPair() throws Exception {
		var methods = methods("SettlementFoodDeliveryScene");
		String poll = named(methods, "poll").toString();
		assertTrue(poll.indexOf("hasSurplus") < poll.indexOf("getEntitiesOfClass"));
		for (String token : List.of("++checked > 16", "registry.attempt", "recipient.position().equals(to)", "giver.getInventory() == fromBag", "recipient.getInventory() == toBag", "SettlementFoodSharing.prepare", "registry.reserve"))
			assertTrue(poll.contains(token), token);
		assertFalse(poll.contains(".join("));
		String arrive = named(methods, "arrive").toString();
		assertTrue(arrive.indexOf("getNavigation().stop()") < arrive.indexOf(".transfer("));
		assertTrue(arrive.contains("receiveDelivery(d.giver, d.recipient, d.data, d.camp)"));
		assertFalse(arrive.contains(".poll(")); assertFalse(arrive.contains(".finish("));
		assertTrue(arrive.contains("!transferred"));
		assertTrue(arrive.contains("SettlementFoodSharingScene.cancel(result[0])"));
		String settle = named(methods, "settled").toString();
		assertTrue(settle.contains("getNavigation().stop()"));
		assertTrue(settle.contains("now > d.settledSince"));
		assertTrue(settle.contains("d.giver.position().equals(d.settledPosition)"));
		String claimed = named(methods("SettlementFoodSharingScene"), "claimed").toString();
		assertFalse(claimed.contains("foodDelivery"));
	}
	@Test void travelRevalidatesRealTicketTerrainAndPathIdentityWithoutNativeSearch() throws Exception {
		var methods = methods("SettlementFoodDeliveryScene");
		String valid = named(methods, "valid").toString();
		for (String token : List.of(".active(", "SettlementFoodSharing.valid", "SettlementFoodDeliveryRoute.valid", "getPath() == d.path", "pathUnchanged")) assertTrue(valid.contains(token), token);
		String all = java.nio.file.Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementFoodDeliveryScene.java"));
		for (String token : List.of("createPath(", ".shrink(", ".setItem(", ".setItemInHand(", ".finish(", "getStockpile", "recordPractice")) assertFalse(all.contains(token), token);
		assertTrue(named(methods, "visible").toString().indexOf("SettlementTeachingScene.available") < named(methods, "visible").toString().indexOf("hasLineOfSight"));
		assertTrue(named(methods("SettlementFoodSharingScene"), "cancelOnUnload").toString().contains("SettlementFoodDeliveryScene.cancelResident"));
		assertTrue(named(methods("SettlementSavedData"), "removeMember").toString().contains("foodDeliveryReservations.cancelResident"));
	}
	private static MethodTree named(List<MethodTree> methods, String name) {
		return methods.stream().filter(method -> method.getName().contentEquals(name)).findFirst().orElseThrow();
	}
	private static List<MethodTree> methods(String name) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var files = manager.getJavaFileObjects(Path.of("src/main/java/com/thysummer/coopcraft/civilization", name + ".java").toFile());
			var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, files);
			List<MethodTree> result = new ArrayList<>();
			for (var unit : task.parse()) new TreeScanner<Void, Void>() {
				@Override public Void visitMethod(MethodTree tree, Void unused) { result.add(tree); return super.visitMethod(tree, unused); }
			}.scan(unit, null);
			assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
			return result;
		}
	}
	private static List<MethodInvocationTree> calls(Tree tree, String name) {
		List<MethodInvocationTree> result = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
				if (call.getMethodSelect().toString().equals(name)) result.add(call);
				return super.visitMethodInvocation(call, unused);
			}
		}.scan(tree, null);
		return result;
	}
}
