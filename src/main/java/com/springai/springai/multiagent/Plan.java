package com.springai.springai.multiagent;

import java.util.List;

/**
 * Planner（规划者）产出的计划：一串可独立执行的子任务。
 * 用 record（记录类）= 只读数据袋，和 AgentAction / ToolObservation 同一套路。
 */
public record Plan(List<String> steps) {
}
