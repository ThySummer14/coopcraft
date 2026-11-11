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
		assertTrue(prepare.contains("Action.REQUEST_FOOD : Action.DELIVER_FOOD"));
		assertTrue(named(methods, "isFoodDeliveryAction").toString().contains("Action.REQUEST_FOOD"));
		assertTrue(named(methods, "cancelFoodSharing").toString().contains("SettlementFoodDeliveryScene.cancel(foodDelivery)"));
	}
	@Test void recipientDoesNotJoinOrFreezeDuringTravelAndPromotionUsesExactPair() throws Exception {
		var methods = methods("SettlementFoodDeliveryScene");
		String poll = named(methods, "poll").toString();
		assertTrue(poll.indexOf("hasSurplus") < poll.indexOf("getEntitiesOfClass"));
		for (String token : List.of("++checked > 16", "registry.attempt", "prepare(level, data, camp, giver, recipient, giver, recipient)"))
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
		assertTrue(settle.contains("d.mover.position().equals(d.settledPosition)"));
		String claimed = named(methods("SettlementFoodSharingScene"), "claimed").toString();
		assertFalse(claimed.contains("foodDelivery"));
	}
	@Test void travelRevalidatesRealTicketTerrainAndPathIdentityWithoutNativeSearch() throws Exception {
		var methods = methods("SettlementFoodDeliveryScene");
		String valid = named(methods, "valid").toString();
		for (String token : List.of(".active(", "SettlementFoodSharing.valid", "SettlementFoodDeliveryRoute.valid", "getPath() == d.path", "pathUnchanged")) assertTrue(valid.contains(token), token);
		String all = java.nio.file.Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementFoodDeliveryScene.java"));
		for (String token : List.of("createPath(", ".shrink(", ".setItem(", ".setItemInHand(", ".finish(", "recordPractice")) assertFalse(all.contains(token), token);
		assertTrue(named(methods, "visible").toString().indexOf("SettlementTeachingScene.available") < named(methods, "visible").toString().indexOf("hasLineOfSight"));
		assertTrue(named(methods("SettlementFoodSharingScene"), "cancelOnUnload").toString().contains("SettlementFoodDeliveryScene.cancelResident"));
		assertTrue(named(methods("SettlementSavedData"), "removeMember").toString().contains("foodDeliveryReservations.cancelResident"));
	}
	@Test void requestUsesRememberedDonorsAndExplicitCampMealsBeforeAnyRoute() throws Exception {
		var methods = methods("SettlementFoodDeliveryScene");
		String request = named(methods, "pollRequest").toString();
		assertTrue(request.indexOf("requestReady") < request.indexOf("rememberedGivers"));
		for (String token : List.of("level.getEntity(id)", "registry.attempt", "foodSharingSessions().find", "hasSurplus",
			"bodyReady", "visible", "prepare(level, data, camp, giver, recipient, recipient, giver)")) assertTrue(request.contains(token), token);
		assertFalse(request.contains("getEntitiesOfClass")); assertFalse(request.contains(".join("));
		String wrapper = named(methods, "requestReady").toString();
		assertTrue(wrapper.contains("requestEligible(data, camp, recipient.getUUID(), recipient.getInventory(), SettlementStockpile.getStockpile(level, data, camp))"));
		String ready = named(methods, "requestEligible").toString();
		for (String token : List.of("isMember", "isSurvivalEnabled", "HUNGRY_AT", "ordinaryFoodPoints",
			"SettlementNeeds.hasFood(bag, stockpile)", "getItem(slot).isEmpty()")) assertTrue(ready.contains(token), token);
		String prepare = named(methods, "prepare").toString();
		for (String token : List.of("stationary.position().equals(fixed)", "giver.getInventory() == fromBag", "recipient.getInventory() == toBag",
			"movingBodyReady(level, data, camp, mover)", "bodyReady(level, data, camp, stationary)", "requestReady(level, data, camp, recipient)",
			"find(mover.blockPosition(), stationary.blockPosition()", "reserve(giver.getUUID(), recipient.getUUID()")) assertTrue(prepare.contains(token), token);
		String goal = named(methods("VillagerWorkGoal"), "tickFoodDelivery").toString();
		assertTrue(goal.contains("delivery.stationary"));
		assertTrue(goal.contains("session.giver == villager ? Action.SHARE_FOOD : Action.RECEIVE_FOOD"));
		assertTrue(named(methods, "start").toString().contains("d.mover.getNavigation().moveTo"));
		assertTrue(named(methods, "cancel").toString().contains("d.mover.getNavigation().stop()"));
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
