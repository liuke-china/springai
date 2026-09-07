package com.springai.springai.demo.entity;

import lombok.Data;

/**
 * 单次重试记录 DTO
 */
@Data
public class RetryRecord {

    private Integer attempt;
    private Boolean success;
    private String sql;
    private String errorMessage;
    private String aiReflection;
    private Integer rowCount;
}