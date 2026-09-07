package com.springai.springai.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单实体类 - 多个订单属于一个用户（多对一）
 */
@Data
@TableName("sys_order")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String productName;
    private BigDecimal amount;
    private Long userId;

    public Order() {}
}
