# 本地数据质量校验与监控服务

基于 Spring Boot 的本地（进程内存储）数据质量服务：模拟数据集批次导入，按**数据契约**执行
完整性、唯一性、取值域与新鲜度校验，跟踪重复批次与结构漂移，并维护质量指标的告警状态机。
所有判定结论都是**确定性、可复现**的。

## 1. 核心概念与数据契约

| 概念 | 说明 |
| --- | --- |
| 数据契约 `DataContract` | 由 `contractId` 标识，包含一组有序字段声明 `FieldSchema(name, dataType, required)`，类型支持 `STRING / INTEGER / DECIMAL / BOOLEAN / TIMESTAMP`。 |
| 质量规则 `QualityRule` | 归属于某个契约，由 `ruleId` 标识，按版本不可变存储。修改规则会产生新版本，旧版本永久保留。 |
| 批次 `BatchSubmission` | 由调用方提供的 `batchId` 唯一标识，包含 `contractId`、`submittedAt`、可选的 `businessTime`（业务时点）与 `evaluationTime`（判定时刻），以及若干数据行。 |
| 批次结论 `BatchResult` | `PASSED / FAILED / REJECTED` 三态，保存内容哈希、拒绝原因、规则级结论、**规则版本快照**，以及落库的 `businessTime` 与 `evaluatedAt`（判定时刻）。 |
| 告警 `Alert` | 按规则维度（`alertKey = ruleId`）维护的状态机与完整流转记录。 |

### 规则（指标）类型与参数

| 指标 | 必需参数 | 判定口径（measured vs expected） |
| --- | --- | --- |
| `COMPLETENESS` 完整性 | `threshold ∈ [0,1]` | 有效非空（且可按契约类型解析）行数 / 总行数 `>= threshold` |
| `UNIQUENESS` 唯一性 | `threshold ∈ [0,1]` | 字段 distinct 取值数 / 总行数 `>= threshold` |
| `VALUE_DOMAIN` 取值域 | `allowedValues` **或** `numericMin/numericMax`（`threshold` 缺省为 1.0） | 落在允许集合/数值区间内的行数 / 总行数 `>= threshold`；数值区间只能用于 `INTEGER/DECIMAL` 字段 |
| `FRESHNESS` 新鲜度 | 正整数 `maxAgeSeconds` | 批次**业务时点**相对**判定时刻**的滞后秒数 `<= maxAgeSeconds` |

### 新鲜度口径：业务时点与判定时刻

- **业务时点 `businessTime`**：数据自身的业务时间（如订单发生日、账期），提交批次时携带；
  缺省回退为 `submittedAt`，未升级的调用方行为不变。
- **判定时刻 `evaluationTime`**：本次评估新鲜度的“当前时间”。缺省取**系统当前时间**；
  调用方也可显式指定（复盘历史、写测试都必须用它）。
- **滞后 = 判定时刻 − 业务时点**（秒），滞后 `<= maxAgeSeconds` 判为新鲜。
  业务时点晚于判定时刻（未来数据）时滞后为负，恒在窗口内。
- **可复现保证**：业务时点与判定时刻都会随 `BatchResult` 落库；只要这两个时刻相同，
  无论何时重算、算多少次，结论与判定依据（`basis` 中含两个时刻与滞后秒数）完全一致，
  不随运行时刻漂移。
- **重复提交哈希**：内容哈希覆盖 `businessTime`；`evaluationTime` 仅在显式指定时计入哈希
  （缺省取系统时间不计入，否则缺省提交的重复判定会永远冲突）。同一 `batchId` 以不同
  显式判定时刻提交属于不同内容，按 `DUPLICATE_ID_CONTENT_CONFLICT` 拒绝。

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

- 提交时对批次内容计算 **SHA-256 哈希**（覆盖契约、提交时间、业务时点、行数/行顺序、
  每行排序后的键值；显式指定的判定时刻也计入，见“新鲜度口径”）。
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

