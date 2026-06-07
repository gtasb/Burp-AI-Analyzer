# 前置扫描过滤器集成指南

本文档说明如何将前置扫描过滤器集成到现有的 Burp AI Analyzer 扩展中。

## 集成步骤

### 1. 在 AIExtension.java 中初始化管理器

```java
package com.ai.analyzer;

import com.ai.analyzer.rulesMatch.PreScanFilterManager;
import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class AIExtension implements BurpExtension {
    
    private PreScanFilterManager preScanFilterManager;
    
    @Override
    public void initialize(MontoyaApi api) {
        // ... 现有初始化代码 ...
        
        // 初始化前置扫描过滤器管理器
        preScanFilterManager = new PreScanFilterManager(api);
        
        // 尝试初始化（加载规则）
        boolean initialized = preScanFilterManager.initialize();
        if (initialized) {
            api.logging().logToOutput("前置扫描过滤器初始化成功");
        } else {
            api.logging().logToError("前置扫描过滤器初始化失败，将禁用该功能");
        }
        
        // 将管理器传递给 UI 标签页
        AIAnalyzerTab tab = new AIAnalyzerTab(api, apiClient, preScanFilterManager);
        api.userInterface().registerSuiteTab("AI Analyzer", tab);
        
        // ... 其他初始化代码 ...
    }
}
```

### 2. 在 AIAnalyzerTab.java 中添加 UI 控件

#### 2.1 添加字段

```java
public class AIAnalyzerTab extends JPanel {
    
    private final PreScanFilterManager preScanFilterManager;
    private JCheckBox enablePreScanCheckbox;
    
    public AIAnalyzerTab(MontoyaApi api, AgentApiClient apiClient, 
                         PreScanFilterManager preScanFilterManager) {
        this.api = api;
        this.apiClient = apiClient;
        this.preScanFilterManager = preScanFilterManager;
        
        initComponents();
    }
    
    // ... 其他代码 ...
}
```

#### 2.2 添加配置面板

```java
private void initComponents() {
    // ... 现有代码 ...
    
    // 在配置面板中添加前置扫描器开关
    JPanel preScanPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    preScanPanel.setBorder(BorderFactory.createTitledBorder("前置扫描器"));
    
    enablePreScanCheckbox = new JCheckBox("启用前置扫描器", false);
    enablePreScanCheckbox.setToolTipText("在AI分析前快速匹配已知漏洞特征");
    enablePreScanCheckbox.addActionListener(e -> {
        boolean enabled = enablePreScanCheckbox.isSelected();
        if (preScanFilterManager != null) {
            if (enabled) {
                preScanFilterManager.enable();
                api.logging().logToOutput("前置扫描器已启用");
            } else {
                preScanFilterManager.disable();
                api.logging().logToOutput("前置扫描器已禁用");
            }
        }
    });
    
    preScanPanel.add(enablePreScanCheckbox);
    
    // 添加到配置页面
    configPanel.add(preScanPanel);
    
    // ... 其他代码 ...
}
```

### 3. 在 AgentApiClient.java 中集成前置扫描

#### 3.1 添加字段

```java
public class AgentApiClient {
    
    private PreScanFilterManager preScanFilterManager;
    
    // 添加设置方法
    public void setPreScanFilterManager(PreScanFilterManager manager) {
        this.preScanFilterManager = manager;
    }
    
    // ... 其他代码 ...
}
```

#### 3.2 修改 analyzeRequest 方法

```java
public void analyzeRequest(HttpRequestResponse requestResponse, 
                          Consumer<String> onChunk,
                          Runnable onComplete,
                          Consumer<String> onError,
                          AtomicBoolean cancelFlag) {
    
    // 构建基础提示词
    String httpContent = HttpFormatter.formatHttpRequestResponse(requestResponse);
    StringBuilder userPromptBuilder = new StringBuilder();
    userPromptBuilder.append("请分析以下HTTP请求/响应的安全风险：\n\n");
    userPromptBuilder.append(httpContent);
    
    // ========== 前置扫描器集成 ==========
    if (preScanFilterManager != null && preScanFilterManager.isEnabled()) {
        try {
            PreScanFilter filter = preScanFilterManager.getFilter();
            if (filter != null) {
                // 扫描（500ms超时）
                List<ScanMatch> matches = filter.scan(requestResponse, 
                    preScanFilterManager.getDefaultScanTimeout());
                
                if (!matches.isEmpty()) {
                    // 在UI中显示匹配结果
                    String uiMessage = PreScanFilter.buildUiMessage(matches);
                    if (onChunk != null) {
                        onChunk.accept("\n" + uiMessage + "\n");
                    }
                    
                    // 追加到UserPrompt
                    String promptHint = PreScanFilter.buildPromptHint(matches);
                    userPromptBuilder.append(promptHint);
                    
                    api.logging().logToOutput("[PreScan] 检测到 " + matches.size() + " 个疑似漏洞特征");
                }
            }
        } catch (Exception e) {
            api.logging().logToError("[PreScan] 扫描失败: " + e.getMessage());
        }
    }
    
    String userPrompt = userPromptBuilder.toString();
    
    // 继续执行AI分析...
    ensureAssistantInitialized();
    // ... 其他代码 ...
}
```

