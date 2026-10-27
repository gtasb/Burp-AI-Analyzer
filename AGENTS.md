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

`io.agentscope:agentscope-harness:2.0.3` (via `${agentscope.version}`) — HarnessAgent provides native tools:
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

## Knowledge graph (assets / fingerprints / inference)

- Node types: `ORIGIN, INTERFACE, PARAMETER, MODULE, TECHNOLOGY, VERSION` plus `RESOURCE`
  (JS/CSS/source map), `EVIDENCE`, `FEATURE`, `BACKEND_HYPOTHESIS`, `VULN_CLASS`, `CANDIDATE`,
  `ANALYSIS_FACT`. Fingerprints are first-class `TECHNOLOGY` nodes (property `category` =
  frontend/backend/server/middleware/library) with `EVIDENCE --INDICATES--> TECHNOLOGY`.
- `PassiveScanManager.enqueueRequest` calls `GraphStore.observe` **before** the L0 static filter,
  so JS/CSS/source maps are recorded as `RESOURCE` (never sent to the LLM) and JS API paths add
  `RESOURCE --CALLS--> INTERFACE` edges when the interface already exists.
- Agent write tools: `graph_upsert_fingerprint`, `graph_add_feature` (optional `vuln_class` →
  `FEATURE --SUGGESTS--> VULN_CLASS`), `graph_add_backend_hypothesis` (evidence → `SUPPORTS`).
  Both system prompts instruct agents to use them with evidence.
- `graph_upsert_observation` for `INTERFACE` accepts `name` = full URL, or `name` = `METHOD /path`
  with `root` = Origin, or (legacy) a full URL in `root`. Do **not** parse `root` as the request URL
  when it is just an Origin — that historical bug always produced `GET /`.
- Coverage follows ARTEX's fact-anchor semantics: a completed passive scan writes an
  `ANALYSIS_FACT` node plus `COVERS --> INTERFACE`; the viewer paints only COVERS-anchored assets
  as “已分析”. Skipped/low-value scans do not anchor. Active/chat analysis also anchors after a
  normal (non-cancelled, non-throwing) completion via `AgentApiClient.recordActiveAnalysisFact`.
- Viewer (`ui/GraphViewerPanel`) uses ARTEX-like force parameters, folds sibling groups by
  parent+type (PARAMETER 0, EVIDENCE 2, RESOURCE 6, others 12 by default, click “…” to expand),
  and exports a self-contained HTML with the same look.

## Known bugs & quirks

- **Every checkbox action listener MUST call `apiClient.setXxx()`** — not just toggle UI state. `enableCustomMcpCheckBox` was missing this (fixed), check new additions
- **`applySettings()` must mirror `saveSettings()`** — `tokenBudgetTokens` and `enablePlanMode` were saved to file but never restored to runtime (fixed). Any new PluginSettings field needs entries in both methods
- **Windows shell paths**: `SkillToolExecutor.tokenizeArgs()` used to eat backslashes (fixed). Backslash only escapes before `"`, `\`, `'`
- **Shell/Python toggle conflation**: `disableShellTool()` was gated on `!enableCliTool && !enablePythonScript` (fixed to `!enableCliTool`)
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
- **DeepSeek thinking models** need `reasoning_content` echoed back on the next request after a tool call.
  `AgentScopeModelFactory` auto-detects DeepSeek (model name `deepseek*` or URL containing `deepseek`) for the
  OpenAI-compatible provider and installs `compat.deepseek.DeepSeekFormatter`; without it every second
  model call after a tool call fails with HTTP 400. Covered by `DeepSeekReasoningPassthroughTest` and
  `DeepSeekToolLoopReasoningTest` (local fake server, no network).
- **Legacy Java-serialized settings**: removing a field needs no `serialVersionUID` bump; native deserialization ignores extra stream fields. The substitution fallback only exists for UID mismatches.

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
