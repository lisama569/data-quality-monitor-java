# 本地数据质量校验与监控服务

基于 Spring Boot 的本地（进程内存储）数据质量服务：模拟数据集批次导入，按**数据契约**执行
完整性、唯一性、取值域与新鲜度校验，跟踪重复批次与结构漂移，并维护质量指标的告警状态机。
所有判定结论都是**确定性、可复现**的。

## 1. 核心概念与数据契约

| 概念 | 说明 |
| --- | --- |
| 数据契约 `DataContract` | 由 `contractId` 标识，包含一组有序字段声明 `FieldSchema(name, dataType, required)`，类型支持 `STRING / INTEGER / DECIMAL / BOOLEAN / TIMESTAMP`。 |
| 质量规则 `QualityRule` | 归属于某个契约，由 `ruleId` 标识，按版本不可变存储。修改规则会产生新版本，旧版本永久保留。 |
| 批次 `BatchSubmission` | 由调用方提供的 `batchId` 唯一标识，包含 `contractId`、`submittedAt` 与若干数据行。 |
| 批次结论 `BatchResult` | `PASSED / FAILED / REJECTED` 三态，保存内容哈希、拒绝原因、规则级结论及**规则版本快照**。 |
| 告警 `Alert` | 按规则维度（`alertKey = ruleId`）维护的状态机与完整流转记录。 |

### 规则（指标）类型与参数

| 指标 | 必需参数 | 判定口径（measured vs expected） |
| --- | --- | --- |
| `COMPLETENESS` 完整性 | `threshold ∈ [0,1]` | 有效非空（且可按契约类型解析）行数 / 总行数 `>= threshold` |
| `UNIQUENESS` 唯一性 | `threshold ∈ [0,1]` | 字段 distinct 取值数 / 总行数 `>= threshold` |
| `VALUE_DOMAIN` 取值域 | `allowedValues` **或** `numericMin/numericMax`（`threshold` 缺省为 1.0） | 落在允许集合/数值区间内的行数 / 总行数 `>= threshold`；数值区间只能用于 `INTEGER/DECIMAL` 字段 |
| `FRESHNESS` 新鲜度 | 正整数 `maxAgeSeconds` | 批次提交时间相对“评估参考时间”的滞后秒数 `<= maxAgeSeconds` |

> **新鲜度可复现**：评估参考时间取批次自带的 `submittedAt`（滞后 0 秒），
> 而不是运行时墙上时钟，因此任何历史批次无论何时复查结论都一致。

## 2. 拒绝原因 vs 失败原因（严格区分，绝不静默跳过）

- **拒绝原因（REJECTED，HTTP 422，`RejectReason`）**：批次不被接受、不进入评估、指标不累计：
  - `CONTRACT_NOT_FOUND`：数据契约不存在；
  - `EMPTY_BATCH`：批次没有任何数据行；
  - `SCHEMA_MISSING_COLUMN`：批次缺少契约声明的字段（结构漂移·缺列）；
  - `SCHEMA_UNEXPECTED_COLUMN`：批次出现契约未声明的字段（结构漂移·多列）；
  - `ILLEGAL_RULE`：当前契约下存在非法规则（阈值越界、参数缺失、字段不在契约、区间非法等），
    注册非法规则时直接返回 HTTP 400 `ILLEGAL_RULE` 并附带违例明细；
  - `UNKNOWN_METRIC`：指标代码无法识别（如 `MYSTERY_METRIC`），直接 HTTP 400 `UNKNOWN_METRIC`；
  - `DUPLICATE_ID_CONTENT_CONFLICT`：同一 `batchId` 但内容哈希不同，拒绝覆盖既有结果。
- **失败原因（FAILED，HTTP 200，`RuleOutcome.passed=false`）**：批次结构合法且被接受，
  只是某些规则指标未达标。每条结论含 `measured / expected / basis`（判定依据，含总行数、
  空值数、重复行数等明细）。

## 3. 重复批次与并发提交策略

- 提交时对批次内容计算 **SHA-256 哈希**（覆盖契约、提交时间、行数/行顺序、每行排序后的键值）。
- 每个 `batchId` 一把分段锁（`ConcurrentHashMap` + `synchronized`）：
  - **内容相同的重复/并发提交**：只有第一个请求真正评估，其余请求拿到**同一个既有结论对象**；
    批次表不新增记录、告警 `occurrenceCount` 不重复累计。
  - **同一 `batchId` 内容不同**：以 `DUPLICATE_ID_CONTENT_CONFLICT` 拒绝，既有结论与哈希保持不变。

## 4. 结构兼容策略（结构漂移）

批次列集合必须与契约字段集合**严格一致**：

- 缺列与多列分别归入 `SCHEMA_MISSING_COLUMN`、`SCHEMA_UNEXPECTED_COLUMN`（可同时出现，逐条列出）；
- 被判定结构漂移的批次直接 `REJECTED`，不会产生任何规则结论（避免在错误结构上给出误导性指标）。

> 空单元格不属于结构漂移：列存在但值为空由完整性规则评估（记为空值）。

## 5. 规则/阈值版本化与历史可复现

- 同一 `(contractId, ruleId)` 再次登记时版本号自动 +1，全部历史版本保留；
- 每个 `BatchResult` 保存评估时的**规则快照列表**与每条结论的 `ruleVersion`；
- 因此规则或阈值变更后，重新读取历史批次仍能看到当时的阈值、measured 与 PASSED/FAILED 结论；
  只有变更之后提交的新批次才按新版本评估。

