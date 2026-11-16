# AGENTS.md

## Build & test

```powershell
mvn clean package           # full build + tests
mvn test                    # 412 tests, ~2 min
```

Requires JDK 21 + `--enable-preview` (Maven config handles this).

## Architecture

- **Entrypoint**: `src/com/ai/analyzer/core/AIExtension.java` — `implements BurpExtension`
- **Agent runtime**: `AgentScopeAgentRuntime` wraps AgentScope 2.0 `HarnessAgent` via `streamEvents()`
- **Two parallel clients**: `AgentApiClient` (active/chat) and `PassiveScanApiClient` (batch). They have ~90% duplicated code — editing one likely needs the same change in the other
- **UI**: `AIAnalyzerTab` (3475 lines) is the main JPanel tab; `ChatPanel` is the side panel

## Key dependencies

`io.agentscope:agentscope-harness:2.0.5` (via `${agentscope.version}`, the latest stable; 2.1.0
still BETA) — HarnessAgent provides native tools:
- `execute` (native shell tool; the actual `ShellExecuteTool.NAME` constant is `execute`, **not** `execute_shell_command` — controlled by `enableCliTool` checkbox)
- `agent_spawn`/`agent_send`/`agent_list` (sub-agents, always enabled)
- `read_file`/`write_file`/`edit_file`/`grep`/`glob` (filesystem, controlled by `enableFileSystemAccess`)
- Tool results >16KB auto-evicted to disk, readable via `read_file`

## Sandbox vs permission mode (two independent layers)

- **File sandbox** (`LocalFilesystemSpec.mode`, set in `AgentScopeAgentRuntime`): constrains which
  **paths** file tools may touch. `UNRESTRICTED` (default, sandbox off) = any path;
  `ROOTED` = workspace/project roots only. Toggled by `enableFileSystemSandbox` in the CLI tab.
- **Permission mode** (`PermissionContextState.mode`, hardcoded `BYPASS`): constrains whether a tool
  call is **allowed to execute at all**, for every tool. AgentScope offers 5 (`DEFAULT`,
  `ACCEPT_EDITS`, `EXPLORE`, `BYPASS`, `DONT_ASK`) but only non-interactive ones would be safe here:
  the confirm handler is auto-approve (`-> true`), so `DEFAULT`/`ACCEPT_EDITS` would ask-then-auto-yes
  and risk re-triggering the historical ASKING stall. Do not add a UI for them without a real dialog.

## Shell environment

`ShellEnvironment.augmentedEnv()` must be passed to every child process. Burp's own process PATH is
sometimes truncated to just its bundled JRE (launchers/shortcuts/services), which makes `python`,
`py`, `where`, `curl` all disappear. Two separate paths need it: the Harness spec
(`.inheritEnv(true).env("PATH", ...)`) and our own `ShellExecTool` (passive scan).

## File-tool path resolution (namespace)

Relative paths passed to file tools get a **namespace prefix** (`user.name`) injected by
`LocalFilesystem.applyNamespacePrefix`, so they land in `<workspace>\<user.name>\...`. Harness's
`LocalFilesystemSpec.project` is set to that same directory so the shell's default cwd matches
(`resolveFileToolRoot`). Without this alignment the agent writes a file and then cannot find it from
`execute`. Absolute paths are NOT namespaced.

## Knowledge graph (assets / fingerprints / inference / notes)

- Node types: `ORIGIN, INTERFACE, PARAMETER, MODULE, TECHNOLOGY, VERSION` plus `RESOURCE`
  (JS/CSS/source map), `EVIDENCE`, `FEATURE`, `BACKEND_HYPOTHESIS`, `VULN_CLASS`, `CANDIDATE`,
  `ANALYSIS_FACT`, `NOTE`. Fingerprints are first-class `TECHNOLOGY` nodes (property `category` =
  frontend/backend/server/middleware/library) with `EVIDENCE --INDICATES--> TECHNOLOGY`.
- **Notebook was merged into the graph** (the `.md` subsystem is gone). Notes are `NOTE` nodes:
  `canonical_key = root|note:<uuid>`, properties `{content, kind, version, author, target, edited_at}`,
  attached with `NOTE --RELATED_TO--> node` (site-level notes hang off ORIGIN). Version is per-root,
  allocated inside `GraphDatabase.upsertNote` (single synchronized call) and used for incremental
  `graph_get_notes(root, since_version)`. Notes are excluded from `listNodes`/`listEdges`, so they
  never consume the viewer's 500-node budget and are never drawn on the canvas.
