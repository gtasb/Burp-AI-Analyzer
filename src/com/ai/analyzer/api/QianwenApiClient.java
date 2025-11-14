package com.ai.analyzer.api;

import com.ai.analyzer.Agent.Assistant;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.List;
import com.ai.analyzer.model.PluginSettings;
import burp.api.montoya.MontoyaApi;

import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
//import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
//import com.ai.analyzer.listener.DebugChatModelListener;


public class QianwenApiClient {
    private String apiKey;
    private String apiUrl;
    private String model;
    private QwenStreamingChatModel chatModel;
    // private JsonArray tools; // 工具定义列表
    // private Consumer<ToolCall> toolCallHandler; // 工具调用处理器
    private MontoyaApi api; // Burp API 引用，用于日志输出
    private boolean enableThinking = false; // 是否启用思考过程
    private boolean enableSearch = false; // 是否启用搜索
    private boolean isFirstInitialization = true; // 是否是第一次初始化

    /**
     * 无参构造函数，自动从配置文件加载设置
     */
    public QianwenApiClient() {
        loadSettingsFromFile();
        initializeChatModel();
    }

    /**
     * 带参构造函数，如果参数为空则尝试从配置文件加载
     */
    public QianwenApiClient(String apiUrl, String apiKey) {
        if ((apiUrl == null || apiUrl.trim().isEmpty()) || 
            (apiKey == null || apiKey.trim().isEmpty())) {
            // 参数为空，尝试从配置文件加载
            loadSettingsFromFile();
        } else {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
        }
        initializeChatModel();
    }
    
    /**
     * 带 MontoyaApi 的构造函数
     */
    public QianwenApiClient(MontoyaApi api, String apiUrl, String apiKey) {
        this.api = api;
        if ((apiUrl == null || apiUrl.trim().isEmpty()) || 
            (apiKey == null || apiKey.trim().isEmpty())) {
            // 参数为空，尝试从配置文件加载
            loadSettingsFromFile();
        } else {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
        }
        initializeChatModel();
    }
    
