package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AST adapter checks supplement the real pure-service tests; no live world or AI is executed. */
class SettlementProtectedHarvestWiringTest {
	private static final List<String> SERVICES = List.of("SettlementGathering", "SettlementCropHarvest");

	@Test void worldAdaptersPreserveCallerDataButVetoWithCurrentDimensionBeforeWorldReads() throws Exception {
		int checked = 0;
		for (String service : SERVICES) for (MethodTree method : methods(service)) {
			if (!worldMethod(method) || method.getParameters().stream().noneMatch(p -> p.getName().contentEquals("data"))) continue;
			String name = method.getName().toString();
			if (!List.of("validTarget", "harvest", "findRipeBush", "findRipe").contains(name)) continue;
			// Compatibility wrappers are checked separately; inspect all five actual world adapters here.
			if (service.equals("SettlementCropHarvest") && cropPermissionWrapper(method)) continue;
			String position = method.getParameters().stream().anyMatch(p -> p.getName().contentEquals("position")) ? "position" : "pos";
			var delegates = invocations(method, name);
			assertEquals(1, delegates.size(), service + "." + name);
			assertEquals("data", delegates.getFirst().getArguments().getFirst().toString(), "Keep caller business state and its independent veto");
			var gates = lambdas(method).stream().filter(lambda -> invocations(lambda, "level.isLoaded").stream()
				.anyMatch(call -> arguments(call).equals(List.of(position)))).toList();
			assertEquals(1, gates.size(), service + "." + name);
			BinaryTree gate = assertInstanceOf(BinaryTree.class, gates.getFirst().getBody());
			assertEquals(Tree.Kind.CONDITIONAL_AND, gate.getKind(), "Blocked positions must short-circuit the world read");
			assertLiveAllowed(gate.getLeftOperand(), position);
			MethodInvocationTree loaded = assertInstanceOf(MethodInvocationTree.class, gate.getRightOperand());
			assertEquals("level.isLoaded", loaded.getMethodSelect().toString());
			assertEquals(List.of(position), arguments(loaded));
			checked++;
		}
		assertEquals(5, checked, "Gathering/crop continuation and harvest, plus the crop finder");
		assertBerryPermissionWiring();
	}

