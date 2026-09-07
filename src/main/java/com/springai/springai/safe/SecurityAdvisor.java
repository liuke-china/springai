package com.springai.springai.safe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 企业级安全 Advisor（统一挂在 ChatClient 调用链上）
 *
 * 这是把前面所有"护栏"串起来的地方，对应 Spring AI 的 Advisor 链（1.1.4 API：
 * implements BaseAdvisor，before/after 里用 request.mutate().prompt(...) 改请求）。
 *
 * before()（LLM 调用前）：
 *   1. 取 UserMessage（用户输入）
 *   2. 用 PromptInjectionDetector 检测注入
 *      - 恶意 → 直接换成拒绝话术（模型收到的不再是攻击原文）
 *      - 可疑 → 包裹分隔符 + 留痕
 *   3. L1 输入隔离：把用户输入包进分隔符
 *   4. 往 system prompt 注入"优先级强声明"（覆盖 LLM01/LLM07）
 *
 * after()（LLM 调用后）：
 *   5. 输出脱敏检测 + 系统提示泄露检测（仅留痕告警；真正的文本替换在响应边界由 Controller 调 OutputSanitizer 做，
 *      避免在此处脆弱地重建 ChatClientResponse，保证流式/非流式都稳）
 *
 * order() 用极小负值 → 尽量在所有业务 Advisor（记忆等）之前执行，先净化输入。
 */
