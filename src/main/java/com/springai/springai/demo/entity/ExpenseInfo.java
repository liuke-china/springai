package com.springai.springai.smalldemo.entity;

import lombok.Data;

/**
 * 消费信息提取结果
 * 用于演示：从自然语言中提取结构化消费数据
 */
@Data
public class ExpenseInfo {

    /**
     * 消费人
     */
    private String person;

    /**
     * 消费金额（元）
     */
    private Double amount;

    /**
     * 消费商品
     */
    private String item;

    /**
     * 消费日期
     */
    private String date;

    /**
     * 消费地点
     */
    private String location;
}
