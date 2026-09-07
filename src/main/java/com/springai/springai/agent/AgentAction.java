package com.springai.springai.agent;

import java.util.Map;

/**
 * 模型每一步的决策结果（结构化输出）。
 *
 * tool（工具）字段决定这一步 Agent（智能体）要做什么：
 *   FIND_SCHEMA   -> 根据关键词找相关表结构
 *   RUN_SQL      -> 执行只读 SQL（代码级安全校验后才真正执行）
 *   DEVICE_STATUS-> 查 IMS 设备状态统计（调外部 API，非数据库）
 *   ANSWER       -> 给出最终答案，结束循环
 *
 * 这是"模型自己选工具"的关键：之前流程是写死的，现在每步由模型决定调哪个。
 */
public record AgentAction(
        String thought,
        String tool,
        Map<String, Object> args,
        String finalAnswer) {
}
