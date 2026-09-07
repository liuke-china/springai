package com.springai.springai.agent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 自然语言查询 Agent 接口。
 *
 * 请求示例：
 * POST /ai/agent/query
 * {"question":"查询加工中的设备","schema":"public","maxSteps":3}
 */
@RestController
@RequestMapping("/ai/agent")
public class NaturalLanguageQueryController {

    private final NaturalLanguageQueryAgent agent;

    public NaturalLanguageQueryController(NaturalLanguageQueryAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/query")
    public NaturalLanguageQueryResult query(@RequestBody Map<String, Object> request) {
        String question = request.get("question") == null
                ? null : String.valueOf(request.get("question"));
        String schema = request.get("schema") == null
                ? "public" : String.valueOf(request.get("schema"));
        int maxSteps = parseMaxSteps(request.get("maxSteps"));
        return agent.run(question, schema, maxSteps);
    }

    private int parseMaxSteps(Object value) {
        if (value == null) {
            return NaturalLanguageQueryAgent.defaultMaxSteps();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return NaturalLanguageQueryAgent.defaultMaxSteps();
        }
    }
}
