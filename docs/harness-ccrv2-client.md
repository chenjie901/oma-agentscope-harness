# Java Harness 的 CCRv2 客户端接入

本文描述 `aca/oma-harness-core` 作为 CCRv2 worker 客户端时，对服务端 `internal/codesessions` 契约的覆盖范围、恢复流程、控制面、工具审批与本地持久化行为。协议语义以服务端代码与 `oma-brain-dsh` 为准，本文只说明 harness 侧如何落地。

## 1. 覆盖范围

| 能力 | 端点 | 模块 |
|---|---|---|
| 领取 / 释放 | `POST /v1/code/brain/claims`、`.../{id}/release` | `claim/BrainClaimClient` |
| 注册 | `POST .../worker/register` | `transport/WorkerTransport` |
| 事件流 | `GET .../worker/events/stream` | `transport/WorkerTransport` + `SseParser` |
| 事件上行 | `POST .../worker/events` | `transport/WorkerTransport` |
| 投递回执 | `POST .../worker/events/delivery` | `transport/WorkerTransport` |
| 状态写 / 读 | `PUT / GET .../worker` | `transport/WorkerTransport` + `protocol/WorkerMetadata` |
| 心跳 | `POST .../worker/heartbeat` | `transport/WorkerTransport` |
| transcript 写 / 读 | `POST / GET .../worker/internal-events` | `persistence/InternalEventStore` + `persistence/TranscriptStore` |
| 子线程状态 | `GET .../worker/subagent-threads/{thread_id}` | `transport/WorkerTransport` |
| 控制面 | `control_request` / `control_response` / `keep_alive` | `protocol/ControlPlane` |
| 工具调用 | `POST / GET / .../cancel` on `/v2/ccr-sessions/{id}/threads/{tid}/tool-calls` | `tools/OmaToolClient` + `tools/ToolRunner` |
| web 工具 | `POST .../web/search`、`.../web/fetch` | `tools/OmaWebClient` |
| 端口 URL | `POST .../port-url` | `tools/OmaPortClient` |
| 会话上下文 | `GET /v2/sessions/{id}`、`/skills` | `claim/SessionContext`、`resources/OmaResourceLoader` |

不在 harness 职责内：`sign-commit`、`/v1/code/upstreamproxy/*`、`POST/PUT /v1/code/sessions/{id}`、`/v1/code/sandbox/tool-calls/*`。它们分别由沙箱内的 environment-manager、relay 与 sandbox manager 使用。

## 2. 会话恢复

`claim` 成功后、`agent.start` 之前完成恢复，保证首轮对话就能看到历史。

```mermaid
sequenceDiagram
    participant W as OmaWorker
    participant S as OMA Server
    participant L as TranscriptStore
    participant A as OmaAgent
    W->>S: POST /worker/register
    S-->>W: worker_epoch
    W->>S: GET /worker?worker_epoch=N
    S-->>W: worker.external_metadata
    W->>L: 读取本地 transcript.jsonl
    W->>S: GET /worker/internal-events?worker_epoch=N
    S-->>W: {data, next_cursor}
    Note over W: cursor 非空则继续翻页，最多 10 页
    W->>W: 双向前缀校验（uuid + payload 深比较）
    W->>L: 远端更长时把差额补写本地并 fsync
    W->>A: restoredMessages
    A->>A: AgentState.contextMutable().addAll(最近 40 条)
```

约束：

- 恢复只回放 `payload.type` 为 `user` / `assistant` 的文本消息。工具调用与结果由 OMA 公共事件承担，重复回放会与 `agent.tool_use` 冲突。
- 恢复完成后把已见 `payload.uuid` 写入去重集合，避免本轮再次上报同一条；本地存在而远端缺失的尾部会重新进入上传队列。
- `external_metadata.model` 存在时优先于 `session_context.model`，保证 `set_model` 在重启后仍然生效。
- 注入依赖 `stateStore == null`。若后续接入 `AgentStateStore`，需改为在 store 层合并，不能再用 `contextMutable`。
- 本地与远端历史互不为前缀时抛 `Local and remote log histories diverged`，`recover()` 不再降级，worker 释放 claim 后由 `OmaWorker` 重新领取。

## 3. 控制面

