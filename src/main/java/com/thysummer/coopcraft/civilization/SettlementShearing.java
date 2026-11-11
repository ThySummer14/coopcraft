package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.CoopCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Explicit sheep consent, live entity work and transfer of only this call's actual drops. */
final class SettlementShearing {
	private SettlementShearing() {}

	/** World access alone is replaceable in deterministic tests; the state and inventory code are real. */
	interface Probe {
		SettlementSavedData currentData();
		Object actorIdentity();
		boolean activeAdultUnique();
		ItemStack mainHand();
		default ItemStack offHand() { return ItemStack.EMPTY; }
		default ResourceLocation dimension() { return null; }
		long time();
		Target target(UUID sheep);
		double distanceSquared(Target target);
		boolean lineOfSight(Target target);
		SimpleContainer inventory();
	}
	record Target(UUID id, Object identity, Vec3 position, AABB box, boolean readyWhiteAdult, boolean activeInBounds) {}
	private record Choice(SettlementShearingPermits.Permit permit, Target target, double distance) {}

	static final class Session {
		final SettlementSavedData data;
		final Settlement camp;
		final UUID actor, batch = UUID.randomUUID();
		final Object actorIdentity, member, binding, sheepIdentity;
		final SettlementSimulationState simulation;
		final long modeVersion;
		final SettlementShearingPermits.Permit permit;
		final ItemStack tool, toolSnapshot;
		final BooleanSupplier demand;
		final Probe probe;
		final SimpleContainer inventory;
		SettlementShearingLeases.Lease lease;
		boolean consumed;

		private Session(SettlementSavedData data, Settlement camp, UUID actor,
			SettlementShearingPermits.Permit permit, Target target, Probe probe, BooleanSupplier demand) {
			this.data = data; this.camp = camp; this.actor = actor; this.permit = permit; this.probe = probe; this.demand = demand;
			actorIdentity = probe.actorIdentity(); member = data.memberIdentity(actor); sheepIdentity = target.identity();
			inventory = probe.inventory();
			binding = data.stockpileBindingToken(camp); simulation = data.simulationOf(camp).orElse(null);
			modeVersion = simulation == null ? 0 : simulation.foodRequestModeVersion();
			tool = probe.mainHand(); toolSnapshot = tool.copy();
		}
		Sheep sheep() { return (Sheep) sheepIdentity; }
		BlockPos position() { return sheep().blockPosition(); }
	}

	private static boolean actorAllowed(SettlementSavedData data, Settlement camp, UUID actor, Probe probe, boolean requireTool) {
		if (data == null || camp == null || actor == null || probe == null || probe.currentData() != data
			|| !data.canonicalSettlement(camp) || !data.isMember(camp, actor) || data.stockpileBindingToken(camp) == null
			|| data.shearingPermitsBlocked() || data.protectedAreas().blocked() || !probe.activeAdultUnique()
			|| (requireTool && !usableTool(probe.mainHand(), probe.dimension(), camp.id(), actor))) return false;
		var simulation = data.simulationOf(camp).orElse(null);
		if (simulation != null && simulation.survivalEnabled()) {
			String id = simulation.residentId(actor).orElse(null);
			if (id == null) return false;
			try {
				var person = simulation.world().person(id);
				if (!person.isAlive() || !person.campId().equals(simulation.campOwner().id())) return false;
			} catch (IllegalArgumentException missing) { return false; }
		}
		return SettlementSimulationService.hasGatherTime(data, camp, actor);
	}

	private static boolean usableTool(ItemStack tool, ResourceLocation dimension, UUID camp, UUID actor) {
		return tool != null && !tool.isEmpty() && tool.getCount() == 1 && tool.is(Items.SHEARS)
			&& !SettlementWorkbenchCargo.isMarked(tool) && !SettlementShearingCargo.isMarked(tool)
			&& !ConstructionSupply.isMarked(tool)
			&& (!SettlementShearsLoan.isMarked(tool) || SettlementShearsLoan.ownedBy(tool, dimension, camp, actor));
	}