### 迟到与回填策略（告警业务时点水位）

真实导入中业务时间与到达时间经常乱序：回填批次的业务时点可能远早于已处理的数据。
规则是“**业务时点晚的数据说了算**”：

- 每条告警维护 `businessWatermark`：最近一次驱动状态流转的批次业务时点；
- **迟到批次**（业务时点早于水位）：批次自身照常评估、照常落库（该 FAILED 就 FAILED），
  但**不驱动告警流转、不累计 `occurrenceCount`、不追加流转历史**——
  已经因为更晚的数据恢复或关闭的告警，不会被迟到的旧数据重新拉成 OPEN；
- **更晚的数据**（业务时点不早于水位）：照常走打开/合并/恢复/重开，
  确实越界就重新打开，`openCount + 1`；
- 迟到批次以并发或重复提交方式进入时同样只评估一次（分段锁 + 内容哈希），
  不重复累计、结论不漂移。

## 7. 新鲜度只读复盘

`GET /api/contracts/{contractId}/freshness-review?at=<ISO-8601 时刻>`（`at` 缺省取系统当前时间）
以指定时刻为判定时刻，对该契约下全部已评估批次（`REJECTED` 除外）按其落库的
**业务时点**与**规则版本快照**重算新鲜度结论与越界情况，用于事后追溯。

- **只读**：只产出派生视图（`FreshnessReview`），不改写批次结论、告警状态与流转历史；
- **一致性**：结果完全由落库数据与 `at` 决定，不含生成时刻等运行期字段，
  同一 `at` 重复复盘结果逐字节一致；
- **口径**：复用与在线评估完全相同的判定函数（滞后 = `at` − 业务时点，
  阈值取批次当时的规则快照），在线结论与复盘结论不会互相矛盾；
- **规模**：单次复盘为内存中线性扫描，数百个批次可直接算完（有 300 批次的自动化用例）。

## 8. REST API（本地存储）

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/contracts` | 登记数据契约 |
| `POST /api/rules` | 登记/更新规则（自动版本递增，注册时做合法性与未知指标校验） |
| `POST /api/batches` | 提交批次（可带 `businessTime`/`evaluationTime`；重复返回既有结论；冲突/漂移返回 422） |
| `GET  /api/batches/{batchId}` | 查询批次结论（含规则快照、业务时点、判定时刻与判定依据） |
| `GET  /api/batches` | 列出全部批次结论 |
| `GET  /api/alerts` | 列出全部告警及状态 |
| `GET  /api/contracts/{contractId}/freshness-review?at=...` | 新鲜度只读复盘（`at` 缺省取系统当前时间） |
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

# 8. 新鲜度：登记窗口 1 小时，提交业务时点在两天前的回填批次 -> FAILED 并打开告警
curl -s -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "contractId":"orders","ruleId":"r-fresh","type":"FRESHNESS","maxAgeSeconds":3600}'
curl -s -X POST localhost:8080/api/batches -H 'Content-Type: application/json' -d '{
  "batchId":"b-backfill","contractId":"orders",
  "submittedAt":"2026-10-04T08:00:00Z","businessTime":"2026-10-02T08:00:00Z",
  "rows":[{"id":"1","status":"NEW"}]}'

# 9. 指定判定时刻提交（复盘/测试口径，结论不随运行时刻漂移）
curl -s -X POST localhost:8080/api/batches -H 'Content-Type: application/json' -d '{
  "batchId":"b-fixed","contractId":"orders",
  "submittedAt":"2026-10-04T08:00:00Z","businessTime":"2026-10-04T07:00:00Z",
  "evaluationTime":"2026-10-04T07:30:00Z",
  "rows":[{"id":"2","status":"PAID"}]}'

# 10. 只读复盘：以过去某个判定时刻重算该契约下各批次的新鲜度越界情况
curl -s 'localhost:8080/api/contracts/orders/freshness-review?at=2026-10-04T08:00:00Z'
```