    /**
     * 设置 MontoyaApi 引用
     */
    public void setApi(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 初始化 LangChain4j ChatModel
     */
    private void initializeChatModel() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] 警告: API Key为空，无法初始化ChatModel");
            }
            return;
        }
        
        try {
            // 从 apiUrl 中提取 baseUrl（去除 /chat/completions 等路径）
            String baseUrl = apiUrl;
            if (baseUrl != null && !baseUrl.trim().isEmpty()) {
                // 去除末尾的斜杠
                baseUrl = baseUrl.trim();
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                // 如果包含 /chat/completions，去除这部分
                if (baseUrl.contains("/chat/completions")) {
                    baseUrl = baseUrl.substring(0, baseUrl.indexOf("/chat/completions"));
                }
                // 确保 baseUrl 以 /v1 结尾（QwenStreamingChatModel 可能需要）
                if (!baseUrl.endsWith("/v1")) {
                    // 如果以 /compatible-mode 结尾，添加 /v1
                    if (baseUrl.endsWith("/compatible-mode")) {
                        baseUrl = baseUrl + "/v1";
                    } else if (baseUrl.contains("dashscope") && !baseUrl.contains("/v1")) {
                        // 如果是 dashscope URL 但没有 /v1，尝试添加
                        if (baseUrl.contains("/compatible-mode")) {
                            baseUrl = baseUrl + "/v1";
                        } else {
                            baseUrl = baseUrl + "/api/v1";
                        }
                    }
                }
            }
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                baseUrl = "https://dashscope.aliyuncs.com/api/v1";
            }
            
            String modelName = model != null && !model.trim().isEmpty() ? model : "qwen3-max";
            
            // 只在第一次初始化时输出详细信息
            if (api != null && isFirstInitialization) {
                api.logging().logToOutput("[QianwenApiClient] 初始化LangChain4j ChatModel");
                api.logging().logToOutput("[QianwenApiClient] 原始API URL: " + apiUrl);
                api.logging().logToOutput("[QianwenApiClient] 处理后的BaseURL: " + baseUrl);
                api.logging().logToOutput("[QianwenApiClient] Model: " + modelName);
                api.logging().logToOutput("[QianwenApiClient] EnableThinking: " + enableThinking);
                api.logging().logToOutput("[QianwenApiClient] EnableSearch: " + enableSearch);
            }

            // 使用类成员变量的值，而不是硬编码的默认值
            QwenChatRequestParameters.SearchOptions searchOptions = QwenChatRequestParameters.SearchOptions.builder()
                    // 使返回结果中包含搜索信息的来源
                    .enableSource(true)
                    // 强制开启互联网搜索（根据用户设置）
                    .forcedSearch(enableSearch)
                    // 开启角标标注
                    .enableCitation(true)
                    // 设置角标标注样式为[ref_i]
                    .citationFormat("[ref_<number>]")
                    .searchStrategy("max")
                    .build();

            // 创建请求参数
            //OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
            QwenChatRequestParameters parameters = QwenChatRequestParameters.builder()
                    .enableSearch(enableSearch)
                    .searchOptions(searchOptions)
                    .enableThinking(enableThinking)
                    .build();
            //.customParameters(customParameters)
            
            this.chatModel = QwenStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    //.enableSearch(enableSearch)
                    .temperature(0.1f) // 越小越确定，越大越随机，如果效果不好就切换为0.3
                    .defaultRequestParameters(parameters)
                    .build();

                    
            // 只在第一次初始化时输出成功信息
            if (api != null && isFirstInitialization) {
                api.logging().logToOutput("[QianwenApiClient] LangChain4j ChatModel初始化成功");
                isFirstInitialization = false; // 标记已完成第一次初始化
            }
        } catch (Exception e) {
            if (api != null) {
                api.logging().logToError("[QianwenApiClient] 初始化ChatModel失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 重新初始化ChatModel（当配置更新时调用）
     */
    private void reinitializeChatModel() {
        initializeChatModel();
    }
    
    /**
     * 从配置文件自动加载设置
     * 支持多个配置文件路径：
     * 1. 当前目录下的 ai_analyzer_settings.dat
     * 2. 用户主目录下的 .burp_ai_analyzer_settings
     */
    private void loadSettingsFromFile() {
        // 默认值
        String defaultApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        String defaultApiKey = "";
        String defaultModel = "qwen3-max";
        
        // 尝试从当前目录加载
        File localSettingsFile = new File("ai_analyzer_settings.dat");
        if (localSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(localSettingsFile);
            if (settings != null) {
                this.apiUrl = settings.getApiUrl() != null && !settings.getApiUrl().isEmpty() 
                    ? settings.getApiUrl() : defaultApiUrl;
                this.apiKey = settings.getApiKey() != null && !settings.getApiKey().isEmpty() 
                    ? settings.getApiKey() : defaultApiKey;
                this.model = settings.getModel() != null && !settings.getModel().isEmpty() 
                    ? settings.getModel() : defaultModel;
                this.enableThinking = settings.isEnableThinking();
                this.enableSearch = settings.isEnableSearch();
                return;
            }
        }
        
        // 尝试从用户主目录加载
        File userSettingsFile = new File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
        if (userSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(userSettingsFile);
            if (settings != null) {
                this.apiUrl = settings.getApiUrl() != null && !settings.getApiUrl().isEmpty() 
                    ? settings.getApiUrl() : defaultApiUrl;
                this.apiKey = settings.getApiKey() != null && !settings.getApiKey().isEmpty() 
                    ? settings.getApiKey() : defaultApiKey;
                this.model = settings.getModel() != null && !settings.getModel().isEmpty() 
                    ? settings.getModel() : defaultModel;
                this.enableThinking = settings.isEnableThinking();
                this.enableSearch = settings.isEnableSearch();
                return;
            }
        }
        
        // 如果两个文件都不存在，使用默认值
        this.apiUrl = defaultApiUrl;
        this.apiKey = defaultApiKey;
        this.model = defaultModel;
        this.enableThinking = true;
        this.enableSearch = true;
    }
    
    /**
     * 从指定文件加载设置
     */
    private PluginSettings loadSettingsFromFile(File settingsFile) {
        try {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(settingsFile))) {
                return (PluginSettings) ois.readObject();
            }
        } catch (Exception e) {
            // 加载失败，返回null
            return null;
        }
    }
    
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
        reinitializeChatModel();
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        reinitializeChatModel();
    }
    
    public void setModel(String model) {
        this.model = model;
        reinitializeChatModel();
    }
    
    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
        reinitializeChatModel();
    }
    
    public void setEnableSearch(boolean enableSearch) {
        this.enableSearch = enableSearch;
        reinitializeChatModel();
    }
    
    public boolean isEnableThinking() {
        return enableThinking;
    }
    
    public boolean isEnableSearch() {
        return enableSearch;
    }
    
    /**
     * 设置工具定义列表
     */
    // public void setTools(JsonArray tools) {
    //     this.tools = tools;
    // }
    
    /**
     * 设置工具调用处理器
     */
    // public void setToolCallHandler(Consumer<ToolCall> handler) {
    //     this.toolCallHandler = handler;
    // }

    // 流式输出方法 - 使用LangChain4j
    public void analyzeRequestStream(String httpRequest, String userPrompt, Consumer<String> onChunk) throws Exception {
        if (chatModel == null) {
            throw new Exception("ChatModel未初始化，请检查API Key和URL配置");
        }

        // 构建系统消息
        String systemContent = "你是一个专业的Web安全测试专家，擅长分析HTTP请求和响应中的潜在漏洞。\n\n"
            + "工作要求：\n"
            + "只输出可能存在的owasp top 10或中危及以上安全风险，不要输出低危和无风险的项，并且给出对风险点的渗透测试建议，辅助渗透测试工程师继续进行渗透测试；\n"
            + "不要输出代码表格格式，不要输出'---'；\n"
            + "格式简洁，突出重点，不要冗长描述。";

        SystemMessage systemMessage = new SystemMessage(systemContent);
        String userContent = buildAnalysisContent(httpRequest, userPrompt);
        UserMessage userMessage = new UserMessage(userContent);

        logInfo("使用LangChain4j发送流式请求");
        logInfo("模型: " + (model != null ? model : "qwen-max"));
        logInfo("用户消息长度: " + userContent.length() + " 字符");

        // 添加重试机制
        int maxRetries = 3;
        int retryCount = 0;
        Exception lastException = null;
        // 使用数组包装，以便在匿名内部类中修改
        final int[] contentChunkCount = {0};

        while (retryCount < maxRetries) {
            try {
                // 调用流式生成方法（使用 AI Service 和 StreamingResponseHandler）
                List<ChatMessage> messages = List.of(systemMessage, userMessage);

                // 使用AI Service创建Assistant实例
                //Assistant assistant = AiServices.create(Assistant.class, chatModel);
                Assistant assistant = AiServices.builder(Assistant.class)
                        .streamingChatModel(this.chatModel)
                        .chatMemory(MessageWindowChatMemory.withMaxMessages(100000))
                        .build();

                // 调用流式聊天方法
                TokenStream tokenStream = assistant.chat(messages);
                CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();

                tokenStream
                    // 关键：将 partialResponse 通过 onChunk 传递给UI
                    .onPartialResponse((String partialResponse) -> {
                        if (partialResponse != null && !partialResponse.isEmpty()) {
                            onChunk.accept(partialResponse);
                            contentChunkCount[0]++;
                        }
                    })
                    // 可选：处理思考过程
                    .onPartialThinking((PartialThinking partialThinking) -> {
                        logDebug("Thinking: " + partialThinking);
                    })
                    // 可选：处理中间响应
                    .onIntermediateResponse((ChatResponse intermediateResponse) -> {
                        logDebug("Intermediate response received");
                    })
                    // 工具执行前的回调
                    .beforeToolExecution((BeforeToolExecution beforeToolExecution) -> {
                        logDebug("Before tool execution: " + beforeToolExecution);
                    })
                    // 工具执行后的回调
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        logDebug("Tool executed: " + toolExecution);
                    })
                    // 完成响应时
                    .onCompleteResponse((ChatResponse response) -> {
                        logInfo("流式输出完成，共收到 " + contentChunkCount[0] + " 个chunk");
                        futureResponse.complete(response);
                    })
                    // 错误处理
                    .onError((Throwable error) -> {
                        String errorMsg = error.getMessage();
                        String fullErrorMsg = errorMsg;
                        
                        // 获取完整的错误堆栈信息
                        if (error.getCause() != null) {
                            fullErrorMsg = errorMsg + " | Cause: " + error.getCause().getMessage();
                            // 打印堆栈跟踪
                            if (api != null) {
                                api.logging().logToError("[QianwenApiClient] TokenStream错误堆栈:");
                                error.printStackTrace();
                            }
                        }
                        
                        if (errorMsg != null && errorMsg.contains("404")) {
                            logError("TokenStream错误 (404 Not Found): " + fullErrorMsg);
                            logError("请检查：1) API URL是否正确 2) API Key是否有效 3) 模型名称是否正确");
                            logError("当前配置 - API URL: " + apiUrl + ", Model: " + model);
                        } else {
                            logError("TokenStream错误: " + fullErrorMsg);
                            logError("错误类型: " + error.getClass().getName());
                        }
                        
                        futureResponse.completeExceptionally(error);
                    })
                    .start();

                // 等待流式输出完成（最多等待2分钟）
                ChatResponse finalResponse = futureResponse.get(2, java.util.concurrent.TimeUnit.MINUTES);

                // 记录token使用信息（可选）
                if (finalResponse != null && finalResponse.tokenUsage() != null) {
                    logInfo("最终响应token使用: " + finalResponse.tokenUsage().toString());
                }

                // 成功，跳出重试循环
                break;

            } catch (java.util.concurrent.TimeoutException e) {
                lastException = new Exception("流式输出超时（2分钟）", e);
                retryCount++;
                logError("请求超时: " + e.getMessage());
            } catch (java.util.concurrent.ExecutionException e) {
                // 直接使用原始异常，避免过度包装
                Throwable cause = e.getCause();
                String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
                
                // 打印完整的错误信息
                logError("ExecutionException捕获: " + errorMsg);
                if (cause != null) {
                    logError("异常类型: " + cause.getClass().getName());
                    logError("异常消息: " + cause.getMessage());
                    // 打印堆栈跟踪
                    if (api != null) {
                        api.logging().logToError("[QianwenApiClient] ExecutionException堆栈:");
                        cause.printStackTrace();
                    }
                }
                
                // 检查是否是404错误
                if (errorMsg != null && (errorMsg.contains("404") || errorMsg.contains("Not Found"))) {
                    logError("API请求失败 (404 Not Found): " + errorMsg);
                    logError("可能的原因：");
                    logError("1. API URL配置不正确，当前BaseURL: " + (apiUrl != null ? apiUrl : "未设置"));
                    logError("2. API Key无效或已过期");
                    logError("3. 模型名称不正确，当前模型: " + (model != null ? model : "未设置"));
                    logError("4. 端点路径不正确，请确认使用dashscope模式URL: https://dashscope.aliyuncs.com/v1");
                } else {
                    // 对于其他错误，也输出详细信息
                    logError("API请求失败，错误详情: " + errorMsg);
                }
                
                if (cause instanceof Exception) {
                    lastException = (Exception) cause;
                } else {
                    lastException = new Exception("流式输出失败: " + errorMsg, cause);
                }
                retryCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("等待流式输出被中断", e);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                logError("请求异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                // 打印堆栈跟踪
                if (api != null) {
                    api.logging().logToError("[QianwenApiClient] 异常堆栈:");
                    e.printStackTrace();
                }
            }

        }

        // 检查最终结果
        if (lastException != null && retryCount >= maxRetries) {
            logError("所有重试均失败，共尝试 " + maxRetries + " 次");
            throw new Exception(lastException.getMessage() + " (已重试 " + maxRetries + " 次)", lastException);
        }

        logInfo("流式输出处理完成，总chunk数: " + contentChunkCount[0]);
    }

    private String buildAnalysisContent(String httpContent, String userPrompt) {
        StringBuilder content = new StringBuilder();

        // 如果有HTTP内容，先添加HTTP内容（不重复分析要求，system message已说明）
        if (httpContent != null && !httpContent.trim().isEmpty()) {
            if (httpContent.contains("=== HTTP请求 ===") && httpContent.contains("=== HTTP响应 ===")) {
                content.append("以下是完整的HTTP请求和响应信息：\n\n");
                content.append(httpContent);
            } else if (httpContent.contains("=== HTTP请求 ===")) {
                content.append("以下是HTTP请求内容：\n\n");
                content.append(httpContent);
            } else {
                // 没有特定格式，直接添加
                content.append("以下是HTTP请求内容：\n\n");
                content.append(httpContent);
            }
        }

        // 添加用户提示
        if (userPrompt != null && !userPrompt.trim().isEmpty()) {
            if (content.length() > 0) {
                content.append("\n\n用户提示：").append(userPrompt);
            } else {
                content.append("用户提示：").append(userPrompt);
            }
        }

        return content.toString();
    }

    // 获取API配置的方法
    public String getApiUrl() {
        return apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    /**
     * 工具调用数据类
     */
    /* Tools call 相关代码已注释
    public static class ToolCall {
        private String id;
        private String name;
        private String arguments;
        
        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
        
        public String getId() {
            return id;
        }
        
        public String getName() {
            return name;
        }
        
        public String getArguments() {
            return arguments;
        }
    }
    */
    
    // 日志辅助方法
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[QianwenApiClient] " + message);
        }
    }

    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[QianwenApiClient] " + message);
        }
    }

    private void logDebug(String message) {
        if (api != null) {
            api.logging().logToOutput("[QianwenApiClient] " + message);
        }
    }


}
