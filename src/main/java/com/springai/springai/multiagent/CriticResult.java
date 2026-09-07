package com.springai.springai.multiagent;

/**
 * Critic（评审者）的判定结果：是否通过 + 不通过时的反馈（反馈会回灌给 Planner 重新规划）。
 */
public record CriticResult(boolean approved, String feedback) {
}
