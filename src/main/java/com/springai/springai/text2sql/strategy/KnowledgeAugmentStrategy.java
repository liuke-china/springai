package com.springai.springai.text2sql.strategy;

import com.springai.springai.demo.service.TableSchemaService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IMS 项目专属知识注入 —— 把"已知表关联（foreign_key）"和"已知页面/接口（interface_map）"
 * 在 SQL 生成时按用户问题检索，追加到 Prompt。
 *
 * 【做什么】
 *   把代码挖掘出的 FK 关联 + UI→DB 接口映射向量化入库；提问时检索最相关的若干条，
 *   拼成自然语言段追加到 Prompt，给 AI 两条强信号：
 *     1) 这些表项目里就这么 JOIN，直接照抄就行，不要凭直觉拼 ON 条件
 *     2) 用户问的功能很可能对应这个菜单 / 页面，背后涉及的就是这些表
 *
 * 【不做什么】
 *   不管 SQL 写法（FewShotStrategy）、不管术语（GlossaryStrategy）；
 *   只管"IMS 项目代码里表之间的 JOIN 模式 + 页面/接口到表的映射"。
 *
 * 【主要 API】
 *   build(question, enabled, useFK, useIM, candidateTables)
 *     → KnowledgeAugmentResult(promptSection, matchedFKs, matchedInterfaceMaps)
 *   传 candidateTables 时按"表名精确关联"检索，比按问题语义更准；为空则降级为语义检索。
 *
 * 【痛点背景】
 *   IMS 数据库声明了 0 个真实外键约束，所有 JOIN 模式只能从应用代码（MyBatis XML JOIN / resultMap）
 *   反推；同时 IMS 有 447 个菜单 / 按钮，但每个菜单真正读写哪些表并不直观。
 *
 * 【与 TableSchemaService 之间的 metadata 约定】
 *   - type=foreign_key 的 document.metadata 含：from_table / from_column / to_table / to_column /
 *     confidence / evidence / join_hint
 *   - type=interface_map 的 document.metadata 含：menu_id / menu_name / perms /
 *     mappers(List<String>) / tables(List<String>)
 *   索引端 TableSchemaService.indexForeignKeys / indexInterfaceMap 负责写入；
 *   检索端本策略负责读取并拼成 Prompt 段。
 */
@Component
public class KnowledgeAugmentStrategy {

    /** 召回条数上限；与 few-shot 同量级，避免污染 Prompt */
    private static final int TOP_K = 5;

    private final TableSchemaService tableSchemaService;

    public KnowledgeAugmentStrategy(TableSchemaService tableSchemaService) {
        this.tableSchemaService = tableSchemaService;
    }

    /**
     * @param question 用户问题
     * @param enabled  是否启用本方案（开关便于 A/B 对照）
     * @return 注入文本段 + FK 命中明细 + 接口映射命中明细
     */
    public KnowledgeAugmentResult build(String question, boolean enabled) {
        return build(question, enabled, enabled, enabled);
    }

    /**
     * 分别控制 JOIN 关系和页面映射，便于做独立 A/B 评测；旧总开关仍由 build(question, enabled) 兼容。
     * 不传候选表时降级为按问题语义检索（保持原行为）。
     */
    public KnowledgeAugmentResult build(String question,
                                        boolean enabled,
                                        boolean useForeignKey,
                                        boolean useInterfaceMap) {
        return build(question, enabled, useForeignKey, useInterfaceMap, List.of());
    }

    /**
     * 完整版：传入 schema 召回出的候选表名，foreign_key / interface_map 改为"按表名精确关联"检索
     * （而非按问题语义相似度），彻底解决"关系知识有数据却捞不到、模型选表靠猜"的问题。
     * candidateTables 为空时降级回原来的语义检索，保持兼容。
     */
    public KnowledgeAugmentResult build(String question,
                                        boolean enabled,
                                        boolean useForeignKey,
                                        boolean useInterfaceMap,
                                        List<String> candidateTables) {
        if (!enabled || (!useForeignKey && !useInterfaceMap)) {
            return new KnowledgeAugmentResult("", List.of(), List.of());
        }
        List<Document> fks = useForeignKey
                ? retrieve("foreign_key", question, candidateTables)
                : List.of();
        List<Document> ims = useInterfaceMap
                ? retrieve("interface_map", question, candidateTables)
                : List.of();
        return assemble(fks, ims);
    }