- One-time migration: `graph/LegacyNotebookImporter` imports `<workspace>/notebooks/*.md` into NOTE
  nodes on first open of a workspace, then deletes the files. Import failures keep the original files.
- Node assessment: `graph_assess_node(node_id, priority, assessment, next_step)` writes
  `assess_priority|assess_summary|assess_next_step|assess_by|assess_at` into the node's properties.
  `low`/`none` mean a confirmed dead end. The viewer sidebar renders this as “Agent 判断”.
- `PassiveScanManager.enqueueRequest` calls `GraphStore.observe` **before** the L0 static filter,
  so JS/CSS/source maps are recorded as `RESOURCE` (never sent to the LLM) and JS API paths add
  `RESOURCE --CALLS--> INTERFACE` edges when the interface already exists.
- Agent write tools: `graph_upsert_fingerprint`, `graph_add_feature` (optional `vuln_class` →
  `FEATURE --SUGGESTS--> VULN_CLASS`), `graph_add_backend_hypothesis` (evidence → `SUPPORTS`),
  `graph_add_note`, `graph_get_notes`, `graph_assess_node`. Both system prompts instruct agents to
  read human `instruction` notes first and to assess nodes after analysis.
- `graph_upsert_observation` for `INTERFACE` accepts `name` = full URL, or `name` = `METHOD /path`
  with `root` = Origin, or (legacy) a full URL in `root`. Do **not** parse `root` as the request URL
  when it is just an Origin — that historical bug always produced `GET /`. `NOTE` is rejected there;
  notes must go through `graph_add_note`.
