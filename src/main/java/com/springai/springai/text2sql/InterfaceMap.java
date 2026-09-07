package com.springai.springai.text2sql;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * IMS 前端菜单 / 后端接口 → 物理表映射（Interface Map）DTO。
 *
 * 对应 text2sql-refs/ims_interface_map.json 结构。每条 = 一个 sys_menu 行（菜单 / 按钮）
 * 追溯到后端 Spring 端点 → Service → MyBatis Mapper → 真实查询过的物理表，
 * 向量化后 type='interface_map' 入库，提问时按语义检索注入 Prompt，
 * 帮助 AI 理解"用户问的功能可能对应哪个菜单 / 页面，背后是哪些表"，提升召回准确率。
 *
 * 字段命名与生成 JSON 的 key 完全一致（用 @JsonProperty 锚定，避免 snake/camel 错位）。
 * 使用 Lombok @Data 自动生成 getter/setter。
 */
@Data
public class InterfaceMap {

    /** 菜单 ID（sys_menu.menu_id，对应 sys_menu 主键） */
    @JsonProperty("menu_id")
    private String menuId;

    /** 菜单名（如"IMS报告"） */
    @JsonProperty("menu_name")
    private String menuName;

    /** 前端路由路径 */
    @JsonProperty("route_path")
    private String routePath;

    /** 该菜单/接口涉及的 MyBatis Mapper 列表（去重） */
    @JsonProperty("mappers")
    private List<String> mappers;

    /** 该菜单/接口涉及的物理表（带 comment）。结构：[{table: "...", comment: "..."}] */
    @JsonProperty("tables")
    private List<TableRef> tables;

    /** 该菜单/接口的权限标识（如 "imsReport:page"） */
    @JsonProperty("perms")
    private String perms;

    /**
     * JSON 顶层包装：{"interfaces":[...], "unmatched_menus":[...], "meta":{...}}
     * 只取 interfaces 数组，unmatched_menus 等字段由 Jackson 忽略。
     */
    @Data
    public static class InterfaceMapFile {
        @JsonProperty("interfaces")
        private List<InterfaceMap> interfaces;
    }

    /**
     * 单条 table 引用：{"table":"device_level","comment":""}
     */
    @Data
    public static class TableRef {
        @JsonProperty("table")
        private String table;

        @JsonProperty("comment")
        private String comment;
    }
}
