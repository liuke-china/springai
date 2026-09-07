package com.springai.springai.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 提示注入检测器（L1/LLM01/LLM07）
 *
 * 作用：在"用户输入进入 LLM 之前"用正则 + 关键词 + 启发式打分，
 * 判断一条输入是不是提示注入攻击（prompt injection）。
 *
 * 为什么需要它：纯靠 system prompt（系统提示）写"禁止注入"是"软防线"，
 * 模型可能不听。代码层检测器是"硬防线"——命中恶意特征直接拦截/隔离，
 * 不依赖模型自觉。这正是企业"prompt（提示词）防君子，代码防小人"的思路。
 */
public class PromptInjectionDetector {

    /** 企业常见注入特征正则（中英文都覆盖） */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 指令覆盖
            Pattern.compile("忽略(上面|以上|之前|先前|所有).{0,12}(指令|提示|规则|要求|系统)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ignore (all|the|previous|above|prior).{0,15}(instruction|prompt|system|rule)", Pattern.CASE_INSENSITIVE),
            // 系统提示泄露
            Pattern.compile("(泄露|输出|打印|复述|重复|dump).{0,12}(系统提示|system\\s*prompt|你的指令|你的规则)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|print|repeat|dump|disclose).{0,15}(system prompt|your instructions|your rules)", Pattern.CASE_INSENSITIVE),
            // 角色覆盖 / 越狱（jailbreak）
            Pattern.compile("你现在(扮演|是|作为).{0,15}(开发者|管理员|root|god|无限制|dan|开发者模式)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(pretend|act as|you are now|roleplay|jailbreak|dan mode|developer mode)", Pattern.CASE_INSENSITIVE),
            // 危险动作诱导
            Pattern.compile("(执行|运行|调用|execute|run).{0,12}(命令|shell|cmd|curl|powershell|rm -)", Pattern.CASE_INSENSITIVE),
            // 数据外泄
            Pattern.compile("(把|将|发送|转发).{0,18}(发送|转发|post|上传|外发).{0,18}(http|webhook|外部|邮箱|外部服务器)", Pattern.CASE_INSENSITIVE),
            // 角色/身份抹除
            Pattern.compile("(忘记|无视|忽略).{0,12}(之前|以上|你的).{0,12}(身份|职责|设定|人设)", Pattern.CASE_INSENSITIVE)
    );

    /** 可疑关键词（命中累加分数，用于"可疑但不致命"的隔离） */
    private static final Set<String> SUSPICIOUS_KEYWORDS = Set.of(
            "prompt", "instruction", "system", "jailbreak", "dan", "developer mode",
            "越狱", "注入", "忽略指令", "系统提示", "泄露", "外发", "转发", "开发者模式"
    );

    public enum Verdict { SAFE, SUSPICIOUS,
        MALICIOUS }

    public static class Result {
        public final Verdict verdict;
        public final List<String> matchedReasons;
        public Result(Verdict verdict, List<String> matchedReasons) {
            this.verdict = verdict;
            this.matchedReasons = matchedReasons;
        }
    }

    /**
     * 检测输入。
     * 打分规则（可调）：每个正则命中 +2，每个可疑关键词 +1，长串 base64（编码绕过）+1。
     * >=3 判恶意，>=1 判可疑，否则安全。
     */
    public static Result detect(String input) {
        if (input == null || input.isBlank()) {
            return new Result(Verdict.SAFE, List.of());
        }
        List<String> reasons = new ArrayList<>();
        int score = 0;

        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(input).find()) {
                reasons.add("命中注入正则: " + p.pattern());
                score += 2;
            }
        }
        String lower = input.toLowerCase(Locale.ROOT);
        for (String kw : SUSPICIOUS_KEYWORDS) {
            if (lower.contains(kw)) {
                reasons.add("含可疑关键词: " + kw);
                score += 1;
            }
        }
        // 编码混淆启发式：超长 base64 可能是把攻击指令编码绕过
        if (lower.matches(".*[a-z0-9+/]{40,}={0,2}.*")) {
            reasons.add("含长串疑似 base64（可能编码绕过）");
            score += 1;
        }

        // 一次强正则命中（+2）即判恶意直接拦截；仅关键词命中（+1）记可疑留痕不拦。
        // 安全取向：宁可误拦攻击，不可放过注入。
        Verdict v = score >= 2 ? Verdict.MALICIOUS : (score >= 1 ? Verdict.SUSPICIOUS : Verdict.SAFE);
        return new Result(v, reasons);
    }

    /** 是否应直接拦截（恶意） */
    public static boolean isBlocked(String input) {
        return detect(input).verdict == Verdict.MALICIOUS;
    }
}
