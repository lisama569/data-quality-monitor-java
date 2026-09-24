package com.github.lisama569.dataqualitymonitorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