- Coverage follows ARTEX's fact-anchor semantics: a completed passive scan writes an
  `ANALYSIS_FACT` node plus `COVERS --> INTERFACE`; the viewer paints only COVERS-anchored assets
  as “已分析”. Skipped/low-value scans do not anchor. Active/chat analysis also anchors after a
  normal (non-cancelled, non-throwing) completion (recorded by the passive/organizer sides; the
  active agent itself no longer auto-anchors, the organizer's `graph_record_analysis_fact` covers it).
  Human-added nodes default to `INFERRED`; ticking “我已验证” creates them as `CONFIRMED`.
- Viewer (`ui/GraphViewerPanel` + `ui/GraphSidebarPanel` + `ui/GraphNodeDialog`): horizontal split
  (canvas | resizable sidebar), sidebar has node detail (assessment, neighbor counts, parameters)
  over an editable notes list. ARTEX-like force parameters, folds by parent+type (PARAMETER 0,
  EVIDENCE 2, RESOURCE 6, others 12; click “…” to expand), and exports a self-contained HTML.
  **Containment direction is edge-specific**: `HAS_*`/`LOADS`/`USES_*`/`EXPOSES`/`RESOLVES_TO`/`HOSTS`
  are parent→child; `BELONGS_TO`/`INDICATES`/`SUPPORTS`/`SUGGESTS`/`DERIVED_FROM`/`CALLS` are
  child→parent. Treating every source as a child inverted the tree and made the graph collapse.
  The layout also runs a separation pass and auto-zooms (√n, cap 4×) for large graphs; “重新布局”
  resets pan/zoom.
- Custom MCP config (`agent/mcpclient/CustomMcpConfigParser`) accepts a JSON array **or a single
  object**, and both `authorization` (string) and `headers`/`header` (object). `AgentScopeMcpManager.headersOf`
  merges them (auto `Bearer `); registration, `testConnection` and the shared-client key all use it.


## Multi-agent architecture (active / sidebar / organizer)

Three independent `AgentApiClient` instances, each with its own runtime, session id, single-flight
lock and consumers:

- **SIDEBAR** (`AIAnalyzerTab.sidebarApiClient`, returned by `getApiClient()`): Burp side panels;
  behavior unchanged; owns long-term memory (shared workspace, `MemoryConfig` hooks enabled).
- **ACTIVE** (`AIAnalyzerTab.apiClient`): request-analysis tab. Registers the full `GraphTool`
  (read+write, user decision 2026-11-17: the active agent should record findings into the graph while
  testing; the organizer still merges/dedups at round end — canonical keys make double writes idempotent).
  `AgentApiClient.analyzeRequestStream` no longer calls
  `recordActiveAnalysisFact`; it records a `CompletedRun` handoff (method/url/prompt/response) and a
  run history entry instead. State lives in `<workspace>/.agents/active` with memory hooks disabled.
- **ORGANIZER** (`AIAnalyzerTab.organizerClient`): independent context, state in
  `<workspace>/.agents/organizer`, memory disabled. Registers full `GraphTool` + `OrganizerTool`
  (`organizer_send_to_repeater`, `organizer_save_script`, `organizer_finish`) and a dedicated
  organizer system prompt. After each completed ACTIVE run, `OrganizerCoordinator` runs it to
  normalize evidence into the graph (`graph_record_analysis_fact` anchors coverage), inspect other
  agents' notes, and either continue the active agent with a concrete instruction or finish with a
  report saved under `<workspace>/reports/`. POCs are loaded into Repeater (never auto-sent);
  scripts go to `<workspace>/pocs/` and are never executed.

Cross-agent context is **one-way digests only**: sidebar's completed assistant text is recorded in
`AgentDigestStore` and prepended to ACTIVE prompts. Never inject raw messages/tool-call transcripts
between agents (tool pairing / DeepSeek reasoning would break).

Settings (`PluginSettings.organizerAutoContinue` default true, `organizerMaxRounds` default 0 =
unlimited) live in 配置 → 功能开关; `SettingsPanel` mirrors API config to sidebar + organizer via
`syncApiConfigToSidebar()`/`applyConfigTo()` and warms all three runtimes.

- **Active mode UI** (`ui/active/ActiveAnalysisPanel` + `ui/active/ChatTimeline`): session-style
  timeline with message cards (user / active analysis / organizer / report), inline tool rows and a
  throttled thinking line; header shows target, phase, round, auto-continue and history; composer at
  the bottom. During a run, Enter (or the 插话 button) calls `AgentApiClient.interject`, forwarded to
  `AgentScopeAgentRuntime.interject` → AgentScope `HarnessAgent.interrupt(Msg)`; it does not start a
  new analysis. `getResultPane()`/`setActiveMode()` remain only for compatibility.

**Interjection semantics (probed against AgentScope 2.0.4, 2026-11; covered by `AgentInterjectTest`)**:
`interrupt(Msg)` without a slot key lands on `(__anon__, defaultSessionId)` and is silently dropped —
you must call `agent.interrupt(userId, sessionId, msg)` with the exact `slotKey(userId, sessionId)`
the run used. Even then the framework does NOT persist the message into session state: the turn ends
with `AgentResultEvent` whose `getGenerateReason() == GenerateReason.INTERRUPTED` (text
“I noticed that you have interrupted me…”), and the next request does not contain the message.
`AgentScopeAgentRuntime` therefore keeps a `pendingInterjections` queue: on the INTERRUPTED result it
emits a `RuntimeEventType.INTERRUPTED` event (consumers must treat the run as NOT completed — this is
what stops the organizer from firing after a stop/interject), then the chat loop re-enters
`buildEventStream` with the queued text as the next user message so the model actually reads it.
Timing caveat: an interject before the run registers (during agent build / first model call) is lost —
the UI disables the button until `isAnalyzing` is set, and `chatLocked` clears `pendingInterjections`
at the start of every new chat so a raced enqueue can never leak into a later turn.

**Session-context audit (2.0.5, covered by `MultiTurnContextAuditTest`)**: within one
`AgentScopeAgentRuntime`, turn N's model request must still contain turn 1's user text, the
assistant tool_calls, and the tool results — the full current-session context, not just the last
turn; different sessionIds are fully isolated (ACTIVE vs ORGANIZER never see each other's history);
every historical assistant message with `tool_calls` must carry `reasoning_content` (DeepSeek
protocol). ACTIVE/ORGANIZER additionally disable memory hooks and tools, so their context lives only
in the in-memory session — a runtime rebuild (settings change mid-loop) intentionally loses it.

**Auto-loop guards (unlimited rounds must not blow up)**: `OrganizerCoordinator` refuses to continue
when the shared token budget is exhausted, when the instruction repeats, or when the reported gaps
repeat (no-progress); it resets the organizer session every 10 rounds; it keeps only the latest
pending `CompletedRun` when organizing is already in flight. `ActiveAnalysisPanel` clears
`lastCompletedRun` when a run starts so a cancelled/failed run can never re-trigger the organizer
with a stale handoff. Active UI shows 阶段/轮次/自动继续 plus a timeline that includes organizer tool
calls and decisions.

MCP connections are shared per endpoint with reference counting (`AgentScopeMcpManager.acquireSharedClient`/
`releaseSharedClient`), because each `HarnessAgent.close()` calls `Toolkit.closeMcpClients()` and
would otherwise kill the other agents' MCP connections. `AIExtension` unload calls
`closeAllSharedClients()`.

## Config distribution (snapshot pattern)

`core/AgentConnectionConfig` is an immutable record of everything the UI distributes to agent
clients. `AgentApiClient.applySnapshot` and `PassiveScanApiClient.applySnapshot` consume it
through the existing diff-aware setters. All fan-out paths (`SettingsPanel.syncApiConfigToSidebar`,
`syncApiConfigToPassiveScan`, `AIAnalyzerTab.syncApiConfigToPassiveScan`) build the snapshot once
from `apiClient.getConfig()` and call `applySnapshot` — **never hand-copy setter lists again**;
the pre-refactor code had 4 hand-copied blocks / 71 setter calls and the audit found two drift
bugs in them. Adding a config field = add to the record + both `applySnapshot` implementations +
`from()`. UI→primary-client writes (`updateApiClientConfigForAnalysis`) stay explicit and then
fan out via snapshot.

## Tool-call visualization & Burp MCP send-tool blocklist

- Model-behavior message format is now `TYPE|字段…` (multi-field, `|` stripped from field
  content): `TOOL_START|工具名|参数摘要`, `TOOL_END|工具名|failed|true/false`. The old
  `TOOL_END|name|ok` single-detail format caused `✓ execute|ok` to display verbatim
  (the `|ok` suffix was never stripped — split() only cut the first `|`).
- Tool rows in ChatTimeline are pure-text (`调用中/完成/失败` + name + wrapped args + time),
  no ✓/▸ glyphs (missing glyphs render as boxes), START (with args) and END update the SAME row.
- `AgentScopeMcpManager.BURP_SEND_TOOLS` = {http_send_request, http_send_requests_parallel,
  http_race, http_send_request_chain, http_send_raw_bytes}. `ToolFilteringWrapper` removes them
  from listTools/getCachedTool at registration. Toggle `enableBurpMcpSendTools` (default false =
  blocked) in MCP 配置 tab; wired through PluginSettings/AgentConfig/AgentConnectionConfig and
  both clients (passive mirrors the active value). Rationale: these tools regularly hang 60s
  with no Reactor fallback; agents should prefer the command line (`execute` + curl) for sending
  requests — NOT curl_send_request (no prompt guidance steers there; CLI is the preferred path).
- No interject button anymore (user 2026-11-17): Enter while analyzing = interject; the
  composer tooltip says so.

## Session switcher (会话下拉)

`core/SessionStore`（`<workplace>/analysis-runs/sessions.json`，最多 30 会话/会话内 200 轮）
按 session 划分主动分析对话。`AgentApiClient.switchSession(id)` 只换 `currentSessionId`
不销毁运行时——AgentScope 按 sessionId 保存会话状态，切回即续聊（上下文保留、互不
泄漏，`SessionSwitchContextTest` 验证来回切换语义）。旧 `analysis-runs.json` 在首次
加载时按「相邻 run 间隔 <60min 归同会话」迁移成 sessions.json，原文件改名 .bak。
`ActiveAnalysisPanel` 的下拉（替换原 run 历史下拉）可切换/删除会话，「＋ 会话」新开；
新会话不再销毁旧会话状态，只是换 id。`beginTrackedSession` 建会话记录并与
currentSessionId 同步。`AnalysisRunStore` 仍在（被动侧与既有测试依赖），但主动 UI 不再读它。

## Active-mode rendering contract

`ActiveAnalysisPanel` renders into its own `ChatTimeline`; there is **no shared result pane
anymore** (`setActiveMode(active, prompt)` — the target-pane parameter and `getResultPane()` are
deleted; `AIAnalyzerTab` no longer assigns `resultTextPane` in active mode). Passive-mode
「开始分析」switches to the active card before running. History replay snapshots the live
timeline (`showHistoryMode`/`restoreLive`) instead of destroying it. AssistantBlock streams
plain-text deltas per tick (O(1)) and renders full Markdown once at finish — the old
every-tick full re-parse was O(n²).

## Known bugs & quirks

- **Every checkbox action listener MUST call `apiClient.setXxx()`** — not just toggle UI state. `enableCustomMcpCheckBox` was missing this (fixed), check new additions
- **`applySettings()` must mirror `saveSettings()`** — `tokenBudgetTokens` and `enablePlanMode` were saved to file but never restored to runtime (fixed). Any new PluginSettings field needs entries in both methods
- **Windows shell paths**: `SkillToolExecutor.tokenizeArgs()` used to eat backslashes (fixed). Backslash only escapes before `"`, `\`, `'`
- **Shell/Python toggle conflation**: `disableShellTool()` was once gated on `!enableCliTool && !enablePythonScript` (fixed to `!enableCliTool`); `enablePythonScript` + `PythonScriptTool` + its UI checkbox were later deleted entirely — the switch controlled nothing (the tool was never registered)
- **PassiveScanApiClient**: `enableThinking` was a dead field (deleted). System prompt cache hash was missing MCP/filesystem toggles (fixed). `apiProvider` was not restored from settings (fixed)
- **Settings file** (`ai_analyzer_settings.dat` + `~/.burp_ai_analyzer_settings`) is **JSON** via
  `PluginSettings.saveTo()` / `loadCompat()`. Adding/removing fields is safe: Gson invokes the no-arg
  constructor (missing fields keep defaults) and `normalize()` clamps invalid values. Old Java-
  serialized files are still readable — `loadCompat` detects the `0xACED` magic and reads them
  natively, falling back to local-descriptor substitution only on `InvalidClassException`.
  `serialVersionUID = 9L` remains only for that legacy path; do not go back to `ObjectOutputStream`
  for settings.
- **`useAgentScope`** was a serialized dead field never read anywhere (deleted). Remove any future dead field from PluginSettings or it pollutes the file forever
- **`enableUnrestrictedCliTool`** was another one (deleted): stored in settings, shuttled between both API clients, drove a checkbox — but neither the Harness native `execute` tool nor our `ShellExecTool` ever read it. **There is no command-level whitelist anywhere.** The CLI tab's「工具白名单」is only injected as a soft suggestion into the system prompt (`cliToolPrompt`/`cliWhitelist`); nothing intercepts commands. Don't let the UI imply enforcement
- **DeepSeek thinking models** need `reasoning_content` on **every** assistant message that carries
  `tool_calls`, including older turns (official API returns 400 otherwise; verified 2026-11).
  `AgentScopeModelFactory` auto-detects DeepSeek (model name `deepseek*` or URL containing `deepseek`)
  and installs `DeepSeekReasoningFormatter` (extends `OpenAIChatFormatter`; keeps real reasoning,
  pads assistant messages whose reasoning is missing with an empty string — the official API accepts
  empty — and `supportsStrict=false`). This mirrors upstream's own fix, which landed in 2.0.5's
  `compat.deepseek.DeepSeekFormatter` (verified in bytecode: `applyDeepSeekFixes` now pads empty
  string). Keeping our own formatter remains deliberate: it also forces `supportsStrict=false` and
  preserves real reasoning. Covered by `DeepSeekReasoningPassthroughTest`,
  `DeepSeekToolLoopReasoningTest`, and `DeepSeekHarnessLoopReasoningTest` (long loop + compaction,
  local fake server).
- **Model list & provider URLs**: `core/ModelListFetcher` normalizes the models endpoint
  (`…/v1`, `…/api/v1`, or a pasted full endpoint all collapse to one `/models`), uses
  `x-api-key` + `anthropic-version` for Anthropic (Bearer is only added for non-`anthropic.com`
  gateways), and accepts both `{data:[{id}]}` and `{models:[{name|id}]}`. Do not append `/v1`
  blindly — that produced the historical `/v1/v1/models` 404. `AgentScopeModelFactory.normalizeAnthropicBaseUrl`
  strips a user-supplied `/v1` or `/v1/messages` because the official Anthropic SDK appends its own path.
  **OpenAI Responses (`/v1/responses`) is not supported**: AgentScope 2.0.x has no Responses client and
  the plugin intentionally does not implement one (decision 2026-11); use an OpenAI-compatible
  chat/completions endpoint instead.
- **Lazy init + background warm-up**: each role builds its model/Toolkit/MCP on first analysis
  (`ensureAgentScopeInitialized`), and `HarnessAgent` is built one step later on the first `chat()`
  (`getOrCreateHarnessAgent`). `SettingsPanel.scheduleWarmup()` runs 2.5s after settings load/save on
  a daemon thread and calls `warmup()` on ACTIVE, SIDEBAR, ORGANIZER and passive clients, which build
  the runtime and agent in the background **without sending an LLM request**.
  Warm-up skips when the API key is blank or an analysis is in flight.
- **Model HTTP retries**: `AgentScopeModelFactory.MODEL_RETRY` overrides AgentScope's 3-attempt
  default with 10 attempts, 3s initial backoff → 60s max (multiplier 2.0), reusing
  `ExecutionConfig.MODEL_DEFAULTS.getRetryOn()` so 4xx errors are not retried endlessly.
- **MCP init timeouts** (`AgentScopeMcpManager`): stdio keeps 30s (uvx may download), local HTTP is
  5s, remote HTTP is 8s; per-mode handshakes slower than 1.5s are logged to AppLogBuffer. The old
  30s HTTP timeout made one dead MCP endpoint stall the first analysis.
- **Passive scan traffic sources**: `PassiveScanManager` registers a global `HttpHandler` (live traffic,
  not a backfill of Proxy history) and filters by `responseReceived.toolSource().toolType()`.
  Default is `PROXY` only; `passiveScanToolSources` (CSV of ToolType names) plus the checkboxes in
  the「请求分析」passive-scan settings bar (Proxy / Repeater / Scanner-Crawler / Intruder / Extensions)
  enable the other sources. `SettingsHost.refreshPassiveScanSources()` keeps the checkboxes in sync
  after settings load.
  Keep `EXTENSIONS` off by default — the plugin's own tool traffic would re-enter analysis and can loop.
  An empty selection falls back to `PROXY`. Covered by `PassiveScanManagerTest` (流量来源) and
  `SettingsPanelToolSourcesTest`.
- **Performance hot paths (do not regress)**: the global `HttpHandler` and per-analysis helpers must
  never materialize whole messages. `PassiveScanTask.shouldSkipRequest` reads only `Content-Type`
  headers (old `response().toString()` serialized entire response bodies on Burp's traffic thread);
  `RequestFingerprint.bodyDigest` skips bodies >256KB; `GraphStore.observe/observeResponseMeta` use
  `body().length()`/`subArray(0, 256KB)` instead of `bodyToString()`; passive graph observation skips
  static binaries (images/fonts/media/archives) while keeping JS/CSS/source maps. `RequestSourceDetector`
  must not call `api.proxy().history()` per analysis — use `ProxyHttpRequestResponse.id()`/class names.
  `ChatPanel` throttles THINKING rendering to 200ms, caps the buffer at 8k chars and logs at most 2k
  chars to Burp Output.
- **Legacy Java-serialized settings**: removing a field needs no `serialVersionUID` bump; native deserialization ignores extra stream fields. The substitution fallback only exists for UID mismatches.
- **Cross-cutting bugfixes from the 2026-11 deep audit** (7月以来全部新功能对抗式复查): GraphStore.fingerprint uses
  `body().subArray(0,4096)` (was full `bodyToString()` — GB-scale allocation on large archives, OOM kills the
  graph-observe thread since `catch(Exception)` misses `Error`); `graph_record_analysis_fact` generates a unique sid
  per call (constant agent-name sid collapsed ALL organizer facts into one node and dropped every summary after the
  first); `GraphStore.addHumanNode` writes child→parent edges in the child-is-source direction for
  BELONGS_TO/INDICATES/SUPPORTS/DERIVED_FROM/CALLS/SUGGESTS/RELATED_TO (dialog previously inverted them, nesting the
  whole site under the new node); note edits bump the version and broadcast (`bumpNoteVersionAndUpdate`) so
  `since_version` readers re-fetch human corrections; legacy notebook import defers (no phantom `https://host:443`
  root, `markLegacyImportRetry` re-arms the guard) when no matching root exists; host is lowercased in
  `GraphNormalizers.parse`/`rootOf`/`originOf`/`hostOf` (mixed-case Host header split one site into two roots);
  sidebar save-note reports dedupe truthfully; sourceMappingURL tail-probe (last 4KB) for large bundles.
  **Sidebar「新会话」no longer purges the shared `<workspace>/<user.name>` file root** — it deletes only
  MEMORY.md/memory/agents-<agentName> (the old purge destroyed ACTIVE/ORGANIZER files and evicted tool results
  mid-run). Passive-mode「开始分析」switches to the active card first (output previously rendered into the hidden
  card — minutes with zero visible feedback). fetch_url blocks IPv4-mapped IPv6 literals (`::ffff:169.254.169.254`,
  hex form too) and redirect hops re-check. CustomMcpConfigParser never throws on odd JSON shapes (primitive-only
  reads + runtime catch → empty list; the old raw `getAsString` UnsupportedOperationException took down the whole
  passive tab at plugin load). PassiveScanApiClient parses MCP JSON via `parseCustomMcpQuietly`. Burp MCP goes
  through `acquireSharedClient` with auth in the key (was raw `endpointClient` — one transient listTools failure
  could cross-kill the shared connection; token changes never applied). stdio MCP env is actually passed to the
  child (`stdioTransport(cmd, args, envMap)` — env was parsed but silently dropped; key includes env hash).
  `startPassiveScan` starts the producer before `isRunning`, and config sync runs inside the try (half-started
  checkbox states). `analyzeBatch` checks the token budget (was the only spend site without the gate).
  ActiveAuditManager/runtime disposal wired to extension unload (`disposeForUnload`). API-profile delete persists
  immediately. (BatchFuzzTool was deleted 2026-11-17 on user request — fuzzing is better done via agent-written scripts; curl_get/curl_post thin wrappers also removed, keeping only `curl_send_request` as the Burp-MCP fallback.) Google CSE parsing has
  isJsonNull guards. ChatPanel debugLogArea capped at 300KB. Organizer gets an auto-approve confirm handler
  (plan_exit was silently DENIED for it — with Plan Mode saved on, the organizer could never reach
  organizer_finish). Digest injection skips auto-continue rounds (was re-prepended every round, N copies in
  persistent history). Tool-arg summaries no longer emitted as textDelta (polluted digest/handoff). 停止 during
  the queued-continue window aborts the round (`isStopped` check in the continue lambda). `resetLoop` clears
  lastGaps (first round of a new session was killed as “no progress”). Interject returns false while
  `currentSession == null` (init window used to report success while dropping the message). Organizer re-reads
  the OrganizerTool after runTurn (settings rebuild swaps instances mid-round). AnalysisRunStore exposes a
  lock-free immutable snapshot (EDT no longer blocks on multi-MB saves) and callers pass real method/url.

## Project structure

```
src/com/ai/analyzer/
├── core/          AIExtension, AgentApiClient, AgentConfig, PluginSettings, SystemPromptBuilder
├── agent/
│   ├── runtime/   AgentScopeAgentRuntime, RuntimeEvent, StreamSession
│   ├── skills/    SkillToolExecutor (ProcessBuilder), SkillManager, ExecutionPolicy
│   └── mcpclient/ AgentScopeMcpManager (BurpMCP/RAG/Chrome/custom MCP registration)
├── ui/            AIAnalyzerTab (main panel), ChatPanel, ActiveAnalysisPanel
├── scan/
│   ├── pscan/     PassiveScanManager + PassiveScanApiClient + ScanResult
│   ├── active/    ActiveAuditManager (Burp Scanner bridge)
│   └── rulesmatch/ Hardcoded regex rules (35+ vuln families)
├── tools/         BurpExtTools (intruder), WebSearchTools (tavily/google/ddg)
└── util/          MarkdownRenderer, JsonParser, HttpFormatter, DebugContext
```

## Important conventions

- `PassiveScanApiClient` mirrors `AgentApiClient` but lags behind — when adding features to one, replicate in the other
- Plugin has multiple constructors with long parameter lists — prefer setter chaining over new constructors
- `AgentConfig` vs `PluginSettings` vs direct fields on `PassiveScanApiClient`: three ways of storing the same config. Don't add a fourth
- Markdown rendering in ChatPanel uses `org.commonmark` + custom `MarkdownRenderer.appendMarkdown()` — expensive, so streaming uses line-buffered incremental render with 60ms throttle
- **Never let tests touch the real chat history** (`~/.burp_ai_chat_history.dat`). Any test that constructs `ChatPanel` must set
  `-Dburp.ai.chatHistoryFile=<temp>` first (see `SharedChatHistoryCursorTest`), otherwise assertion text is persisted and the
  running Burp instance replays it as chat history on next start. `ChatPanel` also filters the legacy test marker on load/save/render.
