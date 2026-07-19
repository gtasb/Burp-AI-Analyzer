# 前置扫描过滤器模块

**规则来源**：规则已全部硬编码在 `HardcodedRules.java` 中，不再从 `scanners/漏洞匹配规则库.json` 或任何外部文件加载。部署时无需携带 JSON 规则文件。

## 功能概述

前置扫描过滤器是一个轻量级的漏洞特征匹配系统，在AI分析之前快速识别HTTP流量中的已知漏洞特征。

### 核心特性

1. **多线程并发扫描** - 使用线程池并行匹配规则，提高扫描速度
2. **正则表达式匹配** - 基于成熟的规则库进行快速模式匹配
3. **低延迟设计** - 默认500ms超时，不阻塞主流程
4. **智能提示生成** - 自动生成简洁的提示文本追加到UserPrompt

## 架构设计

```
PreScanFilterManager (管理器)
    ↓
PreScanFilter (核心扫描器)
    ↓
RuleLoader (规则加载器)
    ↓
VulnerabilityRule (规则模型)
    ↓
ScanMatch (匹配结果)
```

## 类说明

### 1. PreScanFilterManager
**职责**: 管理过滤器的生命周期和初始化

**主要方法**:
- `initialize()` - 初始化过滤器（加载规则）
- `enable()` / `disable()` - 启用/禁用过滤器
- `getFilter()` - 获取过滤器实例
- `shutdown()` - 关闭并释放资源

### 2. PreScanFilter
**职责**: 执行实际的规则匹配扫描

**主要方法**:
- `scan(HttpRequestResponse, timeoutMs)` - 扫描HTTP流量
- `buildPromptHint(List<ScanMatch>)` - 生成UserPrompt提示
- `buildUiMessage(List<ScanMatch>)` - 生成UI显示消息

### 3. RuleLoader
**职责**: 从JSON文件加载漏洞匹配规则

**支持的漏洞类型** (35+种):
- **SQL注入** (sqli) - 支持16种数据库
- **命令注入** (command_injection) - Linux/Windows
- **文件包含** (file_inclusion) - LFI/RFI
- **XXE** (xxe, xxe_extended) - XML外部实体
- **SSRF** (ssrf_extended) - 服务端请求伪造
- **LDAP注入** (ldap_injection)
- **XPath注入** (xpath_injection)
- **个人信息检测** (pii_detection) - 身份证/银行卡/手机号
- **敏感信息泄露** (sensitive_info) - API密钥/Token/凭证
- **CORS配置错误** (cors)
- **点击劫持** (clickjacking)
- **反序列化** (deserialization) - Java/PHP/Python
- **JNDI注入** (jndi_injection)
- **JSON错误** (json_error)
- **JSONP信息泄露** (jsonp)
- **开放重定向** (redirect)
- **未授权访问** (unauthorized_access)
- **备份文件泄露** (backup_files)
- **源代码泄露** (source_code)
- **ViewState未加密** (viewstate)
- **敏感文件泄露** (sensitive_files) - Git/SVN/配置文件
- **CRLF注入** (crlf_injection)
- **正则拒绝服务** (redos)
- **验证码绕过** (captcha_bypass)
- **服务端模板注入** (ssti) - 12种模板引擎
- **Fastjson反序列化** (fastjson)
- **PHP路径泄露** (php_path_leak)
- **错误消息** (error_messages) - 框架错误/堆栈跟踪
- **HTTP走私** (http_smuggling)
- **目录列表** (directory_listing)
- **弱密码** (weak_password)
- **Host头注入** (host_header_injection)
- **PHPInfo泄露** (phpinfo_disclosure)
- **编辑器备份** (editor_backup) - Vim/Emacs
- **代码注入扩展** (code_injection_extended)
- **参数安全检查** (parameter_safety_check)
- **内容类型检测** (content_type_detection)

### 4. VulnerabilityRule
**职责**: 表示单个漏洞匹配规则

**字段**:
- `type` - 规则类型
- `name` - 漏洞名称
- `severity` - 危险等级 (high/medium/low)
- `patterns` - 编译后的正则表达式列表

### 5. ScanMatch
**职责**: 表示一次扫描匹配结果

**字段**:
- `vulnerabilityType` - 漏洞类型
- `matchedString` - 匹配到的字符串
- `severity` - 危险等级
- `databaseType` - 数据库类型（SQL注入专用）

## 使用示例

### 基本用法

