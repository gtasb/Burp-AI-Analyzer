package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import com.ai.analyzer.Client.AgentApiClient;
import com.ai.analyzer.model.PluginSettings;
import com.ai.analyzer.model.RequestData;
import com.ai.analyzer.pscan.PassiveScanApiClient;
import com.ai.analyzer.pscan.PassiveScanManager;
import com.ai.analyzer.pscan.PassiveScanTask;
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
import javax.swing.text.JTextComponent;
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
    private JTextField workplaceDirectoryField;
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
    private JCheckBox enablePythonScriptCheckbox;
    private JCheckBox enableNotebookCheckbox;
    private JButton browseWorkplaceDirButton;
    private JButton refreshSkillsButton;
    private JButton createExampleSkillButton;
    
    // 自定义系统提示词 & 被动扫描过滤
    private JTextArea activeSystemPromptArea;
    private JTextArea passiveSystemPromptArea;
    private JTextArea passiveScanSkipExtensionsArea;
    private JTextArea passiveScanDomainBlacklistArea;
    
    // 联网搜索配置
    private JComboBox<String> searchModeComboBox;
    private JTextField tavilyApiKeyField;
    private JTextField tavilyBaseUrlField;
    private JTextField googleApiKeyField;
    private JTextField googleCsiField;
    
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
    private JComboBox<String> analysisModeComboBox;
    private CardLayout centerModeCardLayout;
    private JPanel centerModeCardPanel;
    private JPanel passiveControlDetailsPanel;
    private JTextPane activeModeResultTextPane;
    private JTextArea activeModePromptArea;
    private JTextPane passiveModeResultTextPane;
    private JTextArea passiveModePromptArea;
    private boolean activeModeSelected = false;
    
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
    private static final String MODE_PASSIVE = "被动模式";
    private static final String MODE_ACTIVE = "主动模式";
    private static final String CARD_PASSIVE = "passive";
    private static final String CARD_ACTIVE = "active";

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

        // 被动模式内容：请求列表 + HTTP包 + 结果
        JSplitPane passiveMainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        passiveMainSplitPane.setDividerLocation(450);
        JPanel requestListPanel = createRequestListPanel();
        passiveMainSplitPane.setLeftComponent(requestListPanel);

        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setDividerLocation(300);
        JPanel requestPanel = createRequestPanel();
        rightSplitPane.setTopComponent(requestPanel);
        JPanel passiveResultPanel = createResultPanel(true);
        rightSplitPane.setBottomComponent(passiveResultPanel);
        passiveMainSplitPane.setRightComponent(rightSplitPane);

        // 主动模式内容：独立的聊天面板
        JPanel activeModePanel = createActiveModePanel();

        centerModeCardLayout = new CardLayout();
        centerModeCardPanel = new JPanel(centerModeCardLayout);
        centerModeCardPanel.add(passiveMainSplitPane, CARD_PASSIVE);
        centerModeCardPanel.add(activeModePanel, CARD_ACTIVE);
        centerModeCardLayout.show(centerModeCardPanel, CARD_PASSIVE);
        mainPanel.add(centerModeCardPanel, BorderLayout.CENTER);

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
        panel.setBorder(BorderFactory.createTitledBorder("设置"));

        JPanel topModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        topModePanel.add(new JLabel("模式:"));
        analysisModeComboBox = new JComboBox<>(new String[]{MODE_PASSIVE, MODE_ACTIVE});
        analysisModeComboBox.setSelectedItem(MODE_PASSIVE);
        analysisModeComboBox.addActionListener(e -> switchAnalysisMode((String) analysisModeComboBox.getSelectedItem()));
        topModePanel.add(analysisModeComboBox);
        panel.add(topModePanel, BorderLayout.NORTH);

        // 左侧：控制选项（仅被动模式显示）
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
        SpinnerModel spinnerModel = new SpinnerNumberModel(10, 1, 50, 1);
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

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        passiveScanStatusLabel = new JLabel("就绪");
        passiveScanStatusLabel.setPreferredSize(new Dimension(200, 20));
        statusPanel.add(passiveScanStatusLabel);
        passiveScanProgressBar = new JProgressBar(0, 100);
        passiveScanProgressBar.setPreferredSize(new Dimension(150, 20));
        passiveScanProgressBar.setStringPainted(true);
        statusPanel.add(passiveScanProgressBar);

        JPanel passiveModeControlLine = new JPanel(new BorderLayout());
        passiveModeControlLine.add(controlPanel, BorderLayout.WEST);
        passiveModeControlLine.add(statusPanel, BorderLayout.EAST);

        CardLayout controlDetailCard = new CardLayout();
        passiveControlDetailsPanel = new JPanel(controlDetailCard);
        passiveControlDetailsPanel.add(passiveModeControlLine, CARD_PASSIVE);
        JPanel activeHintPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        //activeHintPanel.add(new JLabel("主动模式：仅保留功能开关、AI分析结果、用户提示词。"));
        passiveControlDetailsPanel.add(activeHintPanel, CARD_ACTIVE);
        controlDetailCard.show(passiveControlDetailsPanel, CARD_PASSIVE);
        panel.add(passiveControlDetailsPanel, BorderLayout.CENTER);
        
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
        
        // ========== 设置流式输出回调（两级渲染：纯文本追加 + 定期 Markdown 刷新） ==========
        final long[] pscanPlainTime = {0L};
        final long PSCAN_PLAIN_MS = 150;
        final long[] pscanMdTime = {0L};
        final long PSCAN_MD_MS = 2000;
        final int[] pscanPlainLen = {0};
        passiveScanManager.setOnStreamingChunk(chunk -> {
            SwingUtilities.invokeLater(() -> {
                if (passiveScanResultPane == null) return;
                int viewRow = passiveScanTable.getSelectedRow();
                if (viewRow < 0) return;

                int modelRow = passiveScanTable.convertRowIndexToModel(viewRow);
                Integer selectedId = (Integer) passiveScanTableModel.getValueAt(modelRow, 0);
                ScanResult currentStreaming = passiveScanManager.getCurrentStreamingScanResult();
                if (currentStreaming == null || selectedId == null || !selectedId.equals(currentStreaming.getId())) return;

                if (currentStreamingId == null || !currentStreamingId.equals(selectedId)) {
                    passiveScanStreamBuffer.setLength(0);
                    pscanPlainLen[0] = 0;
                    pscanMdTime[0] = 0;
                    currentStreamingId = selectedId;
                }

                passiveScanStreamBuffer.append(chunk);
                long now = System.currentTimeMillis();

                if (now - pscanMdTime[0] >= PSCAN_MD_MS) {
                    pscanMdTime[0] = now;
                    pscanPlainTime[0] = now;
                    pscanPlainLen[0] = passiveScanStreamBuffer.length();
                    String snapshot = passiveScanStreamBuffer.toString();
                    try {
                        MarkdownRenderer.appendMarkdownStreaming(passiveScanResultPane, snapshot, 0);
                        passiveScanResultPane.setCaretPosition(passiveScanResultPane.getStyledDocument().getLength());
                    } catch (Exception e) {
                        passiveScanResultPane.setText(snapshot);
                    }
                    return;
                }

                if (now - pscanPlainTime[0] < PSCAN_PLAIN_MS) return;
                pscanPlainTime[0] = now;

                try {
                    int start = pscanPlainLen[0];
                    String newText = passiveScanStreamBuffer.substring(start);
                    pscanPlainLen[0] = passiveScanStreamBuffer.length();

                    StyledDocument doc = passiveScanResultPane.getStyledDocument();
                    javax.swing.text.Style plain = doc.getStyle("pscan_streaming");
                    if (plain == null) {
                        plain = doc.addStyle("pscan_streaming", null);
                        javax.swing.text.StyleConstants.setFontFamily(plain, "Microsoft YaHei");
                        javax.swing.text.StyleConstants.setFontSize(plain, 13);
                        Color fg = UIManager.getColor("TextArea.foreground");
                        if (fg != null) javax.swing.text.StyleConstants.setForeground(plain, fg);
                    }
                    doc.insertString(doc.getLength(), newText, plain);
                    passiveScanResultPane.setCaretPosition(doc.getLength());
                } catch (Exception e) {
                    try {
                        passiveScanResultPane.setText(passiveScanStreamBuffer.toString());
                    } catch (Exception ex) {
                        api.logging().logToError("流式输出失败: " + ex.getMessage());
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
            PassiveScanApiClient psClient = passiveScanManager.getApiClient();
            
            // 基础 API 配置
            psClient.setApiUrl(apiClient.getApiUrl());
            psClient.setApiKey(apiClient.getApiKey());
            psClient.setModel(apiClient.getModel());
            psClient.setApiProvider(apiClient.getApiProvider().getDisplayName());
            psClient.setEnableThinking(apiClient.isEnableThinking());
            psClient.setEnableSearch(apiClient.isEnableSearch());
            psClient.setSearchMode(apiClient.getConfig().getSearchMode());
            psClient.setTavilyApiKey(apiClient.getConfig().getTavilyApiKey());
            psClient.setTavilyBaseUrl(apiClient.getConfig().getTavilyBaseUrl());
            psClient.setGoogleSearchApiKey(apiClient.getConfig().getGoogleSearchApiKey());
            psClient.setGoogleSearchCsi(apiClient.getConfig().getGoogleSearchCsi());
            
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
            
            // Python 脚本执行配置
            psClient.setEnablePythonScript(apiClient.isEnablePythonScript());
            psClient.setEnableNotebook(apiClient.isEnableNotebook());
            psClient.setWorkplaceDirectoryPath(workplaceDirectoryField != null ? workplaceDirectoryField.getText().trim() : "");
            psClient.setEnableSkills(enableSkillsCheckBox != null && enableSkillsCheckBox.isSelected());
            
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
     * 使用子标签页组织：基础配置、功能开关、系统提示词、被动扫描过滤
     */
    private JPanel createConfigTabPanel() {
        JPanel configPanel = new JPanel(new BorderLayout());
        
        JTabbedPane subTabs = new JTabbedPane(JTabbedPane.TOP);
        subTabs.addTab("基础配置", createConfigSubTab_Basic());
        subTabs.addTab("功能开关", createConfigSubTab_Features());
        subTabs.addTab("联网搜索", createConfigSubTab_Search());
        subTabs.addTab("系统提示词", createConfigSubTab_Prompts());
        subTabs.addTab("被动扫描过滤", createConfigSubTab_Filters());
        
        configPanel.add(subTabs, BorderLayout.CENTER);
        return configPanel;
    }
    
    private JPanel createConfigSubTab_Basic() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;
        
        // API 提供者
        gbc.gridx = 0; gbc.gridy = row;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("API 提供者:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        String[] providers = {"DashScope", "OpenAI兼容", "Anthropic兼容"};
        apiProviderComboBox = new JComboBox<>(providers);
        apiProviderComboBox.setSelectedIndex(0);
        apiProviderComboBox.addActionListener(e -> {
            String sel = (String) apiProviderComboBox.getSelectedItem();
            apiClient.setApiProvider(sel);
            boolean isToolSearch = searchModeComboBox != null && searchModeComboBox.getSelectedIndex() >= 1;
            if ("DashScope".equals(sel)) {
                if (apiUrlField.getText().contains("openai.com") || apiUrlField.getText().contains("anthropic.com") || apiUrlField.getText().isEmpty())
                    apiUrlField.setText("https://dashscope.aliyuncs.com/api/v1");
                enableThinkingCheckBox.setEnabled(true);
                enableSearchCheckBox.setEnabled(true);
            } else if ("Anthropic兼容".equals(sel)) {
                if (apiUrlField.getText().contains("dashscope") || apiUrlField.getText().contains("openai.com") || apiUrlField.getText().isEmpty())
                    apiUrlField.setText("https://api.anthropic.com");
                enableThinkingCheckBox.setEnabled(true);
                enableSearchCheckBox.setEnabled(isToolSearch);
                if (!isToolSearch) enableSearchCheckBox.setSelected(false);
            } else {
                if (apiUrlField.getText().contains("dashscope") || apiUrlField.getText().contains("anthropic.com") || apiUrlField.getText().isEmpty())
                    apiUrlField.setText("https://api.openai.com/v1");
                enableThinkingCheckBox.setEnabled(false);
                enableThinkingCheckBox.setSelected(false);
                enableSearchCheckBox.setEnabled(isToolSearch);
                if (!isToolSearch) enableSearchCheckBox.setSelected(false);
            }
        });
        apiProviderComboBox.setToolTipText("选择 API 提供者：DashScope（通义千问）、OpenAI 兼容格式、Anthropic 兼容（Claude）");
        panel.add(apiProviderComboBox, gbc);
        
        // API URL
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("API URL:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        apiUrlField = new JTextField("https://dashscope.aliyuncs.com/api/v1", 30);
        panel.add(apiUrlField, gbc);
        
        // API Key
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("API Key:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        apiKeyField = new JTextField("", 30);
        panel.add(apiKeyField, gbc);
        
        // Model
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Model:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        modelField = new JTextField("qwen-max", 30);
        panel.add(modelField, gbc);
        
        // 自定义参数
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("自定义参数:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        customParametersField = new JTextField("", 30);
        customParametersField.setToolTipText("<html><b>自定义参数（JSON 格式，透传到 API）</b><br/>" +
            "temperature, top_p, max_tokens, frequency_penalty 等<br/>" +
            "Ollama: {\"options\": {\"num_ctx\": 8192}, \"keep_alive\": \"30m\"}</html>");
        panel.add(customParametersField, gbc);
        
        // Workplace 目录
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Workplace 目录:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        workplaceDirectoryField = new JTextField("", 30);
        workplaceDirectoryField.setToolTipText("统一工作目录，自动派生：skills / rag / python-workdir / notebooks");
        workplaceDirectoryField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyWorkplaceToDerivedPaths(true, false); }
            @Override public void removeUpdate(DocumentEvent e) { applyWorkplaceToDerivedPaths(true, false); }
            @Override public void changedUpdate(DocumentEvent e) { applyWorkplaceToDerivedPaths(true, false); }
        });
        panel.add(workplaceDirectoryField, gbc);
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        browseWorkplaceDirButton = new JButton("浏览...");
        browseWorkplaceDirButton.addActionListener(e -> browseWorkplaceDirectory());
        panel.add(browseWorkplaceDirButton, gbc);
        
        // Workplace 快捷按钮 + 保存/加载
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        JButton openWorkplaceRootButton = new JButton("打开 Workplace 目录");
        openWorkplaceRootButton.addActionListener(e -> openWorkplaceRootDirectory());
        saveSettingsButton = new JButton("保存设置");
        loadSettingsButton = new JButton("加载设置");
        saveSettingsButton.addActionListener(e -> saveSettings());
        loadSettingsButton.addActionListener(e -> loadSettings());
        actionsPanel.add(openWorkplaceRootButton);
        actionsPanel.add(Box.createHorizontalStrut(20));
        actionsPanel.add(saveSettingsButton);
        actionsPanel.add(loadSettingsButton);
        panel.add(actionsPanel, gbc);
        
        // 底部填充
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JLabel(), gbc);
        
        return panel;
    }
    
    private JPanel createConfigSubTab_Features() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;
        
        // Burp MCP
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Burp MCP:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        enableMcpCheckBox = new JCheckBox("启用 Burp MCP 工具调用", false);
        enableMcpCheckBox.addActionListener(e -> {
            boolean enabled = enableMcpCheckBox.isSelected();
            BurpMcpUrlField.setEnabled(enabled);
            apiClient.setEnableMcp(enabled);
            if (enabled && !BurpMcpUrlField.getText().trim().isEmpty())
                apiClient.setBurpMcpUrl(BurpMcpUrlField.getText().trim());
        });
        panel.add(enableMcpCheckBox, gbc);
        
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("  MCP 地址:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        BurpMcpUrlField = new JTextField("http://127.0.0.1:9876/", 30);
        BurpMcpUrlField.setEnabled(false);
        BurpMcpUrlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { syncBurpMcpUrl(); }
            @Override public void removeUpdate(DocumentEvent e) { syncBurpMcpUrl(); }
            @Override public void changedUpdate(DocumentEvent e) { syncBurpMcpUrl(); }
            private void syncBurpMcpUrl() {
                if (enableMcpCheckBox.isSelected() && !BurpMcpUrlField.getText().trim().isEmpty())
                    apiClient.setBurpMcpUrl(BurpMcpUrlField.getText().trim());
            }
        });
        panel.add(BurpMcpUrlField, gbc);
        
        // 知识库
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("知识库:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JPanel knowledgeBasePanel = new JPanel();
        knowledgeBasePanel.setLayout(new BoxLayout(knowledgeBasePanel, BoxLayout.X_AXIS));
        enableRagMcpCheckBox = new JCheckBox("RAG MCP（语义检索）", false);
        enableRagMcpCheckBox.addActionListener(e -> {
            boolean enabled = enableRagMcpCheckBox.isSelected();
            ragMcpDocumentsPathField.setEnabled(enabled || enableFileSystemAccessCheckBox.isSelected());
            apiClient.setEnableRagMcp(enabled);
            if (enabled && !ragMcpDocumentsPathField.getText().trim().isEmpty())
                apiClient.setRagMcpDocumentsPath(ragMcpDocumentsPathField.getText().trim());
        });
        knowledgeBasePanel.add(enableRagMcpCheckBox);
        knowledgeBasePanel.add(Box.createHorizontalStrut(10));
        enableFileSystemAccessCheckBox = new JCheckBox("直接查找（文件浏览）", false);
        enableFileSystemAccessCheckBox.addActionListener(e -> {
            boolean enabled = enableFileSystemAccessCheckBox.isSelected();
            ragMcpDocumentsPathField.setEnabled(enabled || enableRagMcpCheckBox.isSelected());
            apiClient.setEnableFileSystemAccess(enabled);
            if (enabled && !ragMcpDocumentsPathField.getText().trim().isEmpty())
                apiClient.setRagMcpDocumentsPath(ragMcpDocumentsPathField.getText().trim());
        });
        knowledgeBasePanel.add(enableFileSystemAccessCheckBox);
        panel.add(knowledgeBasePanel, gbc);
        ragMcpDocumentsPathField = new JTextField("", 30);
        ragMcpDocumentsPathField.setEnabled(true);
        ragMcpDocumentsPathField.setEditable(false);
        
        // Chrome MCP
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Chrome MCP:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        enableChromeMcpCheckBox = new JCheckBox("启用 Chrome MCP 工具调用", false);
        enableChromeMcpCheckBox.addActionListener(e -> {
            boolean enabled = enableChromeMcpCheckBox.isSelected();
            chromeMcpUrlField.setEnabled(enabled);
            apiClient.setEnableChromeMcp(enabled);
            if (enabled && !chromeMcpUrlField.getText().trim().isEmpty())
                apiClient.setChromeMcpUrl(chromeMcpUrlField.getText().trim());
        });
        panel.add(enableChromeMcpCheckBox, gbc);
        
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("  Chrome 地址:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        chromeMcpUrlField = new JTextField(" ", 30);
        chromeMcpUrlField.setEnabled(false);
        chromeMcpUrlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e) { sync(); }
            @Override public void changedUpdate(DocumentEvent e) { sync(); }
            private void sync() {
                if (enableChromeMcpCheckBox.isSelected() && !chromeMcpUrlField.getText().trim().isEmpty())
                    apiClient.setChromeMcpUrl(chromeMcpUrlField.getText().trim());
            }
        });
        panel.add(chromeMcpUrlField, gbc);
        
        // 分隔线
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(new JSeparator(), gbc);
        
        // 前置扫描器
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        enablePreScanCheckbox = new JCheckBox("启用前置扫描器（快速规则匹配）", false);
        enablePreScanCheckbox.setToolTipText("<html>AI分析前快速匹配已知漏洞特征（130+条规则，570+个模式）</html>");
        enablePreScanCheckbox.addActionListener(e -> {
            boolean enabled = enablePreScanCheckbox.isSelected();
            if (preScanFilterManager != null) {
                if (enabled) preScanFilterManager.enable(); else preScanFilterManager.disable();
            }
        });
        panel.add(enablePreScanCheckbox, gbc);
        
        // Python
        row++;
        gbc.gridy = row;
        enablePythonScriptCheckbox = new JCheckBox("启用 Python 脚本执行", false);
        enablePythonScriptCheckbox.setToolTipText("<html>让 AI 在本地执行 Python 代码（需本机安装 Python）</html>");
        enablePythonScriptCheckbox.addActionListener(e -> {
            apiClient.setEnablePythonScript(enablePythonScriptCheckbox.isSelected());
        });
        panel.add(enablePythonScriptCheckbox, gbc);
        
        // Notebook
        row++;
        gbc.gridy = row;
        enableNotebookCheckbox = new JCheckBox("启用 Notebook 工具（共享渗透记录）", false);
        enableNotebookCheckbox.setToolTipText("<html>Agent 与人工工程师共享工作笔记，位于 Workplace/notebooks</html>");
        enableNotebookCheckbox.addActionListener(e -> {
            apiClient.setEnableNotebook(enableNotebookCheckbox.isSelected());
        });
        panel.add(enableNotebookCheckbox, gbc);
        
        // 底部填充
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JLabel(), gbc);
        
        return panel;
    }
    
    private JPanel createConfigSubTab_Search() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // 搜索方式
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("搜索方式:"), gbc);
        searchModeComboBox = new JComboBox<>(new String[]{
                "模型内置搜索 (仅DashScope)",
                "Tavily搜索引擎 (所有模型)",
                "Google Custom Search (所有模型)",
                "DuckDuckGo (所有模型)",
        });
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(searchModeComboBox, gbc);

        row++;

        // Tavily API Key
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Tavily API Key:"), gbc);
        tavilyApiKeyField = new JTextField(40);
        tavilyApiKeyField.setToolTipText("Tavily API Key (从 https://tavily.com 获取)");
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(tavilyApiKeyField, gbc);

        row++;

        // Tavily Base URL
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Tavily Base URL:"), gbc);
        tavilyBaseUrlField = new JTextField(40);
        tavilyBaseUrlField.setToolTipText("Tavily API 代理地址 (留空使用默认 https://api.tavily.com/)");
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(tavilyBaseUrlField, gbc);

        row++;

        // Google API Key
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Google API Key:"), gbc);
        googleApiKeyField = new JTextField(40);
        googleApiKeyField.setToolTipText("Google Custom Search API Key (从 Google Cloud Console 获取)");
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(googleApiKeyField, gbc);

        row++;

        // Google CSI (Custom Search Engine ID)
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Google CSE ID:"), gbc);
        googleCsiField = new JTextField(40);
        googleCsiField.setToolTipText("Google 可编程搜索引擎 ID (从 https://programmablesearchengine.google.com 创建)");
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(googleCsiField, gbc);

        row++;

        // 说明
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JTextArea hint = new JTextArea(
                "说明:\n" +
                "• 模型内置搜索: 通过模型参数 enableSearch 实现，仅 DashScope (通义千问) 支持\n" +
                "• Tavily搜索引擎: 通过 WebSearchTools 工具实现，所有模型均可使用\n" +
                "  - Tavily 提供免费 API Key (每月1000次请求)，注册地址: https://tavily.com\n" +
                "• Google Custom Search: 通过 Google Programmable Search Engine 实现\n" +
                "  - 需要 Google Cloud API Key + 自定义搜索引擎 ID (CSE ID)\n" +
                "  - 免费额度每天100次请求，创建地址: https://programmablesearchengine.google.com\n" +
                "• DuckDuckGo: 免费搜索引擎，无需 API Key，通过 HTML 解析获取结果\n" +
                "  - 无请求次数限制，但速度可能较慢，且可能受到反爬虫限制\n" +
                "• 主界面的「启用网络搜索」开关控制是否启用搜索，此处配置搜索的实现方式");
        hint.setEditable(false);
        hint.setOpaque(false);
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        panel.add(hint, gbc);

        Runnable updateSearchFieldState = () -> {
            int idx = searchModeComboBox.getSelectedIndex();
            boolean isTavily = idx == 1;
            boolean isGoogle = idx == 2;
            tavilyApiKeyField.setEnabled(isTavily);
            tavilyBaseUrlField.setEnabled(isTavily);
            googleApiKeyField.setEnabled(isGoogle);
            googleCsiField.setEnabled(isGoogle);
        };
        searchModeComboBox.addActionListener(e -> updateSearchFieldState.run());
        tavilyApiKeyField.setEnabled(false);
        tavilyBaseUrlField.setEnabled(false);
        googleApiKeyField.setEnabled(false);
        googleCsiField.setEnabled(false);

        // 底部填充
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JLabel(), gbc);

        return panel;
    }

    private JPanel createConfigSubTab_Prompts() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.fill = GridBagConstraints.BOTH;
        
        // 主动模式提示词
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel activeLabel = new JLabel("主动模式系统提示词（用于手动分析 / 聊天）:");
        activeLabel.setFont(activeLabel.getFont().deriveFont(Font.BOLD));
        panel.add(activeLabel, gbc);
        
        gbc.gridy = 1; gbc.weighty = 0.5; gbc.fill = GridBagConstraints.BOTH;
        activeSystemPromptArea = new JTextArea(com.ai.analyzer.Client.SystemPromptBuilder.getDefaultBasePrompt());
        activeSystemPromptArea.setLineWrap(true);
        activeSystemPromptArea.setWrapStyleWord(true);
        activeSystemPromptArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        JScrollPane activeScroll = new JScrollPane(activeSystemPromptArea);
        panel.add(activeScroll, gbc);
        
        // 被动扫描提示词
        gbc.gridy = 2; gbc.weighty = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel passiveLabel = new JLabel("被动扫描系统提示词（用于自动被动扫描）:");
        passiveLabel.setFont(passiveLabel.getFont().deriveFont(Font.BOLD));
        panel.add(passiveLabel, gbc);
        
        gbc.gridy = 3; gbc.weighty = 0.5; gbc.fill = GridBagConstraints.BOTH;
        passiveSystemPromptArea = new JTextArea(com.ai.analyzer.pscan.SystemPromptBuilder.getDefaultBasePrompt());
        passiveSystemPromptArea.setLineWrap(true);
        passiveSystemPromptArea.setWrapStyleWord(true);
        passiveSystemPromptArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        JScrollPane passiveScroll = new JScrollPane(passiveSystemPromptArea);
        panel.add(passiveScroll, gbc);
        
        // 恢复默认按钮
        gbc.gridy = 4; gbc.weighty = 0; gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        JButton resetPromptButton = new JButton("恢复默认提示词");
        resetPromptButton.addActionListener(e -> {
            activeSystemPromptArea.setText(com.ai.analyzer.Client.SystemPromptBuilder.getDefaultBasePrompt());
            passiveSystemPromptArea.setText(com.ai.analyzer.pscan.SystemPromptBuilder.getDefaultBasePrompt());
        });
        panel.add(resetPromptButton, gbc);
        
        return panel;
    }
    
    private JPanel createConfigSubTab_Filters() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridx = 0; gbc.weightx = 1.0;
        
        // 静态资源跳过扩展名
        gbc.gridy = 0; gbc.weighty = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel extLabel = new JLabel("跳过的静态资源扩展名（逗号分隔，留空使用默认）:");
        extLabel.setFont(extLabel.getFont().deriveFont(Font.BOLD));
        panel.add(extLabel, gbc);
        
        gbc.gridy = 1; gbc.weighty = 0.4; gbc.fill = GridBagConstraints.BOTH;
        passiveScanSkipExtensionsArea = new JTextArea(PassiveScanTask.getDefaultSkipExtensionsText());
        passiveScanSkipExtensionsArea.setLineWrap(true);
        passiveScanSkipExtensionsArea.setWrapStyleWord(true);
        passiveScanSkipExtensionsArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        passiveScanSkipExtensionsArea.setToolTipText("逗号或空格分隔，如：.js, .css, .png, .jpg");
        JScrollPane extScroll = new JScrollPane(passiveScanSkipExtensionsArea);
        panel.add(extScroll, gbc);
        
        // 域名黑名单
        gbc.gridy = 2; gbc.weighty = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel domainLabel = new JLabel("被动扫描域名黑名单（每行一个，支持通配符如 *.google.com）:");
        domainLabel.setFont(domainLabel.getFont().deriveFont(Font.BOLD));
        panel.add(domainLabel, gbc);
        
        gbc.gridy = 3; gbc.weighty = 0.4; gbc.fill = GridBagConstraints.BOTH;
        passiveScanDomainBlacklistArea = new JTextArea("");
        passiveScanDomainBlacklistArea.setLineWrap(true);
        passiveScanDomainBlacklistArea.setWrapStyleWord(true);
        passiveScanDomainBlacklistArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        passiveScanDomainBlacklistArea.setToolTipText("每行一个域名模式，# 开头为注释。如：\n*.google.com\n*.gstatic.com\nfonts.googleapis.com");
        JScrollPane domainScroll = new JScrollPane(passiveScanDomainBlacklistArea);
        panel.add(domainScroll, gbc);
        
        // 恢复默认 + 立即应用
        gbc.gridy = 4; gbc.weighty = 0; gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton resetFiltersButton = new JButton("恢复默认扩展名");
        resetFiltersButton.addActionListener(e -> {
            passiveScanSkipExtensionsArea.setText(PassiveScanTask.getDefaultSkipExtensionsText());
        });
        JButton applyFiltersButton = new JButton("立即应用过滤规则");
        applyFiltersButton.addActionListener(e -> applyPassiveScanFilters());
        btnPanel.add(resetFiltersButton);
        btnPanel.add(applyFiltersButton);
        panel.add(btnPanel, gbc);
        
        // 说明
        gbc.gridy = 5; gbc.weighty = 0.2; gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JTextArea hint = new JTextArea(
            "说明：\n" +
            "• 扩展名列表用于过滤被动扫描中的静态资源请求（如图片、字体、脚本等）\n" +
            "• 域名黑名单中的主机将被完全跳过扫描，支持通配符 * 和 ?\n" +
            "• 修改后需点击「立即应用」或「保存设置」后生效\n" +
            "• 留空扩展名列表将使用内置默认值");
        hint.setEditable(false);
        hint.setOpaque(false);
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hint.setForeground(java.awt.Color.GRAY);
        panel.add(hint, gbc);
        
        return panel;
    }
    
    private void applyPassiveScanFilters() {
        String extText = passiveScanSkipExtensionsArea.getText().trim();
        String defaultText = PassiveScanTask.getDefaultSkipExtensionsText();
        PassiveScanTask.setCustomSkipExtensions(extText.equals(defaultText) ? null : extText);
        PassiveScanTask.setDomainBlacklist(passiveScanDomainBlacklistArea.getText());
        api.logging().logToOutput("[PassiveScan] 过滤规则已应用");
    }
    
    /**
     * 创建 Skills 标签页（第三个标签页）
     * 用于管理用户自定义的技能（Skills）
     */
    private JPanel createSkillsTabPanel() {
        JPanel skillsPanel = new JPanel(new BorderLayout(10, 10));
        skillsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // 顶部配置面板（统一左对齐）
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBorder(BorderFactory.createTitledBorder("Skills 配置"));
        
        // 启用 Skills 复选框
        enableSkillsCheckBox = new JCheckBox("启用 Skills（自定义技能指令）", false);
        enableSkillsCheckBox.setToolTipText("启用后，AI 将加载并应用选中的技能指令");
        enableSkillsCheckBox.addActionListener(e -> {
            boolean enabled = enableSkillsCheckBox.isSelected();
            // Skills 目录由 Workplace 自动派生，避免手动配置不一致
            skillsDirectoryField.setEnabled(false);
            refreshSkillsButton.setEnabled(enabled);
            createExampleSkillButton.setEnabled(enabled);
            skillsTable.setEnabled(enabled);
            apiClient.setEnableSkills(enabled);
            if (enabled) {
                applyWorkplaceToDerivedPaths(true, true);
            }
        });
        JPanel checkboxRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        checkboxRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        checkboxRow.add(enableSkillsCheckBox);
        topPanel.add(checkboxRow);

        skillsDirectoryField = new JTextField("", 40);
        skillsDirectoryField.setEnabled(false);
        skillsDirectoryField.setEditable(false);
        skillsDirectoryField.setToolTipText("自动使用 Workplace/skills");
        
        // 按钮面板
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttonsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        refreshSkillsButton = new JButton("刷新 Skills");
        refreshSkillsButton.setEnabled(false);
        refreshSkillsButton.addActionListener(e -> refreshSkills());
        buttonsPanel.add(refreshSkillsButton);
        
        createExampleSkillButton = new JButton("创建示例 Skill");
        createExampleSkillButton.setEnabled(false);
        createExampleSkillButton.addActionListener(e -> createExampleSkill());
        buttonsPanel.add(createExampleSkillButton);
        
        topPanel.add(Box.createVerticalStrut(6));
        topPanel.add(buttonsPanel);
        
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
     * 浏览并设置 Workplace 根目录
     */
    private void browseWorkplaceDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择 Workplace 目录");

        String currentPath = workplaceDirectoryField != null ? workplaceDirectoryField.getText().trim() : "";
        if (!currentPath.isEmpty()) {
            File currentDir = new File(currentPath);
            if (currentDir.exists()) {
                fileChooser.setCurrentDirectory(currentDir);
            }
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String selectedPath = fileChooser.getSelectedFile().getAbsolutePath();
            workplaceDirectoryField.setText(selectedPath);
            applyWorkplaceToDerivedPaths(true, true);
        }
    }

    private void openWorkplaceRootDirectory() {
        if (workplaceDirectoryField == null) return;
        String workplace = workplaceDirectoryField.getText() != null ? workplaceDirectoryField.getText().trim() : "";
        if (workplace.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先设置 Workplace 目录", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File dir = new File(workplace);
        if (!dir.exists() && !dir.mkdirs()) {
            JOptionPane.showMessageDialog(this, "创建目录失败: " + dir.getAbsolutePath(), "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                JOptionPane.showMessageDialog(this, "当前环境不支持打开文件管理器: " + dir.getAbsolutePath(), "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "打开目录失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 根据 Workplace 同步派生目录（skills/rag/python-workdir）到 UI 与客户端。
     */
    private void applyWorkplaceToDerivedPaths(boolean pushToClients, boolean createDirs) {
        if (workplaceDirectoryField == null) return;
        String workplace = workplaceDirectoryField.getText() != null ? workplaceDirectoryField.getText().trim() : "";
        if (workplace.isEmpty()) return;

        File workplaceDir = new File(workplace);
        File skillsDir = new File(workplaceDir, "skills");
        File ragDir = new File(workplaceDir, "rag");
        File pythonDir = new File(workplaceDir, "python-workdir");
        File notebooksDir = new File(workplaceDir, "notebooks");

        if (createDirs) {
            if (!workplaceDir.exists()) workplaceDir.mkdirs();
            if (!skillsDir.exists()) skillsDir.mkdirs();
            if (!ragDir.exists()) ragDir.mkdirs();
            if (!pythonDir.exists()) pythonDir.mkdirs();
            if (!notebooksDir.exists()) notebooksDir.mkdirs();
        }

        if (skillsDirectoryField != null) {
            skillsDirectoryField.setText(skillsDir.getAbsolutePath());
        }
        if (ragMcpDocumentsPathField != null) {
            ragMcpDocumentsPathField.setText(ragDir.getAbsolutePath());
        }

        if (pushToClients) {
            apiClient.setWorkplaceDirectoryPath(workplaceDir.getAbsolutePath());
            apiClient.setSkillsDirectoryPath(skillsDir.getAbsolutePath());
            apiClient.setRagMcpDocumentsPath(ragDir.getAbsolutePath());
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setWorkplaceDirectoryPath(workplaceDir.getAbsolutePath());
                passiveScanManager.getApiClient().setRagMcpDocumentsPath(ragDir.getAbsolutePath());
            }
        }
    }

    /**
     * 刷新 Skills 列表
     */
    private void refreshSkills() {
        String dirPath = skillsDirectoryField.getText().trim();
        if (dirPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先设置 Workplace 目录", "提示", JOptionPane.WARNING_MESSAGE);
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
        passiveScanTable.setAutoCreateRowSorter(true);
        
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
                int viewRow = passiveScanTable.getSelectedRow();
                if (viewRow >= 0 && passiveScanManager != null) {
                    int modelRow = passiveScanTable.convertRowIndexToModel(viewRow);
                    Integer id = (Integer) passiveScanTableModel.getValueAt(modelRow, 0);
                    if (id != null) {
                        ScanResult result = passiveScanManager.getResultById(id);
                        if (result != null) {
                            Integer selectedId = id;
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
        int viewRow = passiveScanTable.getSelectedRow();
        if (viewRow >= 0) {
            int modelRow = passiveScanTable.convertRowIndexToModel(viewRow);
            passiveScanTableModel.removeRow(modelRow);
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

    private JPanel createResultPanel(boolean passiveMode) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("AI分析结果"));

        JTextPane localResultTextPane = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
        localResultTextPane.setEditable(false);
        localResultTextPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        localResultTextPane.setContentType("text/plain");
        applyEditorTheme(localResultTextPane);
        JScrollPane resultScrollPane = new JScrollPane(localResultTextPane);
        resultScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(resultScrollPane, BorderLayout.CENTER);

        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("分析提示词"));
        JTextArea localPromptArea = new JTextArea(3, 50);
        localPromptArea.setLineWrap(true);
        localPromptArea.setWrapStyleWord(true);
        localPromptArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        applyEditorTheme(localPromptArea);
        localPromptArea.setText("请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议");
        JScrollPane promptScrollPane = new JScrollPane(localPromptArea);
        promptPanel.add(promptScrollPane, BorderLayout.CENTER);

        panel.add(promptPanel, BorderLayout.SOUTH);

        passiveModeResultTextPane = localResultTextPane;
        passiveScanResultPane = localResultTextPane;
        passiveModePromptArea = localPromptArea;
        resultTextPane = localResultTextPane;
        userPromptArea = localPromptArea;

        return panel;
    }

    /**
     * 创建主动模式面板 - 聊天式布局，独立组件
     * 结果区保留上下文（追加式），输入框动态高度、提交后清空
     * 底部按钮复用 createButtonPanel()，不在此处重复创建
     */
    private JPanel createActiveModePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));

        // ---- 结果区域 ----
        activeModeResultTextPane = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
        activeModeResultTextPane.setEditable(false);
        activeModeResultTextPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        activeModeResultTextPane.setContentType("text/plain");
        applyEditorTheme(activeModeResultTextPane);
        JScrollPane resultScrollPane = new JScrollPane(activeModeResultTextPane);
        resultScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resultScrollPane.setBorder(BorderFactory.createTitledBorder("AI分析结果"));
        panel.add(resultScrollPane, BorderLayout.CENTER);

        // ---- 底部：输入区域 ----
        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("输入"));

        activeModePromptArea = new JTextArea(5, 50);
        activeModePromptArea.setLineWrap(true);
        activeModePromptArea.setWrapStyleWord(true);
        activeModePromptArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        applyEditorTheme(activeModePromptArea);

        JScrollPane promptScroll = new JScrollPane(activeModePromptArea);
        promptScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        promptScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // Enter 发送，Shift+Enter 换行（与 ChatPanel 一致）
        activeModePromptArea.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    performAnalysis();
                }
            }
        });

        promptPanel.add(promptScroll, BorderLayout.CENTER);
        panel.add(promptPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // 左侧：功能开关
        JPanel featurePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        featurePanel.setBorder(BorderFactory.createTitledBorder("功能开关"));
        enableThinkingCheckBox = new JCheckBox("启用深度思考", false);
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

    private void switchAnalysisMode(String selectedMode) {
        boolean active = MODE_ACTIVE.equals(selectedMode);
        activeModeSelected = active;
        if (centerModeCardLayout != null && centerModeCardPanel != null) {
            centerModeCardLayout.show(centerModeCardPanel, active ? CARD_ACTIVE : CARD_PASSIVE);
        }
        if (passiveControlDetailsPanel != null && passiveControlDetailsPanel.getLayout() instanceof CardLayout) {
            ((CardLayout) passiveControlDetailsPanel.getLayout()).show(passiveControlDetailsPanel, active ? CARD_ACTIVE : CARD_PASSIVE);
        }
        if (active) {
            resultTextPane = activeModeResultTextPane;
            userPromptArea = activeModePromptArea;
        } else {
            resultTextPane = passiveModeResultTextPane;
            userPromptArea = passiveModePromptArea;
        }
        revalidate();
        repaint();
    }

    private void applyEditorTheme(JTextComponent component) {
        if (component == null) return;
        Color bg = UIManager.getColor("TextArea.background");
        Color fg = UIManager.getColor("TextArea.foreground");
        Color caret = UIManager.getColor("TextArea.caretForeground");
        if (bg == null) bg = UIManager.getColor("Panel.background");
        if (fg == null) fg = UIManager.getColor("Panel.foreground");
        if (caret == null) caret = fg;
        if (bg != null) component.setBackground(bg);
        if (fg != null) component.setForeground(fg);
        if (caret != null) component.setCaretColor(caret);
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
        int viewRow = (activeModeSelected || passiveScanTable == null) ? -1 : passiveScanTable.getSelectedRow();
        if (viewRow >= 0) {
            int modelRow = passiveScanTable.convertRowIndexToModel(viewRow);
            Integer id = (Integer) passiveScanTableModel.getValueAt(modelRow, 0);
            if (id != null && passiveScanManager != null) {
                scanResult = passiveScanManager.getResultById(id);
            }
            // 如果是手动添加的请求，尝试从 requestList 获取
            if (scanResult == null && modelRow < requestList.size()) {
                requestData = requestList.get(modelRow);
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
        stopButton.setEnabled(true);

        // 主动模式：提交后立即清空输入框
        final boolean isActiveMode = activeModeSelected;
        if (isActiveMode && activeModePromptArea != null) {
            activeModePromptArea.setText("");
        }
        
        String finalUserPrompt = userPrompt;
        final RequestData finalRequestData = requestData;
        final ScanResult finalScanResult = scanResult;
        final JTextPane targetResultPane = resultTextPane;
        currentWorker = new SwingWorker<Void, String>() {
            private StringBuilder fullResponse = new StringBuilder();
            private int aiMessageStartPos = 0;

            @Override
            protected Void doInBackground() throws Exception {
                try {
                    String httpContent = "";
                    if (finalScanResult != null && finalScanResult.getRequestResponse() != null) {
                        httpContent = com.ai.analyzer.utils.HttpFormatter.formatHttpRequestResponse(
                            finalScanResult.getRequestResponse());
                    } else if (finalRequestData != null) {
                        httpContent = finalRequestData.getFullRequestResponse();
                    }
                    
                    final boolean httpTooLong = !httpContent.isEmpty() 
                        && httpContent.length() > com.ai.analyzer.utils.HttpFormatter.DEFAULT_MAX_LENGTH;
                    final int httpOrigLen = httpContent.length();
                    
                    try {
                        SwingUtilities.invokeAndWait(() -> {
                            if (isActiveMode) {
                                try {
                                    StyledDocument doc = targetResultPane.getStyledDocument();
                                    if (doc.getLength() > 0) {
                                        doc.insertString(doc.getLength(), "\n", doc.getStyle("regular"));
                                    }
                                    // "你:" 蓝色加粗
                                    javax.swing.text.Style senderStyle = doc.addStyle("userSender", null);
                                    javax.swing.text.StyleConstants.setBold(senderStyle, true);
                                    javax.swing.text.StyleConstants.setForeground(senderStyle, Color.BLUE);
                                    doc.insertString(doc.getLength(), "你: ", senderStyle);
                                    // 消息文本
                                    javax.swing.text.Style msgStyle = doc.addStyle("userMsg", null);
                                    Color textColor = UIManager.getColor("TextArea.foreground");
                                    javax.swing.text.StyleConstants.setForeground(msgStyle, textColor != null ? textColor : Color.BLACK);
                                    doc.insertString(doc.getLength(), finalUserPrompt + "\n\n", msgStyle);
                                    // "AI助手:" 绿色加粗
                                    javax.swing.text.Style aiSenderStyle = doc.addStyle("aiSender", null);
                                    javax.swing.text.StyleConstants.setBold(aiSenderStyle, true);
                                    javax.swing.text.StyleConstants.setForeground(aiSenderStyle, Color.GREEN);
                                    doc.insertString(doc.getLength(), "AI助手: \n", aiSenderStyle);
                                } catch (Exception ignored) {}
                            } else {
                                targetResultPane.setText("");
                            }
                            if (httpTooLong) {
                                try {
                                    StyledDocument doc = targetResultPane.getStyledDocument();
                                    javax.swing.text.Style warnStyle = doc.addStyle("httpWarning", null);
                                    javax.swing.text.StyleConstants.setForeground(warnStyle, new Color(255, 140, 0));
                                    javax.swing.text.StyleConstants.setItalic(warnStyle, true);
                                    javax.swing.text.StyleConstants.setFontFamily(warnStyle, "Microsoft YaHei");
                                    javax.swing.text.StyleConstants.setFontSize(warnStyle, 12);
                                    doc.insertString(doc.getLength(), 
                                        "HTTP内容过长（" + httpOrigLen + " 字符），已自动压缩处理\n\n", warnStyle);
                                } catch (Exception ignored) {}
                            }
                            aiMessageStartPos = targetResultPane.getStyledDocument().getLength();
                        });
                    } catch (Exception e) {
                        aiMessageStartPos = 0;
                    }
                    
                    final long[] lastPlainTime = {0L};
                    final long PLAIN_INTERVAL_MS = 150;
                    final long[] lastMdTime = {0L};
                    final long MD_INTERVAL_MS = 2000;
                    final int[] lastPlainLen = {0};

                    apiClient.analyzeRequestStream(
                        httpContent,
                        finalUserPrompt,
                        chunk -> {
                            if (currentWorker.isCancelled() || !isAnalyzing) return;

                            fullResponse.append(chunk);

                            long now = System.currentTimeMillis();

                            if (now - lastMdTime[0] >= MD_INTERVAL_MS && aiMessageStartPos >= 0) {
                                lastMdTime[0] = now;
                                lastPlainTime[0] = now;
                                lastPlainLen[0] = fullResponse.length();
                                String snapshot = fullResponse.toString();
                                SwingUtilities.invokeLater(() -> {
                                    if (currentWorker.isCancelled() || !isAnalyzing) return;
                                    try {
                                        MarkdownRenderer.appendMarkdownStreaming(targetResultPane, snapshot, aiMessageStartPos);
                                        targetResultPane.setCaretPosition(targetResultPane.getStyledDocument().getLength());
                                    } catch (Exception e) {
                                        api.logging().logToError("流式Markdown渲染失败: " + e.getMessage());
                                    }
                                });
                                return;
                            }

                            if (now - lastPlainTime[0] < PLAIN_INTERVAL_MS) return;
                            lastPlainTime[0] = now;

                            int start = lastPlainLen[0];
                            String newText = fullResponse.substring(start);
                            lastPlainLen[0] = fullResponse.length();

                            SwingUtilities.invokeLater(() -> {
                                if (currentWorker.isCancelled() || !isAnalyzing) return;
                                try {
                                    StyledDocument doc = targetResultPane.getStyledDocument();
                                    javax.swing.text.Style plain = doc.getStyle("streaming_plain");
                                    if (plain == null) {
                                        plain = doc.addStyle("streaming_plain", null);
                                        javax.swing.text.StyleConstants.setFontFamily(plain, "Microsoft YaHei");
                                        javax.swing.text.StyleConstants.setFontSize(plain, 13);
                                        Color fg = UIManager.getColor("TextArea.foreground");
                                        if (fg != null) javax.swing.text.StyleConstants.setForeground(plain, fg);
                                    }
                                    doc.insertString(doc.getLength(), newText, plain);
                                    targetResultPane.setCaretPosition(doc.getLength());
                                } catch (Exception e) {
                                    api.logging().logToError("流式文本追加失败: " + e.getMessage());
                                }
                            });
                        }
                    );
                    
                    String finalContent = fullResponse.toString();
                    if (!finalContent.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            try {
                                StyledDocument doc = targetResultPane.getStyledDocument();
                                int currentLength = doc.getLength();
                                if (currentLength > aiMessageStartPos) {
                                    doc.remove(aiMessageStartPos, currentLength - aiMessageStartPos);
                                }
                                MarkdownRenderer.appendMarkdown(targetResultPane, finalContent);
                                targetResultPane.setCaretPosition(doc.getLength());
                            } catch (Exception e) {
                                api.logging().logToError("最终Markdown渲染失败: " + e.getMessage());
                            }
                        });
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
            Color textColor = UIManager.getColor("TextArea.foreground");
            javax.swing.text.StyleConstants.setForeground(regularStyle, textColor != null ? textColor : Color.BLACK);
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
            Color textColor = UIManager.getColor("TextArea.foreground");
            javax.swing.text.StyleConstants.setForeground(regularStyle, textColor != null ? textColor : Color.BLACK);
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
        if (activeModeResultTextPane != null && activeModeResultTextPane != resultTextPane) {
            activeModeResultTextPane.setText("");
        }
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
            
            // 设置 Python 脚本执行选项
            settings.setEnablePythonScript(enablePythonScriptCheckbox != null && enablePythonScriptCheckbox.isSelected());
            settings.setEnableNotebook(enableNotebookCheckbox != null && enableNotebookCheckbox.isSelected());
            
            // 设置 Skills 选项
            settings.setEnableSkills(enableSkillsCheckBox.isSelected());
            settings.setSkillsDirectoryPath(skillsDirectoryField.getText().trim());
            settings.setEnabledSkillNames(apiClient.getSkillManager().getEnabledSkillNames());
            settings.setWorkplaceDirectoryPath(workplaceDirectoryField != null ? workplaceDirectoryField.getText().trim() : "");

            // 联网搜索配置
            if (searchModeComboBox != null) {
                int idx = searchModeComboBox.getSelectedIndex();
                String mode = switch (idx) {
                    case 1 -> "tavily";
                    case 2 -> "google";
                    case 3 -> "duckduckgo";
                    default -> "enableSearch";
                };
                settings.setSearchMode(mode);
            }
            if (tavilyApiKeyField != null) {
                settings.setTavilyApiKey(tavilyApiKeyField.getText().trim());
            }
            if (tavilyBaseUrlField != null) {
                settings.setTavilyBaseUrl(tavilyBaseUrlField.getText().trim());
            }
            if (googleApiKeyField != null) {
                settings.setGoogleSearchApiKey(googleApiKeyField.getText().trim());
            }
            if (googleCsiField != null) {
                settings.setGoogleSearchCsi(googleCsiField.getText().trim());
            }
            apiClient.setSearchMode(settings.getSearchMode());
            apiClient.setTavilyApiKey(settings.getTavilyApiKey());
            apiClient.setTavilyBaseUrl(settings.getTavilyBaseUrl());
            apiClient.setGoogleSearchApiKey(settings.getGoogleSearchApiKey());
            apiClient.setGoogleSearchCsi(settings.getGoogleSearchCsi());
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setSearchMode(settings.getSearchMode());
                passiveScanManager.getApiClient().setTavilyApiKey(settings.getTavilyApiKey());
                passiveScanManager.getApiClient().setTavilyBaseUrl(settings.getTavilyBaseUrl());
                passiveScanManager.getApiClient().setGoogleSearchApiKey(settings.getGoogleSearchApiKey());
                passiveScanManager.getApiClient().setGoogleSearchCsi(settings.getGoogleSearchCsi());
            }

            // 自定义系统提示词（与默认值相同时存 null，避免冗余序列化）
            if (activeSystemPromptArea != null) {
                String activeText = activeSystemPromptArea.getText();
                settings.setCustomActiveSystemPrompt(
                    activeText.strip().equals(com.ai.analyzer.Client.SystemPromptBuilder.getDefaultBasePrompt().strip()) ? null : activeText);
            }
            if (passiveSystemPromptArea != null) {
                String passiveText = passiveSystemPromptArea.getText();
                settings.setCustomPassiveSystemPrompt(
                    passiveText.strip().equals(com.ai.analyzer.pscan.SystemPromptBuilder.getDefaultBasePrompt().strip()) ? null : passiveText);
            }
            // 被动扫描过滤（与默认值相同时存空串）
            if (passiveScanSkipExtensionsArea != null) {
                String extText = passiveScanSkipExtensionsArea.getText();
                settings.setPassiveScanSkipExtensions(
                    extText.strip().equals(com.ai.analyzer.pscan.PassiveScanTask.getDefaultSkipExtensionsText().strip()) ? "" : extText);
            }
            if (passiveScanDomainBlacklistArea != null) settings.setPassiveScanDomainBlacklist(passiveScanDomainBlacklistArea.getText());
            
            // 应用系统提示词到 API 客户端
            apiClient.setCustomSystemPrompt(settings.getCustomActiveSystemPrompt());
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setCustomSystemPrompt(settings.getCustomPassiveSystemPrompt());
            }
            // 应用被动扫描过滤规则
            applyPassiveScanFilters();

            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("ai_analyzer_settings.dat"));
            oos.writeObject(settings);
            oos.close();

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
        boolean isAnthropic = "Anthropic兼容".equals(provider);
        enableThinkingCheckBox.setEnabled(isDashScope || isAnthropic);
        enableSearchCheckBox.setEnabled(isDashScope);
        
        apiUrlField.setText(settings.getApiUrl());
        apiKeyField.setText(settings.getApiKey());
        modelField.setText(settings.getModel());
        customParametersField.setText(settings.getCustomParameters());
        if (workplaceDirectoryField != null) {
            workplaceDirectoryField.setText(settings.getWorkplaceDirectoryPath());
        }
        setPromptTextForAllModes(settings.getUserPrompt());
        enableThinkingCheckBox.setSelected((isDashScope || isAnthropic) && settings.isEnableThinking());
        enableSearchCheckBox.setSelected(settings.isEnableSearch());

        // 联网搜索配置
        String searchMode = settings.getSearchMode();
        if (searchModeComboBox != null) {
            int searchIdx = switch (searchMode) {
                case "tavily" -> 1;
                case "google" -> 2;
                case "duckduckgo" -> 3;
                default -> 0;
            };
            searchModeComboBox.setSelectedIndex(searchIdx);
            boolean isTavily = "tavily".equals(searchMode);
            boolean isGoogle = "google".equals(searchMode);
            boolean isToolSearch = isTavily || isGoogle || "duckduckgo".equals(searchMode);
            if (tavilyApiKeyField != null) tavilyApiKeyField.setEnabled(isTavily);
            if (tavilyBaseUrlField != null) tavilyBaseUrlField.setEnabled(isTavily);
            if (googleApiKeyField != null) googleApiKeyField.setEnabled(isGoogle);
            if (googleCsiField != null) googleCsiField.setEnabled(isGoogle);
            if (!isToolSearch && !isDashScope) {
                enableSearchCheckBox.setEnabled(false);
            } else {
                enableSearchCheckBox.setEnabled(true);
            }
        }
        if (tavilyApiKeyField != null) {
            tavilyApiKeyField.setText(settings.getTavilyApiKey());
        }
        if (tavilyBaseUrlField != null) {
            tavilyBaseUrlField.setText(settings.getTavilyBaseUrl());
        }
        if (googleApiKeyField != null) {
            googleApiKeyField.setText(settings.getGoogleSearchApiKey());
        }
        if (googleCsiField != null) {
            googleCsiField.setText(settings.getGoogleSearchCsi());
        }

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
        apiClient.setEnableThinking((isDashScope || isAnthropic) && settings.isEnableThinking());
        apiClient.setEnableSearch(settings.isEnableSearch());
        apiClient.setSearchMode(settings.getSearchMode());
        apiClient.setTavilyApiKey(settings.getTavilyApiKey());
        apiClient.setTavilyBaseUrl(settings.getTavilyBaseUrl());
        apiClient.setGoogleSearchApiKey(settings.getGoogleSearchApiKey());
        apiClient.setGoogleSearchCsi(settings.getGoogleSearchCsi());
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
        String effectiveSkillsPath = settings.resolveSkillsDirectoryPath();
        if (effectiveSkillsPath == null || effectiveSkillsPath.isEmpty()) {
            effectiveSkillsPath = settings.getSkillsDirectoryPath();
        }
        skillsDirectoryField.setText(effectiveSkillsPath != null ? effectiveSkillsPath : "");
        skillsDirectoryField.setEnabled(false);
        refreshSkillsButton.setEnabled(settings.isEnableSkills());
        createExampleSkillButton.setEnabled(settings.isEnableSkills());
        skillsTable.setEnabled(settings.isEnableSkills());
        
        apiClient.setEnableSkills(settings.isEnableSkills());
        apiClient.setWorkplaceDirectoryPath(settings.getWorkplaceDirectoryPath());
        if (effectiveSkillsPath != null && !effectiveSkillsPath.isEmpty()) {
            apiClient.setSkillsDirectoryPath(effectiveSkillsPath);
            apiClient.getSkillManager().loadSkills();
            // 恢复已启用的 skills
            if (settings.getEnabledSkillNames() != null) {
                apiClient.getSkillManager().setEnabledSkillNames(settings.getEnabledSkillNames());
            }
            updateSkillsTable();
        }
        applyWorkplaceToDerivedPaths(true, true);
        
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
        
        // Python 脚本执行配置
        if (enablePythonScriptCheckbox != null) {
            enablePythonScriptCheckbox.setSelected(settings.isEnablePythonScript());
            apiClient.setEnablePythonScript(settings.isEnablePythonScript());
        }
        if (enableNotebookCheckbox != null) {
            enableNotebookCheckbox.setSelected(settings.isEnableNotebook());
            apiClient.setEnableNotebook(settings.isEnableNotebook());
        }
        
        // 自定义系统提示词
        if (activeSystemPromptArea != null) activeSystemPromptArea.setText(settings.getCustomActiveSystemPrompt());
        if (passiveSystemPromptArea != null) passiveSystemPromptArea.setText(settings.getCustomPassiveSystemPrompt());
        apiClient.setCustomSystemPrompt(settings.getCustomActiveSystemPrompt());
        if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
            passiveScanManager.getApiClient().setCustomSystemPrompt(settings.getCustomPassiveSystemPrompt());
        }
        
        // 被动扫描过滤配置
        if (passiveScanSkipExtensionsArea != null) passiveScanSkipExtensionsArea.setText(settings.getPassiveScanSkipExtensions());
        if (passiveScanDomainBlacklistArea != null) passiveScanDomainBlacklistArea.setText(settings.getPassiveScanDomainBlacklist());
        applyPassiveScanFilters();
    }

    private void setPromptTextForAllModes(String text) {
        String prompt = text != null ? text : "";
        if (passiveModePromptArea != null) passiveModePromptArea.setText(prompt);
        // 主动模式输入框不设置默认文本（聊天式交互，每次提交后清空）
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
                    
                    // 选中新添加的行（model index → view index）
                    int modelRow = passiveScanTableModel.getRowCount() - 1;
                    int viewRow = passiveScanTable.convertRowIndexToView(modelRow);
                    passiveScanTable.setRowSelectionInterval(viewRow, viewRow);
                    passiveScanTable.scrollRectToVisible(passiveScanTable.getCellRect(viewRow, 0, true));
                    
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
