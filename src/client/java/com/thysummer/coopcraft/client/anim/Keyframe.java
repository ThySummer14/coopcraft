package com.thysummer.coopcraft.client.anim;

/**
 * 一个关键帧：动画总时长的百分比（0.0 ~ 1.0）+ 该时刻应达到的变换。
 */
public record Keyframe(float time, Transform transform) {
}
