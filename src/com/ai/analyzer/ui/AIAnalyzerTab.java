package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import com.ai.analyzer.core.AgentApiClient;
import com.ai.analyzer.core.PluginSettings;
import com.ai.analyzer.core.RequestData;
import com.ai.analyzer.scan.pscan.PassiveScanApiClient;
import com.ai.analyzer.scan.pscan.PassiveScanManager;
import com.ai.analyzer.scan.pscan.PassiveScanTask;
import com.ai.analyzer.scan.pscan.ScanResult;
import com.ai.analyzer.util.AppLogBuffer;
import com.ai.analyzer.util.DebugContext;
import com.ai.analyzer.util.MarkdownRenderer;
import com.ai.analyzer.agent.skills.Skill;
import com.ai.analyzer.agent.skills.SkillManager;
import com.ai.analyzer.scan.rulesmatch.PreScanFilterManager;
import com.ai.analyzer.ui.active.ActiveAnalysisPanel;
import com.ai.analyzer.ui.active.PassiveDataSource;
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
    private String currentApiKeySecret = "";
    private JTextField modelField;
    private JTextField customParametersField; // 自定义参数输入框
    private JTextField maxTokensField; // 显式上下文预算输入框
    private JTextField tokenBudgetField; // 扫描周期 Token 预算输入框
    private JComboBox<String> apiProfileComboBox;
    private final List<PluginSettings.ApiProfile> apiProfiles = new ArrayList<>();
    private JCheckBox enableSearchCheckBox;
    private JCheckBox enableMcpCheckBox;
    private JTextField BurpMcpUrlField;
    private JTextField burpMcpAuthorizationField;
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
    
    // 前置扫描器组件
    private JCheckBox enablePreScanCheckbox;
    private JCheckBox enablePythonScriptCheckbox;
    // CLI 标签页组件
    private JCheckBox enableCliToolCheckBox;
    private JCheckBox enableUnrestrictedCliToolCheckBox;
    private JTextArea cliWhitelistArea;
    private JTextArea cliToolPromptArea;
    private JButton browseWorkplaceDirButton;

    // Skills 组件
    private SkillManager skillManager;
    private JCheckBox enableSkillsCheckBox;
    private JTextField skillsDirectoryField;
    private JTable skillsTable;
    private DefaultTableModel skillsTableModel;
    private JTextArea skillPreviewPane;
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

    // 自定义 MCP 配置
    private JTextArea customMcpConfigArea;
    private JLabel customMcpStatusLabel;
    private JCheckBox enableCustomMcpCheckBox;
    private JLabel customMcpSummaryLabel;

    // 已替换为 passiveScanTable 和 passiveScanTableModel
    // private JTable requestListTable;
    // private DefaultTableModel requestTableModel;
    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;
    private JTextArea userPromptArea;
    private JTextPane resultTextPane;
    private JScrollPane resultScrollPane;
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
    private JTextPane passiveModeResultTextPane;
    private JTextArea passiveModePromptArea;
    private boolean activeModeSelected = false;
    private ActiveAnalysisPanel activeAnalysisPanel;

    /** 被动扫描数据源：供 ActiveAnalysisPanel 读取请求列表选中项 */
    private final PassiveDataSource passiveDataSource = new PassiveDataSource() {
        @Override
        public int getSelectedViewRow() {
            return (passiveScanTable == null) ? -1 : passiveScanTable.getSelectedRow();
        }

        @Override
        public Object selectionForViewRow(int viewRow) {
            if (passiveScanTable == null || viewRow < 0) return null;
            int modelRow = passiveScanTable.convertRowIndexToModel(viewRow);
            Integer id = (Integer) passiveScanTableModel.getValueAt(modelRow, 0);
            if (id != null && passiveScanManager != null) {
                ScanResult sr = passiveScanManager.getResultById(id);
                if (sr != null) return sr;
            }
            if (modelRow < requestList.size()) {
                return requestList.get(modelRow);
            }
            return null;
        }

        @Override
        public List<Object> getSelectedSelections() {
            List<Object> out = new ArrayList<>();
            if (passiveScanTable == null) return out;
            for (int viewRow : passiveScanTable.getSelectedRows()) {
                Object sel = selectionForViewRow(viewRow);
                if (sel != null) out.add(sel);
            }
            return out;
        }

        @Override
        public burp.api.montoya.http.message.HttpRequestResponse selectedRequestResponse() {
            Object sel = selectionForViewRow(getSelectedViewRow());
            if (sel instanceof ScanResult sr && sr.getRequestResponse() != null) {
                return sr.getRequestResponse();
            }
            if (sel instanceof RequestData rd && rd.getRequest() != null && !rd.getRequest().isEmpty()) {
                try {
                    HttpRequest httpRequest = HttpRequest.httpRequest(rd.getRequest());
                    return burp.api.montoya.http.message.HttpRequestResponse.httpRequestResponse(httpRequest, HttpResponse.httpResponse());
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    };

    // 数据
    private List<RequestData> requestList;
    private int nextRequestId = 1;
    // private ToolExecutor toolExecutor;
    
    // 被动扫描相关组件
    private PassiveScanManager passiveScanManager;
    private JCheckBox enablePassiveScanCheckBox;
    private JSpinner threadCountSpinner;
    private JButton startPassiveScanButton;
    private JButton stopPassiveScanButton;
    private com.ai.analyzer.scan.active.ActiveAuditManager activeAuditManager;
    private JButton auditQueueButton;
    private JLabel passiveScanStatusLabel;
    private JLabel tokenUsageLabel;
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

        // 第三个标签页：cli（命令行工具）
        JPanel cliPanel = createCliTabPanel();
        mainTabbedPane.addTab("Cli", cliPanel);

        JPanel mcpTrafficPanel = createMcpTrafficTabPanel();
        mainTabbedPane.addTab("Logger", mcpTrafficPanel);

        JPanel skillsPanel = createSkillsTabPanel();
        mainTabbedPane.addTab("技能", skillsPanel);

        JPanel debugPanel = createDebugLogTabPanel();
        mainTabbedPane.addTab("Debug", debugPanel);
        
        add(mainTabbedPane, BorderLayout.CENTER);
    }

    private JPanel createDebugLogTabPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(false);
        logArea.setFont(createLogFont());

        // 调试模式开关
        JCheckBox enableDebugCheckBox = new JCheckBox("启用调试模式");
        enableDebugCheckBox.setToolTipText("启用后记录所有 Agent 事件、工具调用、模型交互的详细日志");
        enableDebugCheckBox.addActionListener(e -> {
            if (enableDebugCheckBox.isSelected()) {
                DebugContext.enable();
            } else {
                DebugContext.disable();
            }
        });

        // 导出调试日志按钮
        JButton exportButton = new JButton("导出调试日志");
        exportButton.setToolTipText("将 DebugContext 事件日志导出到文件");
        exportButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new java.io.File("burp-debug-log.txt"));
            if (chooser.showSaveDialog(panel) == JFileChooser.APPROVE_OPTION) {
                try {
                    DebugContext.dumpToFile(chooser.getSelectedFile());
                    api.logging().logToOutput("[Debug] 调试日志已导出到: " + chooser.getSelectedFile().getAbsolutePath());
                } catch (Exception ex) {
                    api.logging().logToError("[Debug] 导出调试日志失败: " + ex.getMessage());
                }
            }
        });

        // 事件计数器
        JLabel eventCounterLabel = new JLabel("事件: 0");

        JButton refreshButton = new JButton("刷新");
        JButton clearButton = new JButton("清空");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(enableDebugCheckBox);
        top.add(exportButton);
        top.add(refreshButton);
        top.add(clearButton);
        top.add(eventCounterLabel);

        Runnable refresh = () -> {
            StringBuilder sb = new StringBuilder();
            // AppLogBuffer 日志
            String appLog = AppLogBuffer.snapshot();
            if (!appLog.isEmpty()) {
                sb.append("=== 应用日志 ===\n");
                sb.append(appLog);
                sb.append("\n\n");
            }
            // DebugContext 事件日志
            if (DebugContext.isEnabled()) {
                sb.append("=== 调试事件 (DebugContext) ===\n");
                sb.append(DebugContext.snapshotText());
                eventCounterLabel.setText("事件: " + DebugContext.snapshot().size());
            } else {
                eventCounterLabel.setText("事件: -- (调试模式未启用)");
            }
            logArea.setText(sb.toString());
        };

        refreshButton.addActionListener(e -> refresh.run());
        clearButton.addActionListener(e -> {
            AppLogBuffer.clear();
            refresh.run();
        });

        Timer timer = new Timer(1500, e -> refresh.run());
        timer.start();
        refresh.run();

        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createMcpTrafficTabPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        DefaultTableModel tableModel = new DefaultTableModel(
                new Object[]{"时间", "工具", "目标", "状态", "耗时"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable trafficTable = new JTable(tableModel);
        trafficTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        trafficTable.setAutoCreateRowSorter(true);
        trafficTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        trafficTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        trafficTable.getColumnModel().getColumn(2).setPreferredWidth(420);
        trafficTable.getColumnModel().getColumn(3).setPreferredWidth(70);
        trafficTable.getColumnModel().getColumn(4).setPreferredWidth(80);

        HttpRequestEditor mcpRequestEditor = api.userInterface().createHttpRequestEditor();
        HttpResponseEditor mcpResponseEditor = api.userInterface().createHttpResponseEditor();
        JTextArea argsArea = new JTextArea();
        argsArea.setEditable(false);
        argsArea.setLineWrap(true);
        argsArea.setWrapStyleWord(true);
        argsArea.setFont(createLogFont());

        JTextArea rawResultArea = new JTextArea();
        rawResultArea.setEditable(false);
        rawResultArea.setLineWrap(true);
        rawResultArea.setWrapStyleWord(true);
        rawResultArea.setFont(createLogFont());

        JSplitPane messageSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        messageSplit.setResizeWeight(0.5);
        messageSplit.setLeftComponent(mcpRequestEditor.uiComponent());
        messageSplit.setRightComponent(mcpResponseEditor.uiComponent());

        JTabbedPane detailTabs = new JTabbedPane();
        detailTabs.addTab("Request / Response", messageSplit);
        detailTabs.addTab("参数", new JScrollPane(argsArea));
        detailTabs.addTab("原始返回", new JScrollPane(rawResultArea));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.35);
        splitPane.setTopComponent(new JScrollPane(trafficTable));
        splitPane.setBottomComponent(detailTabs);

        JButton refreshButton = new JButton("刷新");
        JButton clearButton = new JButton("清空");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(refreshButton);
        top.add(clearButton);

        final List<AppLogBuffer.McpTrafficEntry>[] currentEntries = new List[]{new ArrayList<>()};
        Runnable showSelected = () -> {
            int viewRow = trafficTable.getSelectedRow();
            if (viewRow < 0) {
                mcpRequestEditor.setRequest(HttpRequest.httpRequest());
                mcpResponseEditor.setResponse(HttpResponse.httpResponse());
                argsArea.setText("");
                rawResultArea.setText("");
                return;
            }
            int modelRow = trafficTable.convertRowIndexToModel(viewRow);
            if (modelRow < 0 || modelRow >= currentEntries[0].size()) return;
            AppLogBuffer.McpTrafficEntry entry = currentEntries[0].get(modelRow);
            String request = entry.request() != null ? entry.request() : "";
            if (!request.isBlank()) {
                mcpRequestEditor.setRequest(HttpRequest.httpRequest(request));
            } else {
                mcpRequestEditor.setRequest(HttpRequest.httpRequest());
            }
            String response = extractHttpResponseForEditor(entry.response());
            if (!response.isBlank()) {
                try {
                    mcpResponseEditor.setResponse(HttpResponse.httpResponse(response));
                } catch (Exception ex) {
                    mcpResponseEditor.setResponse(HttpResponse.httpResponse());
                }
            } else {
                mcpResponseEditor.setResponse(HttpResponse.httpResponse());
            }
            argsArea.setText(entry.args() != null ? entry.args() : "");
            argsArea.setCaretPosition(0);
            rawResultArea.setText(entry.response() != null ? entry.response() : "");
            rawResultArea.setCaretPosition(0);
        };
        Runnable refresh = () -> {
            int selectedModelRow = -1;
            if (trafficTable.getSelectedRow() >= 0) {
                selectedModelRow = trafficTable.convertRowIndexToModel(trafficTable.getSelectedRow());
            }
            currentEntries[0] = AppLogBuffer.mcpTrafficEntriesSnapshot();
            tableModel.setRowCount(0);
            for (AppLogBuffer.McpTrafficEntry entry : currentEntries[0]) {
                tableModel.addRow(new Object[]{
                        entry.timestamp(),
                        entry.toolName(),
                        entry.target(),
                        entry.status(),
                        entry.duration()
                });
            }
            if (selectedModelRow >= 0 && selectedModelRow < tableModel.getRowCount()) {
                int viewRow = trafficTable.convertRowIndexToView(selectedModelRow);
                trafficTable.setRowSelectionInterval(viewRow, viewRow);
            } else if (tableModel.getRowCount() > 0 && trafficTable.getSelectedRow() < 0) {
                int last = trafficTable.convertRowIndexToView(tableModel.getRowCount() - 1);
                trafficTable.setRowSelectionInterval(last, last);
            }
            showSelected.run();
        };
        refreshButton.addActionListener(e -> refresh.run());
        clearButton.addActionListener(e -> {
            AppLogBuffer.clearMcpTraffic();
            refresh.run();
        });
        trafficTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected.run();
        });

        Timer timer = new Timer(1500, e -> refresh.run());
        timer.start();
        refresh.run();

        panel.add(top, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);
        return panel;
    }

    private Font createLogFont() {
        String[] candidates = {
                "Microsoft YaHei UI",
                "Microsoft YaHei",
                "SimSun",
                Font.MONOSPACED
        };
        String sample = "中文日志 ABC 123";
        for (String name : candidates) {
            Font font = new Font(name, Font.PLAIN, 12);
            if (font.canDisplayUpTo(sample) < 0) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    private String extractHttpResponseForEditor(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) return "";
        String text = rawResult.replace("\\r\\n", "\r\n").replace("\\n", "\n");
        int httpStart = text.indexOf("HTTP/");
        if (httpStart < 0) {
            return "";
        }
        String response = text.substring(httpStart).trim();
        int fencedEnd = response.indexOf("\n```");
        if (fencedEnd > 0) {
            response = response.substring(0, fencedEnd).trim();
        }
        return response;
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
        activeAnalysisPanel = new ActiveAnalysisPanel(api);
        activeAnalysisPanel.bind(apiClient, this::updateApiClientConfigForAnalysis, this::onAnalysisStateChanged, passiveDataSource);

        centerModeCardLayout = new CardLayout();
        centerModeCardPanel = new JPanel(centerModeCardLayout);
        centerModeCardPanel.add(passiveMainSplitPane, CARD_PASSIVE);
        centerModeCardPanel.add(activeAnalysisPanel, CARD_ACTIVE);
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

        // 左侧：控制选项（仅被动模式显示），按钮分两行避免单行溢出
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        JPanel controlRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        JPanel controlRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        
        enablePassiveScanCheckBox = new JCheckBox("启用被动扫描", false);
        enablePassiveScanCheckBox.setToolTipText("启用后可以自动从HTTP History获取流量进行AI安全扫描");
        enablePassiveScanCheckBox.addActionListener(e -> {
            boolean enabled = enablePassiveScanCheckBox.isSelected();
            threadCountSpinner.setEnabled(enabled);
            startPassiveScanButton.setEnabled(enabled && !passiveScanManager.isRunning());
            stopPassiveScanButton.setEnabled(enabled && passiveScanManager.isRunning());
        });
        controlRow1.add(enablePassiveScanCheckBox);
        
        controlRow1.add(new JLabel("线程数:"));
        SpinnerModel spinnerModel = new SpinnerNumberModel(10, 1, 50, 1);
        threadCountSpinner = new JSpinner(spinnerModel);
        threadCountSpinner.setEnabled(false);
        threadCountSpinner.setPreferredSize(new Dimension(60, 25));
        threadCountSpinner.addChangeListener(e -> {
            if (passiveScanManager != null) {
                passiveScanManager.setThreadCount((Integer) threadCountSpinner.getValue());
            }
        });
        controlRow1.add(threadCountSpinner);
        
        startPassiveScanButton = new JButton("开始扫描");
        startPassiveScanButton.setEnabled(false);
        startPassiveScanButton.addActionListener(e -> startPassiveScan());
        controlRow1.add(startPassiveScanButton);
        
        stopPassiveScanButton = new JButton("停止扫描");
        stopPassiveScanButton.setEnabled(false);
        stopPassiveScanButton.addActionListener(e -> stopPassiveScan());
        controlRow1.add(stopPassiveScanButton);
        
        JButton clearPassiveScanButton = new JButton("清空结果");
        clearPassiveScanButton.addActionListener(e -> clearPassiveScanResults());
        controlRow2.add(clearPassiveScanButton);

        JButton resetTokenButton = new JButton("重置Token统计");
        resetTokenButton.setToolTipText("清零累计 Token 用量与预算状态（预算值本身保留）");
        resetTokenButton.addActionListener(e ->
                com.ai.analyzer.util.TokenUsageTracker.instance().reset());
        controlRow2.add(resetTokenButton);

        JButton activeAuditButton = new JButton("主动审计选中");
        activeAuditButton.setToolTipText("对结果列表中选中的请求发起 Burp Scanner 主动审计，完成后问题自动合并回列表");
        activeAuditButton.addActionListener(e -> startActiveAuditForSelection());
        controlRow2.add(activeAuditButton);

        auditQueueButton = new JButton("审计队列(0)");
        auditQueueButton.setToolTipText("被动扫描判定的高危结果自动进入待审计队列；点击一键对队列内全部目标发起 Burp 主动审计");
        auditQueueButton.setEnabled(false);
        auditQueueButton.addActionListener(e -> auditPendingQueue());
        controlRow2.add(auditQueueButton);

        controlPanel.add(controlRow1);
        controlPanel.add(controlRow2);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        passiveScanStatusLabel = new JLabel("就绪");
        passiveScanStatusLabel.setPreferredSize(new Dimension(200, 20));
        statusPanel.add(passiveScanStatusLabel);
        passiveScanProgressBar = new JProgressBar(0, 100);
        passiveScanProgressBar.setPreferredSize(new Dimension(150, 20));
        passiveScanProgressBar.setStringPainted(true);
        statusPanel.add(passiveScanProgressBar);
        tokenUsageLabel = new JLabel("Token: 0 次调用");
        tokenUsageLabel.setForeground(Color.DARK_GRAY);
        statusPanel.add(tokenUsageLabel);

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

        // ========== 主动审计闭环（Burp Scanner → 问题推送回合并进被动列表） ==========
        activeAuditManager = new com.ai.analyzer.scan.active.ActiveAuditManager(api.scanner());
        activeAuditManager.setOnIssueFound(issue ->
                SwingUtilities.invokeLater(() -> passiveScanManager.addAuditIssue(issue)));
        activeAuditManager.setOnStatusChanged(status ->
                SwingUtilities.invokeLater(() -> passiveScanStatusLabel.setText(status)));

        // ========== 被动 → 主动协作：高危结果自动进入待审计队列 ==========
        passiveScanManager.setOnHighRiskResult(result ->
                activeAuditManager.queueForAudit(result.getRequestResponse()));
        activeAuditManager.setOnQueueChanged(() ->
                SwingUtilities.invokeLater(() -> {
                    int size = activeAuditManager.pendingQueueSize();
                    auditQueueButton.setText("审计队列(" + size + ")");
                    auditQueueButton.setEnabled(size > 0);
                }));
        refreshAuditQueueButton();

        // ========== Token 用量统计与预算显示 ==========
        com.ai.analyzer.util.TokenUsageTracker.instance().setListener(snapshot ->
                SwingUtilities.invokeLater(() -> updateTokenUsageLabel(snapshot)));
        updateTokenUsageLabel(com.ai.analyzer.util.TokenUsageTracker.instance().snapshot());
        
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
                        if (isResultPaneAtBottom()) {
                            passiveScanResultPane.setCaretPosition(passiveScanResultPane.getStyledDocument().getLength());
                        }
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
                    if (isResultPaneAtBottom()) {
                        passiveScanResultPane.setCaretPosition(doc.getLength());
                    }
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
            psClient.setMaxTokens(apiClient.getMaxTokens());
            psClient.setApiProvider(apiClient.getApiProvider().getDisplayName());
            psClient.setEnableThinking(false);
            psClient.setEnableSearch(apiClient.isEnableSearch());
            psClient.setSearchMode(apiClient.getConfig().getSearchMode());
            psClient.setTavilyApiKey(apiClient.getConfig().getTavilyApiKey());
            psClient.setTavilyBaseUrl(apiClient.getConfig().getTavilyBaseUrl());
            psClient.setGoogleSearchApiKey(apiClient.getConfig().getGoogleSearchApiKey());
            psClient.setGoogleSearchCsi(apiClient.getConfig().getGoogleSearchCsi());
            
            // MCP 配置（关键：这些配置决定了工具是否可用）
            psClient.setEnableMcp(apiClient.isEnableMcp());
            psClient.setBurpMcpUrl(apiClient.getBurpMcpUrl());
            psClient.setBurpMcpAuthorization(apiClient.getBurpMcpAuthorization());
            
            // RAG MCP 配置
            psClient.setEnableRagMcp(apiClient.isEnableRagMcp());
            psClient.setRagMcpUrl(apiClient.getRagMcpUrl());
            psClient.setRagMcpDocumentsPath(apiClient.getRagMcpDocumentsPath());
            
            // Chrome MCP 配置
            psClient.setEnableChromeMcp(apiClient.isEnableChromeMcp());
            psClient.setChromeMcpUrl(apiClient.getChromeMcpUrl());
            
            // 文件系统访问配置
            psClient.setEnableFileSystemAccess(apiClient.isEnableFileSystemAccess());

            // 自定义 MCP 配置
            psClient.setCustomMcpConfigJson(settingsEnableCustomMcp() ? apiClient.getConfig().getCustomMcpConfigJson() : "");
            
            // Python 脚本执行配置
            psClient.setEnablePythonScript(apiClient.isEnablePythonScript());
            psClient.setEnableCliTool(apiClient.getConfig().isEnableCliTool());
            psClient.setEnableUnrestrictedCliTool(apiClient.getConfig().isEnableUnrestrictedCliTool());
            psClient.setCliWhitelist(apiClient.getConfig().getCliWhitelist());
            psClient.setCliToolPrompt(apiClient.getConfig().getCliToolPrompt());
            psClient.setWorkplaceDirectoryPath(workplaceDirectoryField != null ? workplaceDirectoryField.getText().trim() : "");

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
    /**
     * 是否位于结果区底部（用户未向上滚动阅读时返回 true，流式输出才会自动滚到底部）
     */
    private boolean isResultPaneAtBottom() {
        if (resultScrollPane == null) return false;
        JScrollBar vbar = resultScrollPane.getVerticalScrollBar();
        return vbar.getMaximum() - vbar.getValue() - vbar.getVisibleAmount() < 80;
    }

    /**
     * 在结果区底部追加内容后，仅当用户位于底部时才滚动到末尾。
     */
    private void scrollResultToEndIfAtBottom() {
        if (isResultPaneAtBottom()) {
            resultTextPane.setCaretPosition(resultTextPane.getDocument().getLength());
        }
    }

    private void startPassiveScan() {
        if (!enablePassiveScanCheckBox.isSelected()) {
            // 按钮静默失效是最常见的"反人类"点：给出明确原因提示
            passiveScanStatusLabel.setText("请先勾选「启用被动扫描」");
            new javax.swing.Timer(3000, e -> {
                ((javax.swing.Timer) e.getSource()).stop();
                passiveScanStatusLabel.setText("就绪");
            }).start();
            return;
        }
        
        // 同步最新的API配置
        syncApiConfigToPassiveScan();

        // 应用过滤规则（避免用户修改后未点「立即应用」直接开扫导致不生效）
        applyPassiveScanFilters();

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
     * 解析 Token 预算输入（支持 1000 / 100K / 1M 后缀），非法或留空返回 0（不限制）。
     */
    private long parseTokenBudget(String text) {
        if (text == null) return 0;
        String s = text.trim().toUpperCase().replace(",", "").replace("_", "");
        if (s.isEmpty()) return 0;
        double multiplier = 1;
        if (s.endsWith("K")) {
            multiplier = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("M")) {
            multiplier = 1_000_000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            double v = Double.parseDouble(s.trim());
            if (v <= 0) return 0;
            return (long) (v * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 更新 Token 用量标签（EDT 线程调用）。
     */
    private void updateTokenUsageLabel(com.ai.analyzer.util.TokenUsageTracker.UsageSnapshot snapshot) {        if (tokenUsageLabel == null) return;
        String text = "Token: " + snapshot.summary();
        if (snapshot.budgetTokens() > 0) {
            text += " / 预算 " + snapshot.budgetTokens();
            if (snapshot.overBudget()) {
                text += " ⛔已用尽";
                tokenUsageLabel.setForeground(Color.RED);
            } else {
                tokenUsageLabel.setForeground(Color.DARK_GRAY);
            }
        } else {
            tokenUsageLabel.setForeground(Color.DARK_GRAY);
        }
        tokenUsageLabel.setText(text);
    }

    /**
     * 一键审计待审计队列（被动高危结果自动入队的目标）。
     */
    private void auditPendingQueue() {
        if (activeAuditManager == null) {
            return;
        }
        activeAuditManager.auditPendingQueue();
    }

    /**
     * 刷新审计队列按钮状态（EDT 线程调用）。
     */
    private void refreshAuditQueueButton() {
        if (auditQueueButton == null || activeAuditManager == null) return;
        int size = activeAuditManager.pendingQueueSize();
        auditQueueButton.setText("审计队列(" + size + ")");
        auditQueueButton.setEnabled(size > 0);
    }
    
    /**
     * 更新被动扫描表格
     */
    /**
     * 对被动扫描结果列表中选中的请求发起主动审计（Burp Scanner）。
     */
    private void startActiveAuditForSelection() {
        if (activeAuditManager == null || passiveScanTable == null) {
            return;
        }
        int[] viewRows = passiveScanTable.getSelectedRows();
        java.util.List<HttpRequestResponse> targets = new java.util.ArrayList<>();
        for (int viewRow : viewRows) {
            int modelRow = passiveScanTable.convertRowIndexToModel(viewRow);
            Object idObj = passiveScanTableModel.getValueAt(modelRow, 0);
            if (idObj instanceof Number) {
                ScanResult result = passiveScanManager.getResultById(((Number) idObj).intValue());
                if (result != null && result.getRequestResponse() != null) {
                    targets.add(result.getRequestResponse());
                }
            }
        }
        if (targets.isEmpty()) {
            passiveScanStatusLabel.setText("请先在结果列表中选择要审计的请求");
            return;
        }
        int started = activeAuditManager.startAudit(targets);
        if (started > 0) {
            passiveScanStatusLabel.setText("主动审计已启动，共 " + started + " 个目标请求");
        }
    }

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
        subTabs.addTab("MCP 配置", createConfigSubTab_Mcp());
        subTabs.addTab("联网搜索", createConfigSubTab_Search());
        subTabs.addTab("系统提示词", createConfigSubTab_Prompts());
        subTabs.addTab("被动扫描过滤", createConfigSubTab_Filters());
        
        configPanel.add(subTabs, BorderLayout.CENTER);
        return configPanel;
    }

    /**
     * 创建 CLI 工具标签页（第三个标签页）
     * 允许配置：是否启用、白名单、工具提示词
     */
    private JPanel createCliTabPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // 启用开关
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        enableCliToolCheckBox = new JCheckBox("启用 CLI 工具（允许 AI 调用本地命令）", false);
        enableCliToolCheckBox.setToolTipText("启用后，AI 可通过 run_cli 工具执行白名单内命令（建议谨慎开启）");
        enableCliToolCheckBox.addActionListener(e -> {
            boolean enabled = enableCliToolCheckBox.isSelected();
            if (cliWhitelistArea != null) cliWhitelistArea.setEnabled(enabled && (enableUnrestrictedCliToolCheckBox == null || !enableUnrestrictedCliToolCheckBox.isSelected()));
            if (cliToolPromptArea != null) cliToolPromptArea.setEnabled(enabled);
            if (enableUnrestrictedCliToolCheckBox != null) enableUnrestrictedCliToolCheckBox.setEnabled(enabled);
            apiClient.setEnableCliTool(enabled);
        });
        panel.add(enableCliToolCheckBox, gbc);

        row++;

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        enableUnrestrictedCliToolCheckBox = new JCheckBox("允许无限制调用任意系统内置命令行工具", false);
        enableUnrestrictedCliToolCheckBox.setToolTipText("启用后将绕过白名单限制，AI 可直接调用系统内置命令及 PATH 中的任意命令，风险极高");
        enableUnrestrictedCliToolCheckBox.addActionListener(e -> {
            boolean unrestricted = enableUnrestrictedCliToolCheckBox.isSelected();
            if (cliWhitelistArea != null) cliWhitelistArea.setEnabled(enableCliToolCheckBox != null && enableCliToolCheckBox.isSelected() && !unrestricted);
            apiClient.setEnableUnrestrictedCliTool(unrestricted);
        });
        panel.add(enableUnrestrictedCliToolCheckBox, gbc);

        row++;

        // 白名单
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("工具白名单（每行一个）:"), gbc);

        cliWhitelistArea = new JTextArea(10, 60);
        cliWhitelistArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        cliWhitelistArea.setLineWrap(false);
        cliWhitelistArea.setToolTipText("每行一个可执行命令：可绝对/相对路径或环境变量命令；也可组合，如：C:\\\\venv\\\\Scripts\\\\python.exe D:\\\\sqlmap.py");
        JScrollPane whitelistScroll = new JScrollPane(cliWhitelistArea);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0;
        panel.add(whitelistScroll, gbc);

        row++;

        // 提示词
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("工具提示词（可选）:"), gbc);

        cliToolPromptArea = new JTextArea(8, 60);
        cliToolPromptArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        cliToolPromptArea.setLineWrap(true);
        cliToolPromptArea.setWrapStyleWord(true);
        cliToolPromptArea.setToolTipText("写给 AI 的额外约束/用法，例如：只能读取指定目录、先用 -h 查看参数、不要执行破坏性命令等");
        JScrollPane promptScroll = new JScrollPane(cliToolPromptArea);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0;
        panel.add(promptScroll, gbc);

        row++;

        // 说明
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JTextArea hint = new JTextArea(
                "说明:\n" +
                "• 该工具会暴露一个名为 run_cli 的 Tool；默认情况下，AI 只能执行你在白名单中列出的命令。\n" +
                "• 如果勾选“无限制调用”，将绕过白名单，允许 AI 直接执行系统内置命令和 PATH 中的任意命令。\n" +
                "• 白名单支持：绝对路径、相对路径、以及系统 PATH 中的命令；也支持组合命令行（例如：python D:\\\\sqlmap.py）。\n" +
                "• 为避免上下文过长，命令输出会被自动截断。\n" +
                "• 强烈建议：只加入只读/安全的工具，避免写入/破坏性命令。");
        hint.setEditable(false);
        hint.setOpaque(false);
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        panel.add(hint, gbc);

        // 初始禁用（等 applySettings 再打开）
        cliWhitelistArea.setEnabled(false);
        cliToolPromptArea.setEnabled(false);
        if (enableUnrestrictedCliToolCheckBox != null) enableUnrestrictedCliToolCheckBox.setEnabled(false);

        // 底部填充
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JLabel(), gbc);

        return panel;
    }
    
    private JPanel createSkillsTabPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        enableSkillsCheckBox = new JCheckBox("启用 Skills");
        enableSkillsCheckBox.addActionListener(e -> {
            boolean enabled = enableSkillsCheckBox.isSelected();
            apiClient.setEnableSkills(enabled);
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setEnableSkills(enabled);
            }
            skillsDirectoryField.setEnabled(enabled);
            if (skillsTable != null) skillsTable.setEnabled(enabled);
        });
        controlPanel.add(enableSkillsCheckBox);

        skillsDirectoryField = new JTextField(40);
        skillsDirectoryField.setEnabled(false);
        controlPanel.add(new JLabel("目录:"));
        controlPanel.add(skillsDirectoryField);

        refreshSkillsButton = new JButton("刷新");
        refreshSkillsButton.addActionListener(e -> refreshSkills());
        controlPanel.add(refreshSkillsButton);

        createExampleSkillButton = new JButton("创建示例技能");
        createExampleSkillButton.addActionListener(e -> createExampleSkill());
        controlPanel.add(createExampleSkillButton);

        panel.add(controlPanel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        String[] columnNames = {"技能名称", "描述", "启用", "工具数"};
        skillsTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 2;
            }
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 2 ? Boolean.class : String.class;
            }
        };
        skillsTable = new JTable(skillsTableModel);
        skillsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillsTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        skillsTable.getColumnModel().getColumn(1).setPreferredWidth(300);
        skillsTable.getColumnModel().getColumn(2).setPreferredWidth(50);
        skillsTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        skillsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSkillPreview();
            }
        });
        skillsTableModel.addTableModelListener(e -> {
            if (e.getType() == javax.swing.event.TableModelEvent.UPDATE && e.getColumn() == 2) {
                int row = e.getFirstRow();
                String name = (String) skillsTableModel.getValueAt(row, 0);
                boolean enabled = (Boolean) skillsTableModel.getValueAt(row, 2);
                if (skillManager != null) {
                    skillManager.setSkillEnabled(name, enabled);
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(skillsTable);
        splitPane.setTopComponent(tableScroll);

        skillPreviewPane = new JTextArea();
        skillPreviewPane.setEditable(false);
        skillPreviewPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        JScrollPane previewScroll = new JScrollPane(skillPreviewPane);
        previewScroll.setBorder(BorderFactory.createTitledBorder("技能预览"));
        splitPane.setBottomComponent(previewScroll);

        splitPane.setResizeWeight(0.6);
        splitPane.setDividerLocation(300);
        panel.add(splitPane, BorderLayout.CENTER);

        skillManager = new SkillManager();
        if (api != null) skillManager.setApi(api);

        return panel;
    }

    private void refreshSkills() {
        if (skillManager == null || skillsDirectoryField == null) return;
        String dir = skillsDirectoryField.getText().trim();
        if (dir.isEmpty()) return;

        skillManager.setSkillsDirectoryPath(dir);
        updateSkillsTable();

        apiClient.setSkillsDirectoryPath(dir);
        if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
            passiveScanManager.getApiClient().setSkillsDirectoryPath(dir);
        }
    }

    private void updateSkillsTable() {
        if (skillsTableModel == null || skillManager == null) return;
        skillsTableModel.setRowCount(0);
        for (Skill skill : skillManager.getAllSkills()) {
            skillsTableModel.addRow(new Object[]{
                    skill.getName(),
                    skill.getShortDescription(),
                    skill.isEnabled(),
                    skill.getToolCount()
            });
        }
    }

    private void updateSkillPreview() {
        if (skillPreviewPane == null || skillsTable == null || skillManager == null) return;
        int row = skillsTable.getSelectedRow();
        if (row < 0) {
            skillPreviewPane.setText("");
            return;
        }
        String name = (String) skillsTableModel.getValueAt(row, 0);
        Skill skill = skillManager.getSkill(name);
        if (skill != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("名称: ").append(skill.getName()).append("\n");
            sb.append("描述: ").append(skill.getDescription()).append("\n");
            sb.append("路径: ").append(skill.getFilePath() != null ? skill.getFilePath() : "N/A").append("\n\n");
            sb.append("--- 内容 ---\n\n");
            sb.append(skill.getContent() != null ? skill.getContent() : "(无内容)");
            skillPreviewPane.setText(sb.toString());
            skillPreviewPane.setCaretPosition(0);
        }
    }

    private void createExampleSkill() {
        if (skillsDirectoryField == null) return;
        String dir = skillsDirectoryField.getText().trim();
        if (dir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先设置 Skills 目录", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File skillDir = new File(dir, "example-scanner");
        if (skillDir.exists()) {
            JOptionPane.showMessageDialog(this, "示例技能目录已存在: " + skillDir.getAbsolutePath(), "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        skillDir.mkdirs();
        File skillMd = new File(skillDir, "SKILL.md");
        String skillContent = "---\n" +
                "name: example-scanner\n" +
                "description: 示例安全扫描技能 — 当需要对目标进行基础信息收集时使用\n" +
                "tools:\n" +
                "  - name: port_scan\n" +
                "    description: 使用 nmap 进行端口扫描\n" +
                "    command: \"nmap\"\n" +
                "    args: \"-sV -p {ports} {target}\"\n" +
                "    working_dir: \".\"\n" +
                "    timeout: 300\n" +
                "    parameters:\n" +
                "      - name: target\n" +
                "        type: string\n" +
                "        description: 目标IP或域名\n" +
                "        required: true\n" +
                "      - name: ports\n" +
                "        type: string\n" +
                "        description: 端口范围\n" +
                "        required: false\n" +
                "        default: \"1-1000\"\n" +
                "---\n" +
                "# Example Scanner\n\n" +
                "## 功能概述\n" +
                "这是一个示例技能，用于演示 Skills 系统的基本用法。\n\n" +
                "## 使用说明\n" +
                "1. 当用户请求对目标进行端口扫描时，使用 `port_scan` 工具\n" +
                "2. 分析扫描结果并报告开放端口\n\n" +
                "## 注意事项\n" +
                "- 请确保 nmap 已安装并在 PATH 中\n" +
                "- 扫描结果可能因防火墙规则而不完整\n";
        try {
            java.nio.file.Files.writeString(skillMd.toPath(), skillContent);
            JOptionPane.showMessageDialog(this, "示例技能已创建: " + skillMd.getAbsolutePath(), "成功", JOptionPane.INFORMATION_MESSAGE);
            refreshSkills();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "创建失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

        private JPanel createConfigSubTab_Basic() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        gbc.gridx = 0; gbc.gridy = row;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("API 配置档案:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        apiProfileComboBox = new JComboBox<>();
        apiProfileComboBox.setToolTipText("保存多套 API Provider / URL / Key / Model，便于一键切换");
        panel.add(apiProfileComboBox, gbc);
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JPanel profileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton applyProfileButton = new JButton("应用");
        JButton saveProfileButton = new JButton("保存当前");
        JButton deleteProfileButton = new JButton("删除");
        applyProfileButton.addActionListener(e -> applySelectedApiProfile());
        saveProfileButton.addActionListener(e -> saveCurrentApiProfile());
        deleteProfileButton.addActionListener(e -> deleteSelectedApiProfile());
        profileButtons.add(applyProfileButton);
        profileButtons.add(saveProfileButton);
        profileButtons.add(deleteProfileButton);
        panel.add(profileButtons, gbc);
        
        // API 提供者
        row++;
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
            } else if ("Anthropic兼容".equals(sel)) {
                if (apiUrlField.getText().contains("dashscope") || apiUrlField.getText().contains("openai.com") || apiUrlField.getText().isEmpty())
                    apiUrlField.setText("https://api.anthropic.com");
            } else {
                if (apiUrlField.getText().contains("dashscope") || apiUrlField.getText().contains("anthropic.com") || apiUrlField.getText().isEmpty())
                    apiUrlField.setText("https://api.openai.com/v1");
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
        
        // max_tokens
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("max_tokens:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        maxTokensField = new JTextField("", 30);
        maxTokensField.setToolTipText("显式指定上下文预算；留空时将自动读取自定义参数、模型元数据或默认规则");
        panel.add(maxTokensField, gbc);

        // Token 预算（扫描周期）
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Token 预算:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        tokenBudgetField = new JTextField("", 30);
        tokenBudgetField.setToolTipText("扫描周期累计输入 Token（含缓存）预算上限；0 或留空表示不限制。用尽后自动跳过新的分析调用");
        panel.add(tokenBudgetField, gbc);

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
        workplaceDirectoryField.setToolTipText("统一工作目录，自动派生：rag / python-workdir / .cache");
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

    private void refreshApiProfileCombo() {
        if (apiProfileComboBox == null) return;
        Object selected = apiProfileComboBox.getSelectedItem();
        apiProfileComboBox.removeAllItems();
        for (PluginSettings.ApiProfile profile : apiProfiles) {
            if (profile != null && !profile.getName().isBlank()) {
                apiProfileComboBox.addItem(profile.getName());
            }
        }
        if (selected != null) {
            apiProfileComboBox.setSelectedItem(selected);
        }
    }

    private void saveCurrentApiProfile() {
        String defaultName = ((String) apiProviderComboBox.getSelectedItem()) + " / " + modelField.getText().trim();
        String name = JOptionPane.showInputDialog(this, "配置档案名称:", defaultName);
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();

        PluginSettings.ApiProfile profile = new PluginSettings.ApiProfile(
                name,
                (String) apiProviderComboBox.getSelectedItem(),
                apiUrlField.getText().trim(),
                getEffectiveApiKeyFromField(),
                modelField.getText().trim(),
                customParametersField.getText().trim()
        );

        int existing = findApiProfileIndex(name);
        if (existing >= 0) {
            apiProfiles.set(existing, profile);
        } else {
            apiProfiles.add(profile);
        }
        refreshApiProfileCombo();
        apiProfileComboBox.setSelectedItem(name);
        api.logging().logToOutput("API 配置档案已保存: " + name);
        saveSettings();
    }

    private void applySelectedApiProfile() {
        PluginSettings.ApiProfile profile = getSelectedApiProfile();
        if (profile == null) return;

        apiProviderComboBox.setSelectedItem(profile.getApiProvider());
        apiUrlField.setText(profile.getApiUrl());
        setApiKeySecretAndMask(profile.getApiKey());
        modelField.setText(profile.getModel());
        customParametersField.setText(profile.getCustomParameters());

        apiClient.setApiProvider(profile.getApiProvider());
        apiClient.setApiUrl(profile.getApiUrl());
        apiClient.setApiKey(profile.getApiKey());
        apiClient.setModel(profile.getModel());
        apiClient.setCustomParameters(profile.getCustomParameters());
        syncApiConfigToPassiveScan();
        api.logging().logToOutput("已应用 API 配置档案: " + profile.getName());
    }

    private void deleteSelectedApiProfile() {
        PluginSettings.ApiProfile profile = getSelectedApiProfile();
        if (profile == null) return;
        int result = JOptionPane.showConfirmDialog(this,
                "确定删除 API 配置档案？\n" + profile.getName(),
                "确认删除",
                JOptionPane.YES_NO_OPTION);
        if (result != JOptionPane.YES_OPTION) return;
        apiProfiles.removeIf(p -> p != null && profile.getName().equals(p.getName()));
        refreshApiProfileCombo();
        api.logging().logToOutput("API 配置档案已删除: " + profile.getName());
    }

    private PluginSettings.ApiProfile getSelectedApiProfile() {
        if (apiProfileComboBox == null || apiProfileComboBox.getSelectedItem() == null) return null;
        String name = apiProfileComboBox.getSelectedItem().toString();
        return apiProfiles.stream()
                .filter(p -> p != null && name.equals(p.getName()))
                .findFirst()
                .orElse(null);
    }

    private int findApiProfileIndex(String name) {
        for (int i = 0; i < apiProfiles.size(); i++) {
            PluginSettings.ApiProfile profile = apiProfiles.get(i);
            if (profile != null && name.equals(profile.getName())) {
                return i;
            }
        }
        return -1;
    }

    private String getEffectiveApiKeyFromField() {
        if (apiKeyField == null) return currentApiKeySecret != null ? currentApiKeySecret : "";
        String displayed = apiKeyField.getText() != null ? apiKeyField.getText().trim() : "";
        String masked = maskApiKey(currentApiKeySecret);
        if (!displayed.isEmpty() && !displayed.equals(masked)) {
            currentApiKeySecret = displayed;
            return displayed;
        }
        return currentApiKeySecret != null ? currentApiKeySecret : "";
    }

    private void setApiKeySecretAndMask(String apiKey) {
        currentApiKeySecret = apiKey != null ? apiKey.trim() : "";
        if (apiKeyField != null) {
            apiKeyField.setText(maskApiKey(currentApiKeySecret));
        }
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        String key = apiKey.trim();
        if (key.length() <= 8) return "********";
        return key.substring(0, Math.min(6, key.length()))
                + "****"
                + key.substring(Math.max(6, key.length() - 4));
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
            if (burpMcpAuthorizationField != null) {
                burpMcpAuthorizationField.setEnabled(enabled);
            }
            apiClient.setEnableMcp(enabled);
            if (enabled && !BurpMcpUrlField.getText().trim().isEmpty())
                apiClient.setBurpMcpUrl(BurpMcpUrlField.getText().trim());
            if (enabled) {
                apiClient.setBurpMcpAuthorization(burpMcpAuthorizationField.getText().trim());
            }
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

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("  Authorization:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        burpMcpAuthorizationField = new JTextField("", 30);
        burpMcpAuthorizationField.setEnabled(false);
        burpMcpAuthorizationField.setToolTipText("可选，示例：Bearer your-token 或其他网关要求的 Authorization 值");
        burpMcpAuthorizationField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e) { sync(); }
            @Override public void changedUpdate(DocumentEvent e) { sync(); }
            private void sync() {
                if (enableMcpCheckBox.isSelected()) {
                    apiClient.setBurpMcpAuthorization(burpMcpAuthorizationField.getText().trim());
                }
            }
        });
        panel.add(burpMcpAuthorizationField, gbc);
        
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
        
        // 底部填充
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JLabel(), gbc);

        return panel;
    }

    /**
     * 创建 MCP 配置标签页（独立子标签页）
     * 包含：总开关、自定义 MCP 服务器 JSON 配置、实时验证、错误明细。
     */
    private JPanel createConfigSubTab_Mcp() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // 总开关
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        enableCustomMcpCheckBox = new JCheckBox("启用自定义 MCP 服务器", false);
        enableCustomMcpCheckBox.setToolTipText("控制下方 JSON 配置是否生效；关闭后已保存的配置仍会保留，但运行时不会加载。");
        enableCustomMcpCheckBox.addActionListener(e -> {
            boolean enabled = enableCustomMcpCheckBox.isSelected();
            if (customMcpConfigArea != null) customMcpConfigArea.setEnabled(enabled);
            validateCustomMcpConfig();
        });
        panel.add(enableCustomMcpCheckBox, gbc);

        // 自定义 MCP 服务器配置
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 0;
        JLabel customMcpLabel = new JLabel("自定义 MCP 服务器配置（JSON 数组）：");
        customMcpLabel.setFont(customMcpLabel.getFont().deriveFont(Font.BOLD));
        panel.add(customMcpLabel, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
        customMcpConfigArea = new JTextArea(12, 70);
        customMcpConfigArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        customMcpConfigArea.setLineWrap(true);
        customMcpConfigArea.setWrapStyleWord(true);
        customMcpConfigArea.setEnabled(false);
        customMcpConfigArea.setToolTipText("""
                简版数组格式，每个对象支持：
                name, enabled, type(sse|streamableHttp|stdio|websocket),
                url, command(数组), env(对象), toolWhitelist(数组)
                示例会显示默认配置。""");
        customMcpConfigArea.setText(com.ai.analyzer.agent.mcpclient.CustomMcpConfigParser.defaultConfigJson());
        customMcpConfigArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { validateCustomMcpConfig(); }
            @Override public void removeUpdate(DocumentEvent e) { validateCustomMcpConfig(); }
            @Override public void changedUpdate(DocumentEvent e) { validateCustomMcpConfig(); }
        });
        JScrollPane customMcpScroll = new JScrollPane(customMcpConfigArea);
        panel.add(customMcpScroll, gbc);

        // 状态 + 统计
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 0;
        customMcpStatusLabel = new JLabel("状态：未验证");
        customMcpStatusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        customMcpStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(customMcpStatusLabel, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 0;
        customMcpSummaryLabel = new JLabel("");
        customMcpSummaryLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        customMcpSummaryLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(customMcpSummaryLabel, gbc);

        // 按钮
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.EAST;
        JPanel customMcpButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton validateCustomMcpButton = new JButton("验证配置");
        JButton restoreCustomMcpButton = new JButton("恢复默认");
        validateCustomMcpButton.addActionListener(e -> validateCustomMcpConfig());
        restoreCustomMcpButton.addActionListener(e -> customMcpConfigArea.setText(
                com.ai.analyzer.agent.mcpclient.CustomMcpConfigParser.defaultConfigJson()));
        customMcpButtonPanel.add(validateCustomMcpButton);
        customMcpButtonPanel.add(restoreCustomMcpButton);
        panel.add(customMcpButtonPanel, gbc);

        // 说明
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 0;
        JTextArea customMcpHint = new JTextArea(
                "说明:\n" +
                "• 自定义 MCP 配置与 Burp/RAG/Chrome MCP 开关互不冲突，可同时启用。\n" +
                "• 通过自定义配置可接入其他 MCP Server（如 Fetch、Browser、PDF 解析等）。\n" +
                "• 每项必须包含 name 和 type；url 用于 streamableHttp / sse / websocket，command 用于 stdio。\n" +
                "• toolWhitelist 留空表示不限制工具；填写后仅暴露指定工具给 AI。\n" +
                "• 关闭「启用自定义 MCP」后，配置将保留但不会生效。");
        customMcpHint.setEditable(false);
        customMcpHint.setOpaque(false);
        customMcpHint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        customMcpHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        customMcpHint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        panel.add(customMcpHint, gbc);

        return panel;
    }

    private int countEnabledCustomMcpConfigs() {
        return com.ai.analyzer.util.McpConfigValidator.countEnabledCustomMcpConfigs(
                customMcpConfigArea != null ? customMcpConfigArea.getText() : "");
    }

    private void validateCustomMcpConfig() {
        boolean masterSwitchOn = enableCustomMcpCheckBox != null && enableCustomMcpCheckBox.isSelected();
        com.ai.analyzer.util.McpConfigValidator.McpConfigValidationResult result =
                com.ai.analyzer.util.McpConfigValidator.validate(
                        customMcpConfigArea != null ? customMcpConfigArea.getText() : "",
                        masterSwitchOn,
                        UIManager.getColor("Label.disabledForeground"));
        if (customMcpStatusLabel != null) {
            customMcpStatusLabel.setText(result.getStatusText());
            customMcpStatusLabel.setForeground(result.getStatusColor());
        }
        if (customMcpSummaryLabel != null) {
            customMcpSummaryLabel.setText(result.getSummaryText());
            customMcpSummaryLabel.setForeground(result.isSuccess() ? new Color(34, 139, 34) : Color.RED);
        }
    }

    private boolean confirmSaveWithInvalidMcpConfig() {
        boolean masterSwitchOn = enableCustomMcpCheckBox != null && enableCustomMcpCheckBox.isSelected();
        com.ai.analyzer.util.McpConfigValidator.McpConfigValidationResult result =
                com.ai.analyzer.util.McpConfigValidator.validate(
                        customMcpConfigArea != null ? customMcpConfigArea.getText() : "",
                        masterSwitchOn,
                        UIManager.getColor("Label.disabledForeground"));
        if (result.isSuccess()) return true;
        int choice = JOptionPane.showConfirmDialog(this,
                "自定义 MCP 配置校验未通过，保存后该配置不会生效。\n\n"
                        + result.getSummaryText() + "\n\n是否仍要继续保存？",
                " MCP 配置校验失败",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }
    
    private JPanel createConfigSubTab_Search() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // 搜索总开关（统一管理入口）
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        enableSearchCheckBox = new JCheckBox("启用网络搜索", false);
        enableSearchCheckBox.setToolTipText("统一控制是否允许 AI 使用联网搜索能力");
        enableSearchCheckBox.addActionListener(e -> apiClient.setEnableSearch(enableSearchCheckBox.isSelected()));
        panel.add(enableSearchCheckBox, gbc);

        row++;

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
        tavilyBaseUrlField.setText("https://api.tavily.com/search");
        tavilyBaseUrlField.setToolTipText("Tavily API 代理地址 (留空使用默认 https://api.tavily.com/search)");
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
                "  - Tavily 提供免费 API Key (每月1000次请求)，默认接口: https://api.tavily.com/search，注册地址: https://tavily.com\n" +
                "• Google Custom Search: 通过 Google Programmable Search Engine 实现\n" +
                "  - 需要 Google Cloud API Key + 自定义搜索引擎 ID (CSE ID)\n" +
                "  - 免费额度每天100次请求，创建地址: https://programmablesearchengine.google.com\n" +
                "• DuckDuckGo: 免费搜索引擎，无需 API Key，通过 HTML 解析获取结果\n" +
                "  - 无请求次数限制，但速度可能较慢，且可能受到反爬虫限制\n" +
                "• 本页的「启用网络搜索」统一控制是否启用搜索，此处同时配置搜索的实现方式");
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
        activeSystemPromptArea = new JTextArea(com.ai.analyzer.core.SystemPromptBuilder.getDefaultBasePrompt());
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
        passiveSystemPromptArea = new JTextArea(com.ai.analyzer.scan.pscan.SystemPromptBuilder.getDefaultBasePrompt());
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
            activeSystemPromptArea.setText(com.ai.analyzer.core.SystemPromptBuilder.getDefaultBasePrompt());
            passiveSystemPromptArea.setText(com.ai.analyzer.scan.pscan.SystemPromptBuilder.getDefaultBasePrompt());
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
        passiveScanDomainBlacklistArea = new JTextArea(PassiveScanTask.getDefaultDomainBlacklistText());
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
        JButton resetFiltersButton = new JButton("恢复默认过滤配置");
        resetFiltersButton.setToolTipText("扩展名与域名黑名单均恢复为内置默认值");
        resetFiltersButton.addActionListener(e -> {
            passiveScanSkipExtensionsArea.setText(PassiveScanTask.getDefaultSkipExtensionsText());
            passiveScanDomainBlacklistArea.setText(PassiveScanTask.getDefaultDomainBlacklistText());
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
     * 根据 Workplace 同步派生目录（rag/python-workdir）到 UI 与客户端。
     */
    private void applyWorkplaceToDerivedPaths(boolean pushToClients, boolean createDirs) {
        if (workplaceDirectoryField == null) return;
        String workplace = workplaceDirectoryField.getText() != null ? workplaceDirectoryField.getText().trim() : "";
        if (workplace.isEmpty()) return;

        File workplaceDir = new File(workplace);
        File ragDir = new File(workplaceDir, "rag");
        File pythonDir = new File(workplaceDir, "python-workdir");
        File cacheDir = new File(workplaceDir, ".cache");
        File skillsDir = new File(workplaceDir, "skills");

        if (createDirs) {
            if (!workplaceDir.exists()) workplaceDir.mkdirs();
            if (!ragDir.exists()) ragDir.mkdirs();
            if (!pythonDir.exists()) pythonDir.mkdirs();
            if (!cacheDir.exists()) cacheDir.mkdirs();
            if (!skillsDir.exists()) skillsDir.mkdirs();
        }

        if (ragMcpDocumentsPathField != null) {
            ragMcpDocumentsPathField.setText(ragDir.getAbsolutePath());
        }
        if (skillsDirectoryField != null) {
            skillsDirectoryField.setText(skillsDir.getAbsolutePath());
        }

        if (pushToClients) {
            apiClient.setWorkplaceDirectoryPath(workplaceDir.getAbsolutePath());
            apiClient.setRagMcpDocumentsPath(ragDir.getAbsolutePath());
            apiClient.setSkillsDirectoryPath(skillsDir.getAbsolutePath());
            com.ai.analyzer.util.MessageCollectionStore.setWorkplaceDirectory(workplaceDir.getAbsolutePath());
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setWorkplaceDirectoryPath(workplaceDir.getAbsolutePath());
                passiveScanManager.getApiClient().setRagMcpDocumentsPath(ragDir.getAbsolutePath());
                passiveScanManager.getApiClient().setSkillsDirectoryPath(skillsDir.getAbsolutePath());
            }
        }
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
        this.resultScrollPane = resultScrollPane;
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

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // 操作按钮
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
            resultTextPane = activeAnalysisPanel.getResultPane();
            userPromptArea = activeAnalysisPanel.getPromptArea();
            activeAnalysisPanel.setActiveMode(true, activeAnalysisPanel.getResultPane(), activeAnalysisPanel.getPromptArea());
        } else {
            resultTextPane = passiveModeResultTextPane;
            userPromptArea = passiveModePromptArea;
            activeAnalysisPanel.setActiveMode(false, passiveModeResultTextPane, passiveModePromptArea);
        }
        revalidate();
        repaint();
    }

    /** 分析前同步 API 客户端配置（由 ActiveAnalysisPanel 调用） */
    private void updateApiClientConfigForAnalysis() {
        apiClient.setApiProvider((String) apiProviderComboBox.getSelectedItem());
        apiClient.setApiUrl(apiUrlField.getText().trim());
        apiClient.setApiKey(getEffectiveApiKeyFromField());
        apiClient.setModel(modelField.getText().trim());
        apiClient.setCustomParameters(customParametersField.getText().trim());
        apiClient.setMaxTokens(maxTokensField.getText().trim());
        com.ai.analyzer.util.TokenUsageTracker.instance().setBudget(parseTokenBudget(tokenBudgetField.getText()));
        apiClient.setEnableThinking(false);
        apiClient.setEnableSearch(enableSearchCheckBox.isSelected());
    }

    /** 分析状态变化回调：同步底部"开始分析/停止"按钮 */
    private void onAnalysisStateChanged(boolean analyzing) {
        if (analyzeButton != null) {
            analyzeButton.setEnabled(!analyzing);
            analyzeButton.setText(analyzing ? "分析中..." : "开始分析");
        }
        if (stopButton != null) {
            stopButton.setEnabled(analyzing);
        }
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
        if (activeAnalysisPanel != null) {
            activeAnalysisPanel.performAnalysis();
        }
    }
    private void stopAnalysis() {
        if (activeAnalysisPanel != null) {
            activeAnalysisPanel.stopAnalysis();
        }
    }
    private void clearResults() {
        if (activeAnalysisPanel != null) {
            activeAnalysisPanel.clearResults();
        }
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
            String effectiveApiKey = getEffectiveApiKeyFromField();
            PluginSettings settings = new PluginSettings(
                apiUrlField.getText().trim(),
                effectiveApiKey,
                modelField.getText().trim(),
                userPromptArea.getText().trim(),
                false,
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
            // 设置上下文预算与自定义参数
            settings.setMaxTokens(maxTokensField.getText().trim());
            settings.setTokenBudgetTokens(parseTokenBudget(tokenBudgetField.getText()));
            settings.setCustomParameters(customParametersField.getText().trim());
            settings.setApiProfiles(apiProfiles);
            settings.setBurpMcpAuthorization(burpMcpAuthorizationField != null ? burpMcpAuthorizationField.getText().trim() : "");
            // 设置直接查找知识库选项
            settings.setEnableFileSystemAccess(enableFileSystemAccessCheckBox.isSelected());
            
            // 设置前置扫描器选项
            settings.setEnablePreScanFilter(enablePreScanCheckbox != null && enablePreScanCheckbox.isSelected());
            
            // 设置 Python 脚本执行选项
            settings.setEnablePythonScript(enablePythonScriptCheckbox != null && enablePythonScriptCheckbox.isSelected());

            // CLI 工具选项
            settings.setEnableCliTool(enableCliToolCheckBox != null && enableCliToolCheckBox.isSelected());
            settings.setEnableUnrestrictedCliTool(enableUnrestrictedCliToolCheckBox != null && enableUnrestrictedCliToolCheckBox.isSelected());
            settings.setCliWhitelist(cliWhitelistArea != null ? cliWhitelistArea.getText() : "");
            settings.setCliToolPrompt(cliToolPromptArea != null ? cliToolPromptArea.getText() : "");

            // 自定义 MCP 配置
            settings.setEnableCustomMcp(enableCustomMcpCheckBox != null && enableCustomMcpCheckBox.isSelected());
            settings.setCustomMcpConfigJson(customMcpConfigArea != null ? customMcpConfigArea.getText() : "");
            apiClient.setCustomMcpConfigJson(settings.isEnableCustomMcp() ? settings.getCustomMcpConfigJson() : "");
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setCustomMcpConfigJson(settings.isEnableCustomMcp() ? settings.getCustomMcpConfigJson() : "");
            }
            
            settings.setWorkplaceDirectoryPath(workplaceDirectoryField != null ? workplaceDirectoryField.getText().trim() : "");

            // Plan Mode 配置
            if (activeAnalysisPanel != null) {
                settings.setEnablePlanMode(activeAnalysisPanel.getPlanModeSelected());
            }
            // Skills 配置
            if (enableSkillsCheckBox != null) {
                settings.setEnableSkills(enableSkillsCheckBox.isSelected());
            }
            if (skillsDirectoryField != null) {
                settings.setSkillsDirectoryPath(skillsDirectoryField.getText().trim());
            }
            if (skillManager != null) {
                settings.setEnabledSkillNames(new java.util.ArrayList<>(skillManager.getEnabledSkillNames()));
            }

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
                    activeText.strip().equals(com.ai.analyzer.core.SystemPromptBuilder.getDefaultBasePrompt().strip()) ? null : activeText);
            }
            if (passiveSystemPromptArea != null) {
                String passiveText = passiveSystemPromptArea.getText();
                settings.setCustomPassiveSystemPrompt(
                    passiveText.strip().equals(com.ai.analyzer.scan.pscan.SystemPromptBuilder.getDefaultBasePrompt().strip()) ? null : passiveText);
            }
            // 被动扫描过滤（与默认值相同时存空串）
            if (passiveScanSkipExtensionsArea != null) {
                String extText = passiveScanSkipExtensionsArea.getText();
                settings.setPassiveScanSkipExtensions(
                    extText.strip().equals(com.ai.analyzer.scan.pscan.PassiveScanTask.getDefaultSkipExtensionsText().strip()) ? "" : extText);
            }
            if (passiveScanDomainBlacklistArea != null) {
                String blacklist = passiveScanDomainBlacklistArea.getText();
                settings.setPassiveScanDomainBlacklist(
                    blacklist.isBlank() ? com.ai.analyzer.scan.pscan.PassiveScanTask.getDefaultDomainBlacklistText() : blacklist);
            }
            
            // 应用系统提示词到 API 客户端
            apiClient.setCustomSystemPrompt(settings.getCustomActiveSystemPrompt());
            apiClient.setEnablePlanMode(settings.isEnablePlanMode());
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setCustomSystemPrompt(settings.getCustomPassiveSystemPrompt());
                // Skills 配置同步到被动扫描客户端
                passiveScanManager.getApiClient().setEnableSkills(settings.isEnableSkills());
                passiveScanManager.getApiClient().setSkillsDirectoryPath(settings.resolveSkillsDirectoryPath());
            }
            // 应用被动扫描过滤规则
            applyPassiveScanFilters();

            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("ai_analyzer_settings.dat"));
            oos.writeObject(settings);
            oos.close();
            setApiKeySecretAndMask(effectiveApiKey);

            api.logging().logToOutput("设置已保存");
            flashStatusMessage("设置已保存 ✔");
        } catch (Exception e) {
            flashStatusMessage("保存设置失败: " + e.getMessage());
            api.logging().logToError("保存设置失败: " + e.getMessage());
        }
    }

    /**
     * 在被动扫描状态标签上短暂显示一条提示（3 秒后恢复"就绪"）。
     * 用于保存设置等操作的轻量反馈，避免弹窗打断。
     */
    private void flashStatusMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            passiveScanStatusLabel.setText(message);
            javax.swing.Timer timer = new javax.swing.Timer(3000, ev -> {
                ((javax.swing.Timer) ev.getSource()).stop();
                if (passiveScanStatusLabel.getText().equals(message)) {
                    passiveScanStatusLabel.setText("就绪");
                }
            });
            timer.setRepeats(false);
            timer.start();
        });
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
                settings = PluginSettings.loadCompat(localSettingsFile);
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
                settings = PluginSettings.loadCompat(userSettingsFile);
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
        apiUrlField.setText(settings.getApiUrl());
        setApiKeySecretAndMask(settings.getApiKey());
        modelField.setText(settings.getModel());
        maxTokensField.setText(settings.getMaxTokens());
        if (tokenBudgetField != null) {
            tokenBudgetField.setText(settings.getTokenBudgetTokens() > 0
                    ? String.valueOf(settings.getTokenBudgetTokens()) : "");
        }
        customParametersField.setText(settings.getCustomParameters());
        apiProfiles.clear();
        apiProfiles.addAll(settings.getApiProfiles());
        refreshApiProfileCombo();
        if (workplaceDirectoryField != null) {
            workplaceDirectoryField.setText(settings.getWorkplaceDirectoryPath());
        }
        if (activeAnalysisPanel != null) {
            activeAnalysisPanel.setPlanModeSelected(settings.isEnablePlanMode());
        }
        setPromptTextForAllModes(settings.getUserPrompt());
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

        // CLI 工具配置（第三个标签页）
        if (enableCliToolCheckBox != null) {
            enableCliToolCheckBox.setSelected(settings.isEnableCliTool());
        }
        if (enableUnrestrictedCliToolCheckBox != null) {
            enableUnrestrictedCliToolCheckBox.setSelected(settings.isEnableUnrestrictedCliTool());
            enableUnrestrictedCliToolCheckBox.setEnabled(settings.isEnableCliTool());
        }
        if (cliWhitelistArea != null) {
            cliWhitelistArea.setText(settings.getCliWhitelist());
            cliWhitelistArea.setEnabled(settings.isEnableCliTool() && !settings.isEnableUnrestrictedCliTool());
        }
        if (cliToolPromptArea != null) {
            cliToolPromptArea.setText(settings.getCliToolPrompt());
            cliToolPromptArea.setEnabled(settings.isEnableCliTool());
        }

        // Burp MCP 配置
        enableMcpCheckBox.setSelected(settings.isEnableMcp());
        BurpMcpUrlField.setText(settings.getMcpUrl());
        BurpMcpUrlField.setEnabled(settings.isEnableMcp());
        if (burpMcpAuthorizationField != null) {
            burpMcpAuthorizationField.setText(settings.getBurpMcpAuthorization());
            burpMcpAuthorizationField.setEnabled(settings.isEnableMcp());
        }
        
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

        // 自定义 MCP 配置
        boolean hasSavedCustomMcp = false;
        if (customMcpConfigArea != null) {
            String customMcp = settings.getCustomMcpConfigJson();
            hasSavedCustomMcp = customMcp != null && !customMcp.trim().isEmpty();
            if (!hasSavedCustomMcp) {
                customMcpConfigArea.setText(com.ai.analyzer.agent.mcpclient.CustomMcpConfigParser.defaultConfigJson());
            } else {
                customMcpConfigArea.setText(customMcp);
            }
        }
        if (enableCustomMcpCheckBox != null) {
            boolean customMcpEnabled = settings.isEnableCustomMcp() || hasSavedCustomMcp;
            enableCustomMcpCheckBox.setSelected(customMcpEnabled && hasSavedCustomMcp);
            if (customMcpConfigArea != null) {
                customMcpConfigArea.setEnabled(enableCustomMcpCheckBox.isSelected());
            }
            validateCustomMcpConfig();
        }
        
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
        apiClient.setMaxTokens(settings.getMaxTokens());
        apiClient.setCustomParameters(settings.getCustomParameters());
        apiClient.setEnableThinking(false);
        apiClient.setEnableSearch(settings.isEnableSearch());
        apiClient.setSearchMode(settings.getSearchMode());
        apiClient.setTavilyApiKey(settings.getTavilyApiKey());
        apiClient.setTavilyBaseUrl(settings.getTavilyBaseUrl());
        apiClient.setGoogleSearchApiKey(settings.getGoogleSearchApiKey());
        apiClient.setGoogleSearchCsi(settings.getGoogleSearchCsi());
        apiClient.setEnableMcp(settings.isEnableMcp());
        apiClient.setBurpMcpUrl(settings.getMcpUrl());
        apiClient.setBurpMcpAuthorization(settings.getBurpMcpAuthorization());
        apiClient.setEnableRagMcp(settings.isEnableRagMcp());
        apiClient.setRagMcpUrl(settings.getRagMcpUrl());
        apiClient.setRagMcpDocumentsPath(settings.getRagMcpDocumentsPath());
        apiClient.setEnableChromeMcp(settings.isEnableChromeMcp());
        apiClient.setChromeMcpUrl(settings.getChromeMcpUrl());
        apiClient.setCustomMcpConfigJson(settings.isEnableCustomMcp() ? settings.getCustomMcpConfigJson() : "");
        apiClient.setEnableFileSystemAccess(settings.isEnableFileSystemAccess());
        // apiClient.setEnableRag(settings.isEnableRag()); // 默认 RAG 暂时禁用
        // apiClient.setRagDocumentsPath(settings.getRagDocumentsPath()); // 默认 RAG 暂时禁用
        // apiClient.ensureRagInitialized(); // 默认 RAG 暂时禁用

        apiClient.setWorkplaceDirectoryPath(settings.getWorkplaceDirectoryPath());
        applyWorkplaceToDerivedPaths(true, true);
        // apiClient.setRagDocumentsPath(settings.getRagDocumentsPath()); // 默认 RAG 暂时禁用
        // apiClient.ensureRagInitialized(); // 默认 RAG 暂时禁用
        
        apiClient.setWorkplaceDirectoryPath(settings.getWorkplaceDirectoryPath());
        
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

        // Skills 配置
        if (enableSkillsCheckBox != null) {
            enableSkillsCheckBox.setSelected(settings.isEnableSkills());
            apiClient.setEnableSkills(settings.isEnableSkills());
        }
        if (skillsDirectoryField != null) {
            settings.getSkillsDirectoryPath(); // 触发 resolveUnderWorkplace
            // 如果 workplace 设置了，skills 目录会自动推导
            if (settings.hasWorkplaceDirectory()) {
                skillsDirectoryField.setText(settings.resolveSkillsDirectoryPath());
            } else if (!settings.getSkillsDirectoryPath().isEmpty()) {
                skillsDirectoryField.setText(settings.getSkillsDirectoryPath());
            }
        }
        if (skillManager != null) {
            skillManager.setSkillsDirectoryPath(settings.resolveSkillsDirectoryPath());
            skillManager.setEnabledSkillNames(new java.util.HashSet<>(settings.getEnabledSkillNames()));
        }
        apiClient.setSkillsDirectoryPath(settings.resolveSkillsDirectoryPath());

        // CLI 工具配置
        if (enableCliToolCheckBox != null) {
            enableCliToolCheckBox.setSelected(settings.isEnableCliTool());
            apiClient.setEnableCliTool(settings.isEnableCliTool());
        }
        if (enableUnrestrictedCliToolCheckBox != null) {
            enableUnrestrictedCliToolCheckBox.setSelected(settings.isEnableUnrestrictedCliTool());
            enableUnrestrictedCliToolCheckBox.setEnabled(settings.isEnableCliTool());
            apiClient.setEnableUnrestrictedCliTool(settings.isEnableUnrestrictedCliTool());
        }
        if (cliWhitelistArea != null) {
            cliWhitelistArea.setText(settings.getCliWhitelist());
            cliWhitelistArea.setEnabled(settings.isEnableCliTool() && !settings.isEnableUnrestrictedCliTool());
            apiClient.setCliWhitelist(settings.getCliWhitelist());
        }
        if (cliToolPromptArea != null) {
            cliToolPromptArea.setText(settings.getCliToolPrompt());
            cliToolPromptArea.setEnabled(settings.isEnableCliTool());
            apiClient.setCliToolPrompt(settings.getCliToolPrompt());
        }
        
        // 自定义系统提示词
        if (activeSystemPromptArea != null) activeSystemPromptArea.setText(settings.getCustomActiveSystemPrompt());
        if (passiveSystemPromptArea != null) passiveSystemPromptArea.setText(settings.getCustomPassiveSystemPrompt());
        apiClient.setCustomSystemPrompt(settings.getCustomActiveSystemPrompt());
        if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
            passiveScanManager.getApiClient().setCustomSystemPrompt(settings.getCustomPassiveSystemPrompt());
            // Skills 配置同步到被动扫描客户端
            passiveScanManager.getApiClient().setEnableSkills(settings.isEnableSkills());
            passiveScanManager.getApiClient().setSkillsDirectoryPath(settings.resolveSkillsDirectoryPath());
        }

        // 被动扫描过滤配置
        if (passiveScanSkipExtensionsArea != null) passiveScanSkipExtensionsArea.setText(settings.getPassiveScanSkipExtensions());
        if (passiveScanDomainBlacklistArea != null) {
            String savedBlacklist = settings.getPassiveScanDomainBlacklist();
            passiveScanDomainBlacklistArea.setText(
                savedBlacklist.isBlank()
                    ? com.ai.analyzer.scan.pscan.PassiveScanTask.getDefaultDomainBlacklistText()
                    : savedBlacklist);
        }
        applyPassiveScanFilters();
    }

    private void setPromptTextForAllModes(String text) {
        String prompt = text != null ? text : "";
        if (passiveModePromptArea != null) passiveModePromptArea.setText(prompt);
        // 主动模式输入框不设置默认文本（聊天式交互，每次提交后清空）
    }

    private boolean settingsEnableCustomMcp() {
        return enableCustomMcpCheckBox != null && enableCustomMcpCheckBox.isSelected();
    }
    
    /**
     * 手动加载设置（用户点击"加载设置"按钮时调用）
     */
    private void loadSettings() {
        try {
            PluginSettings settings = PluginSettings.loadCompat(new java.io.File("ai_analyzer_settings.dat"));

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
                    // 立即开始分析：右键发送到 AI 分析时应直接触发默认提示词分析，避免用户还需手动点击
                    SwingUtilities.invokeLater(this::performAnalysis);
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
        return getEffectiveApiKeyFromField();
    }
    
    public String getModel() {
        return modelField.getText().trim();
    }
    
    public boolean isEnableSearch() {
        return enableSearchCheckBox != null && enableSearchCheckBox.isSelected();
    }

    /**
     * 获取工作区目录（侧栏同步记忆/技能时使用）
     */
    public String getWorkplaceDirectoryPath() {
        return workplaceDirectoryField != null ? workplaceDirectoryField.getText().trim() : "";
    }

    public boolean isEnableSkills() {
        return enableSkillsCheckBox != null && enableSkillsCheckBox.isSelected();
    }

    public String getSkillsDirectoryPath() {
        return skillsDirectoryField != null ? skillsDirectoryField.getText().trim() : "";
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