```java
// 1. 初始化管理器
PreScanFilterManager manager = new PreScanFilterManager(api);
manager.initialize();  // 使用默认配置

// 2. 启用过滤器
manager.enable();

// 3. 扫描HTTP流量
PreScanFilter filter = manager.getFilter();
List<ScanMatch> matches = filter.scan(requestResponse, 500);

// 4. 生成提示文本
if (!matches.isEmpty()) {
    String promptHint = PreScanFilter.buildPromptHint(matches);
    String uiMessage = PreScanFilter.buildUiMessage(matches);
    
    // 追加到UserPrompt
    userPrompt += promptHint;
    
    // 显示到UI
    chatArea.append(uiMessage);
}

// 5. 关闭
manager.shutdown();
```

### 自定义配置

```java
// 使用自定义规则文件和线程池大小
manager.initialize("custom/rules.json", 8);
```

### 集成到主动扫描

在 `AgentApiClient.analyzeRequest()` 中集成：

```java
public void analyzeRequest(HttpRequestResponse requestResponse, ...) {
    // 前置扫描
    if (preScanFilterManager.isEnabled()) {
        PreScanFilter filter = preScanFilterManager.getFilter();
        List<ScanMatch> matches = filter.scan(requestResponse, 500);
        
        if (!matches.isEmpty()) {
            // 追加提示到UserPrompt
            String hint = PreScanFilter.buildPromptHint(matches);
            userPrompt += hint;
            
            // 显示到UI
            String uiMsg = PreScanFilter.buildUiMessage(matches);
            chatArea.append(uiMsg + "\n");
        }
    }
    
    // 继续AI分析...
}
```

### 集成到被动扫描

在 `PassiveScanApiClient.analyzeRequest()` 中集成：

```java
public CompletableFuture<String> analyzeRequest(
    HttpRequestResponse requestResponse, ...) {
    
    // 前置扫描
    List<ScanMatch> matches = new ArrayList<>();
    if (preScanFilterManager.isEnabled()) {
        PreScanFilter filter = preScanFilterManager.getFilter();
        matches = filter.scan(requestResponse, 500);
    }
    
    // 构建扫描提示词
    String scanPrompt = buildScanPrompt(requestResponse);
    
    // 追加前置扫描结果
    if (!matches.isEmpty()) {
        scanPrompt += PreScanFilter.buildPromptHint(matches);
    }
    
    // 继续AI分析...
}
```

## 性能优化

### 1. 线程池大小
- **默认值**: 4个线程
- **推荐值**: 2-8个线程
- **考虑因素**: CPU核心数、规则数量

### 2. 扫描超时
- **默认值**: 500ms
- **推荐值**: 300-1000ms
- **影响**: 超时后自动取消未完成的扫描任务

### 3. 规则优化
- 优先匹配高危漏洞规则
- 避免过于宽泛的正则表达式
- 定期更新规则库

## 规则文件格式

规则文件使用JSON格式，结构示例：

```json
{
  "meta": {
    "version": "2.0.0",
    "description": "漏洞匹配规则库"
  },
  "categories": {
    "sqli": {
      "name": "SQL注入",
      "severity": "high",
      "databases": {
        "mysql": {
          "patterns": [
            "You have an error in your SQL syntax",
            "MySQL server version"
          ]
        }
      }
    }
  }
}
```

## 配置UI集成

在 `AIAnalyzerTab.java` 中添加配置项：

```java
// 添加复选框
private JCheckBox enablePreScanCheckbox;

// 初始化
enablePreScanCheckbox = new JCheckBox("启用前置扫描器");
enablePreScanCheckbox.addActionListener(e -> {
    if (enablePreScanCheckbox.isSelected()) {
        preScanFilterManager.enable();
    } else {
        preScanFilterManager.disable();
    }
});
```

## 注意事项

1. **规则文件路径**: 确保 `scanners/漏洞匹配规则库.json` 文件存在且可读
2. **内存使用**: 规则会在初始化时全部加载到内存
3. **线程安全**: 所有方法都是线程安全的
4. **错误处理**: 单个规则匹配失败不会影响整体扫描
5. **资源释放**: 应用退出时调用 `shutdown()` 释放线程池

## 故障排查

### 问题: 规则加载失败
**解决**: 检查规则文件路径和JSON格式是否正确

### 问题: 扫描超时
**解决**: 增加超时时间或减少规则数量

### 问题: 内存占用高
**解决**: 优化正则表达式或减少线程池大小

## 未来扩展

- [ ] 支持动态加载/卸载规则
- [ ] 添加规则优先级排序
- [ ] 支持自定义规则语法
- [ ] 添加规则命中统计
- [ ] 支持规则热更新
