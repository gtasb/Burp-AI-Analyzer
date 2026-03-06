# 构建说明

本文档详细说明如何编译和构建AI漏洞分析助手插件。

## 前置要求

### 必需
- **Java Development Kit (JDK) 21** 或更高版本
  - 下载地址：https://www.oracle.com/java/technologies/downloads/
  - 或使用OpenJDK：https://adoptium.net/

### 可选（根据构建方式选择）
- **Maven 3.6+**（如果使用Maven构建）
- **Gradle 8.0+**（如果使用Gradle构建）

## 验证Java版本

打开命令行，运行：
```bash
java -version
```

应该看到类似输出：
```
java version "21.0.x" 2024-xx-xx LTS
Java(TM) SE Runtime Environment (build 21.0.x+xx-LTS-xxx)
Java HotSpot(TM) 64-Bit Server VM (build 21.0.x+xx-LTS-xxx, mixed mode, sharing)
```

## 构建方法

### 方法1：使用批处理脚本（推荐，最简单）

**适用于Windows系统，无需安装Maven或Gradle**

1. 打开命令提示符或PowerShell
2. 进入项目目录：
   ```bash
   cd E:\HackTools\develop\PluginExample\untitled
   ```

3. 运行构建脚本：
   ```bash
   compile.bat
   ```

4. 脚本会自动：
   - 检查Java版本
   - 下载Gson依赖库
   - 编译所有Java源文件
   - 打包成JAR文件

5. 构建成功后，JAR文件位于：
   ```
   build\libs\ai-analyzer.jar
   ```

### 方法2：使用Maven

**需要安装Maven**

1. 进入项目目录：
   ```bash
   cd E:\HackTools\develop\PluginExample\untitled
   ```

2. 清理并构建：
   ```bash
   mvn clean package
   ```

3. 构建成功后，JAR文件位于：
   ```
   target\ai-analyzer-1.0.0-jar-with-dependencies.jar
   ```

### 方法3：使用Gradle

**需要安装Gradle**

1. 进入项目目录：
   ```bash
   cd E:\HackTools\develop\PluginExample\untitled
   ```

2. 清理并构建：
   ```bash
   gradle clean jar
   ```

3. 构建成功后，JAR文件位于：
   ```
   build\libs\ai-analyzer-1.0.0.jar
   ```

### 方法4：手动编译（高级用户）

如果你想完全手动控制编译过程：

1. 创建输出目录：
   ```bash
   mkdir build\classes
   mkdir build\libs
   mkdir lib
   ```

2. 下载Gson库到lib目录：
   - 访问：https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/
   - 下载：gson-2.10.1.jar
   - 放到：lib\gson-2.10.1.jar

3. 编译Java文件：
   ```bash
   javac -encoding UTF-8 -source 21 -target 21 ^
         -cp "lib\gson-2.10.1.jar" ^
         -d build\classes ^
         src\*.java
   ```

4. 解压Gson库到classes目录：
   ```bash
   cd build\classes
   jar -xf ..\..\lib\gson-2.10.1.jar
   cd ..\..
   ```

5. 打包JAR：
   ```bash
   jar -cvf build\libs\ai-analyzer.jar -C build\classes .
   ```

## 常见问题

### Q1: 编译时提示"javac不是内部或外部命令"

**解决方案**：
1. 确认已安装JDK（不是JRE）
2. 配置环境变量：
   - 添加`JAVA_HOME`环境变量，指向JDK安装目录
   - 在`Path`中添加`%JAVA_HOME%\bin`

### Q2: 提示Java版本不兼容

**解决方案**：
- 确保使用Java 21或更高版本
- 如果系统中有多个Java版本，确保使用正确的版本：
  ```bash
  set JAVA_HOME=C:\Program Files\Java\jdk-21
  set PATH=%JAVA_HOME%\bin;%PATH%
  ```

### Q3: 无法下载Gson库

**解决方案**：
- 检查网络连接
- 手动下载Gson库：
  1. 访问：https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
  2. 下载后放到`lib`目录

### Q4: Maven/Gradle构建失败

**解决方案**：
- 清理缓存：
  ```bash
  # Maven
  mvn clean
  
  # Gradle
  gradle clean --refresh-dependencies
  ```
- 检查网络代理设置
- 使用国内镜像源（如阿里云Maven镜像）

### Q5: 编译成功但Burp Suite无法加载

**解决方案**：
- 确认JAR文件包含所有依赖（使用jar-with-dependencies）
- 检查Burp Suite的Errors标签页查看错误信息
- 确认Burp Suite版本支持Montoya API

## 验证构建

构建成功后，可以验证JAR文件：

```bash
# 查看JAR文件内容
jar -tf build\libs\ai-analyzer.jar

# 应该包含：
# - AIExtension.class
# - AIAnalyzerTab.class
# - AgentApiClient.class
# - AIContextMenuProvider.class
# - com/google/gson/... (Gson库)
```

## 加载到Burp Suite

1. 启动Burp Suite
2. 进入 `Extensions` -> `Installed`
3. 点击 `Add` 按钮
4. 选择 `Extension type` 为 `Java`
5. 点击 `Select file`，选择构建好的JAR文件
6. 点击 `Next`

如果加载成功，你应该看到：
- Extensions列表中出现"AI漏洞分析助手"
- 顶部标签栏出现"AI分析"标签
- Output标签页显示加载成功的消息

## 开发模式

如果你想修改代码并快速测试：

1. 修改源代码
2. 运行构建脚本
3. 在Burp Suite中卸载旧版本插件
4. 加载新构建的JAR文件

**提示**：可以在Burp Suite中启用"自动重载扩展"功能，修改代码后自动重新加载。

## 调试技巧

### 启用详细日志

在代码中添加更多日志输出：
```java
api.logging().logToOutput("调试信息");
api.logging().logToError("错误信息");
```

### 使用IDE调试

1. 在IntelliJ IDEA或Eclipse中打开项目
2. 配置远程调试
3. 启动Burp Suite时添加调试参数：
   ```bash
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar burpsuite.jar
   ```
4. 在IDE中连接到端口5005进行调试

## 性能优化

### 减小JAR文件大小

如果需要减小JAR文件大小，可以使用ProGuard或其他混淆工具：

```xml
<!-- 在pom.xml中添加 -->
<plugin>
    <groupId>com.github.wvengen</groupId>
    <artifactId>proguard-maven-plugin</artifactId>
    <version>2.6.0</version>
</plugin>
```

### 优化依赖

只包含必需的依赖项，移除未使用的库。

## 持续集成

可以配置GitHub Actions自动构建：

```yaml
name: Build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 21
        uses: actions/setup-java@v2
        with:
          java-version: '21'
          distribution: 'adopt'
      - name: Build with Maven
        run: mvn clean package
      - name: Upload artifact
        uses: actions/upload-artifact@v2
        with:
          name: ai-analyzer
          path: target/*.jar
```

## 发布流程

准备发布新版本时：

1. 更新版本号（pom.xml、build.gradle）
2. 更新CHANGELOG.md
3. 运行完整测试
4. 构建最终版本
5. 创建Git标签
6. 发布到GitHub Releases

## 技术支持

如果遇到构建问题：
1. 查看本文档的常见问题部分
2. 检查Burp Suite的Errors标签页
3. 查看构建日志
4. 提交Issue到GitHub

---

**祝构建顺利！** 🚀

