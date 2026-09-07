package com.springai.springai.mcp.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 天气查询 MCP 工具 — 接 open-meteo 真实天气 API
 * =====================================================================
 * 【数据源】open-meteo（https://open-meteo.com/）
 * - 免费、无需 API Key、全球覆盖
 * - 返回当前温度/湿度/天气码（WMO 标准）
 *
 * 【MCP 注册方式】
 * @Tool 标注方法 → WeatherMcpServerConfig 包装成 MethodToolCallbackProvider Bean
 * → MCP Server auto-config 自动注册到 /mcp 端点。注解和 MCP 注册方式完全不变。
 * =====================================================================
 */
@Slf4j
@Component
public class WeatherMcpTools {

    /** 城市中文名 → 经纬度（纬度, 经度） */
    private static final Map<String, double[]> CITY_COORDS = new LinkedHashMap<>();
    static {
        CITY_COORDS.put("深圳",   new double[]{22.54, 114.06});
        CITY_COORDS.put("北京",   new double[]{39.90, 116.40});
        CITY_COORDS.put("上海",   new double[]{31.23, 121.47});
        CITY_COORDS.put("广州",   new double[]{23.13, 113.26});
        CITY_COORDS.put("哈尔滨", new double[]{45.80, 126.53});
        CITY_COORDS.put("成都",   new double[]{30.57, 104.07});
        CITY_COORDS.put("杭州",   new double[]{30.27, 120.15});
        CITY_COORDS.put("武汉",   new double[]{30.58, 114.31});
        CITY_COORDS.put("西安",   new double[]{34.26, 108.94});
        CITY_COORDS.put("南京",   new double[]{32.06, 118.80});
    }

    /** WMO 天气码 → 中文天气描述 */
    private static String weatherCodeToCn(int code) {
        // open-meteo 使用 WMO 天气码（https://open-meteo.com/en/docs#weathervariables）
        if (code == 0)                     return "晴";
        if (code <= 2)                     return "少云";
        if (code == 3)                     return "多云";
        if (code <= 48)                    return "雾";
        if (code <= 55)                    return "毛毛雨";
        if (code <= 57)                    return "冻毛毛雨";
        if (code <= 65)                    return "雨";
        if (code <= 67)                    return "冻雨";
        if (code <= 77)                    return "雪";
        if (code <= 82)                    return "阵雨";
        if (code <= 86)                    return "阵雪";
        if (code <= 99)                    return "雷暴";
        return "未知";
    }

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 调 open-meteo 获取当前天气。
     * GET https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&current=temperature_2m,relative_humidity_2m,weather_code
     */
    @SuppressWarnings("unchecked")
    private WeatherInfo fetchFromOpenMeteo(String city, double lat, double lon) {
        String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code",
                lat, lon);
        try {
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp == null) return fallback(city);

            Map<String, Object> current = (Map<String, Object>) resp.get("current");
            if (current == null) return fallback(city);

            double temp = toDouble(current.get("temperature_2m"));
            int humidity = toInt(current.get("relative_humidity_2m"));
            int weatherCode = toInt(current.get("weather_code"));
            String condition = weatherCodeToCn(weatherCode);

            return new WeatherInfo(city, (int) Math.round(temp), condition, humidity);
        } catch (Exception e) {
            log.warn("[WeatherMcpTools] open-meteo 调用失败: {}，降级 mock", e.getMessage());
            return fallback(city);
        }
    }

    private WeatherInfo fallback(String city) {
        return new WeatherInfo(city, 20, "晴（降级数据）", 50);
    }

    private static double toDouble(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        return 0;
    }

    private static int toInt(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        return 0;
    }

    /**
     * 获取某城市的天气概况（温度 + 天气 + 湿度）。
     * 数据源：open-meteo 免费实时 API。
     */
    @Tool(description = "查询指定城市的当前天气，返回温度、天气状况、湿度。"
            + "当用户问'xx天气怎么样'、'xx多少度'、'xx下雨吗'时使用。"
            + "数据来源：open-meteo 实时免费 API，支持全国主要城市。")
    public WeatherInfo getWeather(
            @ToolParam(description = "城市中文名，例如 '深圳'、'北京'、'成都'", required = true) String city) {
        long t0 = System.currentTimeMillis();
        System.out.println("[WeatherMcpTools] ▶ 调用 MCP 工具 getWeather(city=" + city + ")");

        double[] coords = CITY_COORDS.get(city);
        WeatherInfo info;
        if (coords != null) {
            info = fetchFromOpenMeteo(city, coords[0], coords[1]);
        } else {
            info = new WeatherInfo(city, 20, "未知（城市暂无经纬度，请联系管理员添加）", 50);
        }

        System.out.println("[WeatherMcpTools] ✓ getWeather 返回: " + info
                + "  耗时=" + (System.currentTimeMillis() - t0) + "ms");
        return info;
    }

    /**
     * 只返回温度。
     */
    @Tool(description = "获取指定城市的当前温度（摄氏度）。"
            + "比 getWeather 更精简，只关心温度时用，例如'深圳现在几度'。"
            + "数据来源：open-meteo 实时免费 API。")
    public String getTemperature(
            @ToolParam(description = "城市中文名，例如 '深圳'", required = true) String city) {
        long t0 = System.currentTimeMillis();
        System.out.println("[WeatherMcpTools] ▶ 调用 MCP 工具 getTemperature(city=" + city + ")");

        double[] coords = CITY_COORDS.get(city);
        WeatherInfo info;
        if (coords != null) {
            info = fetchFromOpenMeteo(city, coords[0], coords[1]);
        } else {
            info = new WeatherInfo(city, 20, "未知", 50);
        }

        String result = String.format("%s 当前温度: %d°C", city, info.temp());
        System.out.println("[WeatherMcpTools] ✓ getTemperature 返回: " + result
                + "  耗时=" + (System.currentTimeMillis() - t0) + "ms");
        return result;
    }

    /** 天气信息返回值（给 LLM 的结构化结果） */
    public record WeatherInfo(String city, int temp, String condition, int humidity) {
        @Override
        public String toString() {
            return String.format("%s: %d°C, %s, 湿度%d%%", city, temp, condition, humidity);
        }
    }
}
