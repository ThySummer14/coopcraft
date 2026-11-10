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
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AST adapter checks supplement the real pure-service tests; no live world or AI is executed. */
class SettlementProtectedHarvestWiringTest {
	private static final List<String> SERVICES = List.of("SettlementGathering", "SettlementForaging", "SettlementCropHarvest");

	@Test void worldAdaptersPreserveCallerDataButVetoWithCurrentDimensionBeforeWorldReads() throws Exception {
		int checked = 0;
		for (String service : SERVICES) for (MethodTree method : methods(service)) {
			if (!worldMethod(method) || method.getParameters().stream().noneMatch(p -> p.getName().contentEquals("data"))) continue;
			String name = method.getName().toString();
			if (!List.of("validTarget", "harvest", "findRipeBush", "findRipe").contains(name)) continue;
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
		assertEquals(8, checked, "Three continuation/harvest pairs and both food finders must be checked");
	}

	@Test void gatheringAndBerryWorldMutationsRecheckLiveDimensionInsideTheirCallbacks() throws Exception {
		for (String service : List.of("SettlementGathering", "SettlementForaging")) {
			MethodTree method = worldHarvest(service);
			String mutation = service.equals("SettlementGathering") ? "level.destroyBlock" : "level.setBlock";
			String position = service.equals("SettlementGathering") ? "pos" : "position";
			assertEquals(1, invocations(method, mutation).size());
			var writes = lambdas(method).stream().filter(lambda -> !invocations(lambda, mutation).isEmpty()).toList();
			assertEquals(1, writes.size());
			BinaryTree body = assertInstanceOf(BinaryTree.class, writes.getFirst().getBody());
			assertEquals(Tree.Kind.CONDITIONAL_AND, body.getKind());
			assertLiveAllowed(body.getLeftOperand(), position);
			MethodInvocationTree write = assertInstanceOf(MethodInvocationTree.class, body.getRightOperand());
			assertEquals(mutation, write.getMethodSelect().toString());
			assertEquals(position, write.getArguments().getFirst().toString());
		}
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

	@Test void legacyPublicBerryFinderResolvesCurrentDimensionAndDelegatesToProtectedFinder() throws Exception {
		MethodTree legacy = methods("SettlementForaging").stream().filter(method -> worldMethod(method)
			&& method.getName().contentEquals("findRipeBush") && method.getParameters().size() == 2).findFirst().orElseThrow();
		assertEquals(1, legacy.getBody().getStatements().size());
		ReturnTree returned = assertInstanceOf(ReturnTree.class, legacy.getBody().getStatements().getFirst());
		MethodInvocationTree call = assertInstanceOf(MethodInvocationTree.class, returned.getExpression());
		assertEquals("findRipeBush", call.getMethodSelect().toString());
		assertEquals(List.of("level", "SettlementSavedData.get(level)", "origin"), arguments(call));
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
	private static MethodTree worldHarvest(String service) throws Exception {
		return methods(service).stream().filter(method -> worldMethod(method) && method.getName().contentEquals("harvest"))
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
