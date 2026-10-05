# 本地数据质量校验与监控服务

基于 Spring Boot 的本地（进程内存储）数据质量服务：模拟数据集批次导入，按**数据契约**执行
完整性、唯一性、取值域与新鲜度校验，跟踪重复批次与结构漂移，并维护质量指标的告警状态机。
新鲜度按数据自身的**业务时点**到**判定时刻**的滞后计算，支持迟到/回填批次的业务时序保护，
并支持按任意历史判定时刻做**只读复盘**。
所有判定结论都是**确定性、可复现**的。

## 1. 核心概念与数据契约

| 概念 | 说明 |
| --- | --- |
| 数据契约 `DataContract` | 由 `contractId` 标识，包含一组有序字段声明 `FieldSchema(name, dataType, required)`，类型支持 `STRING / INTEGER / DECIMAL / BOOLEAN / TIMESTAMP`。 |
| 质量规则 `QualityRule` | 归属于某个契约，由 `ruleId` 标识，按版本不可变存储。修改规则会产生新版本，旧版本永久保留。 |
| 批次 `BatchSubmission` | 由调用方提供的 `batchId` 唯一标识，包含 `contractId`、`submittedAt`（到达/提交时间）、`businessTime`（数据自身业务时点，缺省回退 `submittedAt`）与若干数据行。 |
| 判定时刻 `judgedAt` | 新鲜度判定的“现在”。缺省取服务端系统当前时间；调用方可显式指定（复盘、测试、补算）。 |
| 批次结论 `BatchResult` | `PASSED / FAILED / REJECTED` 三态，保存内容哈希、拒绝原因、规则级结论、**规则版本快照**，以及落库时的 `businessTime` 与 `judgedAt`。 |
| 告警 `Alert` | 按规则维度（`alertKey = ruleId`）维护的状态机、业务时点水位与完整流转记录。 |
| 复盘报告 `ReplayReport` | 指定判定时刻下对历史批次新鲜度的**只读派生**查看结果（`ReplayBatchView` 列表），不落库。 |

### 规则（指标）类型与参数

| 指标 | 必需参数 | 判定口径（measured vs expected） |
| --- | --- | --- |
| `COMPLETENESS` 完整性 | `threshold ∈ [0,1]` | 有效非空（且可按契约类型解析）行数 / 总行数 `>= threshold` |
| `UNIQUENESS` 唯一性 | `threshold ∈ [0,1]` | 字段 distinct 取值数 / 总行数 `>= threshold` |
| `VALUE_DOMAIN` 取值域 | `allowedValues` **或** `numericMin/numericMax`（`threshold` 缺省为 1.0） | 落在允许集合/数值区间内的行数 / 总行数 `>= threshold`；数值区间只能用于 `INTEGER/DECIMAL` 字段 |
| `FRESHNESS` 新鲜度 | 正整数 `maxAgeSeconds` | 数据自身**业务时点**相对**判定时刻**的滞后秒数 `<= maxAgeSeconds` |

### 新鲜度口径：业务时点与判定时刻（重点）

- **业务时点 `businessTime`**：数据自身描述的业务发生时刻，由批次提交时显式携带；
  缺省回退到 `submittedAt`（到达时间），以兼容历史调用方。
- **判定时刻 `judgedAt`**：新鲜度判定中的“现在”。批次提交时缺省取服务端系统当前时间，
  也可通过 `POST /api/batches?judgedAt=...` 或服务层 `submit(submission, judgedAt)` 显式指定。
- **滞后** = `judgedAt - businessTime`。业务时点晚于判定时刻（例如用更早的判定时刻复盘较新批次）
  时滞后为负，视为在窗口内。
- **确定性**：判定是纯函数，相同的业务时点与判定时刻，无论什么时候算、算多少次，
  `measured / expected / basis` 与 PASSED/FAILED 结论都逐字一致，不随运行时刻漂移。
  判定时刻不参与批次内容哈希——重复提交永远返回首次落库的既有结论，即使两次请求带了不同判定时刻。
- 历史行为修正：旧实现把参考时间误取为 `submittedAt`（滞后恒为 0，旧批次也判新鲜），
  现已改为按业务时点到判定时刻的滞后计算。

## 2. 迟到与回填策略（业务时序保护）

允许补交业务时点远早于现在的批次：该判不新鲜就判 `FAILED` 并进入告警流程，不因“晚到”而豁免。
为避免迟到数据来回翻转告警，每条告警维护一个**业务时点水位** `watermarkBusinessTime`
（已处理批次中最晚的业务时点，无论该批次通过还是越界都会推进水位）。

**水位比较口径**：只有业务时点**严格更早**（`<` 水位）的批次才算迟到；业务时点**等于**水位的
批次不算迟到——业务时点常按整天/整点取整，同一时点内会连续到达多个批次，它们必须照常参与
打开、合并、恢复、重开。