	@Test void cropCompatibilityWrappersOnlyCapturePermissionAndForwardTheSameArguments() throws Exception {
		var wrappers = methods("SettlementCropHarvest").stream()
			.filter(SettlementProtectedHarvestWiringTest::cropPermissionWrapper).toList();
		assertEquals(2, wrappers.size(), "Both legacy world entry points must capture fresh permission");
		for (MethodTree wrapper : wrappers) {
			String name = wrapper.getName().toString();
			List<String> forwarded = name.equals("harvest")
				? List.of("level", "data", "settlement", "villager", "pos")
				: List.of("level", "data", "settlement", "pos");
			assertEquals(forwarded, wrapper.getParameters().stream().map(p -> p.getName().toString()).toList());
			assertEquals(1, wrapper.getBody().getStatements().size(), "No work before or after forwarding");
			ReturnTree returned = assertInstanceOf(ReturnTree.class, wrapper.getBody().getStatements().getFirst());
			MethodInvocationTree delegate = assertInstanceOf(MethodInvocationTree.class, returned.getExpression());
			assertEquals(name, delegate.getMethodSelect().toString());
			assertEquals(forwarded.size() + 1, delegate.getArguments().size());
			assertEquals(forwarded, arguments(delegate).subList(0, forwarded.size()));
			MethodInvocationTree capture = assertInstanceOf(MethodInvocationTree.class, delegate.getArguments().getLast());
			assertEquals("capturePermission", capture.getMethodSelect().toString());
			assertEquals(List.of("data", "settlement", "pos"), arguments(capture));
			List<String> calls = new ArrayList<>();
			new TreeScanner<Void, Void>() {
				@Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
					calls.add(call.getMethodSelect().toString());
					return super.visitMethodInvocation(call, unused);
				}
			}.scan(wrapper.getBody(), null);
			assertEquals(List.of(name, "capturePermission"), calls,
				"Wrappers must neither access the world nor replace actor, caller data or position");
		}
	}

	@Test void gatheringAndBerryWorldMutationsRecheckLiveDimensionInsideTheirCallbacks() throws Exception {
		MethodTree gathering = worldHarvest("SettlementGathering");
		assertEquals(1, invocations(gathering, "level.destroyBlock").size());
		var writes = lambdas(gathering).stream().filter(lambda -> !invocations(lambda, "level.destroyBlock").isEmpty()).toList();
		assertEquals(1, writes.size());
		BinaryTree body = assertInstanceOf(BinaryTree.class, writes.getFirst().getBody());
		assertEquals(Tree.Kind.CONDITIONAL_AND, body.getKind());
		assertLiveAllowed(body.getLeftOperand(), "pos");
		MethodInvocationTree destroy = assertInstanceOf(MethodInvocationTree.class, body.getRightOperand());
		assertEquals("level.destroyBlock", destroy.getMethodSelect().toString());
		assertEquals("pos", destroy.getArguments().getFirst().toString());
		MethodTree berry = methods("SettlementForaging").stream().filter(method -> worldMethod(method)
			&& method.getName().contentEquals("harvest") && method.getParameters().size() == 7).findFirst().orElseThrow();
		VariableTree current = assertInstanceOf(VariableTree.class, berry.getBody().getStatements().getFirst());
		assertEquals("current", current.getName().toString());
		LambdaExpressionTree identity = assertInstanceOf(LambdaExpressionTree.class, current.getInitializer());
		assertConjuncts(identity.getBody(), "liveAdult(level, data, settlement, villager)", "registry != null",
			"registry.valid(lease, data, settlement, villager.getUUID(), position, level.getGameTime())");
		var delegate = invocations(berry, "harvest").getFirst();
		assertEquals(List.of("data", "settlement", "villager.getUUID()", "position", "villager.getInventory()", "current"),
			arguments(delegate).subList(0, 6));
		assertEquals(1, invocations(berry, "level.setBlock").size());
		LambdaExpressionTree write = assertInstanceOf(LambdaExpressionTree.class, delegate.getArguments().get(10));
		assertConjuncts(write.getBody(), "current.getAsBoolean()", "data.canHarvestBerries(settlement, position)",
			"inBounds(level, position)", "level.isLoaded(position)", "level.isPositionEntityTicking(position)",
			"level.setBlock(position, mature.setValue(SweetBerryBushBlock.AGE, 1), Block.UPDATE_ALL)");
		LambdaExpressionTree loot = assertInstanceOf(LambdaExpressionTree.class, delegate.getArguments().get(9));
		ConditionalExpressionTree gatedLoot = assertInstanceOf(ConditionalExpressionTree.class, loot.getBody());
		assertConjuncts(gatedLoot.getCondition(), "current.getAsBoolean()", "data.canHarvestBerries(settlement, position)",
			"inBounds(level, position)", "level.isLoaded(position)", "level.isPositionEntityTicking(position)");
		assertEquals("List.of()", gatedLoot.getFalseExpression().toString());
		assertEquals(1, invocations(gatedLoot.getTrueExpression(), "Block.getDrops").size());
		MethodTree direct = worldHarvest("SettlementForaging");
		assertEquals(1, invocations(direct, "registry.claim").size());
		TryTree scoped = assertInstanceOf(TryTree.class, direct.getBody().getStatements().get(3));
		assertEquals(List.of("lease"), arguments(invocations(scoped.getFinallyBlock(), "registry.release").getFirst()));
	}

	@Test void potatoWorldWriteAndSwingCannotPrecedeTheCurrentDimensionVeto() throws Exception {
		MethodTree method = worldHarvest("SettlementCropHarvest");
		assertEquals(1, invocations(method, "level.setBlock").size());
		var writes = lambdas(method).stream().filter(lambda -> !invocations(lambda, "level.setBlock").isEmpty()).toList();
		assertEquals(1, writes.size());
		BlockTree body = assertInstanceOf(BlockTree.class, writes.getFirst().getBody());
		IfTree guard = assertInstanceOf(IfTree.class, body.getStatements().getFirst());
		BinaryTree condition = assertInstanceOf(BinaryTree.class, unparenthesized(guard.getCondition()));
		assertEquals(Tree.Kind.CONDITIONAL_OR, condition.getKind());
		assertLiveBlocked(condition.getLeftOperand(), "pos");
		UnaryTree failedWrite = assertInstanceOf(UnaryTree.class, condition.getRightOperand());
		assertEquals(Tree.Kind.LOGICAL_COMPLEMENT, failedWrite.getKind());
		MethodInvocationTree write = assertInstanceOf(MethodInvocationTree.class, failedWrite.getExpression());
		assertEquals("level.setBlock", write.getMethodSelect().toString());
		assertEquals("pos", write.getArguments().getFirst().toString());
		ReturnTree rejection = assertInstanceOf(ReturnTree.class, guard.getThenStatement());
		assertEquals("false", rejection.getExpression().toString());
		assertEquals(3, body.getStatements().size());
		assertEquals(1, invocations(body.getStatements().get(1), "villager.swing").size());
		ReturnTree success = assertInstanceOf(ReturnTree.class, body.getStatements().get(2));
		assertEquals("true", success.getExpression().toString());
	}

	@Test void everyBerryFinderRequiresIdentityAndWorldFinderDelegatesWithLivePermission() throws Exception {
		var finders = methods("SettlementForaging").stream().filter(method -> method.getName().contentEquals("findRipeBush")).toList();
		assertEquals(2, finders.size());
		for (MethodTree finder : finders) {
			var parameters = finder.getParameters().stream().map(p -> p.getType() + " " + p.getName()).toList();
			assertTrue(parameters.contains("SettlementSavedData data"));
			assertTrue(parameters.contains("Settlement settlement"));
			assertTrue(parameters.contains(worldMethod(finder) ? "Villager villager" : "UUID actor"));
		}
		MethodTree finder = finders.stream().filter(SettlementProtectedHarvestWiringTest::worldMethod).findFirst().orElseThrow();
		assertInitialPresenceGuard(finder, "Optional.empty()");
		var call = invocations(finder, "findRipeBush").getFirst();
		assertEquals(List.of("data", "settlement", "villager.getUUID()", "origin"), arguments(call).subList(0, 4));
		assertConjuncts(assertInstanceOf(LambdaExpressionTree.class, call.getArguments().get(4)).getBody(),
			"presentAdult(level, data, villager)", "claims.unique(data, settlement, member)");
		assertEquals(List.of("level::isLoaded", "level::isPositionEntityTicking"), arguments(call).subList(6, 8));
		assertEquals("level::getBlockState", arguments(call).get(9));
		MethodTree scan = finders.stream().filter(method -> !worldMethod(method)).findFirst().orElseThrow();
		List<IfTree> scanGates = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitBlock(BlockTree block, Void unused) {
				var statements = block.getStatements();
				for (int index = 0; index + 1 < statements.size(); index++) {
					if (!(statements.get(index) instanceof IfTree guard) || invocations(guard.getCondition(), "loaded.test").isEmpty()) continue;
					scanGates.add(guard);
					assertEquals("!permission.validForScan(pos, adult) || !inBounds.test(pos) || !loaded.test(pos) || !ticking.test(pos) || !available.test(pos)",
						unparenthesized(guard.getCondition()).toString());
					assertEquals(Tree.Kind.CONTINUE, guard.getThenStatement().getKind());
					IfTree read = assertInstanceOf(IfTree.class, statements.get(index + 1));
					assertConjuncts(read.getCondition(), "isRipe(block.apply(pos))", "permission.validForScan(pos, adult)");
				}
				return super.visitBlock(block, unused);
			}
		}.scan(scan, null);
		assertEquals(1, scanGates.size(), "Every scan read must follow the live permission and availability gate");
		ReturnTree returned = assertInstanceOf(ReturnTree.class, finder.getBody().getStatements().getLast());
		assertEquals(List.of("level", "data", "settlement", "villager"), arguments(invocations(returned, "liveAdult").getFirst()));
	}

	private static void assertBerryPermissionWiring() throws Exception {
		var berry = methods("SettlementForaging");
		MethodTree present = named(berry, "presentAdult");
		assertConjuncts(returnExpression(present), "SettlementSavedData.get(level) == data", "villager.level() == level",
			"villager.isAlive()", "!villager.isRemoved()", "!villager.isBaby()",
			"level.getEntity(villager.getUUID()) == villager", "inBounds(level, villager.blockPosition())",
			"level.isLoaded(villager.blockPosition())", "level.isPositionEntityTicking(villager.blockPosition())");
		assertConjuncts(returnExpression(named(berry, "liveAdult")), "presentAdult(level, data, villager)",
			"SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()), level.dimension(), data, settlement)");
		assertConjuncts(returnExpression(named(berry, "permitted")), "area.contains(pos)", "member != null",
			"data.memberIdentity(actor) == member", "data.isMember(settlement, actor)",
			"data.foragingAreaOf(settlement).orElse(null) == area", "data.canHarvestBerries(settlement, pos)", "canForage(data, settlement)");
		for (String check : List.of("valid", "validForScan")) assertConjuncts(returnExpression(named(berry, check)),
			"permitted(pos)", "attempt." + check + "(data, settlement, actor, adult)");
		MethodTree world = berry.stream().filter(method -> worldMethod(method) && method.getName().contentEquals("validTarget")).findFirst().orElseThrow();
		assertInitialPresenceGuard(world, "false");
		var call = invocations(world, "validTarget").getFirst();
		assertEquals(List.of("data", "settlement", "villager.getUUID()", "pos"), arguments(call).subList(0, 4));
		assertConjuncts(assertInstanceOf(LambdaExpressionTree.class, call.getArguments().get(4)).getBody(),
			"presentAdult(level, data, villager)", "claims.unique(data, settlement, member)");
		MethodTree pure = berry.stream().filter(method -> !worldMethod(method) && method.getName().contentEquals("validTarget")).findFirst().orElseThrow();
		assertConjuncts(returnExpression(pure), "permission != null", "permission.validForScan(pos, adult)",
			"loaded.getAsBoolean()", "ticking.getAsBoolean()", "isRipe(block.get())", "permission.validForScan(pos, adult)");
	}

	private static void assertInitialPresenceGuard(MethodTree method, String rejected) {
		IfTree guard = assertInstanceOf(IfTree.class, method.getBody().getStatements().getFirst());
		assertEquals("!presentAdult(level, data, villager)", unparenthesized(guard.getCondition()).toString());
		assertEquals(rejected, assertInstanceOf(ReturnTree.class, guard.getThenStatement()).getExpression().toString());
	}
	private static MethodTree named(List<MethodTree> methods, String name) {
		return methods.stream().filter(method -> method.getName().contentEquals(name)).findFirst().orElseThrow();
	}
	private static Tree returnExpression(MethodTree method) {
		return assertInstanceOf(ReturnTree.class, method.getBody().getStatements().getLast()).getExpression();
	}
	private static void assertConjuncts(Tree expression, String... expected) {
		List<String> actual = new ArrayList<>();
		collectConjuncts(expression, actual);
		assertEquals(List.of(expected), actual, "Every gate must short-circuit in order before world access");
	}
	private static void collectConjuncts(Tree tree, List<String> result) {
		tree = unparenthesized(tree);
		if (tree instanceof BinaryTree binary && binary.getKind() == Tree.Kind.CONDITIONAL_AND) {
			collectConjuncts(binary.getLeftOperand(), result);
			collectConjuncts(binary.getRightOperand(), result);
		} else result.add(tree.toString());
	}

	private static void assertLiveAllowed(Tree tree, String position) {
		UnaryTree allowed = assertInstanceOf(UnaryTree.class, tree);
		assertEquals(Tree.Kind.LOGICAL_COMPLEMENT, allowed.getKind());
		assertLiveBlocked(allowed.getExpression(), position);
	}

	private static void assertLiveBlocked(Tree tree, String position) {
		MethodInvocationTree call = assertInstanceOf(MethodInvocationTree.class, tree);
		MemberSelectTree selection = assertInstanceOf(MemberSelectTree.class, call.getMethodSelect());
		assertEquals("isResidentModificationBlocked", selection.getIdentifier().toString());
		assertEquals(List.of(position), arguments(call));
		MethodInvocationTree current = assertInstanceOf(MethodInvocationTree.class, selection.getExpression());
		assertEquals("SettlementSavedData.get", current.getMethodSelect().toString(), "Never use a captured or supplied snapshot for the dimension veto");
		assertEquals(List.of("level"), arguments(current));
	}

	private static boolean worldMethod(MethodTree method) {
		return !method.getParameters().isEmpty() && method.getParameters().getFirst().getType().toString().equals("ServerLevel");
	}
	private static boolean cropPermissionWrapper(MethodTree method) {
		return worldMethod(method)
			&& ((method.getName().contentEquals("validTarget") && method.getParameters().size() == 4)
				|| (method.getName().contentEquals("harvest") && method.getParameters().size() == 5));
	}
	private static MethodTree worldHarvest(String service) throws Exception {
		return methods(service).stream().filter(method -> worldMethod(method) && method.getName().contentEquals("harvest"))
			.filter(method -> !service.equals("SettlementCropHarvest")
				|| method.getParameters().stream().anyMatch(parameter -> parameter.getType().toString().equals("Permission")))
			.findFirst().orElseThrow();
	}
	private static Tree unparenthesized(Tree tree) {
		while (tree instanceof ParenthesizedTree parenthesized) tree = parenthesized.getExpression();
		return tree;
	}
	private static List<String> arguments(MethodInvocationTree call) { return call.getArguments().stream().map(Object::toString).toList(); }

	private static List<MethodTree> methods(String service) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var files = manager.getJavaFileObjects(Path.of("src/main/java/com/thysummer/coopcraft/civilization", service + ".java").toFile());
			var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, files);
			List<MethodTree> result = new ArrayList<>();
			for (var unit : task.parse()) new TreeScanner<Void, Void>() {
				@Override public Void visitMethod(MethodTree tree, Void unused) { result.add(tree); return super.visitMethod(tree, unused); }
			}.scan(unit, null);
			assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR), diagnostics.getDiagnostics().toString());
			return result;
		}
	}
	private static List<MethodInvocationTree> invocations(Tree root, String name) {
		List<MethodInvocationTree> result = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
				if (tree.getMethodSelect().toString().equals(name)) result.add(tree);
				return super.visitMethodInvocation(tree, unused);
			}
		}.scan(root, null);
		return result;
	}
	private static List<LambdaExpressionTree> lambdas(Tree root) {
		List<LambdaExpressionTree> result = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
				result.add(tree); return super.visitLambdaExpression(tree, unused);
			}
		}.scan(root, null);
		return result;
	}
}
