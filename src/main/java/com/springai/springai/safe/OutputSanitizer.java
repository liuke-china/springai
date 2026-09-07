package com.springai.springai.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 输出脱敏器（L6/LLM02/LLM05）
 *
 * 作用：在"模型回答返回给用户之前"，用代码层正则扫描回答内容，
 * 把密钥/连接串/身份证/手机号等敏感信息替换成 [已脱敏]，并检测是否泄露了系统提示。
 *
 * 为什么是"硬防线"：纯靠 prompt（提示词）写"禁止泄露密钥"不可靠，
 * 模型可能还是会写漏。这里在响应边界做**强制正则替换**，不依赖模型听话。
 * 这是企业"输出处理不当（Improper Output Handling）"的标准防护。
 */
public class OutputSanitizer {

    public static class SanitizeResult {
        public String cleaned;
        public List<String> redactedItems = new ArrayList<>();
        public boolean leakedSystemPrompt = false;

        public SanitizeResult(String cleaned) {
            this.cleaned = cleaned;
        }
    }

    public static SanitizeResult sanitize(String modelOutput) {
        SanitizeResult r = new SanitizeResult(modelOutput == null ? "" : modelOutput);
        if (modelOutput == null || modelOutput.isBlank()) {
            return r;
        }
        String out = modelOutput;

        // 1. 脱敏密钥/敏感信息（硬编码正则，硬防线）
        for (var p : SensitiveDataPatterns.SECRET_PATTERNS) {
            Matcher m = p.matcher(out);
            while (m.find()) {
                String hit = m.group();
                if (hit.length() >= 4) {
                    r.redactedItems.add(hit.length() > 24 ? hit.substring(0, 24) + "…" : hit);
                }
            }
            out = p.matcher(out).replaceAll(SecurityConstants.OUTPUT_LEAK_REDACTED);
        }

        // 2. 系统提示泄露检测（把强声明片段从输出里摘掉，并标记）
        if (out.contains("最高优先级安全指令")
                || out.contains(INPUT_DELIM_HINT)
                || (out.toLowerCase().contains("system prompt") && out.contains("职责"))) {
            r.leakedSystemPrompt = true;
            out = out.replace(SecurityConstants.PRECEDENCE_DIRECTIVE, "");
        }

        r.cleaned = out;
        return r;
    }

    private static final String INPUT_DELIM_HINT = "<<<【用户输入";
}
