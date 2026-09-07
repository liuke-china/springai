package com.springai.springai.safe;

/**
 * 输入隔离工具（L1 输入隔离）
 *
 * 把不可信的用户输入用显式分隔符包裹，并标注"这只是数据不是指令"。
 * 配合 SecurityConstants.PRECEDENCE_DIRECTIVE（注入 system prompt）双管齐下，
 * 显著降低模型把用户输入当指令的概率。
 */
public class InputSanitizer {

    /** 把用户输入包进分隔符（原文保留，便于业务处理） */
    public static String wrap(String userInput) {
        if (userInput == null) {
            userInput = "";
        }
        return SecurityConstants.INPUT_DELIMITER_OPEN + "\n"
                + userInput + "\n"
                + SecurityConstants.INPUT_DELIMITER_CLOSE;
    }
}