	private static boolean targetAllowed(SettlementSavedData data, Settlement camp,
		SettlementShearingPermits.Permit permit, Target target, Probe probe) {
		if (permit == null || target == null || target.identity() == null || !permit.sheepId().equals(target.id())
			|| !permit.settlementId().equals(camp.id()) || data.shearingPermitOf(permit.sheepId()).orElse(null) != permit
			|| !target.readyWhiteAdult() || !target.activeInBounds() || !finite(target.position()) || !finite(target.box())) return false;
		double distance = probe.distanceSquared(target);
		double dx = target.position().x - camp.center().getX(), dz = target.position().z - camp.center().getZ();
		boolean allowed = Double.isFinite(distance) && distance >= 0 && distance <= 144 && dx * dx + dz * dz <= 4096
			&& !SettlementShearingCommands.protectedBox(data.protectedAreas(), target.box());
		return allowed;
	}

	/** Read-only eligibility: never installs synthetic shears and never claims a sheep lease. */
	static boolean hasBorrowCandidate(SettlementSavedData data, Settlement camp, UUID actor, Probe probe, BooleanSupplier demand) {
		if (demand == null || !demand.getAsBoolean() || !borrowerAllowed(data, camp, actor, probe)) return false;
		Object entity = probe.actorIdentity(), member = data.memberIdentity(actor), binding = data.stockpileBindingToken(camp);
		SimpleContainer bag = probe.inventory();
		for (var permit : data.shearingPermitsOf(camp).stream().limit(SettlementShearingPermits.MAX_PER_SETTLEMENT).toList()) {
			if (!data.shearingLeases().available(permit.sheepId(), actor, probe.time())) continue;
			Target target = probe.target(permit.sheepId());
			if (!targetAllowed(data, camp, permit, target, probe) || !borrowCapacity(probe.inventory(), camp, permit)) continue;
			if (!demand.getAsBoolean() || !borrowerAllowed(data, camp, actor, probe)) return false;
			Target current = probe.target(permit.sheepId());
			if (current != null && current.identity() == target.identity() && targetAllowed(data, camp, permit, current, probe)
				&& demand.getAsBoolean() && borrowerAllowed(data, camp, actor, probe)
				&& probe.actorIdentity() == entity && probe.inventory() == bag && data.memberIdentity(actor) == member
				&& data.stockpileBindingToken(camp) == binding
				&& data.shearingLeases().available(permit.sheepId(), actor, probe.time())) return true;
		}
		return false;
	}

	private static boolean borrowerAllowed(SettlementSavedData data, Settlement camp, UUID actor, Probe probe) {
		return actorAllowed(data, camp, actor, probe, false) && probe.mainHand() != null && probe.mainHand().isEmpty()
			&& !SettlementShearsLoan.isMarked(probe.offHand()) && probe.inventory() != null
			&& !probe.inventory().hasAnyMatching(SettlementShearsLoan::isMarked);
	}

	static boolean hasBorrowCandidate(ServerLevel level, SettlementSavedData data, Settlement camp,
		Villager villager, BooleanSupplier demand) {
		return level != null && villager != null && level.getServer().isSameThread()
			&& hasBorrowCandidate(data, camp, villager.getUUID(), probe(level, data, camp, villager), demand);
	}

	/** A travel ticket, not a durable work grant. A stopped or invalid ticket cannot revive. */
	static final class LoanTicket {
		static final long MAX_AGE = 600;
		final SettlementSavedData data;
		final Settlement camp;
		final UUID actor;
		final Probe probe;
		final SettlementShearsLoan.Hand hand;
		final ResourceLocation dimension;
		final BlockPos store;
		final Object entity, member, binding;
		final SimpleContainer inventory;
		final SettlementSimulationState simulation;
		final long modeVersion, created;
		final boolean returning;
		final ItemStack returningTool;
		final BooleanSupplier storeCurrent, demand;
		boolean cancelled;

