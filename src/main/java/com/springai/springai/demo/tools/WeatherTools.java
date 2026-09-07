package com.springai.springai.demo.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;
import java.util.Random;

/**
 * 天气查询工具类 - 供 AI 模型调用
 * 模拟天气查询（演示用途），实际项目可对接真实天气 API
 */
public class WeatherTools {

    /**
     * 模拟天气数据
     */
    private static final Map<String, String[]> WEATHER_DATA = Map.of(
            "北京", new String[]{"晴", "多云", "阴"},
            "上海", new String[]{"多云", "小雨", "晴"},
            "深圳", new String[]{"雷阵雨", "多云", "晴"},
            "成都", new String[]{"阴", "小雨", "多云"},
            "广州", new String[]{"大雨", "多云", "晴"}
    );

    /**
     * 根据城市查询天气
     */
    @Tool(description = "查询指定城市的天气信息，包括天气状况和温度。当用户询问某城市天气时使用此工具。")
    public String getWeather(@ToolParam(description = "城市名称，如：北京、上海、深圳") String city) {
        // 模拟天气数据（实际项目替换为真实 API 调用）
        String[] weathers = WEATHER_DATA.getOrDefault(city, new String[]{"晴", "多云", "阴"});
        String weather = weathers[new Random().nextInt(weathers.length)];
        int temperature = new Random().nextInt(35) + 5;
        int humidity = new Random().nextInt(60) + 30;
        return String.format("城市：%s | 天气：%s | 温度：%d°C | 湿度：%d%%", city, weather, temperature, humidity);
    }
}