## 9. 本地验证方法

```bash
# 运行全部测试（单元测试 + REST 集成测试 + Spring 上下文加载）
mvn test

# 仅服务层场景测试（含 16 线程并发重复导入）
mvn test -Dtest=DataQualityServiceTest

# 仅新鲜度/迟到乱序/只读复盘测试（含 16 线程并发迟到提交与 300 批次复盘）
mvn test -Dtest=FreshnessTimelinessTest

# 仅 REST 集成测试（错误归类、HTTP 状态码与复盘端点）
mvn test -Dtest=DataQualityControllerTest

# 启动服务后配合上面的 curl 手工验证
mvn spring-boot:run
```

测试日志中会打印**批次标识、业务时点、判定时刻、命中的规则（含版本）、measured/expected
与中文判定依据**，例如：

```
批次 b-fail 规则 r-id-complete v1 [COMPLETENESS] field=id passed=false
      measured=0.7500 expected=>=0.9900 依据=总行数=4，有效非空=3，空值=1，... 不达标
批次 b-stale 规则 r-fresh v1 [FRESHNESS] passed=false measured=172800s expected=<=3600s
      依据=业务时点=2026-10-02T08:00:00Z，判定时刻=2026-10-04T08:00:00Z，滞后=172800 秒，... 超出新鲜度窗口
批次 b-concurrent 为重复提交（内容哈希一致），返回既有结论 status=FAILED，指标不重复累计
```

### 覆盖的场景

- 并发重复导入（16 线程同一 `batchId`）：仅一份批次结论、告警仅累计一次；
- 顺序重复提交幂等；同一 `batchId` 内容不同被明确拒绝且不覆盖；
- 结构漂移：缺列 / 多列给出不同拒绝原因；
- 非法规则（阈值越界、取值域参数缺失、字段不在契约）与未知指标，均不静默跳过；
- 完整性与唯一性失败原因可区分；
- 告警 打开 → 合并 → 恢复 → 重新打开 完整流转；
- 阈值变更产生新版本后，历史批次结论（含 v1 快照）仍可复现；
- 新鲜度：新鲜批次通过、陈旧/回填批次明确失败并打开告警；
- 显式判定时刻下结论与判定依据可复现，重复提交返回完全一致结论；
- 迟到乱序：已恢复告警不被业务时点更早的迟到批次拉回，更晚越界数据仍重开；
- 迟到批次 16 线程并发重复提交：只评估一次、告警不重复累计、状态不倒退；
- 只读复盘：按指定时刻重算越界情况，不改写批次结论与告警历史，重复复盘结果一致，
  300 批次规模一次算完。

## 10. 兼容范围

- **未携带 `businessTime` 的旧调用方**：业务时点回退为 `submittedAt`，其余行为不变；
- **未携带 `evaluationTime` 的旧调用方**：判定时刻取系统当前时间。注意这与旧版
  “参考时间恒等于 `submittedAt`（滞后恒 0）”的行为**有意不同**——旧行为下业务上很旧的
  批次也会被判新鲜，本次修正后按真实滞后判定；
- **历史规则版本**：复盘按各批次落库的规则快照取 `maxAgeSeconds`，规则变更不影响
  历史批次的复盘口径；评估与复盘共用同一判定函数；
- **存储**：进程内存储随重启清空，无持久化数据迁移问题；`Alert` 新增的水位字段
  只影响新发生的流转，既有打开/恢复/重开语义不变。

## 11. 代码结构

```
model/      契约、规则、批次、结论、拒绝原因、告警等领域模型
exception/  DataQualityException 及三类可区分异常（拒绝/非法规则/未知指标）
storage/    LocalStorage：并发安全的进程内存储（规则按版本保存）
service/    ContentHasher / RuleValidator / QualityEvaluator / AlertService / DataQualityService
web/        REST 控制器与请求 DTO，按异常类型返回不同 HTTP 错误
```
