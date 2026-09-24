# 本地数据质量校验与监控服务

基于 Spring Boot 的本地（内存存储）数据质量校验与监控服务，模拟数据集的批次导入、质量评估、
幂等去重、指标沉淀与告警状态流转。所有判定结论确定且可复现，适合离线演示与单测验证。

## 1. 数据契约（Data Contract）

契约按数据集（dataset）注册、**版本化**管理，由字段定义与质量规则两部分组成。

- 字段定义 `FieldSpec`：`name`、`type`（STRING / LONG / DOUBLE / BOOLEAN / TIMESTAMP）、`required`。
- 质量规则 `QualityRule`：`ruleId` + `ruleType` + `field` + `params`，共四类：

| 规则类型 | 参数 | 指标 | 失败原因（FailureReason） |
|---|---|---|---|
| `COMPLETENESS` 完整性 | `min` ∈ [0,1]，非空（空串视为缺失）比例下限 | `COMPLETENESS_RATIO` | `COMPLETENESS_BELOW_THRESHOLD` |
| `UNIQUENESS` 唯一性 | 无，字段即业务键，空值不参与比较 | `UNIQUENESS_RATIO` | `UNIQUENESS_VIOLATION`（detail 列出重复键） |
| `VALUE_DOMAIN` 取值域 | `allowed` 枚举列表，或数值区间 `min`/`max` | `VALUE_DOMAIN_RATIO` | `VALUE_OUT_OF_DOMAIN`（detail 列出越界样例） |
| `FRESHNESS` 新鲜度 | `maxAgeSeconds`，`now - eventTime` 上限 | `FRESHNESS_AGE_SECONDS` | `FRESHNESS_STALE`（批次级，field 可缺省） |

每条规则都会被评估，结果以 `RuleVerdict(ruleId, type, field, passed, observed, detail)` 落在批次结论中，
`detail` 记录阈值、观测值与样本计数，即“命中的规则 + 判定依据”。字段值为空属于完整性规则范畴，
不属于结构漂移。

### 合法指标（未知指标不得静默跳过）

可监控指标枚举固定为：`COMPLETENESS_RATIO`、`UNIQUENESS_RATIO`、
`VALUE_DOMAIN_RATIO`、`FRESHNESS_AGE_SECONDS`。告警规则引用枚举外的指标一律按
`UNKNOWN_METRIC` 拒绝。

## 2. 拒绝原因 vs 失败原因（严格区分）

- **拒绝（REJECTED，批次未进入评估流程，HTTP 4xx）** —— `RejectReason`：

| RejectReason | 触发场景 | HTTP |
|---|---|---|
| `MISSING_FIELD` | 请求缺少 dataset / batchId / rows | 422 |
| `EMPTY_BATCH` | rows 为空列表 | 422 |
| `UNKNOWN_DATASET` | 数据集没有任何契约版本 | 422 |
| `SCHEMA_DRIFT_UNKNOWN_FIELD` | 批次出现契约未声明字段（结构漂移） | 422 |
| `SCHEMA_DRIFT_MISSING_REQUIRED_FIELD` | 批次行缺少必填字段键（结构漂移） | 422 |
| `INVALID_RULE_CONFIG` | 规则引用未知字段、阈值缺失/越界（min∉[0,1]、maxAge<0、min>max、对非数值字段用区间、allowed 为空）、ruleId 缺失/重复、字段重名、告警比较符非法 | 422 |
| `UNKNOWN_METRIC` | 告警引用了不支持的指标 | 422 |
| `DUPLICATE_BATCH_CONFLICT` | **batchId 相同但内容哈希不同**，拒绝覆盖既有结果 | 409 |

- **失败（FAILED，已进入评估但有规则未通过，HTTP 200）** —— 规则级 `FailureReason` 见上表，
  与拒绝原因完全分开，调用方可据原因字段精确分流。

结构漂移与字段缺失都在评估**之前**拦截，非法规则在契约**注册时**即拦截，绝不静默跳过。

## 3. 幂等、重复批次与兼容策略

- `batchId` 是调用方提供的幂等键；提交内容按「dataset + eventTime + 逐行（字段名排序后的键值）」
  计算 **SHA-256 内容哈希**。
- **精确重复提交**（同 batchId 且哈希一致，无论串行还是并发）：直接返回首次结论（200），
  指标样本按 `batchId+metric` 幂等去重，不会重复累计，结论不会漂移。
- **同标识不同内容**：抛 `DUPLICATE_BATCH_CONFLICT`（409），既有结论与哈希保持不变，绝不覆盖。
- 并发提交由存储的原子 `putIfAbsent` 保证只有一个提交完成评估占位：内容相同则其余返回同一结论；
  内容不同则其余全部 409。
- 被结构漂移拒绝的批次**不占据** batchId，调用方修正内容后可用同一标识重试。
- **结构兼容策略**：字段集合必须与契约一致 —— 多字段（未知）或少必填字段键都判定为漂移拒绝；
  两类漂移会在拒绝消息中分别列出。宽松演进（如新增可选字段）需先注册新版本契约。

### 历史结论可复现

批次结论持久化了评估时的 `contractVersion` 与每条规则的观测快照；契约更新（字段、规则、阈值变更）
只生成新版本、只影响后续批次，历史批次查询结果永远是当时的 PASSED/FAILED 与判定依据；
历史版本契约可通过注册表按版本号取回。

## 4. 告警状态流转

告警规则 `AlertRule(alertId, dataset, metric, operator(">"|"<"), threshold, version)`，
每沉淀一个批次指标样本后驱动一次状态机：

