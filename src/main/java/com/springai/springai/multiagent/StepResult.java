package com.springai.springai.multiagent;

/**
 * Executor（执行者）跑完一个子任务后得到的结果：子任务描述 + 它的回答。
 */
public record StepResult(String step, String answer) {
}