```mermaid
sequenceDiagram
    participant S as OMA Server
    participant H as OmaHarness
    participant C as ControlPlane
    participant A as OmaAgent
    S->>H: control_request(subtype=initialize)
    H->>C: handle()
    C-->>S: control_response(success)
    S->>H: control_request(subtype=set_model, model=m)
    H->>C: handle()
    C->>S: PUT /worker {external_metadata.model}
    C->>A: applyModel(m)
    C-->>S: control_response(success)
    S->>H: control_response(request_id, behavior=allow)
    H->>C: handle()
    C->>C: 完成 pending 审批
```

- `initialize`：直接回 `success`，不回填 system prompt。
- `set_model`：先 `PUT /worker` 落 `external_metadata.model`，成功后再切换 `OmaChatModel`，最后回 `success`。任何一步失败都回 `error`，避免服务端与 harness 模型状态分叉。
- 上行审批：`control_request` 只发 `subtype=can_use_tool`，携带 `tool_name`、`tool_use_id` 与原始 `input`；`control_response` 解析 `behavior`、`updatedInput`、`message`/`denyMessage`、`externalResult`。
- 审批超时（5 分钟）按 `deny` 收尾并从等待表移除；中断时 `interruptApprovals` 让所有待审批调用立即以 `Tool call interrupted.` 结束。
- 中断：`control_request.subtype=interrupt` 与顶层 `user.interrupt` 都按中断处理，必须在 SSE 读循环线程立即执行，不能排进轮次队列。

## 4. 工具下发与审批

工具集合完全由 `session_context` 决定，harness 不再维护写死的工具列表：

```mermaid
flowchart LR
    A[runtime_tools] --> B[ToolCatalog.enabled]
    C[runtime_tool_permissions] --> B
    B --> D[OmaToolkit 注册]
    D --> E[模型 tool_use]
    E --> F{权限}
    F -->|allow| G[ToolRunner 分发]
    F -->|ask / deny / 缺省| H[can_use_tool 审批]
    H -->|deny| I[denyMessage 作为工具错误]
    H -->|externalResult| J[直接作为工具输出]
    H -->|updatedInput| G
    G --> K[SANDBOX / WEB / PORT]
```

| 模型可见名 | `runtime_tools` 名字 | 权限键 | 类型 | 远程名 |
|---|---|---|---|---|
| `Bash` `Read` `Write` `Edit` `Grep` `Glob` | 同名 | `bash` `read` `write` `edit` `grep` `glob` | SANDBOX | `bash` `read` `write` `edit` `grep` `glob` |
| `web_search` `web_fetch` | 同名 | `web_search` `web_fetch` | WEB | `search` `fetch` |
| `get_port_url` | `get_port_url` | `get_port_url` | PORT | `port-url` |
| `AskUserQuestion` | `ask_user_question` | `ask_user_question` | QUESTION | — |

规则：

- 名称匹配对大小写、下划线与连字符不敏感，因此 `AskUserQuestion` 能命中 `ask_user_question`。
- `runtime_tools` 为空表示服务端没有下发 `agent_toolset_20260401`，此时不注册任何工具；权限为 `deny` 的工具同样不进入模型可见列表。
- 权限缺省为 `ask`。`allow` 直接执行；其他情况一律发 `can_use_tool`，因此 `deny` 与「未配置」在 harness 侧行为一致。
- `updatedInput` 与原始入参不同时，结果前追加 `OMA approval changed <tool> arguments to <json>. The returned result is for these approved arguments.`。
- `AskUserQuestion` 只接受 `externalResult`：缺少时报 `Missing user question result`；答案要求数量与 id 一一对应、`selected` 去重且属于 `options[].label`、单选不允许同时给选项与自定义答案。
- `web_search` / `web_fetch` 超时 35s；响应含 `full_content` 时通过 `Write` 工具把 `{...page, content: full_content, truncated: false}` 写入 `/tmp/tool-outputs/mcp_<uuid>.txt` 并回填 `output_file`，写失败只留预览并给出 `storage_error`。
- `get_port_url` 先校验 `1 <= port <= 65535`，再以 10s 超时请求服务端；错误文案与服务端一致（`OMA port URL HTTP <status>: <message>`）。
- `user.message.oma_refresh_tools == true` 时，回合开始前用 `X-OMA-Session-Thread-ID` 重新拉取 `GET /v2/sessions/{id}`，同步 `runtime_tools` 与权限：新增工具注册、消失工具移除。
- 每次模型请求前都会重新拉取 `runtime_tool_permissions`（对齐 dsh 的 `agent/request` 钩子），因此前端修改权限后下一轮即生效；`runtime_tools` 只在 `oma_refresh_tools` 时同步，避免模型可见工具在回合中途变化。
- 会话上下文解析 fail-closed：`runtime_tools` 元素非字符串、`runtime_tool_permissions` 值不在 `allow|ask|deny` 内都视为协议错误，直接终止当前会话。

