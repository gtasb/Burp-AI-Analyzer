package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.ai.analyzer.api.AgentApiClient;
import com.ai.analyzer.model.PluginSettings;
import com.ai.analyzer.model.RequestData;
import com.ai.analyzer.pscan.PassiveScanManager;
import com.ai.analyzer.pscan.ScanResult;
import com.ai.analyzer.skills.Skill;
import com.ai.analyzer.skills.SkillManager;
import com.ai.analyzer.utils.MarkdownRenderer;
import com.ai.analyzer.rulesMatch.PreScanFilterManager;
// import com.example.ai.analyzer.Tools.ToolDefinitions;
// import com.example.ai.analyzer.Tools.ToolExecutor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

public class AIAnalyzerTab extends JPanel {
    private final MontoyaApi api;
    private final AgentApiClient apiClient;
    private final PreScanFilterManager preScanFilterManager;
    
    // UI组件
    private JComboBox<String> apiProviderComboBox; // API 提供者下拉框
    private JTextField apiUrlField;
    private JTextField apiKeyField;
    private JTextField modelField;
    private JTextField customParametersField; // 自定义参数输入框
    private JCheckBox enableThinkingCheckBox;
    private JCheckBox enableSearchCheckBox;
    private JCheckBox enableMcpCheckBox;
    private JTextField BurpMcpUrlField;
    private JCheckBox enableRagMcpCheckBox;
    // private JTextField ragMcpUrlField; // RAG MCP 地址暂时隐藏
    private JTextField ragMcpDocumentsPathField;
    private JCheckBox enableFileSystemAccessCheckBox; // 启用直接查找知识库
    private JCheckBox enableChromeMcpCheckBox;
    private JTextField chromeMcpUrlField;
    // 默认 RAG 功能暂时禁用，改用 RAG MCP
    // private JCheckBox enableRagCheckBox;
    // private JTextField ragDocumentsPathField;
    
    // Skills 标签页组件
    private JCheckBox enableSkillsCheckBox;
    private JTextField skillsDirectoryField;
    private JTable skillsTable;
    private DefaultTableModel skillsTableModel;
    private JTextPane skillPreviewPane;
    
    // 前置扫描器组件
    private JCheckBox enablePreScanCheckbox;
    private JButton browseSkillsDirButton;
    private JButton refreshSkillsButton;
    private JButton createExampleSkillButton;
    
    // 已替换为 passiveScanTable 和 passiveScanTableModel
    // private JTable requestListTable;
    // private DefaultTableModel requestTableModel;
    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;
    private JTextArea userPromptArea;
    private JTextPane resultTextPane;
    private JButton analyzeButton;
    private JButton clearButton;
    private JButton deleteRequestButton;
    private JButton clearAllRequestsButton;
    private JButton saveSettingsButton;
    private JButton loadSettingsButton;
    private JButton stopButton;
    
    // 数据
    private List<RequestData> requestList;
    private int nextRequestId = 1;
    private boolean isAnalyzing = false;
    private SwingWorker<Void, String> currentWorker;
    // private ToolExecutor toolExecutor;
    
    // 被动扫描相关组件
    private PassiveScanManager passiveScanManager;
    private JCheckBox enablePassiveScanCheckBox;
    private JSpinner threadCountSpinner;
    private JButton startPassiveScanButton;
    private JButton stopPassiveScanButton;
    private JLabel passiveScanStatusLabel;
    private JProgressBar passiveScanProgressBar;
    private JTable passiveScanTable;
    private DefaultTableModel passiveScanTableModel;
    private JTextPane passiveScanResultPane;
    private final StringBuilder passiveScanStreamBuffer = new StringBuilder(); // 累积流式输出的buffer
    private Integer currentStreamingId = null; // 当前流式输出的请求ID

    public AIAnalyzerTab(MontoyaApi api, PreScanFilterManager preScanFilterManager) {
        this.api = api;
        this.preScanFilterManager = preScanFilterManager;
        this.apiClient = new AgentApiClient(
            api,
            "https://dashscope.aliyuncs.com/api/v1",
            ""
        );
        this.requestEditor = api.userInterface().createHttpRequestEditor();
        this.responseEditor = api.userInterface().createHttpResponseEditor();
        
        /* Tools call 相关代码已注释
        // 设置工具定义
        apiClient.setTools(ToolDefinitions.getBurpTools());
        
        // 设置工具调用处理器
        apiClient.setToolCallHandler(toolCall -> {
            SwingUtilities.invokeLater(() -> {
                handleToolCall(toolCall);
            });
        });
        
        this.toolExecutor = new ToolExecutor(api);
        */
        this.requestList = new ArrayList<>();
        initializeUI();
        
        // 自动加载配置文件（如果存在）
        autoLoadSettings();
    }

    private void initializeUI() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 创建主标签页
        JTabbedPane mainTabbedPane = new JTabbedPane();
        
        // 第一个标签页：主要功能（请求分析）
        JPanel mainPanel = createMainPanel();
        mainTabbedPane.addTab("请求分析", mainPanel);
        
        // 第二个标签页：配置
        JPanel configPanel = createConfigTabPanel();
        mainTabbedPane.addTab("配置", configPanel);
        
        // 第三个标签页：Skills（自定义技能）
        JPanel skillsPanel = createSkillsTabPanel();
        mainTabbedPane.addTab("Skills", skillsPanel);
        