### 4. 在 PassiveScanApiClient.java 中集成前置扫描

#### 4.1 添加字段

```java
public class PassiveScanApiClient {
    
    private PreScanFilterManager preScanFilterManager;
    
    // 添加设置方法
    public void setPreScanFilterManager(PreScanFilterManager manager) {
        this.preScanFilterManager = manager;
    }
    
    // ... 其他代码 ...
}
```

#### 4.2 修改 analyzeRequest 方法

```java
public CompletableFuture<String> analyzeRequest(
    HttpRequestResponse requestResponse,
    AtomicBoolean cancelFlag,
    Consumer<String> onChunk) {
    
    CompletableFuture<String> future = new CompletableFuture<>();
    
    try {
        // 构建扫描提示词
        String scanPrompt = buildScanPrompt(requestResponse);
        
        // ========== 前置扫描器集成 ==========
        if (preScanFilterManager != null && preScanFilterManager.isEnabled()) {
            try {
                PreScanFilter filter = preScanFilterManager.getFilter();
                if (filter != null) {
                    // 扫描（500ms超时）
                    List<ScanMatch> matches = filter.scan(requestResponse, 
                        preScanFilterManager.getDefaultScanTimeout());
                    
                    if (!matches.isEmpty()) {
                        // 追加到扫描提示词
                        String promptHint = PreScanFilter.buildPromptHint(matches);
                        scanPrompt += promptHint;
                        
                        api.logging().logToOutput("[PassiveScan-PreScan] 检测到 " + 
                            matches.size() + " 个疑似漏洞特征");
                    }
                }
            } catch (Exception e) {
                api.logging().logToError("[PassiveScan-PreScan] 扫描失败: " + e.getMessage());
            }
        }
        
        // 继续执行AI分析...
        ensureAssistantInitialized();
        // ... 其他代码 ...
        
    } catch (Exception e) {
        // ... 错误处理 ...
    }
    
    return future;
}
```

### 5. 在 AIAnalyzerTab 构造函数中传递管理器

```java
private void initializePassiveScanManager() {
    passiveScanManager = new PassiveScanManager(api, 1);
    
    // 同步配置
    syncApiConfigToPassiveScan();
    
    // ========== 传递前置扫描管理器 ==========
    if (preScanFilterManager != null) {
        passiveScanManager.getApiClient().setPreScanFilterManager(preScanFilterManager);
        api.logging().logToOutput("已将前置扫描管理器传递给被动扫描客户端");
    }
    
    // ... 其他代码 ...
}
```

### 6. 在 syncApiConfigToPassiveScan 中同步设置

```java
private void syncApiConfigToPassiveScan() {
    if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
        // ... 现有同步代码 ...
        
        // ========== 同步前置扫描管理器 ==========
        if (preScanFilterManager != null) {
            passiveScanManager.getApiClient()
                .setPreScanFilterManager(preScanFilterManager);
        }
        
        // ========== 同步前置扫描管理器到主动扫描 ==========
        if (apiClient != null) {
            apiClient.setPreScanFilterManager(preScanFilterManager);
        }
    }
}
```

## 配置文件

### 确保规则文件存在

规则文件应位于以下路径之一：
1. `scanners/漏洞匹配规则库.json` (推荐，相对于扩展JAR)
2. 绝对路径（可在代码中配置）

如果使用打包的JAR，可以将规则文件放在classpath中，或者在运行时指定路径。

## 测试验证

### 1. 检查初始化日志

启动 Burp Suite 后，在 Output 标签页应看到：

```
[RuleLoader] 成功加载 XXX 条规则
[PreScanFilter] 初始化完成，规则数：XXX，线程池大小：4
前置扫描过滤器初始化成功
```

### 2. 测试前置扫描

1. 在配置页面勾选"启用前置扫描器"
2. 发送一个包含SQL错误信息的响应
3. 观察聊天框是否输出匹配结果
4. 检查发送给LLM的提示词是否包含前置扫描结果

### 3. 性能测试

- 观察扫描延迟（应在500ms内完成）
- 检查CPU使用率（应保持在合理范围）
- 验证多线程并发扫描是否正常工作

## 常见问题

### Q: 规则文件找不到怎么办？
A: 确保规则文件路径正确，可以使用绝对路径或将文件打包到JAR的resources目录。

### Q: 扫描速度太慢怎么办？
A: 可以调整线程池大小或减少规则数量，也可以增加超时时间。

### Q: 如何禁用前置扫描？
A: 在UI中取消勾选"启用前置扫描器"复选框。

### Q: 如何自定义规则？
A: 编辑 `scanners/漏洞匹配规则库.json` 文件，添加自定义规则。

## 后续优化建议

1. **规则缓存**: 对常用规则进行缓存优化
2. **增量扫描**: 只扫描响应体，跳过请求
3. **规则优先级**: 高危规则优先匹配
4. **统计功能**: 记录规则命中率
5. **规则热更新**: 支持运行时更新规则库

## 参考资料

- [README.md](./README.md) - 模块使用说明
- [前置扫描过滤器plan.md](./前置扫描过滤器plan.md) - 开发计划
- `scanners/漏洞匹配规则库.json` - 规则库文件
