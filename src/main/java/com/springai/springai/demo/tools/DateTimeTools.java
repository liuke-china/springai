package com.springai.springai.smalldemo.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 日期时间工具类 - 供 AI 模型调用
 * AI 本身不知道当前时间，通过此工具可以让 AI 获取实时时间信息
 */
public class DateTimeTools {

    /**
     * 获取当前日期和时间
     */
    @Tool(description = "获取当前的日期和时间，包含时区信息。当用户询问今天日期、现在几点等问题时使用此工具。")
    public String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        String zoneId = LocaleContextHolder.getTimeZone().toZoneId().toString();
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        String dayOfWeekCn = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return String.format("当前时间：%s，星期：%s，时区：%s",
                now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")),
                dayOfWeekCn, zoneId);
    }

    /**
     * 设置闹钟
     */
    @Tool(description = "在指定时间设置闹钟。时间格式为 ISO-8601 格式，例如 2025-05-07T14:30:00")
    public String setAlarm(@ToolParam(description = "闹钟时间，ISO-8601格式，例如 2025-05-07T14:30:00") String time) {
        LocalDateTime alarmTime = LocalDateTime.parse(time, DateTimeFormatter.ISO_DATE_TIME);
        return "闹钟已设置：" + alarmTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
    }
}