public class SecurityAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SecurityAdvisor.class);
    private final boolean blockMalicious;

    public SecurityAdvisor() {
        this(true);
    }

    public SecurityAdvisor(boolean blockMalicious) {
        this.blockMalicious = blockMalicious;
    }

    @Override
    public String getName() {
        return "EnterpriseSecurityAdvisor";
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // —— P1-⑥ 修复：扫描全部 messages（UserMessage + AssistantMessage），不只是当前 UserMessage。
        //    多轮对话场景下，恶意用户可能在上一轮 Assistant 历史回复中植入"忽略以上指令"，
        //    本轮用户输入本身没有恶意词，但把这段历史一起送给 LLM 就会被绕开。
        //    策略：任一 message 命中 MALICIOUS → 整轮拒掉；命中 SUSPICIOUS → 留痕告警。
        PromptInjectionDetector.Result inj = scanAllMessages(request.prompt());
        if (inj.verdict == PromptInjectionDetector.Verdict.MALICIOUS && blockMalicious) {
            log.warn("[Security] 拦截恶意提示注入(multi-message scan): {}", inj.matchedReasons);
            return replaceUserMessage(request, SecurityConstants.INJECTION_REJECTED);
        }
        if (inj.verdict == PromptInjectionDetector.Verdict.SUSPICIOUS) {
            log.info("[Security] 可疑输入已隔离包裹(multi-message scan): {}", inj.matchedReasons);
        }

        // —— 1~3：处理用户输入 ——
        UserMessage um = request.prompt().getUserMessage();
        if (um != null) {
            // P0-⑦ 修复：getText() 可能返回 null（Spring AI 1.1.4 在某些构造路径下未设 text），null 时按空串处理避免 NPE
            String text = Objects.requireNonNullElse(um.getText(), "");

            // L1 输入隔离
            String wrapped = InputSanitizer.wrap(text);
            request = replaceUserMessage(request, wrapped);
        }

        // —— 4：往 system prompt 注入优先级强声明 ——
        Prompt p = request.prompt();
        String baseSys = p.getSystemMessage() != null ? p.getSystemMessage().getText() : "";
        if (!baseSys.contains("最高优先级安全指令")) {
            String augmented = baseSys + SecurityConstants.PRECEDENCE_DIRECTIVE;
            List<Message> inst = new ArrayList<>(p.getInstructions());
            boolean replaced = false;
            for (int i = 0; i < inst.size(); i++) {
                if (inst.get(i) instanceof SystemMessage) {
                    inst.set(i, new SystemMessage(augmented));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                inst.add(0, new SystemMessage(augmented));
            }
            request = request.mutate().prompt(new Prompt(inst, p.getOptions())).build();
        }
        return request;
    }

    /**
     * P1-⑥ 扫描 prompt 内全部 messages 的文本，合并所有命中。
     * 优先级：MALICIOUS > SUSPICIOUS > BENIGN。任一 message 命中 MALICIOUS 即返回 MALICIOUS。
     */
    private PromptInjectionDetector.Result scanAllMessages(Prompt prompt) {
        PromptInjectionDetector.Result merged = PromptInjectionDetector.detect("");
        for (Message m : prompt.getInstructions()) {
            String text = null;
            if (m instanceof UserMessage) {
                text = Objects.requireNonNullElse(((UserMessage) m).getText(), null);
            } else if (m instanceof AssistantMessage) {
                text = Objects.requireNonNullElse(((AssistantMessage) m).getText(), null);
            }
            // SystemMessage 跳过硬编码的 PRECEDENCE_DIRECTIVE 等系统指令，扫它会误报
            if (text == null || text.isBlank()) continue;
            PromptInjectionDetector.Result r = PromptInjectionDetector.detect(text);
            if (r.verdict == PromptInjectionDetector.Verdict.MALICIOUS) {
                return r; // 命中即返回，不再合并
            }
            if (r.verdict == PromptInjectionDetector.Verdict.SUSPICIOUS) {
                if (merged.verdict != PromptInjectionDetector.Verdict.SUSPICIOUS) {
                    merged = r;
                } else {
                    // 合并 matchedReasons
                    java.util.Set<String> all = new java.util.LinkedHashSet<>(merged.matchedReasons);
                    all.addAll(r.matchedReasons);
                    merged = new PromptInjectionDetector.Result(
                            PromptInjectionDetector.Verdict.SUSPICIOUS, new java.util.ArrayList<>(all));
                }
            }
        }
        return merged;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // —— 5：输出层检测（留痕告警）——
        try {
            var chatResp = response.chatResponse();
            if (chatResp != null && chatResp.getResult() != null
                    && chatResp.getResult().getOutput() != null) {
                String out = chatResp.getResult().getOutput().getText();
                if (out != null) {
                    OutputSanitizer.SanitizeResult s = OutputSanitizer.sanitize(out);
                    if (!s.redactedItems.isEmpty()) {
                        log.warn("[Security] 输出含敏感信息（已脱敏）: {}", s.redactedItems);
                    }
                    if (s.leakedSystemPrompt) {
                        log.warn("[Security] 输出疑似泄露系统提示");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[Security] after 处理异常(忽略不影响主流程): {}", e.getMessage());
        }
        return response;
    }

    /**
     * 把第一条 UserMessage 文本替换（用于拦截/包裹）。
     * 多轮对话场景下 prompt 里可能有多条 UserMessage（用户问题 + 补充资料），只替换第一条，避免把补充资料也覆盖成同一段。
     * 如果未来有"全量替换"需求，加个 replaceAllUserMessages() 重载即可。
     */
    private ChatClientRequest replaceUserMessage(ChatClientRequest request, String newText) {
        List<Message> msgs = new ArrayList<>(request.prompt().getInstructions());
        boolean replaced = false;
        for (int i = 0; i < msgs.size(); i++) {
            if (msgs.get(i) instanceof UserMessage) {
                msgs.set(i, new UserMessage(newText));
                replaced = true;
                break; // P0-① 修复：只替换第一条，避免多轮乱覆盖
            }
        }
        if (!replaced) {
            // 兜底：理论上 before() 入口处已确认 UserMessage != null；这里只为防御性
            log.warn("[Security] replaceUserMessage 未找到 UserMessage，原样返回");
        }
        return request.mutate().prompt(new Prompt(msgs, request.prompt().getOptions())).build();
    }
}
