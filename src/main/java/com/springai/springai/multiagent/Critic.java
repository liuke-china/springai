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
 * Critic（评审者）：只负责"查"——检查 Executor（执行者）给出的子任务回答，
 * 是否已经完整、正确地回答了原始问题。
 * 类比：QA 测试，不写代码，只判断"过没过"，不过就给出修改意见。
 */
@Service
public class Critic {

    private final ChatModel chatModel;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Critic(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 评审。返回 CriticResult（approved + feedback）。
     * feedback 会回灌给 Planner（规划者）触发"重新规划"，这就是多 Agent 的反思-重试闭环。
     * 解析失败时乐观处理为"通过"，避免编排器死循环。
     */
    public CriticResult review(String question, List<StepResult> results) {
        StringBuilder sb = new StringBuilder();
        for (StepResult r : results) {
            sb.append("子任务：").append(r.step())
                    .append(" -> 回答：").append(r.answer()).append("\n");
        }

        String system = "你是评审者（Critic）。判断下面的子任务回答是否已经完整、正确地回答了原始问题。";

        String user = "原始问题：" + question
                + "\n\n子任务回答：\n" + sb
                + "\n只返回 JSON，不要 markdown："
                + "{\"approved\":true 或 false,\"feedback\":\"如果不通过，说明缺什么或哪里错\"}";

        ChatResponse resp = chatModel.call(
                new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
        String text = MultiAgentUtils.stripFences(resp.getResult().getOutput().getText());

        try {
            return MAPPER.readValue(text, CriticResult.class);
        } catch (Exception e) {
            return new CriticResult(true, "评审解析失败，按通过处理");
        }
    }
}
