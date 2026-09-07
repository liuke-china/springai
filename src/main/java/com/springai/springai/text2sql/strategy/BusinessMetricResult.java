package com.springai.springai.text2sql.strategy;

import java.util.List;
import java.util.Map;

/**
 * 业务口径召回结果。
 */
public record BusinessMetricResult(String promptSection, List<Map<String, Object>> matchedMetrics) {}