		private LoanTicket(SettlementSavedData data, Settlement camp, UUID actor, Probe probe,
			SettlementShearsLoan.Hand hand, boolean returning, BooleanSupplier storeCurrent, BooleanSupplier demand) {
			this.data = data; this.camp = camp; this.actor = actor; this.probe = probe; this.hand = hand;
			this.returning = returning; this.storeCurrent = storeCurrent; this.demand = demand;
			dimension = probe.dimension(); store = data.getStockpile(camp).orElse(null);
			entity = probe.actorIdentity(); member = data.memberIdentity(actor); binding = data.stockpileBindingToken(camp);
			inventory = probe.inventory(); simulation = data.simulationOf(camp).orElse(null);
			modeVersion = simulation == null ? 0 : simulation.foodRequestModeVersion(); created = probe.time();
			returningTool = returning ? hand.get() : null;
		}
	}

	static LoanTicket prepareLoan(SettlementSavedData data, Settlement camp, UUID actor, Probe probe,
		SettlementShearsLoan.Hand hand, boolean returning, BooleanSupplier storeCurrent, BooleanSupplier demand) {
		if (data == null || camp == null || actor == null || probe == null || hand == null || storeCurrent == null) return null;
		LoanTicket ticket = new LoanTicket(data, camp, actor, probe, hand, returning, storeCurrent, demand);
		return validLoan(ticket) ? ticket : null;
	}

	static LoanTicket prepareLoan(ServerLevel level, SettlementSavedData data, Settlement camp, Villager villager,
		SettlementShearsLoan.Hand hand, boolean returning, BooleanSupplier storeCurrent, BooleanSupplier demand) {
		if (level == null || villager == null || !level.getServer().isSameThread()) return null;
		return prepareLoan(data, camp, villager.getUUID(), probe(level, data, camp, villager), hand, returning, storeCurrent, demand);
	}

	private static boolean loanIdentityCurrent(LoanTicket t) {
		long now = t.probe.time(), age = now - t.created;
		return !t.cancelled && now >= t.created && age >= 0 && age < LoanTicket.MAX_AGE && t.dimension != null
			&& t.dimension.equals(t.probe.dimension()) && t.probe.currentData() == t.data
			&& t.data.canonicalSettlement(t.camp) && t.data.isMember(t.camp, t.actor)
			&& t.member != null && t.data.memberIdentity(t.actor) == t.member
			&& t.entity != null && t.probe.actorIdentity() == t.entity && t.probe.activeAdultUnique()
			&& t.inventory != null && t.probe.inventory() == t.inventory
			&& t.binding != null && t.data.stockpileBindingToken(t.camp) == t.binding
			&& t.store != null && t.data.getStockpile(t.camp).filter(t.store::equals).isPresent()
			&& t.data.simulationOf(t.camp).orElse(null) == t.simulation
			&& (t.simulation == null || t.simulation.foodRequestModeVersion() == t.modeVersion)
			&& t.storeCurrent.getAsBoolean();
	}

	static boolean validLoan(LoanTicket t) {
		if (t == null || t.cancelled) return false;
		boolean valid = false;
		try {
			// Return does not depend on wool demand, a sheep permit, gathering time or the original grant.
			valid = loanIdentityCurrent(t) && (t.returning
				? t.hand.get() == t.returningTool && SettlementShearsLoan.ownedBy(t.hand.get(), t.dimension, t.camp.id(), t.actor)
				: t.hand.get() == t.probe.mainHand() && hasBorrowCandidate(t.data, t.camp, t.actor, t.probe, t.demand))
				&& loanIdentityCurrent(t);
			return valid;
		} finally { if (!valid) cancelLoan(t); }
	}

