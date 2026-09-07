package com.springai.springai.demo.entity;

import lombok.Data;

/**
 * 从文本中提取的实体信息
 * 用于演示：.entities() 返回 List 对象
 */
@Data
public class ExtractedEntity {

    /**
     * 实体类型（人名/金额/地点/时间/组织等）
     */
    private String type;

    /**
     * 实体值（如"张三"、"5000元"、"北京"）
     */
    private String value;

    /**
     * 实体描述
     */
    private String description;
}
