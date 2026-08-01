package com.ai.analyzer.agent.runtime;

/**
 * 框架无关的 agent 运行时事件。
 *
 * <p>UI 层和扫描管理器订阅 {@link RuntimeEventType} 投影，由 {@link AgentRuntime}
 * 实现负责把底层框架（LangChain4j / AgentScope）的事件翻译成本类实例。
 *
 * <p>字段按事件类型按需填充，{@link #type} 之外的字段可为 null。
 * 通过 {@link #builder(RuntimeEventType)} 构建，或使用便捷工厂方法。
 */
public final class RuntimeEvent {

    private final RuntimeEventType type;
    private final String text;
    private final String toolName;
    private final String toolArgs;
    private final String toolResult;
    private final boolean toolFailed;
    private final long durationMs;
    private final String subagentName;
    private final String memoryAction;
    private final int inputTokens;
    private final int outputTokens;
    private final int cachedTokens;
    private final int totalTokens;
    private final Throwable error;

    private RuntimeEvent(Builder builder) {
        this.type = builder.type;
        this.text = builder.text;
        this.toolName = builder.toolName;
        this.toolArgs = builder.toolArgs;
        this.toolResult = builder.toolResult;
        this.toolFailed = builder.toolFailed;
        this.durationMs = builder.durationMs;
        this.subagentName = builder.subagentName;
        this.memoryAction = builder.memoryAction;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.cachedTokens = builder.cachedTokens;
        this.totalTokens = builder.totalTokens;
        this.error = builder.error;
    }

    // ---- Getters ----

    public RuntimeEventType type()        { return type; }
    public String text()                  { return text; }
    public String toolName()              { return toolName; }
    public String toolArgs()              { return toolArgs; }
    public String toolResult()            { return toolResult; }
    public boolean toolFailed()           { return toolFailed; }
    public long durationMs()              { return durationMs; }
    public String subagentName()          { return subagentName; }
    public String memoryAction()          { return memoryAction; }
    public int inputTokens()              { return inputTokens; }
    public int outputTokens()             { return outputTokens; }
    public int cachedTokens()             { return cachedTokens; }
    public int totalTokens()              { return totalTokens; }
    public Throwable error()              { return error; }

    // ---- Builder ----

    public static Builder builder(RuntimeEventType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final RuntimeEventType type;
        private String text;
        private String toolName;
        private String toolArgs;
        private String toolResult;
        private boolean toolFailed;
        private long durationMs = -1;
        private String subagentName;
        private String memoryAction;
        private int inputTokens;
        private int outputTokens;
        private int cachedTokens;
        private int totalTokens;
        private Throwable error;

        Builder(RuntimeEventType type) {
            this.type = type;
        }

        public Builder text(String text)             { this.text = text; return this; }
        public Builder toolName(String toolName)     { this.toolName = toolName; return this; }
        public Builder toolArgs(String toolArgs)     { this.toolArgs = toolArgs; return this; }
        public Builder toolResult(String toolResult) { this.toolResult = toolResult; return this; }
        public Builder toolFailed(boolean failed)    { this.toolFailed = failed; return this; }
        public Builder durationMs(long ms)           { this.durationMs = ms; return this; }
        public Builder subagentName(String name)     { this.subagentName = name; return this; }
        public Builder memoryAction(String action)   { this.memoryAction = action; return this; }
        public Builder inputTokens(int tokens)       { this.inputTokens = tokens; return this; }
        public Builder outputTokens(int tokens)      { this.outputTokens = tokens; return this; }
        public Builder cachedTokens(int tokens)      { this.cachedTokens = tokens; return this; }
        public Builder totalTokens(int tokens)       { this.totalTokens = tokens; return this; }
        public Builder error(Throwable error)        { this.error = error; return this; }

        public RuntimeEvent build() {
            return new RuntimeEvent(this);
        }
    }

    // ---- Convenience factories ----

    /** 创建一个 {@link RuntimeEventType#TEXT_DELTA} 事件 */
    public static RuntimeEvent textDelta(String text) {
        return builder(RuntimeEventType.TEXT_DELTA).text(text).build();
    }

    /** 创建一个 {@link RuntimeEventType#THINKING} 事件 */
    public static RuntimeEvent thinking(String text) {
        return builder(RuntimeEventType.THINKING).text(text).build();
    }

    /** 创建一个 {@link RuntimeEventType#TOOL_START} 事件 */
    public static RuntimeEvent toolStart(String toolName, String toolArgs) {
        return builder(RuntimeEventType.TOOL_START)
                .toolName(toolName)
                .toolArgs(toolArgs)
                .build();
    }

    /** 创建一个 {@link RuntimeEventType#TOOL_END} 事件 */
    public static RuntimeEvent toolEnd(String toolName, String toolResult, boolean failed, long durationMs) {
        return builder(RuntimeEventType.TOOL_END)
                .toolName(toolName)
                .toolResult(toolResult)
                .toolFailed(failed)
                .durationMs(durationMs)
                .build();
    }

    /** 创建一个 {@link RuntimeEventType#REPLY_END} 事件 */
    public static RuntimeEvent replyEnd(String fullText) {
        return builder(RuntimeEventType.REPLY_END).text(fullText).build();
    }

    /** 创建一个 {@link RuntimeEventType#ERROR} 事件 */
    public static RuntimeEvent error(Throwable error) {
        return builder(RuntimeEventType.ERROR).error(error).build();
    }

    /** 创建一个 {@link RuntimeEventType#SUBAGENT} 事件 */
    public static RuntimeEvent subagent(String subagentName, String text) {
        return builder(RuntimeEventType.SUBAGENT)
                .subagentName(subagentName)
                .text(text)
                .build();
    }

    /** 创建一个 {@link RuntimeEventType#MEMORY} 事件 */
    public static RuntimeEvent memory(String action, String text) {
        return builder(RuntimeEventType.MEMORY)
                .memoryAction(action)
                .text(text)
                .build();
    }

    /** 创建一个 {@link RuntimeEventType#USAGE} 事件 */
    public static RuntimeEvent usage(int inputTokens, int outputTokens, int cachedTokens, int totalTokens) {
        return builder(RuntimeEventType.USAGE)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cachedTokens(cachedTokens)
                .totalTokens(totalTokens)
                .build();
    }

    @Override
    public String toString() {
        return "RuntimeEvent{type=" + type
                + (text != null ? ", text=" + text.substring(0, Math.min(text.length(), 80)) : "")
                + (toolName != null ? ", toolName=" + toolName : "")
                + (error != null ? ", error=" + error.getMessage() : "")
                + "}";
    }
}