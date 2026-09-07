package com.springai.springai.agent;

import com.springai.springai.memory.tool.ImsDeviceTool;
import com.springai.springai.smalldemo.service.TableSchemaService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaturalLanguageQueryAgentTest {

    // ============ 一、原安全边界测试（保留，不依赖 LLM/DB） ============

    @Test
    void allowsSelectWithLimit() {
        assertDoesNotThrow(() -> NaturalLanguageQueryAgent
                .validateReadOnlySql("SELECT device_name FROM device_info LIMIT 100"));
    }

    @Test
    void allowsWithQueryWithLimit() {
        assertDoesNotThrow(() -> NaturalLanguageQueryAgent
                .validateReadOnlySql("WITH online AS (SELECT * FROM device_info) SELECT * FROM online LIMIT 10"));
    }

    @Test
    void rejectsWriteOperation() {
        assertThrows(IllegalArgumentException.class, () -> NaturalLanguageQueryAgent
                .validateReadOnlySql("DELETE FROM device_info LIMIT 1"));
    }

    @Test
    void rejectsStackedSql() {
        assertThrows(IllegalArgumentException.class, () -> NaturalLanguageQueryAgent
                .validateReadOnlySql("SELECT * FROM device_info LIMIT 10; DROP TABLE device_info"));
    }

    @Test
    void rejectsCommentInjection() {
        assertThrows(IllegalArgumentException.class, () -> NaturalLanguageQueryAgent
                .validateReadOnlySql("SELECT * FROM device_info LIMIT 10 -- ignore rules"));
    }

    @Test
    void rejectsMissingLimit() {
        assertThrows(IllegalArgumentException.class, () -> NaturalLanguageQueryAgent
                .validateReadOnlySql("SELECT * FROM device_info"));
    }

    @Test
    void rejectsTooLargeLimit() {
        assertThrows(IllegalArgumentException.class, () -> NaturalLanguageQueryAgent
                .validateReadOnlySql("SELECT * FROM device_info LIMIT 1001"));
    }

    // ============ 二、多工具 Agent 路由测试（stub 决策器模拟模型） ============

    /** 构造一个不依赖真实 LLM 的 Agent，决策器由测试注入。 */
    private NaturalLanguageQueryAgent agentWithStub(
            NaturalLanguageQueryAgent.DecisionMaker maker,
            TableSchemaService tss, JdbcTemplate jdbc) {
        org.springframework.ai.chat.model.ChatModel chatModel = mock(org.springframework.ai.chat.model.ChatModel.class);
        ImsDeviceTool ims = mock(ImsDeviceTool.class);
        org.springframework.ai.chat.memory.ChatMemory chatMemory = mock(org.springframework.ai.chat.memory.ChatMemory.class);
        NaturalLanguageQueryAgent agent =
                new NaturalLanguageQueryAgent(chatModel, jdbc, tss, ims, chatMemory);
        agent.setDecisionMaker(maker);
        return agent;
    }

    @Test
    void routesThroughFindSchemaThenRunSqlThenAnswer() {
        TableSchemaService tss = mock(TableSchemaService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(tss.findRelevantTables(any())).thenReturn(List.of("device"));
        when(tss.readTableSchema(any(), anyList())).thenReturn("表 device(id, name)");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", 1, "name", "CNC-001")));

        // 模型逐步决策：先找表 → 再执行 SQL → 最后回答
        NaturalLanguageQueryAgent.DecisionMaker maker = (q, s, hist, conv, cid) -> {
            if (hist.isEmpty())
                return new AgentAction("先找相关表", "FIND_SCHEMA", Map.of("keyword", "设备"), null);
            if (hist.size() == 1)
                return new AgentAction("用 SQL 查设备", "RUN_SQL",
                        Map.of("sql", "SELECT * FROM device LIMIT 10"), null);
            return new AgentAction("信息够了", "ANSWER", Map.of(), "共1台设备 CNC-001");
        };

        NaturalLanguageQueryResult r = agentWithStub(maker, tss, jdbc).run("查询设备", "public", 4);

        assertTrue(r.success());
        // 关键：trace 显示模型真的调了多个不同工具，而不是写死流程
        assertEquals(List.of("FIND_SCHEMA", "RUN_SQL"), r.toolsUsed());
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("FIND_SCHEMA")));
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("RUN_SQL")));
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("ANSWER")));
    }

    @Test
    void stopsAtMaxStepsWhenNoAnswer() {
        TableSchemaService tss = mock(TableSchemaService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(tss.findRelevantTables(any())).thenReturn(List.of("t"));
        when(tss.readTableSchema(any(), anyList())).thenReturn("表 t(id)");

        // 模型永远只调 FIND_SCHEMA，从不 ANSWER -> 应在最大步数停下
        NaturalLanguageQueryAgent.DecisionMaker maker =
                (q, s, hist, conv, cid) -> new AgentAction("继续找", "FIND_SCHEMA", Map.of("keyword", "x"), null);

        NaturalLanguageQueryResult r = agentWithStub(maker, tss, jdbc).run("查询", "public", 3);

        assertFalse(r.success());
        assertEquals(3, r.steps());
        assertEquals(List.of("FIND_SCHEMA", "FIND_SCHEMA", "FIND_SCHEMA"), r.toolsUsed());
    }

    @Test
    void dangerousSqlFromModelIsBlockedInDispatchAndFedBack() {
        TableSchemaService tss = mock(TableSchemaService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(tss.findRelevantTables(any())).thenReturn(List.of("device"));

        // 模型直接要求跑危险 SQL（DELETE）
        NaturalLanguageQueryAgent.DecisionMaker maker =
                (q, s, hist, conv, cid) -> new AgentAction("我想删", "RUN_SQL",
                        Map.of("sql", "DELETE FROM device LIMIT 1"), null);

        NaturalLanguageQueryResult r = agentWithStub(maker, tss, jdbc).run("删设备", "public", 3);

        // 危险 SQL 不应真正执行，而是作为"工具执行失败"反馈，Agent 不崩溃
        assertFalse(r.success());
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("工具执行失败")));
    }

    @Test
    void recordsEveryLlmInteractionIntoConversationArray() {
        TableSchemaService tss = mock(TableSchemaService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(tss.findRelevantTables(any())).thenReturn(List.of("device"));
        when(tss.readTableSchema(any(), anyList())).thenReturn("表 device(id, name)");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", 1, "name", "CNC-001")));

        // stub 模拟"模型"：每步决策时把本次 LLM 交互写进 conversation（对应真实 askModel 的行为）
        NaturalLanguageQueryAgent.DecisionMaker maker = (q, s, hist, conv, cid) -> {
            conv.add("Q: " + q + "\nA: thought=查设备 | tool="
                    + (hist.isEmpty() ? "FIND_SCHEMA" : "ANSWER"));
            if (hist.isEmpty())
                return new AgentAction("先找相关表", "FIND_SCHEMA", Map.of("keyword", "设备"), null);
            return new AgentAction("信息够了", "ANSWER", Map.of(), "共1台设备 CNC-001");
        };

        NaturalLanguageQueryResult r = agentWithStub(maker, tss, jdbc).run("查询设备", "public", 4);

        assertTrue(r.success());
        // 两次 LLM 交互 -> conversation 应有 2 条 Q/A 字符串
        assertEquals(2, r.conversation().size());
        assertTrue(r.conversation().get(0).startsWith("Q: 查询设备"));
        assertTrue(r.conversation().get(0).contains("\nA: "));
        // conversationId 非空、以 "ag" 开头(Agent 来源标识)、且不超过 36 字符(VARCHAR 上限)
        assertTrue(r.conversationId() != null && r.conversationId().startsWith("ag")
                && r.conversationId().length() <= 36);
    }

    @Test
    void extractsSqlFromFinalAnswerWhenModelReturnsSql() {
        TableSchemaService tss = mock(TableSchemaService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(tss.findRelevantTables(any())).thenReturn(List.of("device"));
        when(tss.readTableSchema(any(), anyList())).thenReturn("表 device(id, name)");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", 1)));

        // 模型最终答案里带 ```sql 代码块
        NaturalLanguageQueryAgent.DecisionMaker maker = (q, s, hist, conv, cid) -> {
            if (hist.isEmpty())
                return new AgentAction("先找表", "FIND_SCHEMA", Map.of("keyword", "设备"), null);
            if (hist.size() == 1)
                return new AgentAction("跑SQL", "RUN_SQL",
                        Map.of("sql", "SELECT * FROM device LIMIT 10"), null);
            return new AgentAction("给答案", "ANSWER", Map.of(),
                    "查询设备数量：\n```sql\nSELECT COUNT(*) FROM device LIMIT 10;\n```\n共1台");
        };

        NaturalLanguageQueryResult r = agentWithStub(maker, tss, jdbc).run("查询设备", "public", 4);

        assertTrue(r.success());
        // 关键：返回数据的 sql 字段不再为空，提取出模型给的 SQL（修复"回答是空的"）
        assertTrue(r.sql() != null && r.sql().contains("SELECT COUNT(*) FROM device"),
                "sql 字段应为提取出的 SQL，实际=" + r.sql());
    }
}
