package com.thysummer.coopcraft.client.anim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * 洗牌袋：把候选动作打乱后依次取出，取完再重新洗牌。
 *
 * <p>为什么不直接用 {@link Random#nextInt}：纯随机会连续两次抽到同一个动作，
 * 玩家会觉得"怎么老是这个"。洗牌袋保证每一轮里每个动作都出现一次才可能重复，
 * 同时避免新一轮的第一个和上一轮的最后一个撞车。
 */
public final class ShuffleBag<T> {
	private final List<T> source;
	private final Random random;
	private final Deque<T> bag = new ArrayDeque<>();
	private T last;

	public ShuffleBag(List<T> source, long seed) {
		if (source.isEmpty()) {
			throw new IllegalArgumentException("shuffle bag needs at least one entry");
		}

		this.source = List.copyOf(source);
		this.random = new Random(seed);
	}

	public T next() {
		if (this.bag.isEmpty()) {
			this.refill();
		}

		T value = this.bag.poll();
		this.last = value;
		return value;
	}

	private void refill() {
		List<T> shuffled = new ArrayList<>(this.source);
		Collections.shuffle(shuffled, this.random);

		// 新一轮开头别和上一轮结尾重复。
		if (shuffled.size() > 1 && shuffled.getFirst().equals(this.last)) {
			Collections.swap(shuffled, 0, 1);
		}

		this.bag.addAll(shuffled);
	}
}
