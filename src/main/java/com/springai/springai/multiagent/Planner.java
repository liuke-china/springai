package com.springai.springai.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Planner（规划者）：只负责"想"——把用户问题拆成一串可独立执行的子任务。
 * 类比：产品经理写需求拆解，自己不动手。
 *
 * 注意 Planner 不执行任何工具，只和 LLM（大语言模型）对话，产出一份 JSON 计划。
 */
@Service
public class Planner {

    private final ChatModel chatModel;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Planner(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 生成计划。feedback 是上一轮 Critic（评审者）给的修改意见（首轮为空）。
     * 返回 Plan（一串子任务）；若 LLM（大语言模型）JSON 解析失败，降级为"单步=原问题"，保证可跑。
     */
    public Plan plan(String question, String feedback) {
        String fb = (feedback == null || feedback.isBlank()) ? "（无）" : feedback;

        String system = "你是任务规划者（Planner）。把用户问题拆成若干可独立执行的子任务，"
                + "每个子任务是一句清晰的自然语言指令（例如\"查询设备总数\"、\"查询当前时间\"）。"
                + "问题简单则只返回一步。";

        String user = "原始问题：" + question
                + "\n评审者上一轮反馈：" + fb
                + "\n只返回 JSON，不要 markdown：{\"steps\":[\"子任务1\",\"子任务2\"]}";

        ChatResponse resp = chatModel.call(
                new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
        String text = MultiAgentUtils.stripFences(resp.getResult().getOutput().getText());

        try {
            Plan plan = MAPPER.readValue(text, Plan.class);
            if (plan.steps() == null || plan.steps().isEmpty()) {
                return new Plan(List.of(question));
            }
            return plan;
        } catch (Exception e) {
            // 解析失败降级：把整个问题当成一个子任务，避免编排器直接挂掉
            return new Plan(List.of(question));
        }
    }
}
