# AGENTS.md

## Build & test

```powershell
mvn clean package           # full build + tests
mvn test                    # 349 tests, ~2 min
```

Requires JDK 21 + `--enable-preview` (Maven config handles this).

## Architecture

- **Entrypoint**: `src/com/ai/analyzer/core/AIExtension.java` — `implements BurpExtension`
- **Agent runtime**: `AgentScopeAgentRuntime` wraps AgentScope 2.0 `HarnessAgent` via `streamEvents()`
- **Two parallel clients**: `AgentApiClient` (active/chat) and `PassiveScanApiClient` (batch). They have ~90% duplicated code — editing one likely needs the same change in the other
- **UI**: `AIAnalyzerTab` (3475 lines) is the main JPanel tab; `ChatPanel` is the side panel

## Key dependencies

`io.agentscope:agentscope-harness:2.0.0` — HarnessAgent provides native tools:
- `execute_shell_command` (controlled by `enableCliTool` checkbox)
- `agent_spawn`/`agent_send`/`agent_list` (sub-agents, always enabled)
- `read_file`/`write_file`/`edit_file`/`grep`/`glob` (filesystem, controlled by `enableFileSystemAccess`)
- Tool results >16KB auto-evicted to disk, readable via `read_file`

## Known bugs & quirks

- **Every checkbox action listener MUST call `apiClient.setXxx()`** — not just toggle UI state. `enableCustomMcpCheckBox` was missing this (fixed), check new additions
- **`applySettings()` must mirror `saveSettings()`** — `tokenBudgetTokens` and `enablePlanMode` were saved to file but never restored to runtime (fixed). Any new PluginSettings field needs entries in both methods
- **Windows shell paths**: `SkillToolExecutor.tokenizeArgs()` used to eat backslashes (fixed). Backslash only escapes before `"`, `\`, `'`
- **Shell/Python toggle conflation**: `disableShellTool()` was gated on `!enableCliTool && !enablePythonScript` (fixed to `!enableCliTool`)
- **PassiveScanApiClient**: `enableThinking` was a dead field (deleted). System prompt cache hash was missing MCP/filesystem toggles (fixed). `apiProvider` was not restored from settings (fixed)
- **Serialized settings** (`ai_analyzer_settings.dat`) use Java serialization with `serialVersionUID = 9L`. `PluginSettings.loadCompat()` handles UID mismatch
- **`useAgentScope`** was a serialized dead field never read anywhere (deleted). Remove any future dead field from PluginSettings or it pollutes the binary forever

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