```
OK ──首次越界(OPENED)──▶ FIRING ──恢复(RESOLVED)──▶ RESOLVED ──再次越界(REOPENED)──▶ FIRING
                           ▲                              │
                           └──── 持续越界: SUPPRESSED(合并，不重复打开)
```

- **重复告警合并**：FIRING 期间继续越界只追加 `SUPPRESSED` 事件，`firedCount` 不增长。
- **恢复后重开**：进入 RESOLVED 后再次越界产生 `REOPENED`，`firedCount` +1。
- 全部流转形成不可变事件时间线 `AlertEvent`（含批次、观测值、阈值、规则版本）。
- **阈值/比较符变更生成新版本号**：新事件按新规则判定并记录新版本；历史事件携带旧版本号、
  不可改写，历史告警结论可复现。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/contracts` | 注册新版本数据契约（非法规则 422） |
| POST | `/api/batches` | 提交批次（精确重复 200 返回既有结论；冲突 409；漂移/非法 422） |
| GET | `/api/datasets/{dataset}/batches/{batchId}` | 查询批次结论（含内容哈希、契约版本、逐条判定依据） |
| GET | `/api/datasets/{dataset}/batches` | 列出数据集全部批次 |
| POST | `/api/alerts` | 注册/升版告警规则 |
| GET | `/api/datasets/{dataset}/alerts` | 查询告警当前状态与事件时间线 |

应用启动时会自动注册演示数据集 `orders`（id/user_id/amount/created_at 字段、4 条规则）
与 2 条告警规则。

### 本地手工验证示例

```bash
mvn spring-boot:run
TS=$(($(date +%s)*1000))
# 正常批次
curl -s -XPOST localhost:8080/api/batches -H 'Content-Type: application/json' -d "{
  \"dataset\":\"orders\",\"batchId\":\"demo-1\",\"eventTime\":$TS,
  \"rows\":[{\"id\":\"o1\",\"user_id\":\"u1\",\"amount\":10.0}]}"
# 精确重复：200，同一结论
curl -s -XPOST localhost:8080/api/batches -H 'Content-Type: application/json' -d "{
  \"dataset\":\"orders\",\"batchId\":\"demo-1\",\"eventTime\":$TS,
  \"rows\":[{\"id\":\"o1\",\"user_id\":\"u1\",\"amount\":10.0}]}"
# 同标识不同内容：409
curl -i -XPOST localhost:8080/api/batches -H 'Content-Type: application/json' -d "{
  \"dataset\":\"orders\",\"batchId\":\"demo-1\",\"eventTime\":$TS,
  \"rows\":[{\"id\":\"o1\",\"user_id\":\"u2\",\"amount\":10.0}]}"
# 结构漂移（未知字段）：422
curl -i -XPOST localhost:8080/api/batches -H 'Content-Type: application/json' -d "{
  \"dataset\":\"orders\",\"batchId\":\"demo-2\",\"eventTime\":$TS,
  \"rows\":[{\"id\":\"o2\",\"user_id\":\"u2\",\"amount\":10.0,\"ghost\":1}]}"
# 告警状态
curl -s localhost:8080/api/datasets/orders/alerts
```

## 6. 本地测试

```bash
mvn test
```

共 25 个用例（纯 JUnit 手工装配 + 一个 Spring Boot Web 端到端测试），覆盖：

- `QualityRuleEvaluationTest`：四类规则通过/失败，失败原因可区分，固定时钟验证新鲜度；
- `BatchIdempotencyTest`：串行精确重复、同标识不同内容 409、16 线程并发相同提交只评估一次、
  12 线程并发不同内容恰好一个成功其余冲突、漂移拒绝后同 batchId 修正重试；
- `RejectionClassificationTest`：未知字段、缺失必填字段、入参字段缺失、空批次、未知数据集、
  非法规则（未知字段引用/阈值越界/取值域缺参/负值）、ruleId 重复、未知指标；
- `AlertLifecycleTest`：打开 → 合并 → 恢复 → 重开完整状态机、阈值变更后历史事件与结论可复现；
- `HistoryReproducibilityTest`：契约 v1→v2（收紧阈值+新增必填字段）后历史批次结论不漂移；
- `WebApiTest`：200/409/422 的 HTTP 归类端到端验证。

测试使用可替换的固定时钟（`Clock.fixed`），新鲜度与告警时序结论完全确定。
评估过程通过 SLF4J 打印 `[批次评估] batchId=... rule=... passed=... observed=... 依据=...`、
`[指标沉淀]`、`[告警打开/合并/恢复]` 等日志；`mvn test` 的控制台输出与
`target/surefire-reports/TEST-*.xml` 的 `system-out` 中可直接查看批次标识、命中规则与判定依据。

## 7. 关键类一览

- `contract/`：`DataContract`、`FieldSpec`、`QualityRule`、`ContractRegistry`（版本化注册表）
- `rule/`：四类 `RuleEvaluator`、`RuleEvaluatorRegistry`、`RuleValidator`（注册期合法性校验）
- `engine/`：`SchemaValidator`（结构漂移）、`QualityEvaluationEngine`（逐规则评估汇总）
- `store/`：`BatchStore`（原子幂等占位）、`MetricsStore`（指标时序幂等样本）
- `monitor/`：`AlertService`（状态机）、`AlertEvaluator`、`AlertRule/AlertEvent/AlertStatus`
- `service/QualityMonitorService`：门面，串联校验、哈希幂等、指标沉淀与告警流转
- `web/`：REST 控制器与全局异常映射（409/422）
