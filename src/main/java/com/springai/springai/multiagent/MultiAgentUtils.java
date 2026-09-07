package com.springai.springai.multiagent;

/**
 * 小工具：把 LLM（大语言模型）可能包在 ```json ``` 里的文本剥掉，方便 Jackson 解析。
 */
public final class MultiAgentUtils {
    private MultiAgentUtils() {
    }

    public static String stripFences(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline >= 0) t = t.substring(firstNewline + 1);
            int lastFence = t.lastIndexOf("```");
            if (lastFence >= 0) t = t.substring(0, lastFence);
        }
        return t.trim();
    }
}