- **迟到批次不翻转状态**：批次业务时点严格早于水位时——
  - 已 `RESOLVED` 的告警不会被更旧的越界批次重新拉成 `OPEN`；
  - 已 `OPEN` 的告警不会被更旧的通过批次提前关闭；
  - `occurrenceCount / openCount / lastBatchId` 均不被迟到批次改动，水位也不回退；
  - 无论该迟到批次自身判越界还是通过，都仅追加一条**同态的“迟到抑制”流转记录**
    （状态不变，from==to，note 含“抑制”）留痕，保证可追溯。
- **同一时点照常流转**：业务时点等于水位的批次与正常批次完全同权——越界则首次打开/合并/重开
  并累计次数，通过则恢复关闭，最近批次正常更新。
- **更晚数据照常流转**：业务时点严格晚于水位的批次，越界则按原规则首次打开/合并/重开，
  通过则恢复关闭，原有合并、恢复、重开语义与历史记录完全保留。
- **并发/重复提交**：同一 `batchId` 的分段锁保证迟到批次并发提交只真实评估一次，
  抑制留痕也只产生一条；重复内容返回既有结论，计数不重复累计、结论不漂移；
  不同 `batchId` 的迟到批次并发提交时，告警状态机在锁内串行，每个真实批次恰好留下
  一条抑制记录，不多不少。
- 水位只增不减，随 `Alert` 一起保存在进程内存储中，`reset` 时清空。

> 历史行为修正：旧实现把“业务时点不晚于水位（`<=`）”都当作迟到抑制，导致同一业务时点的
> 后续批次不再累计越界次数、通过也无法关闭已打开的告警、再越界也无法重开，告警卡在打开状态。
> 现已修正为“严格更早（`<`）才抑制”，同一时点照常流转。

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
- **迟到保护**：只有业务时点**严格更早**于水位的迟到/回填批次才被抑制（见第 2 节），
  仅追加同态抑制记录；业务时点**等于**水位的批次照常参与上述全部流转，更晚的数据亦不受限制；
- 所有流转写入 `transitions`（时间、批次、from→to、说明），可追溯。

## 7. 只读复盘（按指定判定时刻重算）

`GET /api/contracts/{contractId}/replay?judgedAt=<ISO-8601>`（服务层 `service.replay(contractId, judgedAt)`）

- **用途**：给定一个过去的判定时刻，对该契约下**已落库批次**按当时口径重算新鲜度结论与越界情况，
  用于事后追溯。
- **只读范围**：复盘是纯派生计算，**不写入、不修改**任何批次结论（PASSED/FAILED 原样保留在
  `carriedStatus`）、告警状态、计数与流转历史；不产生告警、不推进水位。
- **规则版本口径**：重算使用每个批次落库时保存的**规则版本快照**，复盘结果就是“当时版本的规则
  在该判定时刻下会给出的结论”，与规则后续是否改版无关。
- **重算范围**：只重算 `FRESHNESS` 规则——完整性/唯一性/取值域只取决于数据行、与判定时刻无关，
  其落库结论不随复盘时刻变化，因此不出现在复盘明细中。
- **一致性**：相同 (契约, 判定时刻) 复盘任意次结果完全一致；明细固定按
  `(业务时点, batchId)` 排序，与调用时间无关。`ReplayReport` / `ReplayBatchView` 为 record、
  `RuleOutcome` 实现了值相等（`equals/hashCode`），因此两次复盘返回的报告**可直接 `equals`
  判等**（`hashCode` 亦一致），便于去重与比对；每次复盘返回独立的报告对象，互不影响。
- **性能**：单遍流式计算，复杂度 O(批次数 × 每批快照规则数)，500 批次规模实测毫秒级
  （测试断言 3 秒内完成）。被 `REJECTED` 的批次未进入评估、没有业务时点，不纳入复盘。

## 8. REST API（本地存储）

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/contracts` | 登记数据契约 |
| `POST /api/rules` | 登记/更新规则（自动版本递增，注册时做合法性与未知指标校验） |
| `POST /api/batches?judgedAt=` | 提交批次；`businessTime` 在请求体中携带，`judgedAt` 可选（缺省取当前时间）；重复返回既有结论；冲突/漂移返回 422 |
| `GET  /api/contracts/{contractId}/replay?judgedAt=` | **只读复盘**：按指定判定时刻重算契约下批次新鲜度（不回写任何状态） |
| `GET  /api/batches/{batchId}` | 查询批次结论（含业务时点、判定时刻、规则快照与判定依据） |
| `GET  /api/batches` | 列出全部批次结论 |
| `GET  /api/alerts` | 列出全部告警及状态（含业务时点水位） |
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

# 8. 新鲜度规则：业务时点到判定时刻滞后不超过 1 小时
curl -s -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "contractId":"orders","ruleId":"r-fresh","type":"FRESHNESS","maxAgeSeconds":3600}'

# 9. 迟到/回填批次：业务时点 00:00、03:00 才判定 -> 滞后 3h，FAILED 并进告警
curl -s -X POST 'localhost:8080/api/batches?judgedAt=2026-10-01T03:00:00Z' \
  -H 'Content-Type: application/json' -d '{
  "batchId":"b-late","contractId":"orders",
  "submittedAt":"2026-10-01T03:00:00Z","businessTime":"2026-10-01T00:00:00Z",
  "rows":[{"id":"1","status":"NEW"}]}'

# 10. 只读复盘：在 03:00 口径下重算该契约全部批次的新鲜度（不改历史）
curl -s 'localhost:8080/api/contracts/orders/replay?judgedAt=2026-10-01T03:00:00Z'
```

