package com.thysummer.coopcraft.civilization;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.animal.Sheep;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Checks this project's mapped dependency bytecode, not Mixin transformation or an in-game run. */
class SettlementShearingMixinWiringTest {
	@Test void actualMinecraftShearBytecodeContainsExactlyTheTargetedInvocation() throws Exception {
		AtomicInteger methods = new AtomicInteger(), calls = new AtomicInteger();
		try (InputStream input = Sheep.class.getResourceAsStream("Sheep.class")) {
			assertNotNull(input, "Mapped Minecraft Sheep class must be on the test classpath");
			new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					if (!name.equals("shear") || !descriptor.equals("(Lnet/minecraft/sounds/SoundSource;)V")) return null;
					methods.incrementAndGet();
					return new MethodVisitor(Opcodes.ASM9) {
						@Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
							if (owner.equals("net/minecraft/world/entity/animal/Sheep") && name.equals("spawnAtLocation")
								&& descriptor.equals("(Lnet/minecraft/world/level/ItemLike;I)Lnet/minecraft/world/entity/item/ItemEntity;")) calls.incrementAndGet();
						}
					};
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}
		assertEquals(1, methods.get()); assertEquals(1, calls.get());
	}
	@Test void requiredMixinTargetsOnlyTheShearExpressionAndFinallyClearsDepth() throws Exception {
		String config = Files.readString(Path.of("src/main/resources/coopcraft.mixins.json"));
		String mixin = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/mixin/SheepShearingDropsMixin.java"));
		assertTrue(config.contains("\"SheepShearingDropsMixin\""));
		assertTrue(mixin.contains("@ModifyExpressionValue")); assertTrue(mixin.contains("@WrapMethod"));
		assertTrue(mixin.contains("require = 1, allow = 1"));
		assertTrue(mixin.contains("Lnet/minecraft/world/entity/animal/Sheep;spawnAtLocation(Lnet/minecraft/world/level/ItemLike;I)Lnet/minecraft/world/entity/item/ItemEntity;"));
		assertTrue(mixin.contains("finally")); assertTrue(mixin.contains("SettlementShearingDrops.exit(sheep, completed)"));
		assertFalse(mixin.contains("@Mixin(Entity.class)"));
	}
}
