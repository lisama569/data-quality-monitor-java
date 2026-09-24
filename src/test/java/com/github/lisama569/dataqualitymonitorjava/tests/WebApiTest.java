package com.github.lisama569.dataqualitymonitorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.lisama569.dataqualitymonitorjava.service.DemoDataInitializer;
import com.github.lisama569.dataqualitymonitorjava.service.QualityMonitorService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web 层端到端：演示契约随应用启动注册；
 * 重复冲突映射 409，结构漂移/非法规则等映射 422，精确重复为 200。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QualityMonitorService service;

    @Test
    void duplicateConflictReturns409AndSchemaDriftReturns422() throws Exception {
        String goodBatch = """
                {"dataset":"orders","batchId":"web-1","eventTime":%d,"rows":[
                  {"id":"o1","user_id":"u1","amount":10.0}
                ]}""".formatted(System.currentTimeMillis());

        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(goodBatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
        log.info("[测试断言] web-1 首次提交 200 PASSED");

        // 同 batchId 不同内容 -> 409
        String conflict = """
                {"dataset":"orders","batchId":"web-1","eventTime":%d,"rows":[
                  {"id":"o1","user_id":"DIFFERENT","amount":10.0}
                ]}""".formatted(System.currentTimeMillis());
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_BATCH_CONFLICT"));
        log.info("[测试断言] web-1 不同内容重复提交 409 DUPLICATE_BATCH_CONFLICT");

        // 结构漂移（未知字段）-> 422
        String drift = """
                {"dataset":"orders","batchId":"web-2","eventTime":%d,"rows":[
                  {"id":"o2","user_id":"u2","amount":10.0,"ghost":1}
                ]}""".formatted(System.currentTimeMillis());
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(drift))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("SCHEMA_DRIFT_UNKNOWN_FIELD"));
        log.info("[测试断言] web-2 未知字段漂移 422 SCHEMA_DRIFT_UNKNOWN_FIELD");

        // 精确重复提交 -> 200，且结论与首次一致
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(goodBatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
        log.info("[测试断言] web-1 精确重复 200，结论不漂移");

        // 结论查询
        mockMvc.perform(get("/api/datasets/orders/batches/web-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentHash").isString());
    }

    @Test
    void invalidRuleContractReturns422() throws Exception {
        String body = """
                {"dataset":"bad-ds","fields":[{"name":"id","type":"STRING","required":true}],
                 "rules":[{"ruleId":"r1","ruleType":"COMPLETENESS","field":"missing","params":{"min":1.0}}]}""";
        mockMvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_RULE_CONFIG"));
        log.info("[测试断言] 非法契约注册 422 INVALID_RULE_CONFIG");
        assertEquals(0, service.listBatches("bad-ds").size());
    }
}