	/** Core transfer temporarily detaches the source. The same captured loan remains the only allowed item. */
	static boolean returnLoanAuthority(LoanTicket t) {
		if (t == null || !t.returning || t.cancelled) return false;
		boolean valid = false;
		try {
			valid = loanIdentityCurrent(t) && (t.hand.get() == t.returningTool || t.hand.get().isEmpty())
				&& SettlementShearsLoan.ownedBy(t.returningTool, t.dimension, t.camp.id(), t.actor)
				&& loanIdentityCurrent(t);
			return valid;
		} finally { if (!valid) cancelLoan(t); }
	}

	static void cancelLoan(LoanTicket ticket) { if (ticket != null) ticket.cancelled = true; }

	static Session prepare(SettlementSavedData data, Settlement camp, UUID actor, Probe probe, BooleanSupplier demand) {
		if (demand == null || !demand.getAsBoolean() || !actorAllowed(data, camp, actor, probe, true)) return null;
		List<Choice> choices = new ArrayList<>();
		// At most sixteen explicit UUID lookups. No broad animal query and no chunk load.
		for (var permit : data.shearingPermitsOf(camp).stream().limit(SettlementShearingPermits.MAX_PER_SETTLEMENT).toList()) {
			if (!data.shearingLeases().available(permit.sheepId(), actor, probe.time())) continue;
			Target target = probe.target(permit.sheepId());
			if (targetAllowed(data, camp, permit, target, probe)) choices.add(new Choice(permit, target, probe.distanceSquared(target)));
		}
		choices.sort(Comparator.comparingDouble(Choice::distance));
		for (Choice choice : choices) {
			Session session = new Session(data, camp, actor, choice.permit(), choice.target(), probe, demand);
			session.lease = data.shearingLeases().claim(data, camp, actor, session.actorIdentity,
				session.permit.sheepId(), session.sheepIdentity, probe.time());
			if (valid(session)) return session;
		}
		return null;
	}

	private static boolean identityAllowed(Session s) {
		boolean actor = actorAllowed(s.data, s.camp, s.actor, s.probe, true);
		boolean identity = s.probe.actorIdentity() == s.actorIdentity && s.probe.inventory() == s.inventory
			&& s.member != null && s.data.memberIdentity(s.actor) == s.member;
		boolean binding = s.binding != null && s.data.stockpileBindingToken(s.camp) == s.binding;
		boolean simulation = s.data.simulationOf(s.camp).orElse(null) == s.simulation
			&& (s.simulation == null || s.simulation.foodRequestModeVersion() == s.modeVersion);
		boolean permit = s.data.shearingPermitOf(s.permit.sheepId()).orElse(null) == s.permit;
		boolean tool = s.probe.mainHand() == s.tool && ItemStack.matches(s.tool, s.toolSnapshot);
		boolean lease = s.data.shearingLeases().valid(s.lease, s.data, s.camp, s.actor, s.actorIdentity,
			s.permit.sheepId(), s.sheepIdentity, s.probe.time());
		return !s.consumed && actor && identity && binding && simulation && permit && tool && lease;
	}

	static boolean valid(Session s) {
		if (s == null || s.consumed) return false;
		boolean valid = false;
		try {
			if (s.demand.getAsBoolean() && identityAllowed(s)) {
				Target target = s.probe.target(s.permit.sheepId());
				valid = target != null && target.identity() == s.sheepIdentity
					&& targetAllowed(s.data, s.camp, s.permit, target, s.probe)
					&& s.demand.getAsBoolean() && identityAllowed(s);
				if (valid) {
					Target current = s.probe.target(s.permit.sheepId());
					valid = current != null && current.identity() == s.sheepIdentity
						&& targetAllowed(s.data, s.camp, s.permit, current, s.probe) && identityAllowed(s);
				}
			}
			return valid;
		} finally { if (!valid) cancel(s); }
	}

	static void cancel(Session session) {
		if (session == null) return;
		session.consumed = true;
		session.data.shearingLeases().release(session.lease);
	}

	/** The wrapper may only expose actual entities created by that shear invocation. */
	interface Drop {
		Object identity();
		boolean current();
		ItemStack stack();
		void removeAccepted(int count);
	}
	interface Action {
		List<Drop> shear();
		boolean sheared();
		void damageTool();
		void eventAndAnimation();
	}