## 9. 本地验证方法

```bash
# 运行全部测试（单元测试 + REST 集成测试 + Spring 上下文加载）
mvn test

# 仅新鲜度/迟到/复盘专项测试（13 个场景）
mvn test -Dtest=FreshnessFeatureTest

# 仅服务层场景测试（含 16 线程并发重复导入）
mvn test -Dtest=DataQualityServiceTest

# 仅 REST 集成测试（错误归类、判定时刻与复盘接口）
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

- **新鲜度新口径**：业务时点在窗口内的批次照常通过；业务时点远早于判定时刻的迟到批次明确 FAILED 并进告警；
- **判定可复现**：相同业务时点+判定时刻，跨运行时刻重复计算/重复提交，结论与判定依据逐字一致，不漂移；
- **同一业务时点连续流转**：同一时点连续到达的批次照常参与 打开 → 合并 → 恢复 → 重开，
  越界累计次数、通过恢复关闭、再越界重新打开，不产生抑制记录；
- **迟到抑制边界**：业务时点早 1 秒才抑制（越界与通过都抑制、仅留痕），等于水位照常流转，晚 1 秒照常重开；
- **迟到乱序不倒退**：已 RESOLVED 的告警不被更旧业务时点的越界批次重开，已 OPEN 的告警不被更旧通过批次关闭；
  业务时点确实更晚且越界的数据仍会重开；迟到抑制有留痕、不计数；
- **迟到并发/重复提交**：12 线程同一迟到批次只落一份结论、只产生一条抑制记录；
  10 线程不同 batchId 的迟到批次各留恰好一条抑制记录、计数零累计；
  并发后再顺序重复提交，结论不漂移、抑制记录仍只有一条；
- **只读复盘**：重算结论正确、同一判定时刻重复复盘报告可直接 `equals` 判等（`hashCode` 一致），
  批次结论/告警状态/计数/流转历史/水位零改动；500 批次规模 3 秒内完成；
- 并发重复导入（16 线程同一 `batchId`）：仅一份批次结论、告警仅累计一次；
- 顺序重复提交幂等；同一 `batchId` 内容不同被明确拒绝且不覆盖；
- 结构漂移：缺列 / 多列给出不同拒绝原因；
- 非法规则（阈值越界、取值域参数缺失、字段不在契约）与未知指标，均不静默跳过；
- 完整性与唯一性失败原因可区分；
- 告警 打开 → 合并 → 恢复 → 重新打开 完整流转；
- 阈值变更产生新版本后，历史批次结论（含 v1 快照）仍可复现。

## 10. 兼容范围（已有批次与历史规则版本）

- **批次提交**：`BatchRequest`/`BatchSubmission` 的旧构造方式保持可用；不带 `businessTime` 时
  自动回退 `submittedAt`，旧调用方无需改动。
- **历史规则版本**：`FRESHNESS` 规则参数不变（仍是 `maxAgeSeconds`），无需迁移即可用于新口径；
  历史批次落库结论与规则快照保持原样，旧批次没有 `businessTime/judgedAt` 回显字段（为 null），
  且不纳入复盘（复盘只覆盖新口径落库、带业务时点的批次）。
- **告警数据**：`Alert` 新增 `watermarkBusinessTime` 字段；进程内存储无历史持久化文件，
  重启/重置后重新建立水位。迟到保护对所有规则类型一视同仁（水位按批次业务时点推进，
  与规则类型无关）。
- **接口兼容**：`POST /api/batches` 的 `judgedAt` 与请求体 `businessTime` 均为可选；
  复盘接口为新增端点，不影响既有接口。

## 11. 代码结构

```
model/      契约、规则、批次、结论、拒绝原因、告警、复盘报告(ReplayReport/ReplayBatchView)等领域模型
exception/  DataQualityException 及三类可区分异常（拒绝/非法规则/未知指标）
storage/    LocalStorage：并发安全的进程内存储（规则按版本保存）
service/    ContentHasher / RuleValidator / QualityEvaluator / AlertService / ReplayService / DataQualityService
web/        REST 控制器与请求 DTO，按异常类型返回不同 HTTP 错误
```
