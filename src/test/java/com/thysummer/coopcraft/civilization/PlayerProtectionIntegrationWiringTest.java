package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.tools.ToolProvider;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual marker mutations plus AST wiring checks, not live interaction or AI tests. */
class PlayerProtectionIntegrationWiringTest {

	@Test void protectedAreaRejectsNewResourceMarksWithoutChangingExistingState() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		data.addProtectedArea(UUID.randomUUID(), "House", -2, -2, 2, 2);
		data.setDirty(false);
		CompoundTag before = data.save(new CompoundTag(), null);
		assertEquals(Civilization.ResourceMarkChange.BLOCKED,
			Civilization.changeResourceMark(data, camp, new BlockPos(0, 200, 0)));
		assertEquals(before, data.save(new CompoundTag(), null));
		assertFalse(data.isDirty());
		assertTrue(data.resourcesOf(camp).isEmpty());
	}

	@Test void existingPermissionCanBeWithdrawnWhileProtectedAndDoesNotReturnOnRemoval() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		UUID owner = UUID.randomUUID();
		assertEquals(Civilization.ResourceMarkChange.ADDED, Civilization.changeResourceMark(data, camp, BlockPos.ZERO));
		var area = data.addProtectedArea(owner, "House", -2, -2, 2, 2).area();
		assertNotNull(area);
		assertEquals(Civilization.ResourceMarkChange.REMOVED, Civilization.changeResourceMark(data, camp, BlockPos.ZERO));
		assertEquals(PlayerProtectedAreas.Status.REMOVED, data.removeProtectedArea(area.id(), owner, false));
		assertTrue(data.resourcesOf(camp).isEmpty());
		assertEquals(Civilization.ResourceMarkChange.ADDED, Civilization.changeResourceMark(data, camp, BlockPos.ZERO));
	}

	@Test void corruptProtectionRejectsNewMarksButStillAllowsWithdrawingOldOnes() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		data.toggleResource(camp, BlockPos.ZERO);
		CompoundTag saved = data.save(new CompoundTag(), null);
		saved.putString("PlayerProtectedAreas", "future-format");
		data = SettlementSavedData.load(saved, null);
		camp = data.find(BlockPos.ZERO).orElseThrow();
		assertEquals(Civilization.ResourceMarkChange.BLOCKED,
			Civilization.changeResourceMark(data, camp, BlockPos.ZERO.east()));
		assertEquals(Civilization.ResourceMarkChange.REMOVED, Civilization.changeResourceMark(data, camp, BlockPos.ZERO));
		assertEquals("future-format", data.save(new CompoundTag(), null).getString("PlayerProtectedAreas"));
	}

	@Test void markersOutsideProtectionRetainExistingToggleBehavior() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		data.addProtectedArea(UUID.randomUUID(), "House", -2, -2, 2, 2);
		BlockPos outside = new BlockPos(3, 64, 0);
		assertEquals(Civilization.ResourceMarkChange.ADDED, Civilization.changeResourceMark(data, camp, outside));
		assertEquals(Civilization.ResourceMarkChange.REMOVED, Civilization.changeResourceMark(data, camp, outside));
	}

	@Test void selectionInterceptPrecedesEveryExistingBlockHandlerAndCommandsRegisterOnce() throws Exception {
		List<MethodTree> methods = methods("Civilization.java");
		MethodTree use = methods.stream().filter(m -> m.getName().contentEquals("onUseBlock")).findFirst().orElseThrow();
		var first = assertInstanceOf(VariableTree.class, use.getBody().getStatements().getFirst());
		var call = assertInstanceOf(MethodInvocationTree.class, first.getInitializer());
		assertEquals("PlayerProtectionCommands.interceptUseBlock", call.getMethodSelect().toString());
		assertEquals(List.of("player", "level", "hand", "hit"), call.getArguments().stream().map(Object::toString).toList());
		assertTrue(use.getBody().getStatements().get(1).toString().contains("return selection"));
		MethodTree register = methods.stream().filter(m -> m.getName().contentEquals("register")).findFirst().orElseThrow();
		assertEquals(1, invocations(register, "PlayerProtectionCommands.register").size());
		for (String name : List.of("onUseCropPlot", "onUseResource")) {
			MethodTree mark = methods.stream().filter(m -> m.getName().contentEquals(name)).findFirst().orElseThrow();
			assertEquals(1, invocations(mark, "changeResourceMark").size());
			assertTrue(invocations(mark, "data.toggleResource").isEmpty());
		}
	}

	@Test void goalUsesProtectedFinderAndSupplyReturnPolicy() throws Exception {
		List<MethodTree> methods = methods("VillagerWorkGoal.java");
		MethodTree find = methods.stream().filter(m -> m.getName().contentEquals("findRipeBerryBush")).findFirst().orElseThrow();
		var findCalls = invocations(find, "SettlementForaging.findRipeBush");
		assertEquals(1, findCalls.size());
		assertEquals(List.of("level", "settlementData()", "origin"), findCalls.getFirst().getArguments().stream().map(Object::toString).toList());
		MethodTree returning = methods.stream().filter(m -> m.getName().contentEquals("returnSupplyJob")).findFirst().orElseThrow();
		assertEquals(1, invocations(returning, "ConstructionSystem.returnSupplyJob").size());
	}

	private static List<MethodTree> methods(String name) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler);
		try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			var files = manager.getJavaFileObjects(Path.of("src/main/java/com/thysummer/coopcraft/civilization", name).toFile());
			var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, files);
			List<MethodTree> result = new ArrayList<>();
			for (var unit : task.parse()) new TreeScanner<Void, Void>() {
				@Override public Void visitMethod(MethodTree tree, Void unused) {
					result.add(tree);
					return super.visitMethod(tree, unused);
				}
			}.scan(unit, null);
			return result;
		}
	}

	private static List<MethodInvocationTree> invocations(MethodTree method, String name) {
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
