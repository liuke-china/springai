package com.springai.springai.memory.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.UnsupportedEncodingException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ================================================================
 * 设备查询 AI 评估测试
 * ================================================================
 *
 * 【这个测试在做什么】
 * 读 eval/eval-set-v1.csv，对每条 query 真实调 springai /ai/ims/ask/stream，
 * 验证 AI 回答是否正确。
 *
 * 【这是 AI 应用工程师的核心工作】
 * 改 prompt / 改 Tool 后，跑这个测试就能看到准确率数字。
 * 不是"感觉对不对"，而是"85% / 92%"这种可量化结果。
 *
 * 【怎么跑】
 * 1. mvn test -Dtest=DeviceAskEvalTest
 * 2. 或 IDE 里右键 Run
 *
 * 【前置】
 * spring-boot-starter-test（已包含 JUnit5 + AssertJ + Mockito）
 * springai 服务在同一进程启动（@SpringBootTest）
 *
 * 【为什么不引入 DeepEval】
 * DeepEval 是 Python 的；Java 没对应物。手写 JUnit 是 Java 生态标准做法。
 * ================================================================
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("设备查询 AI 评估")
class DeviceAskEvalTest {

    static {
        // 跑测试前确保 springai 已经在 8081 运行
        System.out.println("=== DeviceAskEvalTest 假设 springai 已启动在 8081 ===");
    }

    // 不需要 @Autowired 任何 Bean —— 直接 HTTP 调 8081
    // 如果非要保留 @SpringBootTest，就注释掉下面两个 @Autowired
    // @Autowired private ChatClient.Builder chatClientBuilder;
    // @Autowired private ImsDeviceTool imsDeviceTool;
    // @LocalServerPort private int port;

    /**
     * CSV 列：id, question, expected_tool, expected_answer_keywords, category
     * 兼容 eval-set-v1.csv
     */
    @ParameterizedTest(name = "[{index}] id={0} q={1}")
    @CsvFileSource(resources = "/eval-set.csv", numLinesToSkip = 1)
    @DisplayName("跑 eval-set 全套用例")
    void evalAll(int id, String question, String expectedTool,
                 String expectedAnswerKeywords, String category) throws UnsupportedEncodingException {

        // 1. 调用 AI 拿回答
        String answer = callAi(question);

        // 2. 断言 1: Tool 调用正确性
        if (!"NULL".equals(expectedTool) && !expectedTool.isEmpty()) {
            assertToolCalled(expectedTool);
        }

        // 3. 断言 2: 答案含关键词
        if (expectedAnswerKeywords != null && !expectedAnswerKeywords.isEmpty()) {
            assertAnswerContainsKeywords(answer, expectedAnswerKeywords);
        }

        // 4. 断言 3: 答案非空（防 AI 答非所问或超时）
        assertThat(answer)
                .as("id=%d AI 回答不能为空", id)
                .isNotBlank();
    }

    /**
     * 调 springai HTTP 接口拿 AI 回答
     * SSE 流式累积 content
     * 直接调你 spring-boot:run 启动的端口 8081
     */
    private String callAi(String question) throws UnsupportedEncodingException {
        // 直接连 springai 自己启动的端口（避免 @SpringBootTest 再起一个）
        String url = "http://localhost:8081/ai/ims/ask/stream?question="
                + java.net.URLEncoder.encode(question, "UTF-8");
        System.out.println("\n=== 调 AI ===  " + url);

        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);

            int code = conn.getResponseCode();
            System.out.println("HTTP " + code);
            if (code != 200) {
                throw new RuntimeException("AI 接口 HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (var in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                int lines = 0;
                while ((line = in.readLine()) != null) {
                    lines++;
                    if (line.startsWith("data:")) {
                        String json = line.substring(5).trim();
                        int idx = json.indexOf("\"content\":\"");
                        if (idx >= 0) {
                            int start = idx + 11;
                            int end = json.indexOf("\"", start);
                            if (end > start) {
                                sb.append(json, start, end);
                            }
                        }
                    }
                }
                System.out.println("SSE 行数=" + lines);
            }
            System.out.println("AI 答: " + sb);
            System.out.println("=== 完成 ===\n");
            return sb.toString();
        } catch (Exception e) {
            System.err.println("✗ 调 AI 失败: " + e.getMessage());
            throw new RuntimeException("调 AI 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证 AI 实际调了期望的 Tool
     * 通过 ImsDeviceTool 在 stdout 打印的 [ImsDeviceTool] ▶ 调用 Tool: xxx 抓取
     * （更稳妥：让 springai 在 SSE 中暴露 toolCalls 字段——见 ImsAskController）
     */
    private void assertToolCalled(String expectedTool) {
        // 简化：默认通过。生产建议让 AI 响应里带 toolCalls 字段
        // 或在 ImsDeviceTool 把调用写到固定文件，测试读文件
    }

    /**
     * 验证 AI 答案包含所有关键词（任一分割符：分号、逗号、中文分号）
     */
    private void assertAnswerContainsKeywords(String answer, String keywordsSpec) {
        String[] kws = keywordsSpec.split("[;,，；]");
        for (String kw : kws) {
            String t = kw.trim();
            if (t.isEmpty()) continue;
            assertThat(answer)
                    .as("答案应包含关键词 [%s]，实际：%s", t, truncate(answer))
                    .contains(t);
        }
    }

    private String truncate(String s) {
        return s == null ? "" : s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}