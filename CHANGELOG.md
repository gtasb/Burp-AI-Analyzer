# Changelog

## v1.5.0 - 2026-11-16

### Added
- **三 agent 架构**：主动扫描、侧栏聊天、证据整理 agent 各自独立运行时/会话/单航班，MCP 共享连接改为引用计数
- **会话式主动 UI**（Claude Code 风格）：消息卡片时间线、内联工具调用行、阶段/轮次/自动继续状态条、历史分析回看（快照恢复）
- **运行中插话**：分析进行时按 Enter/插话按钮向 agent 补充指令（AgentScope interrupt 语义实测适配 + 队列补投）
- **证据整理 agent**（organizer）：轮末整理入图、覆盖检查、自动继续/出报告，POC 送 Repeater、脚本存 pocs/
- **知识图谱增强**：主动 agent 只读（GraphReadTool），笔记/评估/事实锚点统一归整理 agent 写入；节点详情侧栏（Agent 判断/参数/邻居统计/笔记编辑）
- **配置快照分发**（AgentConnectionConfig）取代 4 处手抄 setter
- **缓存管理页**：Agent 记忆/分析历史/报告与 POC 可清理，释放量按实际删除计
- 右键「发送到AI分析」在扫描未启动时自动启动
- 被动扫描流量来源可选（Proxy/Repeater/Scanner/Intruder/Extensions）
- 插件卸载时释放 MCP 连接、审计队列与运行时
- AgentScope 升级到 2.0.5

### Fixed
- 大响应指纹探测只取 4KB，防止大文件 OOM（Error 不被 catch 捕获）
- `graph_record_analysis_fact` 唯一 sid：不再把所有分析挤成同一个节点
- 人工新增节点边方向与 agent 创建边一致（站点不再被嵌到新节点下）
- 笔记编辑递增版本并广播，增量读取方能看到更正
- 旧 Notebook .md 一次性迁移改为「无 root 时延迟重试」
- host 大小写归一（防同一站点拆成两个 root）
- 被动批次分析补上 token 预算闸门；startPassiveScan 先起 producer，失败可回滚
- 自定义 MCP JSON 坏值不再击穿插件加载；Burp MCP 走引用计数并把 token 纳入共享 key；stdio env 真正传给子进程
- fetch_url 堵住 IPv4-mapped IPv6 内网绕过；BatchFuzzTool JSON 反斜杠转义；ChatPanel 调试日志封顶 300KB；API 档案删除立即落盘
- DeepSeek：历史轮次 reasoning_content 补空串，长会话不再 400

## v1.4.0 - 2026-07-19

### Changed
- 移除主界面和侧栏中的「启用深度思考」GUI，避免重复入口与状态分裂。
- 移除主界面和侧栏中的「启用网络搜索」GUI。
- 将联网搜索开关统一迁移到 **配置 → 联网搜索** 中集中管理。
- 更新 README，统一说明联网搜索的配置入口与使用方式。

### Fixed
- 修复网络搜索复选框状态异常的问题。
- 修复不同界面中网络搜索状态不同步的问题。

### Build
- 发布新的可分发 JAR 包：`ai-analyzer-1.4.0-jar-with-dependencies.jar`

## v1.2.1
- 修复了一些问题
- 增加主动模式
- 增加 Notebook 模块，现在可以把项目中的重要信息记载到其中，更好进行人机协同
- 增加 workplace 功能，用于统一管理 skills/知识库/python脚本/Notebook

## v1.2.0
- 允许 Agent 运行 python 代码
- 完善了被动扫描功能
- 参考 HaE、z0scan 等优秀开源工具，增加了前置扫描器
- 优化某些逻辑

## v1.1.0
- Skills 自定义技能系统
- 支持定义可执行工具（nmap、sqlmap 等）
- 参数模板和智能填充
- 工具执行超时和输出限制

## v1.0.0
- 基础 AI 分析功能
- 流式输出支持
- MCP 工具调用（Burp MCP、RAG MCP）
- Markdown 渲染
- 工具执行可视化