        add(mainTabbedPane, BorderLayout.CENTER);
    }
    
    /**
     * 创建主功能面板（第一个标签页）
     * 包含被动扫描控制、请求列表（带风险等级）、请求/响应显示、分析结果等
     */
    private JPanel createMainPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        
        // 顶部：被动扫描控制面板
        JPanel passiveScanControlPanel = createPassiveScanControlPanel();
        mainPanel.add(passiveScanControlPanel, BorderLayout.NORTH);
        
        // 创建主分割面板
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setDividerLocation(450);

        // 左侧面板：请求列表（包含风险等级列）
        JPanel requestListPanel = createRequestListPanel();
        mainSplitPane.setLeftComponent(requestListPanel);
        
        // 右侧面板
        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setDividerLocation(300);

        // 请求面板
        JPanel requestPanel = createRequestPanel();
        rightSplitPane.setTopComponent(requestPanel);

        // 结果面板
        JPanel resultPanel = createResultPanel();
        rightSplitPane.setBottomComponent(resultPanel);

        mainSplitPane.setRightComponent(rightSplitPane);
        mainPanel.add(mainSplitPane, BorderLayout.CENTER);

        // 按钮面板
        JPanel buttonPanel = createButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        // 初始化被动扫描管理器
        initializePassiveScanManager();
        
        return mainPanel;
    }
    
    /**
     * 创建被动扫描控制面板
     */
    private JPanel createPassiveScanControlPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("被动扫描"));
        
        // 左侧：控制选项
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        
        enablePassiveScanCheckBox = new JCheckBox("启用被动扫描", false);
        enablePassiveScanCheckBox.setToolTipText("启用后可以自动从HTTP History获取流量进行AI安全扫描");
        enablePassiveScanCheckBox.addActionListener(e -> {
            boolean enabled = enablePassiveScanCheckBox.isSelected();
            threadCountSpinner.setEnabled(enabled);
            startPassiveScanButton.setEnabled(enabled && !passiveScanManager.isRunning());
            stopPassiveScanButton.setEnabled(enabled && passiveScanManager.isRunning());
        });
        controlPanel.add(enablePassiveScanCheckBox);
        
        controlPanel.add(new JLabel("线程数:"));
        SpinnerModel spinnerModel = new SpinnerNumberModel(3, 1, 10, 1);
        threadCountSpinner = new JSpinner(spinnerModel);
        threadCountSpinner.setEnabled(false);
        threadCountSpinner.setPreferredSize(new Dimension(60, 25));
        threadCountSpinner.addChangeListener(e -> {
            if (passiveScanManager != null) {
                passiveScanManager.setThreadCount((Integer) threadCountSpinner.getValue());
            }
        });
        controlPanel.add(threadCountSpinner);
        
        startPassiveScanButton = new JButton("开始扫描");
        startPassiveScanButton.setEnabled(false);
        startPassiveScanButton.addActionListener(e -> startPassiveScan());
        controlPanel.add(startPassiveScanButton);
        
        stopPassiveScanButton = new JButton("停止扫描");
        stopPassiveScanButton.setEnabled(false);
        stopPassiveScanButton.addActionListener(e -> stopPassiveScan());
        controlPanel.add(stopPassiveScanButton);
        
        JButton clearPassiveScanButton = new JButton("清空结果");
        clearPassiveScanButton.addActionListener(e -> clearPassiveScanResults());
        controlPanel.add(clearPassiveScanButton);
        
        panel.add(controlPanel, BorderLayout.WEST);
        
        // 右侧：状态和进度
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        
        passiveScanStatusLabel = new JLabel("就绪");
        passiveScanStatusLabel.setPreferredSize(new Dimension(200, 20));
        statusPanel.add(passiveScanStatusLabel);
        
        passiveScanProgressBar = new JProgressBar(0, 100);
        passiveScanProgressBar.setPreferredSize(new Dimension(150, 20));
        passiveScanProgressBar.setStringPainted(true);
        statusPanel.add(passiveScanProgressBar);
        
        panel.add(statusPanel, BorderLayout.EAST);
        
        return panel;
    }
    
    /**
     * 初始化被动扫描管理器
     */
    private void initializePassiveScanManager() {
        passiveScanManager = new PassiveScanManager(api);
        passiveScanManager.setThreadCount((Integer) threadCountSpinner.getValue());
        
        // 设置结果更新回调
        passiveScanManager.setOnResultUpdated(result -> {
            SwingUtilities.invokeLater(() -> updatePassiveScanTable(result));
        });
        
        // 设置状态变化回调
        passiveScanManager.setOnStatusChanged(status -> {
            SwingUtilities.invokeLater(() -> {
                passiveScanStatusLabel.setText(status);
                boolean running = passiveScanManager.isRunning();
                startPassiveScanButton.setEnabled(enablePassiveScanCheckBox.isSelected() && !running);
                stopPassiveScanButton.setEnabled(enablePassiveScanCheckBox.isSelected() && running);
            });
        });
        
        // 设置进度变化回调
        passiveScanManager.setOnProgressChanged(progress -> {
            SwingUtilities.invokeLater(() -> passiveScanProgressBar.setValue(progress));
        });
        
        // ========== 设置流式输出回调（新增） ==========
        passiveScanManager.setOnStreamingChunk(chunk -> {
            SwingUtilities.invokeLater(() -> {
                if (passiveScanResultPane == null) return;
                // 检查当前选中的行是否正在流式输出
                int selectedRow = passiveScanTable.getSelectedRow();
                if (selectedRow >= 0) {
                    Integer selectedId = (Integer) passiveScanTableModel.getValueAt(selectedRow, 0);
                    ScanResult currentStreaming = passiveScanManager.getCurrentStreamingScanResult();
                    
                    // 只有当前选中的行正在流式输出时，才显示流式输出
                    if (currentStreaming != null && selectedId != null && selectedId.equals(currentStreaming.getId())) {
                        // 如果是新的流式请求（ID不匹配或为null），初始化
                        if (currentStreamingId == null || !currentStreamingId.equals(selectedId)) {
                            passiveScanStreamBuffer.setLength(0);
                            currentStreamingId = selectedId;
                        }
                        
                        // 累积chunk到buffer
                        passiveScanStreamBuffer.append(chunk);
                        
                        try {
                            // 使用流式Markdown渲染 - 传入完整累积的内容
                            MarkdownRenderer.appendMarkdownStreaming(
                                passiveScanResultPane, 
                                passiveScanStreamBuffer.toString(),  // 传入完整累积内容
                                0  // 从头开始渲染
                            );
                            passiveScanResultPane.setCaretPosition(passiveScanResultPane.getStyledDocument().getLength());
                        } catch (Exception e) {
                            // 如果Markdown渲染失败，使用纯文本
                            try {
                                passiveScanResultPane.setText(passiveScanStreamBuffer.toString());
                                passiveScanResultPane.setCaretPosition(passiveScanResultPane.getText().length());
                            } catch (Exception ex) {
                                api.logging().logToError("流式输出失败: " + ex.getMessage());
                            }
                        }
                    }
                }
            });
        });
        
        // 同步API配置到被动扫描客户端
        syncApiConfigToPassiveScan();
    }
    
    /**
     * 同步API配置到被动扫描客户端
     * 包括所有基础配置和MCP相关配置
     */
    private void syncApiConfigToPassiveScan() {
        if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
            com.ai.analyzer.pscan.PassiveScanApiClient psClient = passiveScanManager.getApiClient();
            
            // 基础 API 配置
            psClient.setApiUrl(apiClient.getApiUrl());
            psClient.setApiKey(apiClient.getApiKey());
            psClient.setModel(apiClient.getModel());
            psClient.setApiProvider(apiClient.getApiProvider().getDisplayName());
            psClient.setEnableThinking(apiClient.isEnableThinking());
            psClient.setEnableSearch(apiClient.isEnableSearch());
            
            // MCP 配置（关键：这些配置决定了工具是否可用）
            psClient.setEnableMcp(apiClient.isEnableMcp());
            psClient.setBurpMcpUrl(apiClient.getBurpMcpUrl());
            
            // RAG MCP 配置
            psClient.setEnableRagMcp(apiClient.isEnableRagMcp());
            psClient.setRagMcpUrl(apiClient.getRagMcpUrl());
            psClient.setRagMcpDocumentsPath(apiClient.getRagMcpDocumentsPath());
            
            // Chrome MCP 配置
            psClient.setEnableChromeMcp(apiClient.isEnableChromeMcp());
            psClient.setChromeMcpUrl(apiClient.getChromeMcpUrl());
            
            // 文件系统访问配置
            psClient.setEnableFileSystemAccess(apiClient.isEnableFileSystemAccess());
            
            // ========== 同步前置扫描管理器 ==========
            if (preScanFilterManager != null) {
                psClient.setPreScanFilterManager(preScanFilterManager);
                apiClient.setPreScanFilterManager(preScanFilterManager);
                api.logging().logToOutput("[AIAnalyzerTab] 已将前置扫描管理器同步到主动/被动扫描客户端");
            }
            
            api.logging().logToOutput("[AIAnalyzerTab] 已同步所有配置到被动扫描客户端");
            api.logging().logToOutput("[AIAnalyzerTab] MCP配置 - EnableMcp: " + apiClient.isEnableMcp() + 
                                     ", EnableRagMcp: " + apiClient.isEnableRagMcp() + 
                                     ", EnableChromeMcp: " + apiClient.isEnableChromeMcp());
        }
    }
    
    /**
     * 开始被动扫描
     */
    private void startPassiveScan() {
        if (!enablePassiveScanCheckBox.isSelected()) {
            return;
        }
        
        // 同步最新的API配置
        syncApiConfigToPassiveScan();
        
        // 开始扫描（生产者-消费者模型）
        passiveScanManager.startPassiveScan();
    }
    
    /**
     * 停止被动扫描
     */
    private void stopPassiveScan() {
        passiveScanManager.stopPassiveScan();
    }
    
    /**
     * 清空被动扫描结果
     */
    private void clearPassiveScanResults() {
        passiveScanManager.clearResults();
        passiveScanTableModel.setRowCount(0);
        passiveScanProgressBar.setValue(0);
        passiveScanStatusLabel.setText("就绪");
    }
    
    /**
     * 更新被动扫描表格
     */
    private void updatePassiveScanTable(ScanResult result) {
        // 查找是否已存在该行
        int existingRow = -1;
        for (int i = 0; i < passiveScanTableModel.getRowCount(); i++) {
            Integer id = (Integer) passiveScanTableModel.getValueAt(i, 0);
            if (id != null && id == result.getId()) {
                existingRow = i;
                break;
            }
        }
        
        Object[] rowData = {
            result.getId(),
            result.getMethod(),
            result.getShortUrl(),
            result.getFormattedTimestamp(),
            result.hasResponse() ? "是" : "否",
            result.getRiskLevel().getDisplayName(),
            result.getStatus().getDisplayName()
        };
        
        if (existingRow >= 0) {
            // 更新现有行
            for (int i = 0; i < rowData.length; i++) {
                passiveScanTableModel.setValueAt(rowData[i], existingRow, i);
            }
        } else {
            // 添加新行
            passiveScanTableModel.addRow(rowData);
        }
    }
    
    /**
     * 创建配置标签页（第二个标签页）
     * 包含 API 配置、功能开关、设置按钮等
     */
    private JPanel createConfigTabPanel() {
        JPanel configPanel = new JPanel(new BorderLayout(10, 10));
        configPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // 创建配置面板（复用原有的 createConfigPanel 逻辑，但去掉边框）
        JPanel apiConfigPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        
        // API 提供者选择
        gbc.gridx = 0;
        gbc.gridy = 0;
        apiConfigPanel.add(new JLabel("API 提供者:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] providers = {"DashScope", "OpenAI兼容"};
        apiProviderComboBox = new JComboBox<>(providers);
        apiProviderComboBox.setSelectedIndex(0); // 默认 DashScope
        apiProviderComboBox.addActionListener(e -> {
            String selectedProvider = (String) apiProviderComboBox.getSelectedItem();
            apiClient.setApiProvider(selectedProvider);
            // 根据选择更新默认 URL
            if ("DashScope".equals(selectedProvider)) {
                if (apiUrlField.getText().contains("openai.com") || apiUrlField.getText().isEmpty()) {
                    apiUrlField.setText("https://dashscope.aliyuncs.com/api/v1");
                }
                // DashScope 支持深度思考和搜索
                enableThinkingCheckBox.setEnabled(true);
                enableSearchCheckBox.setEnabled(true);
            } else {
                // OpenAI 兼容模式不支持 DashScope 特有的深度思考和搜索功能
                enableThinkingCheckBox.setEnabled(false);
                enableThinkingCheckBox.setSelected(false);
                enableSearchCheckBox.setEnabled(false);
                enableSearchCheckBox.setSelected(false);
            }
        });
        apiProviderComboBox.setToolTipText("选择 API 提供者：DashScope（通义千问）或 OpenAI 兼容格式（支持 OpenAI、Ollama、LM Studio 等）");
        apiConfigPanel.add(apiProviderComboBox, gbc);
        
        // API URL
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("API URL:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        apiUrlField = new JTextField("https://dashscope.aliyuncs.com/api/v1", 30);
        apiConfigPanel.add(apiUrlField, gbc);
        
        // API Key
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("API Key:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        apiKeyField = new JTextField("", 30);
        apiConfigPanel.add(apiKeyField, gbc);
        
        // Model
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("Model:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        modelField = new JTextField("qwen-max", 30);
        apiConfigPanel.add(modelField, gbc);
        
        // 自定义参数
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("自定义参数:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        customParametersField = new JTextField("", 30);
        customParametersField.setToolTipText("<html><b>自定义参数（JSON 格式，直接透传到 API）</b><br/>" +
            "<br/><b>OpenAI 标准参数：</b><br/>" +
            "temperature, top_p, max_tokens, frequency_penalty, presence_penalty, seed, stop<br/>" +
            "<br/><b>Ollama 专有参数：</b><br/>" +
            "• format: \"json\" 或 JSON Schema 对象（结构化输出）<br/>" +
            "• options: {\"num_ctx\": 8192, \"top_k\": 50, \"min_p\": 0.05, ...}<br/>" +
            "• keep_alive: \"30m\" / \"24h\" / -1（永久）/ 0（立即卸载）<br/>" +
            "• think: true（启用 reasoning 模式，如 qwen3-thinking）<br/>" +
            "<br/><b>示例：</b><br/>" +
            "{\"format\": \"json\", \"keep_alive\": \"30m\", \"options\": {\"num_ctx\": 8192}}</html>");
        apiConfigPanel.add(customParametersField, gbc);
        
        // MCP 配置分隔线
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JSeparator mcpSeparator = new JSeparator();
        apiConfigPanel.add(mcpSeparator, gbc);
        
        // Burp MCP 工具调用开关
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("启用 Burp MCP 工具:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        enableMcpCheckBox = new JCheckBox("启用 Burp MCP 工具调用", false);
        enableMcpCheckBox.addActionListener(e -> {
            boolean enabled = enableMcpCheckBox.isSelected();
            BurpMcpUrlField.setEnabled(enabled);
            apiClient.setEnableMcp(enabled);
            if (enabled && !BurpMcpUrlField.getText().trim().isEmpty()) {
                apiClient.setBurpMcpUrl(BurpMcpUrlField.getText().trim());
            }
        });
        apiConfigPanel.add(enableMcpCheckBox, gbc);
        
        // Burp MCP 地址
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("Burp MCP 地址:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        BurpMcpUrlField = new JTextField("http://127.0.0.1:9876/sse", 30);
        BurpMcpUrlField.setEnabled(false); // 默认禁用，只有启用 MCP 时才可用
        BurpMcpUrlField.addActionListener(e -> {
            if (enableMcpCheckBox.isSelected()) {
                apiClient.setBurpMcpUrl(BurpMcpUrlField.getText().trim());
            }
        });
        BurpMcpUrlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateBurpMcpUrl();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                updateBurpMcpUrl();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                updateBurpMcpUrl();
            }
            private void updateBurpMcpUrl() {
                if (enableMcpCheckBox.isSelected() && !BurpMcpUrlField.getText().trim().isEmpty()) {
                    apiClient.setBurpMcpUrl(BurpMcpUrlField.getText().trim());
                }
            }
        });
        apiConfigPanel.add(BurpMcpUrlField, gbc);

        // 知识库检索工具开关（两个选项放同一行）
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("启用知识库检索工具:"), gbc);
        
        // 创建一个面板放两个 checkbox，左对齐无边距
        JPanel knowledgeBasePanel = new JPanel();
        knowledgeBasePanel.setLayout(new BoxLayout(knowledgeBasePanel, BoxLayout.X_AXIS));
        
        enableRagMcpCheckBox = new JCheckBox("RAG MCP（语义检索）", false);
        enableRagMcpCheckBox.setToolTipText("通过 RAG MCP 服务进行语义检索（需要 uvx rag-mcp）");
        enableRagMcpCheckBox.addActionListener(e -> {
            boolean enabled = enableRagMcpCheckBox.isSelected();
            ragMcpDocumentsPathField.setEnabled(enabled || enableFileSystemAccessCheckBox.isSelected());
            apiClient.setEnableRagMcp(enabled);
            if (enabled && !ragMcpDocumentsPathField.getText().trim().isEmpty()) {
                apiClient.setRagMcpDocumentsPath(ragMcpDocumentsPathField.getText().trim());
            }
        });
        knowledgeBasePanel.add(enableRagMcpCheckBox);
        knowledgeBasePanel.add(Box.createHorizontalStrut(15)); // 间距
        
        enableFileSystemAccessCheckBox = new JCheckBox("直接查找（文件浏览）", false);
        enableFileSystemAccessCheckBox.setToolTipText("让 AI 主动浏览、搜索、读取知识库文件（无需额外服务）");
        enableFileSystemAccessCheckBox.addActionListener(e -> {
            boolean enabled = enableFileSystemAccessCheckBox.isSelected();
            ragMcpDocumentsPathField.setEnabled(enabled || enableRagMcpCheckBox.isSelected());
            apiClient.setEnableFileSystemAccess(enabled);
            if (enabled && !ragMcpDocumentsPathField.getText().trim().isEmpty()) {
                apiClient.setRagMcpDocumentsPath(ragMcpDocumentsPathField.getText().trim());
            }
        });
        knowledgeBasePanel.add(enableFileSystemAccessCheckBox);
        
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        apiConfigPanel.add(knowledgeBasePanel, gbc);
        
        // RAG MCP 地址输入框暂时隐藏
        // // RAG MCP 命令
        // gbc.gridx = 0;
        // gbc.gridy = 7;
        // gbc.fill = GridBagConstraints.NONE;
        // gbc.weightx = 0;
        // apiConfigPanel.add(new JLabel("RAG MCP 地址:"), gbc);
        // gbc.gridx = 1;
        // gbc.fill = GridBagConstraints.HORIZONTAL;
        // gbc.weightx = 1.0;
        // ragMcpUrlField = new JTextField(" ", 30);
        // ragMcpUrlField.setEnabled(false); // 默认禁用，只有启用 RAG MCP 时才可用
        // ragMcpUrlField.addActionListener(e -> {
        //     if (enableRagMcpCheckBox.isSelected()) {
        //         apiClient.setRagMcpUrl(ragMcpUrlField.getText().trim());
        //     }
        // });
        // ragMcpUrlField.getDocument().addDocumentListener(new DocumentListener() {
        //     @Override
        //     public void insertUpdate(DocumentEvent e) {
        //         updateRagMcpUrl();
        //     }
        //     @Override
        //     public void removeUpdate(DocumentEvent e) {
        //         updateRagMcpUrl();
        //     }
        //     @Override
        //     public void changedUpdate(DocumentEvent e) {
        //         updateRagMcpUrl();
        //     }
        //     private void updateRagMcpUrl() {
        //         if (enableRagMcpCheckBox.isSelected() && !ragMcpUrlField.getText().trim().isEmpty()) {
        //             apiClient.setRagMcpUrl(ragMcpUrlField.getText().trim());
        //         }
        //     }
        // });
        // apiConfigPanel.add(ragMcpUrlField, gbc);

        // RAG MCP 文档路径
        gbc.gridx = 0;
        gbc.gridy = 9;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("知识库 文档路径:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        ragMcpDocumentsPathField = new JTextField("", 30);
        ragMcpDocumentsPathField.setEnabled(true); // 默认启用
        ragMcpDocumentsPathField.setToolTipText("指定 知识库文档目录路径");
        ragMcpDocumentsPathField.addActionListener(e -> {
            if (enableRagMcpCheckBox.isSelected()) {
                apiClient.setRagMcpDocumentsPath(ragMcpDocumentsPathField.getText().trim());
            }
        });
        ragMcpDocumentsPathField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateRagMcpDocumentsPath();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                updateRagMcpDocumentsPath();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                updateRagMcpDocumentsPath();
            }
            private void updateRagMcpDocumentsPath() {
                if (enableRagMcpCheckBox.isSelected() && !ragMcpDocumentsPathField.getText().trim().isEmpty()) {
                    apiClient.setRagMcpDocumentsPath(ragMcpDocumentsPathField.getText().trim());
                }
            }
        });
        apiConfigPanel.add(ragMcpDocumentsPathField, gbc);

        // Chrome MCP 工具调用开关
        gbc.gridx = 0;
        gbc.gridy = 10;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("启用 Chrome MCP 工具:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        enableChromeMcpCheckBox = new JCheckBox("启用 Chrome MCP 工具调用", false);
        enableChromeMcpCheckBox.addActionListener(e -> {
            boolean enabled = enableChromeMcpCheckBox.isSelected();
            chromeMcpUrlField.setEnabled(enabled);
            apiClient.setEnableChromeMcp(enabled);
            if (enabled && !chromeMcpUrlField.getText().trim().isEmpty()) {
                apiClient.setChromeMcpUrl(chromeMcpUrlField.getText().trim());
            }
        });
        apiConfigPanel.add(enableChromeMcpCheckBox, gbc);

        // Chrome MCP 地址
        gbc.gridx = 0;
        gbc.gridy = 11;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        apiConfigPanel.add(new JLabel("Chrome MCP 地址:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        chromeMcpUrlField = new JTextField(" ", 30);
        chromeMcpUrlField.setEnabled(false); // 默认禁用，只有启用 Chrome MCP 时才可用
        chromeMcpUrlField.addActionListener(e -> {
            if (enableChromeMcpCheckBox.isSelected()) {
                apiClient.setChromeMcpUrl(chromeMcpUrlField.getText().trim());
            }
        });
        chromeMcpUrlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateChromeMcpUrl();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                updateChromeMcpUrl();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                updateChromeMcpUrl();
            }
            private void updateChromeMcpUrl() {
                if (enableChromeMcpCheckBox.isSelected() && !chromeMcpUrlField.getText().trim().isEmpty()) {
                    apiClient.setChromeMcpUrl(chromeMcpUrlField.getText().trim());
                }
            }
        });
        apiConfigPanel.add(chromeMcpUrlField, gbc);

        // RAG 配置分隔线
        gbc.gridx = 0;
        gbc.gridy = 12;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JSeparator ragSeparator = new JSeparator();
        apiConfigPanel.add(ragSeparator, gbc);
        
        // ========== 前置扫描器配置 ==========
        // 前置扫描器开关
        gbc.gridx = 0;
        gbc.gridy = 13;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        enablePreScanCheckbox = new JCheckBox("启用前置扫描器", false);
        enablePreScanCheckbox.setToolTipText("<html><b>前置扫描器</b>：在AI分析前快速匹配已知漏洞特征<br/>" +
            "• 支持35+种漏洞类型（SQL注入、命令注入、文件包含、XXE、SSRF等）<br/>" +
            "• 硬编码规则库（130+条规则，570+个模式）<br/>" +
            "• 多线程并发扫描（默认500ms超时）<br/>" +
            "• 匹配结果会自动追加到AI提示词中<br/>" +
            "• 配置会自动保存，无需每次手动勾选</html>");
        enablePreScanCheckbox.addActionListener(e -> {
            boolean enabled = enablePreScanCheckbox.isSelected();
            if (preScanFilterManager != null) {
                if (enabled) {
                    preScanFilterManager.enable();
                    api.logging().logToOutput("[PreScan] 前置扫描器已启用");
                } else {
                    preScanFilterManager.disable();
                    api.logging().logToOutput("[PreScan] 前置扫描器已禁用");
                }
            } else {
                api.logging().logToError("[PreScan] 前置扫描管理器未初始化");
            }
        });
        apiConfigPanel.add(enablePreScanCheckbox, gbc);
        
        // ========== 默认 RAG 功能暂时禁用，改用 RAG MCP ==========
        // // RAG 工具调用开关
        // gbc.gridx = 0;
        // gbc.gridy = 12;
        // gbc.gridwidth = 1;
        // gbc.fill = GridBagConstraints.NONE;
        // gbc.weightx = 0;
        // apiConfigPanel.add(new JLabel("启用 RAG:"), gbc);
        // gbc.gridx = 1;
        // gbc.fill = GridBagConstraints.HORIZONTAL;
        // gbc.weightx = 1.0;
        // enableRagCheckBox = new JCheckBox("启用 RAG（检索增强生成）", false);
        // enableRagCheckBox.addActionListener(e -> {
        //     boolean enabled = enableRagCheckBox.isSelected();
        //     ragDocumentsPathField.setEnabled(enabled);
        //     apiClient.setEnableRag(enabled);
        //     if (enabled && !ragDocumentsPathField.getText().trim().isEmpty()) {
        //         apiClient.setRagDocumentsPath(ragDocumentsPathField.getText().trim());
        //     }
        // });
        // apiConfigPanel.add(enableRagCheckBox, gbc);
        // 
        // // RAG 文档路径
        // gbc.gridx = 0;
        // gbc.gridy = 13;
        // gbc.fill = GridBagConstraints.NONE;
        // gbc.weightx = 0;
        // apiConfigPanel.add(new JLabel("RAG 文档路径:"), gbc);
        // gbc.gridx = 1;
        // gbc.fill = GridBagConstraints.HORIZONTAL;
        // gbc.weightx = 1.0;
        // ragDocumentsPathField = new JTextField("", 30);
        // ragDocumentsPathField.setEnabled(true);
        // ragDocumentsPathField.addActionListener(e -> {
        //     if (enableRagCheckBox.isSelected()) {
        //         apiClient.setRagDocumentsPath(ragDocumentsPathField.getText().trim());
        //     }
        // });
        // ragDocumentsPathField.getDocument().addDocumentListener(new DocumentListener() {
        //     @Override public void insertUpdate(DocumentEvent e) { updateRagDocumentsPath(); }
        //     @Override public void removeUpdate(DocumentEvent e) { updateRagDocumentsPath(); }
        //     @Override public void changedUpdate(DocumentEvent e) { updateRagDocumentsPath(); }
        //     private void updateRagDocumentsPath() {
        //         if (enableRagCheckBox.isSelected() && !ragDocumentsPathField.getText().trim().isEmpty()) {
        //             apiClient.setRagDocumentsPath(ragDocumentsPathField.getText().trim());
        //         }
        //     }
        // });
        // apiConfigPanel.add(ragDocumentsPathField, gbc);
        // ========== 默认 RAG 功能暂时禁用结束 ==========
        
        // 设置按钮
        gbc.gridx = 0;
        gbc.gridy = 14;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        saveSettingsButton = new JButton("保存设置");
        loadSettingsButton = new JButton("加载设置");
        
        saveSettingsButton.addActionListener(e -> saveSettings());
        loadSettingsButton.addActionListener(e -> loadSettings());
        
        settingsPanel.add(saveSettingsButton);
        settingsPanel.add(loadSettingsButton);
        apiConfigPanel.add(settingsPanel, gbc);
        
        configPanel.add(apiConfigPanel, BorderLayout.NORTH);
        
        // 添加说明文本
        JTextArea infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        infoArea.setBackground(new Color(245, 245, 245));
        infoArea.setForeground(new Color(100, 100, 100));
        infoArea.setText("配置说明：\n" +
                        "• API URL: 通义千问 API 的端点地址\n" +
                        "• API Key: 从阿里云 DashScope 获取的 API 密钥\n" +
                        "• Model: 使用的模型名称（如 qwen-max, qwen-plus 等）\n" +
                        "• 启用 MCP 工具: 启用后 AI 可以调用 Burp Suite 的 MCP 工具\n" +
                        "• Burp MCP 地址: Burp MCP Server 的 SSE 端点地址（默认: http://127.0.0.1:9876/sse）\n" +
                        "• 启用 RAG: 启用检索增强生成，AI 可以从指定文档目录中检索相关信息\n" +
                        "• RAG 文档路径: 包含文档的目录路径（支持 PDF、Word、HTML 等格式，会递归加载子目录）\n" +
                        "\n提示：配置修改后会自动应用到 API 客户端，无需重启插件。\n" +
                        "功能开关（深度思考、网络搜索）位于\"请求分析\"标签页中。\n" +
                        "注意：启用 RAG 后，每次加载插件时会自动加载文档到内存中（向量化过程）。");
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        JScrollPane infoScrollPane = new JScrollPane(infoArea);
        infoScrollPane.setBorder(BorderFactory.createTitledBorder("配置说明"));
        configPanel.add(infoScrollPane, BorderLayout.CENTER);
        
        return configPanel;
    }
    
    /**
     * 创建 Skills 标签页（第三个标签页）
     * 用于管理用户自定义的技能（Skills）
     */
    private JPanel createSkillsTabPanel() {
        JPanel skillsPanel = new JPanel(new BorderLayout(10, 10));
        skillsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // 顶部配置面板
        JPanel topPanel = new JPanel(new GridBagLayout());
        topPanel.setBorder(BorderFactory.createTitledBorder("Skills 配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        
        // 启用 Skills 复选框
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        enableSkillsCheckBox = new JCheckBox("启用 Skills（自定义技能指令）", false);
        enableSkillsCheckBox.setToolTipText("启用后，AI 将加载并应用选中的技能指令");
        enableSkillsCheckBox.addActionListener(e -> {
            boolean enabled = enableSkillsCheckBox.isSelected();
            skillsDirectoryField.setEnabled(enabled);
            browseSkillsDirButton.setEnabled(enabled);
            refreshSkillsButton.setEnabled(enabled);
            createExampleSkillButton.setEnabled(enabled);
            skillsTable.setEnabled(enabled);
            apiClient.setEnableSkills(enabled);
        });
        topPanel.add(enableSkillsCheckBox, gbc);
        
        // Skills 目录路径
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        topPanel.add(new JLabel("Skills 目录:"), gbc);
        
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        skillsDirectoryField = new JTextField("", 40);
        skillsDirectoryField.setEnabled(false);
        skillsDirectoryField.setToolTipText("包含 SKILL.md 文件的目录路径");
        topPanel.add(skillsDirectoryField, gbc);
        
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        browseSkillsDirButton = new JButton("浏览...");
        browseSkillsDirButton.setEnabled(false);
        browseSkillsDirButton.addActionListener(e -> browseSkillsDirectory());
        topPanel.add(browseSkillsDirButton, gbc);
        
        // 按钮面板
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        
        refreshSkillsButton = new JButton("刷新 Skills");
        refreshSkillsButton.setEnabled(false);
        refreshSkillsButton.addActionListener(e -> refreshSkills());
        buttonsPanel.add(refreshSkillsButton);
        
        createExampleSkillButton = new JButton("创建示例 Skill");
        createExampleSkillButton.setEnabled(false);
        createExampleSkillButton.addActionListener(e -> createExampleSkill());
        buttonsPanel.add(createExampleSkillButton);
        
        topPanel.add(buttonsPanel, gbc);
        
        skillsPanel.add(topPanel, BorderLayout.NORTH);
        
        // 中间分割面板：技能列表 + 预览
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);
        
        // 左侧：Skills 列表
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createTitledBorder("已加载的 Skills"));
        
        String[] columnNames = {"启用", "名称", "描述"};
        skillsTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                return String.class;
            }
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // 只有"启用"列可编辑
            }
        };
        skillsTable = new JTable(skillsTableModel);
        skillsTable.setEnabled(false);
        skillsTable.getColumnModel().getColumn(0).setMaxWidth(50);
        skillsTable.getColumnModel().getColumn(0).setMinWidth(50);
        skillsTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        skillsTable.getColumnModel().getColumn(2).setPreferredWidth(250);
        
        // 监听复选框变化
        skillsTableModel.addTableModelListener(e -> {
            if (e.getColumn() == 0) {
                int row = e.getFirstRow();
                Boolean enabled = (Boolean) skillsTableModel.getValueAt(row, 0);
                String skillName = (String) skillsTableModel.getValueAt(row, 1);
                apiClient.getSkillManager().setSkillEnabled(skillName, enabled);
            }
        });
        
        // 监听选择变化，更新预览
        skillsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSkillPreview();
            }
        });
        
        JScrollPane tableScrollPane = new JScrollPane(skillsTable);
        listPanel.add(tableScrollPane, BorderLayout.CENTER);
        
        splitPane.setLeftComponent(listPanel);
        
        // 右侧：Skill 预览
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createTitledBorder("Skill 预览"));
        
        skillPreviewPane = new JTextPane();
        skillPreviewPane.setEditable(false);
        skillPreviewPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        skillPreviewPane.setBackground(new Color(250, 250, 250));
        JScrollPane previewScrollPane = new JScrollPane(skillPreviewPane);
        previewPanel.add(previewScrollPane, BorderLayout.CENTER);
        
        splitPane.setRightComponent(previewPanel);
        
        skillsPanel.add(splitPane, BorderLayout.CENTER);
        
        // 底部说明
        JTextArea infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        infoArea.setBackground(new Color(245, 245, 245));
        infoArea.setForeground(new Color(100, 100, 100));
        infoArea.setText("Skills 使用说明：\n" +
                        "• Skills 是用户自定义的指令集，用于指导 AI 执行特定任务\n" +
                        "• 每个 Skill 是一个包含 SKILL.md 文件的文件夹\n" +
                        "• SKILL.md 格式：\n" +
                        "  ---\n" +
                        "  name: skill-name\n" +
                        "  description: 技能描述\n" +
                        "  ---\n" +
                        "  # 技能指令内容...\n" +
                        "• 勾选技能后，其指令将被添加到 AI 的系统提示词中\n" +
                        "• 参考: https://github.com/anthropics/skills");
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        infoArea.setRows(6);
        JScrollPane infoScrollPane = new JScrollPane(infoArea);
        infoScrollPane.setBorder(BorderFactory.createTitledBorder("使用说明"));
        infoScrollPane.setPreferredSize(new Dimension(0, 120));
        
        skillsPanel.add(infoScrollPane, BorderLayout.SOUTH);
        
        return skillsPanel;
    }
    
    /**
     * 浏览 Skills 目录
     */
    private void browseSkillsDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择 Skills 目录");
        
        // 如果已有路径，从该路径开始
        String currentPath = skillsDirectoryField.getText().trim();
        if (!currentPath.isEmpty()) {
            File currentDir = new File(currentPath);
            if (currentDir.exists()) {
                fileChooser.setCurrentDirectory(currentDir);
            }
        }
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String selectedPath = fileChooser.getSelectedFile().getAbsolutePath();
            skillsDirectoryField.setText(selectedPath);
            apiClient.setSkillsDirectoryPath(selectedPath);
            refreshSkills();
        }
    }
    
    /**
     * 刷新 Skills 列表
     */
    private void refreshSkills() {
        String dirPath = skillsDirectoryField.getText().trim();
        if (dirPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先设置 Skills 目录路径", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // 设置路径并加载
        apiClient.setSkillsDirectoryPath(dirPath);
        apiClient.getSkillManager().loadSkills();
        
        // 更新表格
        updateSkillsTable();
        
        api.logging().logToOutput("Skills 已刷新，共 " + apiClient.getSkillManager().getAllSkills().size() + " 个");
    }
    
    /**
     * 更新 Skills 表格
     */
    private void updateSkillsTable() {
        skillsTableModel.setRowCount(0);
        
        List<Skill> skills = apiClient.getSkillManager().getAllSkills();
        for (Skill skill : skills) {
            Object[] row = {
                skill.isEnabled(),
                skill.getName(),
                skill.getShortDescription()
            };
            skillsTableModel.addRow(row);
        }
    }
    
    /**
     * 更新 Skill 预览
     */
    private void updateSkillPreview() {
        int selectedRow = skillsTable.getSelectedRow();
        if (selectedRow < 0) {
            skillPreviewPane.setText("");
            return;
        }
        
        String skillName = (String) skillsTableModel.getValueAt(selectedRow, 1);
        Skill skill = apiClient.getSkillManager().getSkill(skillName);
        
        if (skill != null) {
            StringBuilder preview = new StringBuilder();
            preview.append("【名称】").append(skill.getName()).append("\n\n");
            preview.append("【描述】").append(skill.getDescription()).append("\n\n");
            preview.append("【文件路径】").append(skill.getFilePath()).append("\n\n");
            preview.append("【指令内容】\n").append(skill.getContent());
            skillPreviewPane.setText(preview.toString());
            skillPreviewPane.setCaretPosition(0);
        }
    }
    
    /**
     * 创建示例 Skill
     */
    private void createExampleSkill() {
        String dirPath = skillsDirectoryField.getText().trim();
        if (dirPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先设置 Skills 目录路径", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // 检查目录是否存在
        File dir = new File(dirPath);
        if (!dir.exists()) {
            int result = JOptionPane.showConfirmDialog(this, 
                "目录不存在，是否创建？\n" + dirPath, 
                "确认", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                if (!dir.mkdirs()) {
                    JOptionPane.showMessageDialog(this, "创建目录失败", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else {
                return;
            }
        }
        
        // 创建示例 Skill
        apiClient.getSkillManager().createExampleSkill(dirPath);
        
        // 刷新列表
        refreshSkills();
        
        JOptionPane.showMessageDialog(this, 
            "示例 Skill 已创建！\n路径: " + dirPath + "/example-skill/SKILL.md", 
            "成功", JOptionPane.INFORMATION_MESSAGE);
    }

    private JPanel createRequestListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("请求列表（被动扫描）"));

        // 创建带风险等级的表格
        String[] columnNames = {"ID", "方法", "URL", "时间", "有响应", "风险等级", "状态"};
        passiveScanTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
            
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Integer.class;
                return String.class;
            }
        };
        
        passiveScanTable = new JTable(passiveScanTableModel);
        passiveScanTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // 设置列宽
        passiveScanTable.getColumnModel().getColumn(0).setPreferredWidth(40);  // ID
        passiveScanTable.getColumnModel().getColumn(0).setMaxWidth(50);
        passiveScanTable.getColumnModel().getColumn(1).setPreferredWidth(50);  // 方法
        passiveScanTable.getColumnModel().getColumn(1).setMaxWidth(60);
        passiveScanTable.getColumnModel().getColumn(2).setPreferredWidth(200); // URL
        passiveScanTable.getColumnModel().getColumn(3).setPreferredWidth(60);  // 时间
        passiveScanTable.getColumnModel().getColumn(3).setMaxWidth(70);
        passiveScanTable.getColumnModel().getColumn(4).setPreferredWidth(50);  // 有响应
        passiveScanTable.getColumnModel().getColumn(4).setMaxWidth(60);
        passiveScanTable.getColumnModel().getColumn(5).setPreferredWidth(60);  // 风险等级
        passiveScanTable.getColumnModel().getColumn(5).setMaxWidth(70);
        passiveScanTable.getColumnModel().getColumn(6).setPreferredWidth(60);  // 状态
        passiveScanTable.getColumnModel().getColumn(6).setMaxWidth(70);
        
        // 设置风险等级列的颜色渲染器
        passiveScanTable.getColumnModel().getColumn(5).setCellRenderer(new RiskLevelCellRenderer());
        
        // 选择行时显示详细信息
        passiveScanTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selectedRow = passiveScanTable.getSelectedRow();
                if (selectedRow >= 0 && passiveScanManager != null) {
                    Integer id = (Integer) passiveScanTableModel.getValueAt(selectedRow, 0);
                    if (id != null) {
                        ScanResult result = passiveScanManager.getResultById(id);
                        if (result != null) {
                            // 如果切换到不同的请求，清空流式buffer（但不清空 currentStreamingId，让 displayScanResult 处理）
                            Integer selectedId = (Integer) passiveScanTableModel.getValueAt(selectedRow, 0);
                            if (currentStreamingId != null && !selectedId.equals(currentStreamingId)) {
                                passiveScanStreamBuffer.setLength(0);
                            }
                            
                            displayScanResult(result);
                            
                            // 如果选中的行正在扫描中，清空结果区域准备接收流式输出
                            if (result.getStatus() == ScanResult.ScanStatus.SCANNING) {
                                passiveScanResultPane.setText("");
                                passiveScanStreamBuffer.setLength(0);
                                currentStreamingId = selectedId;
                            } else if (currentStreamingId != null && currentStreamingId.equals(selectedId)) {
                                // 如果这个请求之前在流式输出，但现在已经完成，清理状态
                                currentStreamingId = null;
                                passiveScanStreamBuffer.setLength(0);
                            }
                        }
                    }
                } else {
                    clearHttpEditors();
                    passiveScanResultPane.setText("");
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(passiveScanTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        // 请求列表按钮
        JPanel requestListButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        deleteRequestButton = new JButton("删除选中");
        clearAllRequestsButton = new JButton("清空所有");
        
        deleteRequestButton.addActionListener(e -> deleteSelectedPassiveScanResult());
        clearAllRequestsButton.addActionListener(e -> clearPassiveScanResults());

        requestListButtonPanel.add(deleteRequestButton);
        requestListButtonPanel.add(clearAllRequestsButton);
        
        // 添加统计信息
        JButton showStatsButton = new JButton("统计信息");
        showStatsButton.addActionListener(e -> showPassiveScanStats());
        requestListButtonPanel.add(showStatsButton);
        
        panel.add(requestListButtonPanel, BorderLayout.SOUTH);

        return panel;
    }
    
    /**
     * 风险等级单元格渲染器
     * 根据风险等级显示不同的背景颜色
     */
    private class RiskLevelCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            
            String riskLevel = value != null ? value.toString() : "";
            
            if (!isSelected) {
                switch (riskLevel) {
                    case "严重":
                        c.setBackground(new Color(255, 100, 100)); // 红色
                        c.setForeground(Color.WHITE);
                        break;
                    case "高":
                        c.setBackground(new Color(255, 165, 0)); // 橙色
                        c.setForeground(Color.BLACK);
                        break;
                    case "中":
                        c.setBackground(new Color(100, 149, 237)); // 蓝色
                        c.setForeground(Color.WHITE);
                        break;
                    case "低":
                        c.setBackground(new Color(144, 238, 144)); // 浅绿色
                        c.setForeground(Color.BLACK);
                        break;
                    case "信息":
                        c.setBackground(new Color(200, 200, 200)); // 灰色
                        c.setForeground(Color.BLACK);
                        break;
                    default:
                        c.setBackground(Color.WHITE);
                        c.setForeground(Color.BLACK);
                        break;
                }
            }
            
            setHorizontalAlignment(CENTER);
            return c;
        }
    }
    
    /**
     * 显示扫描结果详情
     */
    private void displayScanResult(ScanResult result) {
        // 显示HTTP请求/响应
        HttpRequestResponse requestResponse = result.getRequestResponse();
        if (requestResponse != null) {
            if (requestResponse.request() != null) {
                requestEditor.setRequest(requestResponse.request());
            } else {
                requestEditor.setRequest(HttpRequest.httpRequest());
            }
            
            if (requestResponse.response() != null) {
                responseEditor.setResponse(requestResponse.response());
            } else {
                responseEditor.setResponse(HttpResponse.httpResponse());
            }
        }
        
        // 显示AI分析结果
        String analysisResult = result.getAnalysisResult();
        if (analysisResult != null && !analysisResult.isEmpty()) {
            // 扫描完成，清理流式输出状态
            if (currentStreamingId != null && currentStreamingId == result.getId()) {
                currentStreamingId = null;
                passiveScanStreamBuffer.setLength(0);
            }
            
            try {
                passiveScanResultPane.setText("");
                MarkdownRenderer.appendMarkdown(passiveScanResultPane, analysisResult);
            } catch (Exception e) {
                passiveScanResultPane.setText(analysisResult);
            }
        } else if (result.getErrorMessage() != null) {
            // 扫描出错，清理流式输出状态
            if (currentStreamingId != null && currentStreamingId == result.getId()) {
                currentStreamingId = null;
                passiveScanStreamBuffer.setLength(0);
            }
            passiveScanResultPane.setText("扫描错误: " + result.getErrorMessage());
        } else if (result.getStatus() == ScanResult.ScanStatus.SCANNING) {
            // 如果正在扫描，显示提示信息（等待流式输出）
            passiveScanResultPane.setText("正在分析中，请稍候...\n\n");
        } else {
            passiveScanResultPane.setText("状态: " + result.getStatus().getDisplayName());
        }
    }
    
    /**
     * 删除选中的被动扫描结果
     */
    private void deleteSelectedPassiveScanResult() {
        int selectedRow = passiveScanTable.getSelectedRow();
        if (selectedRow >= 0) {
            passiveScanTableModel.removeRow(selectedRow);
            clearHttpEditors();
            resultTextPane.setText("");
        }
    }
    
    /**
     * 显示被动扫描统计信息
     */
    private void showPassiveScanStats() {
        if (passiveScanManager == null) return;
        
        Map<ScanResult.RiskLevel, Integer> stats = passiveScanManager.getStatsByRiskLevel();
        
        StringBuilder sb = new StringBuilder();
        sb.append("被动扫描统计\n");
        sb.append("================\n\n");
        sb.append("总扫描数: ").append(passiveScanManager.getTotalCount()).append("\n");
        sb.append("已完成: ").append(passiveScanManager.getCompletedCount()).append("\n\n");
        sb.append("风险分布:\n");
        sb.append("  严重: ").append(stats.get(ScanResult.RiskLevel.CRITICAL)).append("\n");
        sb.append("  高: ").append(stats.get(ScanResult.RiskLevel.HIGH)).append("\n");
        sb.append("  中: ").append(stats.get(ScanResult.RiskLevel.MEDIUM)).append("\n");
        sb.append("  低: ").append(stats.get(ScanResult.RiskLevel.LOW)).append("\n");
        sb.append("  信息: ").append(stats.get(ScanResult.RiskLevel.INFO)).append("\n");
        sb.append("  无: ").append(stats.get(ScanResult.RiskLevel.NONE)).append("\n");
        
        JOptionPane.showMessageDialog(this, sb.toString(), "扫描统计", JOptionPane.INFORMATION_MESSAGE);
    }

    private JPanel createRequestPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("HTTP请求和响应"));

        // 创建左右分割面板
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);

        // 左侧：请求区域
        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.setBorder(BorderFactory.createTitledBorder("HTTP请求"));
        requestPanel.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        // 右侧：响应区域
        JPanel responsePanel = new JPanel(new BorderLayout());
        responsePanel.setBorder(BorderFactory.createTitledBorder("HTTP响应"));
        responsePanel.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        splitPane.setLeftComponent(requestPanel);
        splitPane.setRightComponent(responsePanel);

        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createResultPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("AI分析结果"));

        resultTextPane = new JTextPane();
        resultTextPane.setEditable(false);
        resultTextPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        resultTextPane.setContentType("text/plain");
        resultTextPane.setBackground(Color.WHITE);
        resultTextPane.setForeground(Color.BLACK);
        // 被动扫描与主动分析共用同一结果区域
        passiveScanResultPane = resultTextPane;
        JScrollPane resultScrollPane = new JScrollPane(resultTextPane);
        panel.add(resultScrollPane, BorderLayout.CENTER);

        // 用户提示词区域
        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("分析提示词"));
        userPromptArea = new JTextArea(3, 50);
        userPromptArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        userPromptArea.setBackground(Color.WHITE);
        userPromptArea.setForeground(Color.BLACK);
        userPromptArea.setText("请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议");
        JScrollPane promptScrollPane = new JScrollPane(userPromptArea);
        promptPanel.add(promptScrollPane, BorderLayout.CENTER);

        panel.add(promptPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // 左侧：功能开关
        JPanel featurePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        featurePanel.setBorder(BorderFactory.createTitledBorder("功能开关"));
        enableThinkingCheckBox = new JCheckBox("启用深度思考", true);
        enableSearchCheckBox = new JCheckBox("启用网络搜索", false);
        
        // 添加监听器，当复选框状态改变时更新API客户端配置
        enableThinkingCheckBox.addActionListener(e -> {
            apiClient.setEnableThinking(enableThinkingCheckBox.isSelected());
        });
        enableSearchCheckBox.addActionListener(e -> {
            apiClient.setEnableSearch(enableSearchCheckBox.isSelected());
        });
        
        featurePanel.add(enableThinkingCheckBox);
        featurePanel.add(enableSearchCheckBox);
        panel.add(featurePanel, BorderLayout.WEST);
        
        // 右侧：操作按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        analyzeButton = new JButton("开始分析");
        clearButton = new JButton("清空结果");
        stopButton = new JButton("停止");
        
        analyzeButton.addActionListener(e -> performAnalysis());
        clearButton.addActionListener(e -> clearResults());
        stopButton.addActionListener(e -> stopAnalysis());
        
        stopButton.setEnabled(false); // 初始状态禁用

        buttonPanel.add(analyzeButton);
        buttonPanel.add(clearButton);
        buttonPanel.add(stopButton);
        
        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private void updateRequestDisplay(RequestData requestData) {
        if (requestData == null) {
            clearHttpEditors();
            return;
        }

        String requestText = requestData.getRequest() != null ? requestData.getRequest() : "";
        if (requestText.isEmpty()) {
            requestEditor.setRequest(HttpRequest.httpRequest());
        } else {
            requestEditor.setRequest(HttpRequest.httpRequest(requestText));
        }

        String responseText = requestData.getResponse();
        if (responseText != null && !responseText.trim().isEmpty()) {
            responseEditor.setResponse(HttpResponse.httpResponse(responseText));
        } else {
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
    }

    private void refreshRequestTable() {
        // 使用新的 passiveScanTableModel 刷新手动添加的请求
        // 注意：不清空整个表，只更新手动添加的请求
        for (RequestData requestData : requestList) {
            // 检查是否已存在
            boolean exists = false;
            for (int i = 0; i < passiveScanTableModel.getRowCount(); i++) {
                Integer id = (Integer) passiveScanTableModel.getValueAt(i, 0);
                if (id != null && id == requestData.getId()) {
                    exists = true;
                    break;
                }
            }
            
            if (!exists) {
                Object[] row = {
                    requestData.getId(),
                    requestData.getMethod(),
                    requestData.getUrl().length() > 60 ? requestData.getUrl().substring(0, 60) + "..." : requestData.getUrl(),
                    requestData.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                    requestData.hasResponse() ? "是" : "否",
                    "待分析",  // 风险等级
                    "手动添加"  // 状态
                };
                passiveScanTableModel.addRow(row);
            }
        }
    }

    private void performAnalysis() {
        if (isAnalyzing) {
            return;
        }

        // 允许没有选择请求时也能进行分析（自由对话模式）
        RequestData requestData = null;
        ScanResult scanResult = null;
        int selectedRow = passiveScanTable.getSelectedRow();
        if (selectedRow >= 0) {
            // 优先从被动扫描结果获取
            Integer id = (Integer) passiveScanTableModel.getValueAt(selectedRow, 0);
            if (id != null && passiveScanManager != null) {
                scanResult = passiveScanManager.getResultById(id);
            }
            // 如果是手动添加的请求，尝试从 requestList 获取
            if (scanResult == null && selectedRow < requestList.size()) {
                requestData = requestList.get(selectedRow);
            }
        }

        String userPrompt = userPromptArea.getText().trim();
        if (userPrompt.isEmpty()) {
            if (requestData != null || scanResult != null) {
                userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
            } else {
                userPrompt = "";
            }
        }

        // 更新API客户端配置
        apiClient.setApiProvider((String) apiProviderComboBox.getSelectedItem());
        apiClient.setApiUrl(apiUrlField.getText().trim());
        apiClient.setApiKey(apiKeyField.getText().trim());
        apiClient.setModel(modelField.getText().trim());
        apiClient.setCustomParameters(customParametersField.getText().trim());
        apiClient.setEnableThinking(enableThinkingCheckBox.isSelected());
        apiClient.setEnableSearch(enableSearchCheckBox.isSelected());
        
        // 如果没有选择请求，提示用户
        if (requestData == null && scanResult == null) {
            api.logging().logToOutput("当前没有选择请求，将以自由对话模式进行分析");
        }

        isAnalyzing = true;
        analyzeButton.setEnabled(false);
        analyzeButton.setText("分析中...");
        stopButton.setEnabled(true); // 启用停止按钮
        
        String finalUserPrompt = userPrompt;
        final RequestData finalRequestData = requestData; // 创建final引用供内部类使用
        final ScanResult finalScanResult = scanResult; // 创建final引用供内部类使用
        currentWorker = new SwingWorker<Void, String>() {
            private StringBuilder fullResponse = new StringBuilder();
            private int aiMessageStartPos = 0; // 记录AI消息开始位置（分析开始时resultTextPane被清空，所以从0开始）

            @Override
            protected Void doInBackground() throws Exception {
                try {
                    // 构建HTTP内容（使用统一的格式化工具）
                    String httpContent = "";
                    if (finalScanResult != null && finalScanResult.getRequestResponse() != null) {
                        httpContent = com.ai.analyzer.utils.HttpFormatter.formatHttpRequestResponse(
                            finalScanResult.getRequestResponse());
                    } else if (finalRequestData != null) {
                        httpContent = finalRequestData.getFullRequestResponse();
                    }
                    
                    // 在开始分析前清空结果面板
                    SwingUtilities.invokeLater(() -> {
                        resultTextPane.setText("");
                        aiMessageStartPos = 0;
                    });
                    
                    apiClient.analyzeRequestStream(
                        httpContent,
                        finalUserPrompt,
                        chunk -> {
                            // 检查是否已取消（检查 SwingWorker 和 isAnalyzing 状态）
                            if (currentWorker.isCancelled() || !isAnalyzing) {
                                return; // 立即返回，不再处理后续的 chunk
                            }
                            
                            // 将chunk添加到缓冲区
                            fullResponse.append(chunk);
                            
                            // 流式输出时，实时进行Markdown解析和渲染
                            // 使用invokeAndWait确保最后一个chunk的渲染完成
                            try {
                                SwingUtilities.invokeAndWait(() -> {
                                    // 再次检查取消状态（双重检查）
                                    if (currentWorker.isCancelled() || !isAnalyzing) {
                                        return;
                                    }
                                    try {
                                        // 使用流式Markdown渲染
                                        MarkdownRenderer.appendMarkdownStreaming(resultTextPane, fullResponse.toString(), aiMessageStartPos);
                                        resultTextPane.setCaretPosition(resultTextPane.getStyledDocument().getLength());
                                    } catch (Exception e) {
                                        api.logging().logToError("流式Markdown渲染失败: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                });
                            } catch (Exception e) {
                                // 如果invokeAndWait失败，回退到invokeLater
                                SwingUtilities.invokeLater(() -> {
                                    // 再次检查取消状态
                                    if (currentWorker.isCancelled() || !isAnalyzing) {
                                        return;
                                    }
                                    try {
                                        MarkdownRenderer.appendMarkdownStreaming(resultTextPane, fullResponse.toString(), aiMessageStartPos);
                                        resultTextPane.setCaretPosition(resultTextPane.getStyledDocument().getLength());
                                    } catch (Exception ex) {
                                        api.logging().logToError("流式Markdown渲染失败: " + ex.getMessage());
                                    }
                                });
                            }
                        }
                    );
                    
                    // 流式输出完成后，使用完整的Markdown渲染替换流式渲染的内容
                    String finalContent = fullResponse.toString();
                    if (!finalContent.isEmpty()) {
                        try {
                            SwingUtilities.invokeAndWait(() -> {
                                try {
                                    StyledDocument doc = resultTextPane.getStyledDocument();
                                    // 删除流式渲染的内容
                                    int currentLength = doc.getLength();
                                    if (currentLength > aiMessageStartPos) {
                                        doc.remove(aiMessageStartPos, currentLength - aiMessageStartPos);
                                    }
                                    // 使用完整的Markdown渲染
                                    MarkdownRenderer.appendMarkdown(resultTextPane, finalContent);
                                    resultTextPane.setCaretPosition(resultTextPane.getStyledDocument().getLength());
                                } catch (BadLocationException e) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + e.getMessage());
                                } catch (Exception e) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + e.getMessage());
                                }
                            });
                        } catch (Exception e) {
                            // 如果invokeAndWait失败，使用invokeLater作为备选
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    StyledDocument doc = resultTextPane.getStyledDocument();
                                    int currentLength = doc.getLength();
                                    if (currentLength > aiMessageStartPos) {
                                        doc.remove(aiMessageStartPos, currentLength - aiMessageStartPos);
                                    }
                                    MarkdownRenderer.appendMarkdown(resultTextPane, finalContent);
                                    resultTextPane.setCaretPosition(resultTextPane.getStyledDocument().getLength());
                                } catch (BadLocationException ex) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + ex.getMessage());
                                } catch (Exception ex) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + ex.getMessage());
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        appendToResult("分析过程中出现错误: " + e.getMessage());
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // 检查是否有异常
                    
                    // 流式输出已完成，在doInBackground()中已经完成了完整渲染，这里不需要再渲染
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        appendToResult("分析过程中出现错误: " + e.getMessage());
                    });
                } finally {
                    analyzeButton.setEnabled(true);
                    analyzeButton.setText("开始分析");
                    stopButton.setEnabled(false); // 禁用停止按钮
                    isAnalyzing = false;
                }
            }
        };

        currentWorker.execute();
    }

    private void appendStreamChunk(String chunk) {
        try {
            StyledDocument doc = resultTextPane.getStyledDocument();
            javax.swing.text.Style regularStyle = doc.addStyle("regular", null);
            javax.swing.text.StyleConstants.setFontFamily(regularStyle, "Microsoft YaHei");
            javax.swing.text.StyleConstants.setFontSize(regularStyle, 12);
            javax.swing.text.StyleConstants.setForeground(regularStyle, Color.BLACK);
            doc.insertString(doc.getLength(), chunk, regularStyle);
            resultTextPane.setCaretPosition(doc.getLength());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void appendToResult(String text) {
        try {
            StyledDocument doc = resultTextPane.getStyledDocument();
            javax.swing.text.Style regularStyle = doc.addStyle("regular", null);
            javax.swing.text.StyleConstants.setFontFamily(regularStyle, "Microsoft YaHei");
            javax.swing.text.StyleConstants.setFontSize(regularStyle, 12);
            javax.swing.text.StyleConstants.setForeground(regularStyle, Color.BLACK);
            doc.insertString(doc.getLength(), text + "\n", regularStyle);
            resultTextPane.setCaretPosition(doc.getLength());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void stopAnalysis() {
        if (currentWorker != null && !currentWorker.isDone()) {
            // 先取消流式输出连接（使用 StreamingHandle.cancel()）
            if (apiClient != null) {
                apiClient.cancelStreaming();
            }
            // 然后取消 SwingWorker
            currentWorker.cancel(true);
            isAnalyzing = false;
            stopButton.setEnabled(false);
            analyzeButton.setEnabled(true);
            analyzeButton.setText("开始分析");
            
            // 添加中断提示
            try {
                StyledDocument doc = resultTextPane.getStyledDocument();
                javax.swing.text.Style stopStyle = doc.addStyle("stop", null);
                javax.swing.text.StyleConstants.setForeground(stopStyle, java.awt.Color.ORANGE);
                javax.swing.text.StyleConstants.setBold(stopStyle, true);
                javax.swing.text.StyleConstants.setItalic(stopStyle, true);
                doc.insertString(doc.getLength(), "\n\n[输出已中断]", stopStyle);
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            api.logging().logToOutput("用户中断了AI分析");
        }
    }
    
    private void clearResults() {
        // 如果正在分析，先停止
        if (currentWorker != null && !currentWorker.isDone()) {
            if (apiClient != null) {
                apiClient.cancelStreaming();
            }
            currentWorker.cancel(true);
            isAnalyzing = false;
            stopButton.setEnabled(false);
            analyzeButton.setEnabled(true);
            analyzeButton.setText("开始分析");
        }
        
        resultTextPane.setText("");
        // 清空 Assistant 的聊天记忆（共享实例）
        // 注意：apiClient.clearContext() 内部已经会先调用 cancelStreaming()
        apiClient.clearContext();
    }

    private void deleteSelectedRequest() {
        // 已被 deleteSelectedPassiveScanResult() 替代
        deleteSelectedPassiveScanResult();
    }

    private void clearAllRequests() {
        // 已被 clearPassiveScanResults() 替代
        int result = JOptionPane.showConfirmDialog(this, "确定要清空所有请求吗？", "确认", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            clearPassiveScanResults();
        }
    }

    private void saveSettings() {
        try {
            PluginSettings settings = new PluginSettings(
                apiUrlField.getText().trim(),
                apiKeyField.getText().trim(),
                modelField.getText().trim(),
                userPromptArea.getText().trim(),
                enableThinkingCheckBox.isSelected(),
                enableSearchCheckBox.isSelected(),
                enableMcpCheckBox.isSelected(),
                BurpMcpUrlField.getText().trim(),
                enableRagMcpCheckBox.isSelected(),
                "", // ragMcpUrlField.getText().trim(), // RAG MCP 地址暂时隐藏
                ragMcpDocumentsPathField.getText().trim(),
                enableChromeMcpCheckBox.isSelected(),
                chromeMcpUrlField.getText().trim(),
                false, // enableRag 暂时禁用
                ""    // ragDocumentsPath 暂时禁用
            );
            // 设置 API 提供者
            settings.setApiProvider((String) apiProviderComboBox.getSelectedItem());
            // 设置自定义参数
            settings.setCustomParameters(customParametersField.getText().trim());
            // 设置直接查找知识库选项
            settings.setEnableFileSystemAccess(enableFileSystemAccessCheckBox.isSelected());
            
            // 设置前置扫描器选项
            settings.setEnablePreScanFilter(enablePreScanCheckbox != null && enablePreScanCheckbox.isSelected());
            
            // 设置 Skills 选项
            settings.setEnableSkills(enableSkillsCheckBox.isSelected());
            settings.setSkillsDirectoryPath(skillsDirectoryField.getText().trim());
            settings.setEnabledSkillNames(apiClient.getSkillManager().getEnabledSkillNames());

            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("ai_analyzer_settings.dat"));
            oos.writeObject(settings);
            oos.close();

            //JOptionPane.showMessageDialog(this, "设置已保存", "成功", JOptionPane.INFORMATION_MESSAGE);
            api.logging().logToOutput("设置已保存");
        } catch (Exception e) {
            //JOptionPane.showMessageDialog(this, "保存设置失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            api.logging().logToError("保存设置失败: " + e.getMessage());
        }
    }

    /**
     * 自动加载配置文件（在插件初始化时调用）
     * 优先从当前目录加载，如果不存在则从用户主目录加载
     */
    private void autoLoadSettings() {
        PluginSettings settings = null;
        
        // 优先尝试从当前目录加载
        java.io.File localSettingsFile = new java.io.File("ai_analyzer_settings.dat");
        if (localSettingsFile.exists()) {
            try {
                java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.FileInputStream(localSettingsFile));
                settings = (PluginSettings) ois.readObject();
                ois.close();
                if (settings != null) {
                    applySettings(settings);
                    api.logging().logToOutput("已自动加载配置文件: ai_analyzer_settings.dat");
                    return;
                }
            } catch (Exception e) {
                // 如果加载失败，继续尝试用户主目录
            }
        }
        
        // 如果当前目录没有配置文件，尝试从用户主目录加载
        java.io.File userSettingsFile = new java.io.File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
        if (userSettingsFile.exists()) {
            try {
                java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.FileInputStream(userSettingsFile));
                settings = (PluginSettings) ois.readObject();
                ois.close();
                if (settings != null) {
                    applySettings(settings);
                    api.logging().logToOutput("已自动加载配置文件: " + userSettingsFile.getAbsolutePath());
                    return;
                }
            } catch (Exception e) {
                // 加载失败，使用默认值
            }
        }
        
        // 如果没有找到配置文件，使用默认值（不输出日志，这是正常情况）
    }
    
    /**
     * 应用设置到UI和API客户端
     */
    private void applySettings(PluginSettings settings) {
        // API 提供者（需要在其他设置之前应用，因为会影响 UI 状态）
        String provider = settings.getApiProvider();
        apiProviderComboBox.setSelectedItem(provider);
        // 根据提供者设置功能开关的启用状态
        boolean isDashScope = "DashScope".equals(provider);
        enableThinkingCheckBox.setEnabled(isDashScope);
        enableSearchCheckBox.setEnabled(isDashScope);
        
        apiUrlField.setText(settings.getApiUrl());
        apiKeyField.setText(settings.getApiKey());
        modelField.setText(settings.getModel());
        customParametersField.setText(settings.getCustomParameters());
        userPromptArea.setText(settings.getUserPrompt());
        enableThinkingCheckBox.setSelected(isDashScope && settings.isEnableThinking());
        enableSearchCheckBox.setSelected(isDashScope && settings.isEnableSearch());
        
        // Burp MCP 配置
        enableMcpCheckBox.setSelected(settings.isEnableMcp());
        BurpMcpUrlField.setText(settings.getMcpUrl());
        BurpMcpUrlField.setEnabled(settings.isEnableMcp());
        
        // RAG MCP 配置
        enableRagMcpCheckBox.setSelected(settings.isEnableRagMcp());
        // ragMcpUrlField.setText(settings.getRagMcpUrl()); // RAG MCP 地址暂时隐藏
        // ragMcpUrlField.setEnabled(settings.isEnableRagMcp());
        ragMcpDocumentsPathField.setText(settings.getRagMcpDocumentsPath());
        ragMcpDocumentsPathField.setEnabled(settings.isEnableRagMcp());
        
        // Chrome MCP 配置
        enableChromeMcpCheckBox.setSelected(settings.isEnableChromeMcp());
        chromeMcpUrlField.setText(settings.getChromeMcpUrl());
        chromeMcpUrlField.setEnabled(settings.isEnableChromeMcp());
        
        // 直接查找知识库配置
        enableFileSystemAccessCheckBox.setSelected(settings.isEnableFileSystemAccess());
        // 如果 RAG MCP 或直接查找知识库任一启用，则启用文档路径输入框
        ragMcpDocumentsPathField.setEnabled(settings.isEnableRagMcp() || settings.isEnableFileSystemAccess());
        
        // 默认 RAG 配置暂时禁用
        // enableRagCheckBox.setSelected(settings.isEnableRag());
        // ragDocumentsPathField.setText(settings.getRagDocumentsPath());
        // ragDocumentsPathField.setEnabled(settings.isEnableRag());
        
        // 更新API客户端配置
        apiClient.setApiProvider(settings.getApiProvider());
        apiClient.setApiUrl(settings.getApiUrl());
        apiClient.setApiKey(settings.getApiKey());
        apiClient.setModel(settings.getModel());
        apiClient.setCustomParameters(settings.getCustomParameters());
        apiClient.setEnableThinking(isDashScope && settings.isEnableThinking());
        apiClient.setEnableSearch(isDashScope && settings.isEnableSearch());
        apiClient.setEnableMcp(settings.isEnableMcp());
        apiClient.setBurpMcpUrl(settings.getMcpUrl());
        apiClient.setEnableRagMcp(settings.isEnableRagMcp());
        apiClient.setRagMcpUrl(settings.getRagMcpUrl());
        apiClient.setRagMcpDocumentsPath(settings.getRagMcpDocumentsPath());
        apiClient.setEnableChromeMcp(settings.isEnableChromeMcp());
        apiClient.setChromeMcpUrl(settings.getChromeMcpUrl());
        apiClient.setEnableFileSystemAccess(settings.isEnableFileSystemAccess());
        // apiClient.setEnableRag(settings.isEnableRag()); // 默认 RAG 暂时禁用
        // apiClient.setRagDocumentsPath(settings.getRagDocumentsPath()); // 默认 RAG 暂时禁用
        // apiClient.ensureRagInitialized(); // 默认 RAG 暂时禁用
        
        // Skills 配置
        enableSkillsCheckBox.setSelected(settings.isEnableSkills());
        skillsDirectoryField.setText(settings.getSkillsDirectoryPath());
        skillsDirectoryField.setEnabled(settings.isEnableSkills());
        browseSkillsDirButton.setEnabled(settings.isEnableSkills());
        refreshSkillsButton.setEnabled(settings.isEnableSkills());
        createExampleSkillButton.setEnabled(settings.isEnableSkills());
        skillsTable.setEnabled(settings.isEnableSkills());
        
        apiClient.setEnableSkills(settings.isEnableSkills());
        if (settings.getSkillsDirectoryPath() != null && !settings.getSkillsDirectoryPath().isEmpty()) {
            apiClient.setSkillsDirectoryPath(settings.getSkillsDirectoryPath());
            apiClient.getSkillManager().loadSkills();
            // 恢复已启用的 skills
            if (settings.getEnabledSkillNames() != null) {
                apiClient.getSkillManager().setEnabledSkillNames(settings.getEnabledSkillNames());
            }
            updateSkillsTable();
        }
        
        // 前置扫描器配置
        if (enablePreScanCheckbox != null) {
            enablePreScanCheckbox.setSelected(settings.isEnablePreScanFilter());
            if (preScanFilterManager != null) {
                if (settings.isEnablePreScanFilter()) {
                    preScanFilterManager.enable();
                } else {
                    preScanFilterManager.disable();
                }
            }
        }
    }
    
    /**
     * 手动加载设置（用户点击"加载设置"按钮时调用）
     */
    private void loadSettings() {
        try {
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.FileInputStream("ai_analyzer_settings.dat"));
            PluginSettings settings = (PluginSettings) ois.readObject();
            ois.close();

            applySettings(settings);

            //JOptionPane.showMessageDialog(this, "设置已加载", "成功", JOptionPane.INFORMATION_MESSAGE);
            api.logging().logToOutput("设置已加载");
        } catch (Exception e) {
            //JOptionPane.showMessageDialog(this, "加载设置失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            api.logging().logToError("加载设置失败: " + e.getMessage());
        }
    }
    
    public void addRequestFromHttpRequestResponse(String method, String url, burp.api.montoya.http.message.HttpRequestResponse requestResponse) {
        try {
            // 同时添加到 passiveScanManager（用于统一管理和显示）
            if (passiveScanManager != null) {
                ScanResult scanResult = passiveScanManager.addRequest(requestResponse);
                if (scanResult != null) {
                    // 手动添加的请求标记为 "手动添加" 状态
                    Object[] rowData = {
                        scanResult.getId(),
                        scanResult.getMethod(),
                        scanResult.getShortUrl(),
                        scanResult.getFormattedTimestamp(),
                        scanResult.hasResponse() ? "是" : "否",
                        "待分析",  // 风险等级
                        "手动添加"  // 状态
                    };
                    passiveScanTableModel.addRow(rowData);
                    
                    // 选中新添加的行
                    int newRow = passiveScanTableModel.getRowCount() - 1;
                    passiveScanTable.setRowSelectionInterval(newRow, newRow);
                    passiveScanTable.scrollRectToVisible(passiveScanTable.getCellRect(newRow, 0, true));
                    
                    // 显示请求详情
                    displayScanResult(scanResult);
                    
                    api.logging().logToOutput("请求已添加到AI分析器: " + method + " " + url);
                } else {
                    api.logging().logToOutput("请求已存在，跳过添加: " + method + " " + url);
                }
            } else {
                // 兼容旧逻辑
                RequestData requestData = new RequestData(
                    nextRequestId++,
                    method,
                    url,
                    requestResponse.request().toString(),
                    requestResponse.response() != null ? requestResponse.response().toString() : null
                );
                requestList.add(requestData);
                refreshRequestTable();
                updateRequestDisplay(requestData);
                api.logging().logToOutput("请求已添加到AI分析器: " + method + " " + url);
            }
        } catch (Exception e) {
            api.logging().logToError("添加请求到AI分析器失败: " + e.getMessage());
        }
    }
    
    public Component getUiComponent() {
        return this;
    }

    public String getApiUrl() {
        return apiUrlField.getText().trim();
    }
    
    public String getApiKey() {
        return apiKeyField.getText().trim();
    }
    
    public String getModel() {
        return modelField.getText().trim();
    }
    
    public boolean isEnableThinking() {
        return enableThinkingCheckBox.isSelected();
    }
    
    public boolean isEnableSearch() {
        return enableSearchCheckBox.isSelected();
    }
    
    /**
     * 获取共享的 API Client 实例
     * 用于 Side Panel 等组件共享同一个实例，避免重复初始化
     */
    public AgentApiClient getApiClient() {
        return apiClient;
    }

    private void clearHttpEditors() {
        requestEditor.setRequest(HttpRequest.httpRequest());
        responseEditor.setResponse(HttpResponse.httpResponse());
    }
    
    /**
     * 处理工具调用
     */
    /* Tools call 相关代码已注释
    private void handleToolCall(AgentApiClient.ToolCall toolCall) {
        appendToResult("\n[系统] 正在调用工具: " + toolCall.getName() + "\n");
        api.logging().logToOutput("[AI分析器] 工具调用: " + toolCall.getName());
        api.logging().logToOutput("[AI分析器] 工具参数: " + toolCall.getArguments());
        
        // 执行工具（在后台线程中执行，避免阻塞UI）
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            String result = toolExecutor.executeTool(toolCall.getName(), toolCall.getArguments());
            long duration = System.currentTimeMillis() - startTime;
            
            api.logging().logToOutput("[AI分析器] 工具执行完成，耗时: " + duration + "ms");
            
            // 显示工具执行结果
            SwingUtilities.invokeLater(() -> {
                appendToResult("\n[工具执行结果] " + result + "\n");
            });
        }).start();
    }
    */
    
}
