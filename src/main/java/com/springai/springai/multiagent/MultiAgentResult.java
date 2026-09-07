package com.springai.springai.multiagent;

import java.util.List;

/**
 * 多 Agent 编排的最终返回：综合答案 + 规划轨迹 + 各子任务结果 + 评审日志 + 会话ID。
 */
public record MultiAgentResult(String answer,
                               List<String> planSteps,
                               List<StepResult> stepResults,
                               List<String> criticLog,
                               String conversationId) {
}
