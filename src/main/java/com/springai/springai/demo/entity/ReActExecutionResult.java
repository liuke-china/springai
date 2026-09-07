package com.springai.springai.smalldemo.entity;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ReAct 循环执行结果 DTO
 */
@Data
public class ReActExecutionResult {

    private Boolean success;
    private List<Map<String, Object>> data;
    private String finalSql;
    private String finalExplanation;
    private Integer retryCount;
    private List<RetryRecord> history;
    private String errorMessage;
    private List<String> tables;
}