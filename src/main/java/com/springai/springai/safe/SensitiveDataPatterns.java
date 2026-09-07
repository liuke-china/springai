package com.springai.springai.safe;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感信息正则库（L6/LLM02 输出脱敏的"硬防线"）
 *
 * 原则：脱敏是**代码层正则替换**，不依赖模型"听话"。
 * 即使模型真的把密钥写进回答，代码也会在响应边界把它替换成 [已脱敏]。
 *
 * 注意：这些正则是"宁可错杀"的安全基线。生产环境应按业务微调，
 * 避免把正常的工单号/设备编码误伤（可用白名单排除）。
 */
public final class SensitiveDataPatterns {

    private SensitiveDataPatterns() {}

    public static final List<Pattern> SECRET_PATTERNS = List.of(
            // 通用 API Key: sk-xxxx / ak.xxxx
            Pattern.compile("\\b(sk|ak)-[A-Za-z0-9_-]{16,}\\b", Pattern.CASE_INSENSITIVE),
            // AWS Access Key
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            // JWT（JSON Web Token，一种令牌）
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
            // 数据库连接串
            Pattern.compile("jdbc:(postgresql|mysql|sqlserver)://[^\\s\"']+", Pattern.CASE_INSENSITIVE),
            // 密码/密钥赋值（password=... / token: ...）
            Pattern.compile("\\b(password|passwd|pwd|secret|token|apikey|api_key)\\s*[:=]\\s*['\"]?[^\"'\\s]{4,}", Pattern.CASE_INSENSITIVE),
            // 内网 IP（10.x / 192.168.x / 172.16-31.x）
            Pattern.compile("\\b(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})\\b"),
            // 身份证号
            Pattern.compile("\\b\\d{17}[\\dXx]\\b"),
            // 手机号
            Pattern.compile("\\b1[3-9]\\d{9}\\b")
    );
}
