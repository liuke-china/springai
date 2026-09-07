package com.springai.springai.multiagent;

import com.springai.springai.agent.NaturalLanguageQueryAgent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 多 Agent 编排接口（作品集第三个项目：多智能体协作）。
 *
 * 请求示例：
 * POST /ai/multi/query
 * {"question":"帮我查一下加工中的设备有多少台，顺便告诉我现在几点了","schema":"public","maxSteps":3,"maxRounds":2}
 */
@RestController
@RequestMapping("/ai/multi")
public class MultiAgentController {

    private final Orchestrator orchestrator;

    public MultiAgentController(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/query")
    public MultiAgentResult query(@RequestBody Map<String, Object> request) {
        String question = request.get("question") == null
                ? null : String.valueOf(request.get("question"));
        String schema = request.get("schema") == null
                ? "public" : String.valueOf(request.get("schema"));
        int maxSteps = parse(request.get("maxSteps"), NaturalLanguageQueryAgent.defaultMaxSteps());
        int maxRounds = parse(request.get("maxRounds"), Orchestrator.defaultMaxRounds());
        return orchestrator.run(question, schema, maxSteps, maxRounds);
    }

    private int parse(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