## 6. 告警状态流转

```
            首次失败                 再次失败(OPEN中)          后续批次通过            再次失败
  (无告警) ───────────▶ OPEN ───────────────────────▶ OPEN ───────────────▶ RESOLVED ──────────▶ OPEN
                       openCount=1                  合并: occurrence+1     记录 OPEN->RESOLVED   openCount+1
                       occurrence=1                 不新增 openCount                            记录 RESOLVED->OPEN
```

- **重复告警合并**：OPEN 状态下连续失败只累加 `occurrenceCount` 与最近批次，不产生新告警；
- **恢复关闭**：OPEN 规则在后续批次通过时变为 `RESOLVED`；
- **恢复后重开**：RESOLVED 后再次失败会重新打开，`openCount + 1`；
- 所有流转写入 `transitions`（时间、批次、from→to、说明），可追溯。

## 7. REST API（本地存储）

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/contracts` | 登记数据契约 |
| `POST /api/rules` | 登记/更新规则（自动版本递增，注册时做合法性与未知指标校验） |
| `POST /api/batches` | 提交批次（重复返回既有结论；冲突/漂移返回 422） |
| `GET  /api/batches/{batchId}` | 查询批次结论（含规则快照与判定依据） |
| `GET  /api/batches` | 列出全部批次结论 |
| `GET  /api/alerts` | 列出全部告警及状态 |
| `POST /api/admin/reset` | 重置本地存储（仅本地验证/测试使用） |

### curl 本地验证示例

```bash
# 1. 契约
curl -s -X POST localhost:8080/api/contracts -H 'Content-Type: application/json' -d '{
  "contractId":"orders","version":1,
  "fields":[{"name":"id","dataType":"INTEGER","required":true},
            {"name":"status","dataType":"STRING","required":true}]}'

# 2. 规则：id 必须 100% 完整
curl -s -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "contractId":"orders","ruleId":"r-id","type":"COMPLETENESS",
  "fieldName":"id","threshold":1.0}'

# 3. 提交批次（含一个空 id -> FAILED，可看到失败原因与判定依据）
curl -s -X POST localhost:8080/api/batches -H 'Content-Type: application/json' -d '{
  "batchId":"b-1","contractId":"orders","submittedAt":"2026-09-24T08:00:00Z",
  "rows":[{"id":"1","status":"NEW"},{"id":"","status":"PAID"}]}'

# 4. 重复提交（内容一致，返回同一结论，指标不重复累计）
curl -s -X POST localhost:8080/api/batches -H 'Content-Type: application/json' -d '{
  "batchId":"b-1","contractId":"orders","submittedAt":"2026-09-24T08:00:00Z",
  "rows":[{"id":"1","status":"NEW"},{"id":"","status":"PAID"}]}'

# 5. 结构漂移（缺 id 列）-> 422 SCHEMA_MISSING_COLUMN
curl -i -X POST localhost:8080/api/batches -H 'Content-Type: application/json' -d '{
  "batchId":"b-2","contractId":"orders","submittedAt":"2026-09-24T08:00:00Z",
  "rows":[{"status":"NEW"}]}'

# 6. 未知指标 -> 400 UNKNOWN_METRIC
curl -i -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "contractId":"orders","ruleId":"r-x","type":"MYSTERY","fieldName":"id","threshold":1.0}'

# 7. 告警状态
curl -s localhost:8080/api/alerts
```

## 8. 本地验证方法

```bash
# 运行全部测试（单元测试 + REST 集成测试 + Spring 上下文加载）
mvn test

# 仅服务层场景测试（含 16 线程并发重复导入）
mvn test -Dtest=DataQualityServiceTest

# 仅 REST 集成测试（错误归类与 HTTP 状态码）
mvn test -Dtest=DataQualityControllerTest

# 启动服务后配合上面的 curl 手工验证
mvn spring-boot:run
```

测试日志中会打印**批次标识、命中的规则（含版本）、measured/expected 与中文判定依据**，例如：

```
批次 b-fail 规则 r-id-complete v1 [COMPLETENESS] field=id passed=false
      measured=0.7500 expected=>=0.9900 依据=总行数=4，有效非空=3，空值=1，... 不达标
批次 b-concurrent 为重复提交（内容哈希一致），返回既有结论 status=FAILED，指标不重复累计
```

### 覆盖的场景

- 并发重复导入（16 线程同一 `batchId`）：仅一份批次结论、告警仅累计一次；
- 顺序重复提交幂等；同一 `batchId` 内容不同被明确拒绝且不覆盖；
- 结构漂移：缺列 / 多列给出不同拒绝原因；
- 非法规则（阈值越界、取值域参数缺失、字段不在契约）与未知指标，均不静默跳过；
- 完整性与唯一性失败原因可区分；
- 告警 打开 → 合并 → 恢复 → 重新打开 完整流转；
- 阈值变更产生新版本后，历史批次结论（含 v1 快照）仍可复现。

## 9. 代码结构

```
model/      契约、规则、批次、结论、拒绝原因、告警等领域模型
exception/  DataQualityException 及三类可区分异常（拒绝/非法规则/未知指标）
storage/    LocalStorage：并发安全的进程内存储（规则按版本保存）
service/    ContentHasher / RuleValidator / QualityEvaluator / AlertService / DataQualityService
web/        REST 控制器与请求 DTO，按异常类型返回不同 HTTP 错误
```