## 5. 投递回执状态机

```mermaid
stateDiagram-v2
    [*] --> received: 帧到达
    received --> processing: 进入 turn
    processing --> processing: 每 60s 续期
    processing --> processed: 轮次结束
    processed --> [*]
```

`processed` 必须先于 `result` 事件发送。原因见 `internal/db/session_event_mapper.xml` 的 `HasInputBlockingIdle`：只要还存在 `processed_at IS NULL` 的 `user.message`，服务端就会丢弃 idle 状态转换，且不返回错误。

中断回合的 `result` 仍上报 `subtype=success`、`is_error=false`，与 dsh `session.ts` 的 `afterRun` 分支一致；服务端事件映射只看 `is_error`。

`agent.message` / `agent.thinking` 必须携带模型代理返回的 `Request-Id`（即 `model_request_start_id`）与 `uuid`。服务端用 `request_id + 文本摘要` 对 proxy 与 worker 两条回声去重；缺少这两个字段会让前端把同一段回答显示两次。

## 6. 本地 transcript 与远端同步

```mermaid
flowchart TD
    A[record payload] --> B[append transcript.jsonl + fsync]
    B --> C{积压 >= 50}
    C -->|是| D[POST internal-events]
    C -->|否| E[等待轮次结束 flush]
    D -->|失败| F[100ms 起指数退避, 上限 30s, 最多 10 次]
    F -->|仍失败| G[标记 terminal, 关闭 harness, 由 OmaWorker 重新 claim]
    F -->|成功| H[清空积压]
```

- 路径：`${OMA_HARNESS_STATE_DIR:-~/.oma-harness}/sessions/{session_id}/transcript.jsonl`。
- 格式：首行 header（`version`、`session_id`、`created_at`），后续每行 `{seq, type, uuid, payload}`；`seq` 从 0 单调递增。
- 写入：`FileChannel` 追加后 `force(true)`；写入失败截断回写前长度，避免半行破坏 `seq` 连续性；启动时修复缺失换行的尾部。
- 顺序：先本地落盘，再上报远端。远端失败不影响本地，但会阻塞本轮确认直至重试耗尽。
- 校验：本地与远端逐条比较 `uuid` 与 `payload`（对象键序无关的深比较）；只有互为前缀时才允许补齐或补传。

## 7. 与 oma-brain-dsh 的差异

| 项 | dsh | Java harness |
|---|---|---|
| transcript 存储 | 原生动 JSONL（`dsh_log` session 事件 + 前缀校验 + 锁） | 本地 JSONL（`{seq,type,uuid,payload}`）+ 远端前缀校验，无跨进程锁 |
| 压缩 | 有 compaction | 无，恢复只截取最近 40 条 |
| 工具来源 | `launch.tools` / `runtime_tools` | 同源，`ToolCatalog` 按 `runtime_tools` 注册 |
| 审批 | 完整 `can_use_tool` 流程 | 同流程；`deny` 与未配置都走审批 |
| 重启重试 | 无最大次数，409/410 置 `terminal` | 最多 10 次后置 `terminal` |
| 子线程 | 有创建与状态机 | 只读权威状态 |
| 诊断 | 本地 console + OTel | 生产代码统一 SLF4J API + Log4j2，同时写 console 与滚动文件 |

## 8. 未覆盖

MCP 代理（`/mcp`）、图片与消息附件、OTLP logs/traces、`worker/diagnostics` 尚未接入。这些接口不影响主对话链路、工具审批与恢复能力，计划在下一批接入。

## 9. 验收

- 单测：`WorkerMetadataTest`、`ControlPlaneTest`（含审批超时与中断）、`TranscriptTest`、`ToolCatalogTest`、`ToolRunnerTest`、`TranscriptStoreTest`、`InternalEventStoreTest`、`OmaWebClientTest`。
- 端到端：把某工具权限配成 `ask`，验证前端确认的允许 / 拒绝 / 改参三条路径；调用 `AskUserQuestion` 验证答案回传；调用 `web_search` 与 `get_port_url`；重启 harness 验证本地 transcript 与远端对齐；手工制造分叉验证 `Local and remote log histories diverged`。
