package com.springai.springai.smalldemo.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;

/**
 * 邮件发送工具类 - 供 AI 模型调用
 * 用途：发送验证码邮件、通知邮件、营销邮件等
 * 对接：SMTP 邮件服务器（如企业邮箱、163、QQ邮箱等）
 *
 * 需要配置（Spring Boot 标准邮件配置）：
 * - spring.mail.host: SMTP服务器地址
 * - spring.mail.port: SMTP端口
 * - spring.mail.username: 发件邮箱
 * - spring.mail.password: 邮箱授权码（不是登录密码）
 * - spring.mail.default-encoding: UTF-8
 */
@Component
public class EmailTools {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    public EmailTools(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 发送简单文本邮件
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件内容
     */
    @Tool(description = "发送简单文本邮件。当需要给用户发送纯文本邮件（如验证码、简单通知）时使用此工具。")
    public String sendSimpleEmail(
            @ToolParam(description = "收件人邮箱地址，如 user@example.com") String to,
            @ToolParam(description = "邮件主题/标题") String subject,
            @ToolParam(description = "邮件正文内容") String content) {

        if (fromEmail.isEmpty()) {
            return String.format("【模拟发送】收件人：%s，主题：%s。请在 application.yml 配置 spring.mail 相关配置", to, subject);
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);
            mailSender.send(message);

            return String.format("邮件发送成功 | 收件人：%s | 主题：%s", to, subject);
        } catch (Exception e) {
            return "邮件发送失败：" + e.getMessage();
        }
    }

    /**
     * 发送HTML格式邮件
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param html    HTML格式内容
     */
    @Tool(description = "发送HTML格式邮件。当需要发送富文本邮件（如带样式的通知、营销邮件）时使用此工具。")
    public String sendHtmlEmail(
            @ToolParam(description = "收件人邮箱地址") String to,
            @ToolParam(description = "邮件主题") String subject,
            @ToolParam(description = "HTML格式邮件内容，可包含样式") String html) {

        if (fromEmail.isEmpty()) {
            return String.format("【模拟发送-HTML】收件人：%s，主题：%s。请在 application.yml 配置 spring.mail 相关配置", to, subject);
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);

            return String.format("HTML邮件发送成功 | 收件人：%s | 主题：%s", to, subject);
        } catch (Exception e) {
            return "HTML邮件发送失败：" + e.getMessage();
        }
    }
}
