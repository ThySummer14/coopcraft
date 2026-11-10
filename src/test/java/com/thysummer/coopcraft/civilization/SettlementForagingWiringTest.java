package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** AST integration guards only: these do not exercise Minecraft navigation or a real Goal. */
class SettlementForagingWiringTest {
	@Test void everyFinderRequiresCampAndActorAndTheServerRechecksCanonicalMembership() throws Exception {
		List<MethodTree> methods = methods("SettlementForaging.java");
		for (MethodTree method : methods.stream().filter(m -> m.getName().contentEquals("findRipeBush")).toList()) {
			var parameters = method.getParameters().stream().map(p -> p.getName().toString()).toList();
			assertTrue(parameters.contains("settlement"));
			assertTrue(parameters.contains("villager") || parameters.contains("actor"));
		}
		MethodTree finder = methods.stream().filter(m -> m.getName().contentEquals("findRipeBush")
			&& m.getParameters().getFirst().getType().toString().equals("ServerLevel")).findFirst().orElseThrow();
		assertEquals(1, calls(finder, "SettlementObservationClaims.capture").size());
		assertEquals(1, calls(finder, "liveAdult").size());
		MethodTree present = named(methods, "presentAdult");
		assertTrue(present.toString().contains("SettlementSavedData.get(level) == data"));
		assertTrue(present.toString().contains("level.getEntity(villager.getUUID()) == villager"));
		assertTrue(present.toString().contains("!villager.isBaby()"));
		assertEquals(1, calls(named(methods, "liveAdult"), "SettlementMembershipClaims.resolve").size());
	}

	@Test void goalClaimsOnlyFinalBerryChoiceAndReleasesOnStopFailureAndCompletion() throws Exception {
		List<MethodTree> methods = methods("VillagerWorkGoal.java");
		MethodTree choose = named(methods, "findRipeBerryBush");
		var statements = choose.getBody().getStatements();
		int cropChoice = -1, claim = -1;
		for (int index = 0; index < statements.size(); index++) {
			if (statements.get(index) instanceof IfTree condition && condition.getCondition().toString().contains("crop != null")) cropChoice = index;
			if (statements.get(index).toString().contains("forageRegistry.claim(")) claim = index;
		}
		assertTrue(cropChoice >= 0 && claim > cropChoice);
		assertEquals(1, calls(choose, "forageRegistry.claim").size());
		assertEquals(1, calls(named(methods, "stop"), "releaseForageLease").size());
		assertFalse(calls(named(methods, "canContinueToUse"), "releaseForageLease").isEmpty());
		assertEquals(1, calls(named(methods, "forage"), "releaseForageLease").size());
		var harvest = calls(named(methods, "forage"), "SettlementForaging.harvest").getFirst();
		assertEquals(List.of("forageRegistry", "forageLease"), harvest.getArguments().subList(5, 7).stream().map(Object::toString).toList());
		assertEquals(1, calls(named(methods, "isTargetValid"), "forageRegistry.valid").size());
		assertFalse(calls(named(methods, "tick"), "isTargetValid").isEmpty());
	}

	@Test void forageApproachChecksAllReadPositionsAndNavigationChecksSearchRegionAndNodes() throws Exception {
		List<MethodTree> methods = methods("VillagerWorkGoal.java");
		MethodTree approach = named(methods, "navigateToForageApproach");
		assertEquals(List.of("candidate", "candidate.above()", "candidate.below()"),
			calls(approach, "foragePositionAccessible").stream().map(call -> call.getArguments().get(1).toString()).toList());
		MethodTree accessible = named(methods, "foragePositionAccessible");
		for (String call : List.of("level.isOutsideBuildHeight", "level.getWorldBorder().isWithinBounds", "level.isLoaded", "level.isPositionEntityTicking")) {
			assertEquals(1, calls(accessible, call).size());
		}
		MethodTree navigate = named(methods, "navigateTo");
		assertEquals(1, calls(navigate, "navigationSearchRegionLoaded").size());
		assertEquals(1, calls(navigate, "villager.getNavigation().createPath").size());
		assertTrue(calls(navigate, "foragePositionAccessible").stream()
			.anyMatch(call -> call.getArguments().get(1).toString().equals("path.getNodePos(index)")));
		assertFalse(calls(named(methods, "navigateToTarget"), "constructionPathIsTicking").isEmpty());
	}

	private static MethodTree named(List<MethodTree> methods, String name) {
		return methods.stream().filter(method -> method.getName().contentEquals(name)).findFirst().orElseThrow();
	}
	private static List<MethodTree> methods(String file) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
		try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			var files = manager.getJavaFileObjects(Path.of("src/main/java/com/thysummer/coopcraft/civilization", file).toFile());
			var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, files);
			List<MethodTree> methods = new ArrayList<>();
			for (var unit : task.parse()) new TreeScanner<Void, Void>() {
				@Override public Void visitMethod(MethodTree tree, Void unused) { methods.add(tree); return super.visitMethod(tree, unused); }
			}.scan(unit, null);
			return methods;
		}
	}
	private static List<MethodInvocationTree> calls(MethodTree method, String name) {
		List<MethodInvocationTree> result = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
				if (tree.getMethodSelect().toString().equals(name)) result.add(tree);
				return super.visitMethodInvocation(tree, unused);
			}
		}.scan(method, null);
		return result;
	}
}