    /**
     * 检索关系知识：有候选表名则按表精确关联（更准），否则降级按问题语义相似度。
     */
    private List<Document> retrieve(String type, String question, List<String> candidateTables) {
        if (candidateTables != null && !candidateTables.isEmpty()) {
            return tableSchemaService.findKnowledgeByTables(type, candidateTables, TOP_K);
        }
        return tableSchemaService.findDocumentsByType(type, question, TOP_K);
    }

    /**
     * 把命中的 foreign_key / interface_map 文档拼成 Prompt 段（与检索方式解耦）。
     */
    private KnowledgeAugmentResult assemble(List<Document> fks, List<Document> ims) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> matchedFks = new ArrayList<>();
        List<Map<String, Object>> matchedIms = new ArrayList<>();

        // ---------- 段1：已知表关联 ----------
        if (!fks.isEmpty()) {
            sb.append("【已知表关联】项目里实际出现过的高置信 JOIN 模式（直接照抄 JOIN 写法，不要凭直觉拼 ON 条件）：\n");
            for (Document doc : fks) {
                Map<String, Object> meta = doc.getMetadata();

                String fromTable  = strOf(meta.get("from_table"));
                String toTable    = strOf(meta.get("to_table"));
                String joinHint   = strOf(meta.get("join_hint"));
                String confidence = strOf(meta.get("confidence"));
                String evidence   = strOf(meta.get("evidence"));

                // 自然语言拼出的关联描述：joinHint 原文作为骨架，补全 from→to 方向与置信度
                sb.append("- ");
                if (!joinHint.isEmpty()) {
                    sb.append(joinHint);
                } else {
                    sb.append(fromTable).append(" 通过外键关联到 ").append(toTable);
                }
                if (!confidence.isEmpty()) {
                    sb.append(" (置信度=").append(confidence);
                    if (!evidence.isEmpty()) sb.append(", 来源 ").append(evidence);
                    sb.append(")");
                }
                sb.append("\n");

                Map<String, Object> m = new HashMap<>();
                m.put("from", fromTable);
                m.put("to", toTable);
                m.put("join_hint", joinHint);
                m.put("confidence", confidence);
                m.put("evidence", evidence);
                matchedFks.add(m);
            }
            sb.append("\n");
        }

        // ---------- 段2：已知页面/接口 ----------
        if (!ims.isEmpty()) {
            sb.append("【已知页面/接口】用户问的问题可能对应这些菜单页面（背后读写的就是这些表）：\n");
            for (Document doc : ims) {
                Map<String, Object> meta = doc.getMetadata();

                String menuName = strOf(meta.get("menu_name"));
                String menuId   = strOf(meta.get("menu_id"));
                String perms    = strOf(meta.get("perms"));
                // mappers / tables 在 metadata 里是 List<String>，这里拍平成逗号串
                String mappers  = joinList(meta.get("mappers"));
                String tables   = joinList(meta.get("tables"));

                sb.append("- ").append(menuName);
                if (!menuId.isEmpty())  sb.append(" (菜单ID ").append(menuId).append(")");
                if (!perms.isEmpty())   sb.append(", 权限 ").append(perms);
                if (!tables.isEmpty())  sb.append(", 关联表 ").append(tables);
                if (!mappers.isEmpty()) sb.append(", 涉及 Mapper ").append(mappers);
                sb.append("\n");

                Map<String, Object> m = new HashMap<>();
                m.put("menu_id", menuId);
                m.put("menu_name", menuName);
                m.put("perms", perms);
                m.put("tables", tables);
                m.put("mappers", mappers);
                matchedIms.add(m);
            }
            sb.append("\n");
        }

        return new KnowledgeAugmentResult(sb.toString(), matchedFks, matchedIms);
    }

    private static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 把 List<String> 拍平成逗号分隔串；null / 非 List 安全降级。
     */
    @SuppressWarnings("unchecked")
    private static String joinList(Object v) {
        if (v == null) return "";
        if (v instanceof List) {
            List<String> list = (List<String>) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != null) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(list.get(i));
                }
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }
}
