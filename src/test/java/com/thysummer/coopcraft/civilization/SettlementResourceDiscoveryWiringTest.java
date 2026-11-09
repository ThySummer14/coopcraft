package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AST connection check only. This does not execute server ticks, entities or a live game. */
class SettlementResourceDiscoveryWiringTest {
	@Test void serverTickCallsTheSharedDiscoveryAdapterExactlyOncePerLevelLoop() throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
		try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			var files = manager.getJavaFileObjects(Path.of("src/main/java/com/thysummer/coopcraft/civilization/Civilization.java").toFile());
			var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, files);
			AtomicInteger calls = new AtomicInteger();
			for (var unit : task.parse()) new TreeScanner<Void, String>() {
				@Override public Void visitMethod(MethodTree tree, String scope) {
					return super.visitMethod(tree, tree.getName().contentEquals("tick") ? "tick" : "");
				}
				@Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, String scope) {
					return super.visitEnhancedForLoop(tree, scope + (tree.getExpression().toString().equals("server.getAllLevels()") ? "/levels" : "/other"));
				}
				@Override public Void visitMethodInvocation(MethodInvocationTree tree, String scope) {
					if (tree.getMethodSelect().toString().equals("SettlementResourceDiscovery.tick")) {
						assertEquals("tick/levels", scope);
						assertEquals(List.of("level", "data"), tree.getArguments().stream().map(Object::toString).toList()); calls.incrementAndGet();
					}
					return super.visitMethodInvocation(tree, scope);
				}
			}.scan(unit, "");
			assertEquals(1, calls.get());
		}
	}
}
