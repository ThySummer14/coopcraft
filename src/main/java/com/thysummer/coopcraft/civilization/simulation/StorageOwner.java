package com.thysummer.coopcraft.civilization.simulation;

import java.util.Objects;

public record StorageOwner(Scope scope, String id) {
	public StorageOwner {
		Objects.requireNonNull(scope, "scope");
		if (id.isBlank()) {
			throw new IllegalArgumentException("Storage owner ID cannot be blank");
		}
	}

	public static StorageOwner person(String id) {
		return new StorageOwner(Scope.PERSON, id);
	}

	public static StorageOwner household(String id) {
		return new StorageOwner(Scope.HOUSEHOLD, id);
	}

	public static StorageOwner camp(String id) {
		return new StorageOwner(Scope.CAMP, id);
	}

	public String location() {
		return scope.name().toLowerCase(java.util.Locale.ROOT) + ':' + id;
	}

	public enum Scope {
		PERSON,
		HOUSEHOLD,
		CAMP
	}
}
