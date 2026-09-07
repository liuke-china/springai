package com.springai.springai.agent;

/**
 * 工具执行后的观察结果（Observation）。
 *
 * 每次工具调用完成后，把结果包成这个对象喂回给模型，
 * 作为下一步决策的"已掌握信息"。这是 ReAct（推理-行动）循环的核心数据载体。
 */
public record ToolObservation(String tool, String result) {
}
