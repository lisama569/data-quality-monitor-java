package com.github.lisama569.dataqualitymonitorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层集成测试：重点验证拒绝原因与失败原因在 HTTP 层被正确归类且相互可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(BusinessCoverageLoggingExtension.class)
class DataQualityControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;

    @BeforeEach
    void resetStorage() throws Exception {
        mockMvc.perform(post("/api/admin/reset")).andExpect(status().isOk());
    }

    private void registerContract(String cid) throws Exception {
        Map<String, Object> contract = Map.of(
                "contractId", cid,
                "version", 1,
                "fields", List.of(
                        Map.of("name", "id", "dataType", "INTEGER", "required", true),
                        Map.of("name", "status", "dataType", "STRING", "required", true)));
        mockMvc.perform(post("/api/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(contract)))
                .andExpect(status().isOk());
    }

    private void registerRule(String cid, String rid, String type, String field,
                              Double threshold, List<String> allowed, Long maxAge) throws Exception {
        Map<String, Object> rule = new java.util.HashMap<>();
        rule.put("contractId", cid);
        rule.put("ruleId", rid);
        rule.put("type", type);
        rule.put("fieldName", field);
        if (threshold != null) {
            rule.put("threshold", threshold);
        }
        if (allowed != null) {
            rule.put("allowedValues", allowed);
        }
        if (maxAge != null) {
            rule.put("maxAgeSeconds", maxAge);
        }
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(rule)))
                .andExpect(status().isOk());
    }

    private String batchJson(String bid, String cid, List<Map<String, String>> rows) throws Exception {
        return om.writeValueAsString(Map.of(
                "batchId", bid,
                "contractId", cid,
                "submittedAt", "2026-09-24T08:00:00Z",
                "rows", rows));
    }

    /** 携带业务时点的批次 JSON。 */
    private String batchJson(String bid, String cid, String businessTime,
                             List<Map<String, String>> rows) throws Exception {
        return om.writeValueAsString(Map.of(
                "batchId", bid,
                "contractId", cid,
                "submittedAt", "2026-10-01T05:00:00Z",
                "businessTime", businessTime,
                "rows", rows));
    }

    @Test
    @DisplayName("完整链路：登记契约规则 -> 批次通过 -> 重复提交幂等返回")
    void happyPathAndDuplicate() throws Exception {
        String cid = "it-contract-1";
        registerContract(cid);
        registerRule(cid, "r-id", "COMPLETENESS", "id", 1.0, null, null);
        String body = batchJson("it-b1", cid, List.of(
                Map.of("id", "1", "status", "NEW"),
                Map.of("id", "2", "status", "PAID")));

        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.duplicate").value(true));

        mockMvc.perform(get("/api/batches/it-b1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentHash").isNotEmpty())
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    @DisplayName("结构漂移返回 422 且 reason 为 SCHEMA_MISSING_COLUMN")
    void schemaDriftReturns422WithReason() throws Exception {
        String cid = "it-contract-2";
        registerContract(cid);
        registerRule(cid, "r-id", "COMPLETENESS", "id", 1.0, null, null);
        String body = batchJson("it-b2", cid, List.of(Map.of("status", "NEW")));

        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.rejections[0].reason").value("SCHEMA_MISSING_COLUMN"));
    }

    @Test
    @DisplayName("同 ID 不同内容返回 422 且 reason 为 DUPLICATE_ID_CONTENT_CONFLICT")
    void contentConflictReturns422() throws Exception {
        String cid = "it-contract-3";
        registerContract(cid);
        registerRule(cid, "r-id", "COMPLETENESS", "id", 1.0, null, null);
        String original = batchJson("it-b3", cid, List.of(
                Map.of("id", "1", "status", "NEW")));
        String changed = batchJson("it-b3", cid, List.of(
                Map.of("id", "2", "status", "NEW")));

        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejections[0].reason")
                        .value("DUPLICATE_ID_CONTENT_CONFLICT"));
    }

    @Test
    @DisplayName("非法规则返回 400 ILLEGAL_RULE；未知指标返回 400 UNKNOWN_METRIC")
    void illegalRuleAndUnknownMetricClassified() throws Exception {
        String cid = "it-contract-4";
        registerContract(cid);

        // 非法阈值
        String badRule = om.writeValueAsString(Map.of(
                "contractId", cid, "ruleId", "r-bad",
                "type", "COMPLETENESS", "fieldName", "id", "threshold", 1.5));
        mockMvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON).content(badRule))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ILLEGAL_RULE"))
                .andExpect(jsonPath("$.violations[0]").exists());

        // 未知指标
        String unknown = om.writeValueAsString(Map.of(
                "contractId", cid, "ruleId", "r-unknown",
                "type", "MYSTERY_METRIC", "fieldName", "id", "threshold", 1.0));
        mockMvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON).content(unknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_METRIC"))
                .andExpect(jsonPath("$.metric").value("MYSTERY_METRIC"));
    }

    @Test
    @DisplayName("规则级失败原因：批次 200 返回 FAILED，与 422 的拒绝原因区分")
    void failureReasonIsNotRejection() throws Exception {
        String cid = "it-contract-5";
        registerContract(cid);
        registerRule(cid, "r-id", "COMPLETENESS", "id", 1.0, null, null);
        String body = batchJson("it-b5", cid, List.of(
                Map.of("id", "1", "status", "NEW"),
                Map.of("id", "", "status", "PAID")));

        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.rejections").isEmpty())
                .andExpect(jsonPath("$.outcomes[0].passed").value(false))
                .andExpect(jsonPath("$.outcomes[0].basis").isNotEmpty());
    }

    @Test
    @DisplayName("告警接口能观察到 OPEN 状态")
    void alertsObservable() throws Exception {
        String cid = "it-contract-6";
        registerContract(cid);
        registerRule(cid, "r-id", "COMPLETENESS", "id", 1.0, null, null);
        String body = batchJson("it-b6", cid, List.of(
                Map.of("id", "", "status", "NEW")));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ruleId=='r-id')].state").value(org.hamcrest.Matchers.hasItem("OPEN")));
    }

    @Test
    @DisplayName("业务时点+显式判定时刻：陈旧批次 FAILED，结论回显业务时点与判定时刻")
    void staleBusinessTimeFailsWithExplicitJudgedAt() throws Exception {
        String cid = "it-contract-fresh-1";
        registerContract(cid);
        registerRule(cid, "r-fresh", "FRESHNESS", null, null, null, 3600L);
        String body = batchJson("it-fresh-stale", cid, "2026-10-01T00:00:00Z",
                List.of(Map.of("id", "1", "status", "NEW")));

        // 业务 00:00、判定 03:00 -> 滞后 3h 越界
        mockMvc.perform(post("/api/batches?judgedAt=2026-10-01T03:00:00Z")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.businessTime").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.judgedAt").value("2026-10-01T03:00:00Z"))
                .andExpect(jsonPath("$.outcomes[0].measured").value("10800s"))
                .andExpect(jsonPath("$.outcomes[0].basis").isNotEmpty());

        // 同一批数据若在 00:30 判定则在窗口内；重复提交返回既有陈旧结论（不随判定时刻漂移）
        mockMvc.perform(post("/api/batches?judgedAt=2026-10-01T00:30:00Z")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.judgedAt").value("2026-10-01T03:00:00Z"));
    }

    @Test
    @DisplayName("judgedAt 非法时间格式返回 400 ILLEGAL_ARGUMENT")
    void invalidJudgedAtReturns400() throws Exception {
        String cid = "it-contract-fresh-2";
        registerContract(cid);
        registerRule(cid, "r-fresh", "FRESHNESS", null, null, null, 3600L);
        String body = batchJson("it-fresh-bad-ts", cid, "2026-10-01T00:00:00Z",
                List.of(Map.of("id", "1", "status", "NEW")));
        mockMvc.perform(post("/api/batches?judgedAt=not-a-time")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ILLEGAL_ARGUMENT"));
    }

    @Test
    @DisplayName("只读复盘接口：重算越界情况，且不改变历史批次与告警")
    void replayEndpointIsReadOnlyAndConsistent() throws Exception {
        String cid = "it-contract-replay";
        registerContract(cid);
        registerRule(cid, "r-fresh", "FRESHNESS", null, null, null, 3600L);
        // 业务 00:00，到达即判 -> 通过
        mockMvc.perform(post("/api/batches?judgedAt=2026-10-01T00:00:00Z")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("it-rp-1", cid, "2026-10-01T00:00:00Z",
                                List.of(Map.of("id", "1", "status", "NEW")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASSED"));

        // 在 03:00 复盘：该批次滞后 3h 越界
        mockMvc.perform(get("/api/contracts/" + cid + "/replay")
                        .param("judgedAt", "2026-10-01T03:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractId").value(cid))
                .andExpect(jsonPath("$.judgedAt").value("2026-10-01T03:00:00Z"))
                .andExpect(jsonPath("$.batchCount").value(1))
                .andExpect(jsonPath("$.violatingBatchCount").value(1))
                .andExpect(jsonPath("$.batches[0].violatedRuleIds[0]").value("r-fresh"))
                .andExpect(jsonPath("$.batches[0].carriedStatus").value("PASSED"))
                .andExpect(jsonPath("$.batches[0].freshnessOutcomes[0].basis")
                        .value(org.hamcrest.Matchers.containsString("[复盘]")));

        // 复盘未改写历史：落库结论仍是 PASSED，也没有产生任何告警
        mockMvc.perform(get("/api/batches/it-rp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ruleId=='r-fresh')]").isEmpty());
    }
}
