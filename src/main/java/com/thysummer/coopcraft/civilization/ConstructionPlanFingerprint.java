package com.thysummer.coopcraft.civilization;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Canonical compiled semantics, independent of JSON whitespace and property spelling order. */
final class ConstructionPlanFingerprint {
	private ConstructionPlanFingerprint() { }

	static String compute(ResourceLocation id, List<ConstructionInstruction> instructions) {
		try {
			var bytes = new ByteArrayOutputStream();
			try (var output = new DataOutputStream(bytes)) {
				output.writeUTF("coopcraft-construction-plan-v1");
				output.writeUTF(id.toString());
				output.writeInt(instructions.size());
				for (var instruction : instructions) {
					output.writeInt(instruction.offset().getX());
					output.writeInt(instruction.offset().getY());
					output.writeInt(instruction.offset().getZ());
					output.writeUTF(BuiltInRegistries.BLOCK.getKey(instruction.state().getBlock()).toString());
					var properties = instruction.state().getProperties().stream()
						.sorted(Comparator.comparing(Property::getName)).toList();
					output.writeInt(properties.size());
					for (var property : properties) {
						output.writeUTF(property.getName());
						output.writeUTF(value(instruction.state(), property));
					}
					output.writeUTF(BuiltInRegistries.ITEM.getKey(instruction.item()).toString());
					output.writeBoolean(instruction.consume());
				}
			}
			return "v1-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
		}
	}

	private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
