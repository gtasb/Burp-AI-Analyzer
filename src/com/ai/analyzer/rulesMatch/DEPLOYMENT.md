# 前置扫描器规则文件部署指南

## 规则文件位置

规则文件 `漏洞匹配规则库.json` 应该放在项目根目录的 `scanners/` 目录下：

```
Burp-AI-Analyzer/
├── scanners/
│   └── 漏洞匹配规则库.json  ← 规则文件
├── src/
├── target/
└── pom.xml
```

## 加载机制

`RuleLoader` 类支持两种加载方式（按优先级）：

### 1. 从文件系统加载（优先级高）
- 适用于开发和调试
- 路径：`scanners/漏洞匹配规则库.json`（相对于工作目录）
- 优点：可以直接修改规则文件，无需重新打包

### 2. 从 Classpath 加载（JAR 内部）
- 适用于生产环境
- 打包时自动包含到 JAR 中
- 路径：`/scanners/漏洞匹配规则库.json`（JAR内部资源）
- 优点：部署简单，无需额外文件

## 打包配置

在 `pom.xml` 中已配置资源打包：

```xml
<resources>
    <resource>
        <directory>${project.basedir}</directory>
        <includes>
            <include>scanners/**/*.json</include>
        </includes>
    </resource>
</resources>
```

## 部署步骤

### 开发环境
1. 将规则文件放在 `scanners/漏洞匹配规则库.json`
2. 运行项目时，会自动从文件系统加载

### 生产环境（打包JAR）
1. 确保 `scanners/漏洞匹配规则库.json` 存在
2. 运行 `mvn clean package`
3. JAR 文件会自动包含规则文件
4. 加载到 Burp Suite 后，从 JAR 内部加载规则

## 验证

查看 Burp Suite 的 Output 标签页，应该看到：

```
[RuleLoader] 成功加载 1000+ 条规则
[PreScanFilterManager] 前置扫描过滤器初始化成功
```

## 故障排查

### 规则文件加载失败
- 错误信息：`无法加载规则文件: scanners/漏洞匹配规则库.json`
- 解决方案：
  1. 检查文件是否存在于正确位置
  2. 检查 JAR 文件中是否包含规则（解压查看）
  3. 查看 Burp Suite 的 Error 日志

### 规则数量不对
- 检查 JSON 文件格式是否正确
- 查看 Error 日志中的解析错误信息

## 更新规则

### 开发环境
直接修改 `scanners/漏洞匹配规则库.json`，重新加载插件

### 生产环境
1. 修改 `scanners/漏洞匹配规则库.json`
2. 重新打包：`mvn clean package`
3. 在 Burp Suite 中重新加载插件