	private static ItemStack marked(Session s, ItemStack actual) {
		try { return SettlementShearingCargo.mark(actual, s.camp.id(), s.batch, s.permit.sheepId(), s.permit.id()); }
		catch (IllegalArgumentException unsupportedDrop) { return ItemStack.EMPTY; }
	}

	/** Only a capacity probe. This synthetic stack is never inserted into the real bag or world. */
	private static boolean capacity(Session s) {
		return capacity(s.inventory, marked(s, new ItemStack(Items.WHITE_WOOL, 3)));
	}

	/** A fresh batch cannot merge with unrelated personal wool or a previous worker's cargo. */
	private static boolean borrowCapacity(SimpleContainer inventory, Settlement camp, SettlementShearingPermits.Permit permit) {
		return inventory != null && capacity(inventory, SettlementShearingCargo.mark(new ItemStack(Items.WHITE_WOOL, 3),
			camp.id(), UUID.randomUUID(), permit.sheepId(), permit.id()));
	}

	private static boolean capacity(SimpleContainer inventory, ItemStack maximum) {
		SimpleContainer planned = new SimpleContainer(inventory.getContainerSize());
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) planned.setItem(slot, inventory.getItem(slot).copy());
		return !maximum.isEmpty() && planned.addItem(maximum).isEmpty();
	}

	/** Server-thread synchronous transaction. No rollback of sheared state, replay or fabricated compensation.
	 * Standard SimpleContainer is supported, including a throwing change listener. Arbitrary reentrant
	 * third-party world/inventory mutation and process crashes are not an atomic transaction. */
	static int finish(Session s, Action action) {
		if (!valid(s)) return 0;
		try {
			if (!capacity(s) || !valid(s) || !reachable(s)) return 0;
			// Consume before any shear, durability, event or insertion callback. Hold the lease until finally.
			s.consumed = true;
			List<Drop> drops;
			try { drops = action.shear(); }
			finally {
				// An exception after the actual shear is not a tool refund or permission to shear again.
				if (action.sheared()) {
					try { action.damageTool(); }
					finally { action.eventAndAnimation(); }
				}
			}
			return action.sheared() && receiptAllowed(s) ? collect(s, drops) : 0;
		} catch (RuntimeException failure) {
			CoopCraft.LOGGER.warn("Settlement shearing stopped; existing drops are not recreated or replayed", failure);
			return 0;
		} finally { cancel(s); }
	}

	private static boolean receiptAllowed(Session s) {
		return s.probe.currentData() == s.data && s.probe.actorIdentity() == s.actorIdentity
			&& s.probe.inventory() == s.inventory && s.probe.activeAdultUnique()
			&& s.data.canonicalSettlement(s.camp) && s.data.memberIdentity(s.actor) == s.member
			&& s.data.stockpileBindingToken(s.camp) == s.binding
			&& s.data.simulationOf(s.camp).orElse(null) == s.simulation
			&& (s.simulation == null || s.simulation.foodRequestModeVersion() == s.modeVersion);
	}

	private static boolean reachable(Session s) {
		Target target = s.probe.target(s.permit.sheepId());
		if (target == null || target.identity() != s.sheepIdentity || s.probe.distanceSquared(target) > 9
			|| !s.probe.lineOfSight(target) || !identityAllowed(s)) return false;
		Target current = s.probe.target(s.permit.sheepId());
		return current != null && current.identity() == s.sheepIdentity && s.probe.distanceSquared(current) <= 9
			&& targetAllowed(s.data, s.camp, s.permit, current, s.probe) && identityAllowed(s);
	}

	private static int matchingCount(SimpleContainer inventory, ItemStack marked) {
		int count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack item = inventory.getItem(slot);
			if (ItemStack.isSameItemSameComponents(item, marked)) count += item.getCount();
		}
		return count;
	}

	/** Validate the complete bounded output first. Unexpected counts/types/components stay in the world. */
	static int collect(Session s, List<Drop> drops) {
		if (drops == null || drops.isEmpty() || drops.size() > 3) return 0;
		Set<Object> identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		List<ItemStack> original = new ArrayList<>(), cargo = new ArrayList<>();
		int total = 0;
		for (Drop drop : drops) {
			if (drop == null || drop.identity() == null || !identities.add(drop.identity()) || !drop.current()) return 0;
			ItemStack stack = drop.stack();
			if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > 3) return 0;
			ItemStack copy = stack.copy(), marked = marked(s, copy);
			if (marked.isEmpty() || (total += stack.getCount()) > 3) return 0;
			original.add(copy); cargo.add(marked);
		}
		SimpleContainer inventory = s.inventory;
		int received = 0;
		for (int i = 0; i < drops.size(); i++) {
			Drop drop = drops.get(i);
			ItemStack marked = cargo.get(i);
			// Spawn may have been refused, or the real entity removed/replaced since capture.
			if (!receiptAllowed(s)) break;
			if (!drop.current() || !ItemStack.matches(drop.stack(), original.get(i))) continue;
			int before = matchingCount(inventory, marked), accepted = 0;
			RuntimeException failed = null;
			try { inventory.addItem(marked.copy()); }
			catch (RuntimeException failure) { failed = failure; }
			finally {
				// setChanged listeners can throw after a real insertion. Settle that insertion once.
				accepted = Math.max(0, Math.min(original.get(i).getCount(), matchingCount(inventory, marked) - before));
				if (accepted > 0 && drop.current() && ItemStack.matches(drop.stack(), original.get(i))) drop.removeAccepted(accepted);
			}
			received += accepted;
			if (failed != null) {
				CoopCraft.LOGGER.warn("Settlement shear cargo insertion listener failed after accepting {} items", accepted, failed);
				break;
			}
		}
		return received;
	}

	static Session prepare(ServerLevel level, SettlementSavedData data, Settlement camp, Villager villager, BooleanSupplier demand) {
		if (level == null || data == null || camp == null || villager == null || !level.getServer().isSameThread()) return null;
		return prepare(data, camp, villager.getUUID(), probe(level, data, camp, villager), demand);
	}

	static int finish(Session session) {
		if (session == null || !(session.actorIdentity instanceof Villager villager)
			|| !(session.sheepIdentity instanceof Sheep sheep) || !(villager.level() instanceof ServerLevel level)
			|| !level.getServer().isSameThread()) { cancel(session); return 0; }
		int received = finish(session, new Action() {
			public List<Drop> shear() {
				try (var capture = SettlementShearingDrops.begin(sheep)) {
					if (capture == null) return List.of();
					sheep.shear(SoundSource.NEUTRAL);
					return capture.drops().stream().map(entity -> actualDrop(level, entity)).toList();
				}
			}
			public boolean sheared() { return sheep.isSheared(); }
			public void damageTool() {
				if (villager.getMainHandItem() != session.tool || !ItemStack.matches(session.tool, session.toolSnapshot))
					throw new IllegalStateException("Shear callback changed the captured main-hand tool; replacement left untouched");
				SettlementShearsEquipment.allowClear(villager, session.tool,
					() -> session.tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND));
			}
			public void eventAndAnimation() {
				sheep.gameEvent(GameEvent.SHEAR, villager);
				villager.swing(InteractionHand.MAIN_HAND);
			}
		});
		if (received > 0 && receiptAllowed(session)) {
			try {
				SettlementSimulationService.recordCarriedProduction(session.data, session.camp, session.actor,
					session.inventory, Items.WHITE_WOOL, received, "shear");
			} catch (RuntimeException failure) {
				CoopCraft.LOGGER.warn("Shear cargo is already received; practice recording failed", failure);
			}
		}
		return received;
	}

	private static Drop actualDrop(ServerLevel level, ItemEntity entity) {
		ItemStack capturedStack = entity.getItem();
		return new Drop() {
			public Object identity() { return entity; }
			public boolean current() { return entity.level() == level && !entity.isRemoved() && level.getEntity(entity.getUUID()) == entity && entity.getItem() == capturedStack; }
			public ItemStack stack() { return entity.getItem(); }
			public void removeAccepted(int count) {
				if (!current() || count < 1 || count > capturedStack.getCount()) return;
				ItemStack remaining = capturedStack.copy();
				remaining.shrink(count);
				if (remaining.isEmpty()) entity.discard();
				else entity.setItem(remaining);
			}
		};
	}

	private static Probe probe(ServerLevel level, SettlementSavedData data, Settlement camp, Villager villager) {
		return new Probe() {
			public SettlementSavedData currentData() { return SettlementSavedData.get(level); }
			public Object actorIdentity() { return villager; }
			public boolean activeAdultUnique() {
				if (!level.getServer().isSameThread() || villager.level() != level || !villager.isAlive() || villager.isRemoved()
					|| villager.isBaby() || villager.isNoAi() || villager.isSleeping() || villager.isTrading() || villager.isPassenger()
					|| level.getEntity(villager.getUUID()) != villager
					|| !available(level, villager.blockPosition(), villager.getBoundingBox())) return false;
				var claims = SettlementObservationClaims.capture(level, data);
				if (!claims.unique(data, camp, data.rawMember(villager.getUUID()).orElse(null))) return false;
				int dimensions = 0;
				for (ServerLevel other : level.getServer().getAllLevels()) {
					if (other == null || ++dimensions > SettlementObservationClaims.MAX_DIMENSIONS
						|| other != level && other.getEntity(villager.getUUID()) != null) return false;
				}
				return dimensions > 0;
			}
			public ItemStack mainHand() { return villager.getMainHandItem(); }
			public ItemStack offHand() { return villager.getOffhandItem(); }
			public ResourceLocation dimension() { return level.dimension().location(); }
			public long time() { return level.getGameTime(); }
			public Target target(UUID id) {
				if (!(level.getEntity(id) instanceof Sheep sheep)) return null;
				return new Target(id, sheep, sheep.position(), sheep.getBoundingBox(),
					sheep.level() == level && !sheep.isRemoved() && sheep.readyForShearing() && sheep.getColor() == DyeColor.WHITE,
					available(level, sheep.blockPosition(), sheep.getBoundingBox()));
			}
			public double distanceSquared(Target target) { return villager.distanceToSqr(target.position()); }
			public boolean lineOfSight(Target target) {
				return target.identity() instanceof Sheep sheep && loadedBox(level, villager.getBoundingBox().minmax(target.box()))
					&& villager.hasLineOfSight(sheep);
			}
			public SimpleContainer inventory() { return villager.getInventory(); }
		};
	}

	private static boolean available(ServerLevel level, BlockPos pos, AABB box) {
		var border = level.getWorldBorder();
		return finite(box) && !level.isOutsideBuildHeight(pos) && level.isLoaded(pos) && level.isPositionEntityTicking(pos)
			&& box.minX >= border.getMinX() && box.maxX <= border.getMaxX()
			&& box.minZ >= border.getMinZ() && box.maxZ <= border.getMaxZ() && loadedBox(level, box);
	}
	private static boolean loadedBox(ServerLevel level, AABB box) {
		if (!finite(box) || box.getXsize() > 32 || box.getZsize() > 32) return false;
		int minX = ((int) Math.floor(box.minX)) >> 4, maxX = ((int) Math.floor(box.maxX)) >> 4;
		int minZ = ((int) Math.floor(box.minZ)) >> 4, maxZ = ((int) Math.floor(box.maxZ)) >> 4;
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
			if (!level.hasChunk(x, z)) return false;
		}
		return true;
	}
	private static boolean finite(Vec3 point) { return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
	private static boolean finite(AABB box) {
		return box != null && Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
			&& Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
	}
}
