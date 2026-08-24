package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import com.ai.analyzer.core.AgentApiClient;
import com.ai.analyzer.core.RequestData;
import com.ai.analyzer.scan.pscan.PassiveScanApiClient;
import com.ai.analyzer.scan.pscan.PassiveScanManager;
import com.ai.analyzer.scan.pscan.ScanResult;
import com.ai.analyzer.util.AppLogBuffer;
import com.ai.analyzer.util.DebugContext;
import com.ai.analyzer.util.MarkdownRenderer;
import com.ai.analyzer.scan.rulesmatch.PreScanFilterManager;
import com.ai.analyzer.ui.active.ActiveAnalysisPanel;
import com.ai.analyzer.ui.active.PassiveDataSource;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.StyledDocument;
import javax.swing.text.JTextComponent;
import java.awt.*;
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
    private SettingsPanel settingsPanel;

    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;
    private JTextArea userPromptArea;
    private JTextPane resultTextPane;
    private JScrollPane resultScrollPane;
    private JButton analyzeButton;
    private JButton clearButton;
    private JButton deleteRequestButton;
    private JButton clearAllRequestsButton;
    private JButton stopButton;
    private JComboBox<String> analysisModeComboBox;
    private CardLayout centerModeCardLayout;
    private JPanel centerModeCardPanel;
    private JPanel passiveControlDetailsPanel;
    private JTextPane passiveModeResultTextPane;
    private JTextArea passiveModePromptArea;
    private boolean activeModeSelected = false;
    private ActiveAnalysisPanel activeAnalysisPanel;

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
        public HttpRequestResponse selectedRequestResponse() {
            Object sel = selectionForViewRow(getSelectedViewRow());
            if (sel instanceof ScanResult sr && sr.getRequestResponse() != null) {
                return sr.getRequestResponse();
            }
            if (sel instanceof RequestData rd && rd.getRequest() != null && !rd.getRequest().isEmpty()) {
                try {
                    HttpRequest httpRequest = HttpRequest.httpRequest(rd.getRequest());
                    return HttpRequestResponse.httpRequestResponse(httpRequest, HttpResponse.httpResponse());
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    };

    private List<RequestData> requestList;
    private int nextRequestId = 1;

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
    private final StringBuilder passiveScanStreamBuffer = new StringBuilder();
    private Integer currentStreamingId = null;
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
        this.requestList = new ArrayList<>();

        // 先创建 settingsPanel，createMainPanel 里的 syncApiConfigToPassiveScan 会用到它
        settingsPanel = new SettingsPanel(api, apiClient, preScanFilterManager,
            null, null, createSettingsHost());

        initializeUI();
        settingsPanel.autoLoadSettings();
    }

    private void initializeUI() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTabbedPane mainTabbedPane = new JTabbedPane();

        JPanel mainPanel = createMainPanel();
        mainTabbedPane.addTab("请求分析", mainPanel);

        // createMainPanel 创建了 passiveScanManager 和 activeAnalysisPanel，现在回填给 settingsPanel
        settingsPanel.setPassiveScanManager(passiveScanManager);
        settingsPanel.setActiveAnalysisPanel(activeAnalysisPanel);
        settingsPanel.addTabsTo(mainTabbedPane);

        JPanel mcpTrafficPanel = createMcpTrafficTabPanel();
        mainTabbedPane.addTab("Logger", mcpTrafficPanel);

        JPanel debugPanel = createDebugLogTabPanel();
        mainTabbedPane.addTab("Debug", debugPanel);

        add(mainTabbedPane, BorderLayout.CENTER);
    }

    private SettingsPanel.SettingsHost createSettingsHost() {
        return new SettingsPanel.SettingsHost() {
            @Override
            public ActiveAnalysisPanel getActiveAnalysisPanel() {
                return activeAnalysisPanel;
            }

            @Override
            public void onSettingsApplied() {
                syncApiConfigToPassiveScan();
            }

            @Override
            public void flashStatusMessage(String message) {
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

            @Override
            public void setPromptTextForAllModes(String text) {
                String prompt = text != null ? text : "";
                if (passiveModePromptArea != null) passiveModePromptArea.setText(prompt);
            }

            @Override
            public String getUserPromptText() {
                return userPromptArea != null ? userPromptArea.getText().trim() : "";
            }
        };
    }

    private JPanel createDebugLogTabPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(false);
        logArea.setFont(createLogFont());

        JCheckBox enableDebugCheckBox = new JCheckBox("启用调试模式");
        enableDebugCheckBox.setToolTipText("启用后记录所有 Agent 事件、工具调用、模型交互的详细日志");
        enableDebugCheckBox.addActionListener(e -> {
            if (enableDebugCheckBox.isSelected()) {
                DebugContext.enable();
            } else {
                DebugContext.disable();
            }
        });

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
            String appLog = AppLogBuffer.snapshot();
            if (!appLog.isEmpty()) {
                sb.append("=== 应用日志 ===\n");
                sb.append(appLog);
                sb.append("\n\n");
            }
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

    private JPanel createMainPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));

        JPanel passiveScanControlPanel = createPassiveScanControlPanel();
        mainPanel.add(passiveScanControlPanel, BorderLayout.NORTH);

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

        activeAnalysisPanel = new ActiveAnalysisPanel(api);
        activeAnalysisPanel.bind(apiClient, this::updateApiClientConfigForAnalysis, this::onAnalysisStateChanged, passiveDataSource);

        centerModeCardLayout = new CardLayout();
        centerModeCardPanel = new JPanel(centerModeCardLayout);
        centerModeCardPanel.add(passiveMainSplitPane, CARD_PASSIVE);
        centerModeCardPanel.add(activeAnalysisPanel, CARD_ACTIVE);
        centerModeCardLayout.show(centerModeCardPanel, CARD_PASSIVE);
        mainPanel.add(centerModeCardPanel, BorderLayout.CENTER);

        JPanel buttonPanel = createButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        initializePassiveScanManager();

        return mainPanel;
    }

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
        passiveControlDetailsPanel.add(activeHintPanel, CARD_ACTIVE);
        controlDetailCard.show(passiveControlDetailsPanel, CARD_PASSIVE);
        panel.add(passiveControlDetailsPanel, BorderLayout.CENTER);

        return panel;
    }

    private void initializePassiveScanManager() {
        passiveScanManager = new PassiveScanManager(api);
        passiveScanManager.setThreadCount((Integer) threadCountSpinner.getValue());

        passiveScanManager.setOnResultUpdated(result -> {
            SwingUtilities.invokeLater(() -> updatePassiveScanTable(result));
        });

        passiveScanManager.setOnStatusChanged(status -> {
            SwingUtilities.invokeLater(() -> {
                passiveScanStatusLabel.setText(status);
                boolean running = passiveScanManager.isRunning();
                startPassiveScanButton.setEnabled(enablePassiveScanCheckBox.isSelected() && !running);
                stopPassiveScanButton.setEnabled(enablePassiveScanCheckBox.isSelected() && running);
            });
        });

        passiveScanManager.setOnProgressChanged(progress -> {
            SwingUtilities.invokeLater(() -> passiveScanProgressBar.setValue(progress));
        });

        activeAuditManager = new com.ai.analyzer.scan.active.ActiveAuditManager(api.scanner());
        activeAuditManager.setOnIssueFound(issue ->
                SwingUtilities.invokeLater(() -> passiveScanManager.addAuditIssue(issue)));
        activeAuditManager.setOnStatusChanged(status ->
                SwingUtilities.invokeLater(() -> passiveScanStatusLabel.setText(status)));

        passiveScanManager.setOnHighRiskResult(result ->
                activeAuditManager.queueForAudit(result.getRequestResponse()));
        activeAuditManager.setOnQueueChanged(() ->
                SwingUtilities.invokeLater(() -> {
                    int size = activeAuditManager.pendingQueueSize();
                    auditQueueButton.setText("审计队列(" + size + ")");
                    auditQueueButton.setEnabled(size > 0);
                }));
        refreshAuditQueueButton();

        com.ai.analyzer.util.TokenUsageTracker.instance().setListener(snapshot ->
                SwingUtilities.invokeLater(() -> updateTokenUsageLabel(snapshot)));
        updateTokenUsageLabel(com.ai.analyzer.util.TokenUsageTracker.instance().snapshot());

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

        syncApiConfigToPassiveScan();
    }

    private void syncApiConfigToPassiveScan() {
        if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
            PassiveScanApiClient psClient = passiveScanManager.getApiClient();

            psClient.setApiUrl(apiClient.getApiUrl());
            psClient.setApiKey(apiClient.getApiKey());
            psClient.setModel(apiClient.getModel());
            psClient.setMaxTokens(apiClient.getMaxTokens());
            psClient.setApiProvider(apiClient.getApiProvider().getDisplayName());
            psClient.setEnableSearch(apiClient.isEnableSearch());
            psClient.setSearchMode(apiClient.getConfig().getSearchMode());
            psClient.setTavilyApiKey(apiClient.getConfig().getTavilyApiKey());
            psClient.setTavilyBaseUrl(apiClient.getConfig().getTavilyBaseUrl());
            psClient.setGoogleSearchApiKey(apiClient.getConfig().getGoogleSearchApiKey());
            psClient.setGoogleSearchCsi(apiClient.getConfig().getGoogleSearchCsi());

            psClient.setEnableMcp(apiClient.isEnableMcp());
            psClient.setBurpMcpUrl(apiClient.getBurpMcpUrl());
            psClient.setBurpMcpAuthorization(apiClient.getBurpMcpAuthorization());

            psClient.setEnableRagMcp(apiClient.isEnableRagMcp());
            psClient.setRagMcpUrl(apiClient.getRagMcpUrl());
            psClient.setRagMcpDocumentsPath(apiClient.getRagMcpDocumentsPath());

            psClient.setEnableChromeMcp(apiClient.isEnableChromeMcp());
            psClient.setChromeMcpUrl(apiClient.getChromeMcpUrl());

            psClient.setEnableFileSystemAccess(apiClient.isEnableFileSystemAccess());

            psClient.setCustomMcpConfigJson(settingsPanel.isCustomMcpEnabled() ? apiClient.getConfig().getCustomMcpConfigJson() : "");

            psClient.setEnablePythonScript(apiClient.isEnablePythonScript());
            psClient.setEnableCliTool(apiClient.getConfig().isEnableCliTool());
            psClient.setEnableUnrestrictedCliTool(apiClient.getConfig().isEnableUnrestrictedCliTool());
            psClient.setCliWhitelist(apiClient.getConfig().getCliWhitelist());
            psClient.setCliToolPrompt(apiClient.getConfig().getCliToolPrompt());
            psClient.setWorkplaceDirectoryPath(settingsPanel.getWorkplaceDirectoryPath());

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

    private boolean isResultPaneAtBottom() {
        if (resultScrollPane == null) return false;
        JScrollBar vbar = resultScrollPane.getVerticalScrollBar();
        return vbar.getMaximum() - vbar.getValue() - vbar.getVisibleAmount() < 80;
    }

    private void scrollResultToEndIfAtBottom() {
        if (isResultPaneAtBottom()) {
            resultTextPane.setCaretPosition(resultTextPane.getDocument().getLength());
        }
    }

    private void startPassiveScan() {
        if (!enablePassiveScanCheckBox.isSelected()) {
            passiveScanStatusLabel.setText("请先勾选「启用被动扫描」");
            new javax.swing.Timer(3000, e -> {
                ((javax.swing.Timer) e.getSource()).stop();
                passiveScanStatusLabel.setText("就绪");
            }).start();
            return;
        }

        syncApiConfigToPassiveScan();
        settingsPanel.applyPassiveScanFilters();
        passiveScanManager.startPassiveScan();
    }

    private void stopPassiveScan() {
        passiveScanManager.stopPassiveScan();
    }

    private void clearPassiveScanResults() {
        passiveScanManager.clearResults();
        passiveScanTableModel.setRowCount(0);
        passiveScanProgressBar.setValue(0);
        passiveScanStatusLabel.setText("就绪");
    }

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

    private void updateTokenUsageLabel(com.ai.analyzer.util.TokenUsageTracker.UsageSnapshot snapshot) {
        if (tokenUsageLabel == null) return;
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

    private void auditPendingQueue() {
        if (activeAuditManager == null) {
            return;
        }
        activeAuditManager.auditPendingQueue();
    }

    private void refreshAuditQueueButton() {
        if (auditQueueButton == null || activeAuditManager == null) return;
        int size = activeAuditManager.pendingQueueSize();
        auditQueueButton.setText("审计队列(" + size + ")");
        auditQueueButton.setEnabled(size > 0);
    }

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
            for (int i = 0; i < rowData.length; i++) {
                passiveScanTableModel.setValueAt(rowData[i], existingRow, i);
            }
        } else {
            passiveScanTableModel.addRow(rowData);
        }
    }

    private JPanel createRequestListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("请求列表（被动扫描）"));

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

        passiveScanTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        passiveScanTable.getColumnModel().getColumn(0).setMaxWidth(50);
        passiveScanTable.getColumnModel().getColumn(1).setPreferredWidth(50);
        passiveScanTable.getColumnModel().getColumn(1).setMaxWidth(60);
        passiveScanTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        passiveScanTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        passiveScanTable.getColumnModel().getColumn(3).setMaxWidth(70);
        passiveScanTable.getColumnModel().getColumn(4).setPreferredWidth(50);
        passiveScanTable.getColumnModel().getColumn(4).setMaxWidth(60);
        passiveScanTable.getColumnModel().getColumn(5).setPreferredWidth(60);
        passiveScanTable.getColumnModel().getColumn(5).setMaxWidth(70);
        passiveScanTable.getColumnModel().getColumn(6).setPreferredWidth(60);
        passiveScanTable.getColumnModel().getColumn(6).setMaxWidth(70);

        passiveScanTable.getColumnModel().getColumn(5).setCellRenderer(new RiskLevelCellRenderer());

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

        JPanel requestListButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        deleteRequestButton = new JButton("删除选中");
        clearAllRequestsButton = new JButton("清空所有");

        deleteRequestButton.addActionListener(e -> deleteSelectedPassiveScanResult());
        clearAllRequestsButton.addActionListener(e -> clearPassiveScanResults());

        requestListButtonPanel.add(deleteRequestButton);
        requestListButtonPanel.add(clearAllRequestsButton);

        JButton showStatsButton = new JButton("统计信息");
        showStatsButton.addActionListener(e -> showPassiveScanStats());
        requestListButtonPanel.add(showStatsButton);

        panel.add(requestListButtonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private class RiskLevelCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String riskLevel = value != null ? value.toString() : "";

            if (!isSelected) {
                switch (riskLevel) {
                    case "严重":
                        c.setBackground(new Color(255, 100, 100));
                        c.setForeground(Color.WHITE);
                        break;
                    case "高":
                        c.setBackground(new Color(255, 165, 0));
                        c.setForeground(Color.BLACK);
                        break;
                    case "中":
                        c.setBackground(new Color(100, 149, 237));
                        c.setForeground(Color.WHITE);
                        break;
                    case "低":
                        c.setBackground(new Color(144, 238, 144));
                        c.setForeground(Color.BLACK);
                        break;
                    case "信息":
                        c.setBackground(new Color(200, 200, 200));
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

    private void displayScanResult(ScanResult result) {
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

        String analysisResult = result.getAnalysisResult();
        if (analysisResult != null && !analysisResult.isEmpty()) {
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
            if (currentStreamingId != null && currentStreamingId == result.getId()) {
                currentStreamingId = null;
                passiveScanStreamBuffer.setLength(0);
            }
            passiveScanResultPane.setText("扫描错误: " + result.getErrorMessage());
        } else if (result.getStatus() == ScanResult.ScanStatus.SCANNING) {
            passiveScanResultPane.setText("正在分析中，请稍候...\n\n");
        } else {
            passiveScanResultPane.setText("状态: " + result.getStatus().getDisplayName());
        }
    }

    private void deleteSelectedPassiveScanResult() {
        int viewRow = passiveScanTable.getSelectedRow();
        if (viewRow >= 0) {
            int modelRow = passiveScanTable.convertRowIndexToModel(viewRow);
            passiveScanTableModel.removeRow(modelRow);
            clearHttpEditors();
            resultTextPane.setText("");
        }
    }

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

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);

        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.setBorder(BorderFactory.createTitledBorder("HTTP请求"));
        requestPanel.add(requestEditor.uiComponent(), BorderLayout.CENTER);

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

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        JButton newSessionButton = new JButton("＋ 新会话");
        newSessionButton.setToolTipText("新开一个会话：销毁当前 Agent 会话与记忆，从头开始（结果区清空）");
        newSessionButton.addActionListener(e -> {
            if (activeAnalysisPanel != null) {
                activeAnalysisPanel.startNewSession();
            }
        });
        analyzeButton = new JButton("开始分析");
        clearButton = new JButton("清空结果");
        stopButton = new JButton("停止");

        analyzeButton.addActionListener(e -> performAnalysis());
        clearButton.addActionListener(e -> clearResults());
        stopButton.addActionListener(e -> stopAnalysis());

        stopButton.setEnabled(false);

        buttonPanel.add(newSessionButton);
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

    private void updateApiClientConfigForAnalysis() {
        apiClient.setApiProvider(settingsPanel.getApiProvider());
        apiClient.setApiUrl(settingsPanel.getApiUrl());
        apiClient.setApiKey(settingsPanel.getApiKey());
        apiClient.setModel(settingsPanel.getModel());
        apiClient.setCustomParameters(settingsPanel.getCustomParameters());
        apiClient.setMaxTokens(settingsPanel.getMaxTokens());
        com.ai.analyzer.util.TokenUsageTracker.instance().setBudget(parseTokenBudget(settingsPanel.getTokenBudget()));
        apiClient.setEnableSearch(settingsPanel.isSearchEnabled());
    }

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
        for (RequestData requestData : requestList) {
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
                    "待分析",
                    "手动添加"
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
        deleteSelectedPassiveScanResult();
    }

    private void clearAllRequests() {
        int result = JOptionPane.showConfirmDialog(this, "确定要清空所有请求吗？", "确认", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            clearPassiveScanResults();
        }
    }

    public void addRequestFromHttpRequestResponse(String method, String url, HttpRequestResponse requestResponse) {
        try {
            if (passiveScanManager != null) {
                ScanResult scanResult = passiveScanManager.addRequest(requestResponse);
                if (scanResult != null) {
                    Object[] rowData = {
                        scanResult.getId(),
                        scanResult.getMethod(),
                        scanResult.getShortUrl(),
                        scanResult.getFormattedTimestamp(),
                        scanResult.hasResponse() ? "是" : "否",
                        "?析中...",
                        "手动添加"
                    };
                    passiveScanTableModel.addRow(rowData);
                    // 立即异步分析，不阻塞用户操作，结果通过 onResultUpdated/onStreamingChunk 回填
                    passiveScanManager.analyzeSingleRequest(requestResponse);
                    api.logging().logToOutput("请求已发送到AI异步分析: " + method + " " + url);
                } else {
                    api.logging().logToOutput("请求已存在，跳过添加: " + method + " " + url);
                }
            }
        } catch (Exception e) {
            api.logging().logToError("添加请求到AI分析器失败: " + e.getMessage());
        }
    }

    public Component getUiComponent() {
        return this;
    }

    public String getApiUrl() {
        return settingsPanel.getApiUrl();
    }

    public String getApiKey() {
        return settingsPanel.getApiKey();
    }

    public String getModel() {
        return settingsPanel.getModel();
    }

    public boolean isEnableSearch() {
        return settingsPanel.isSearchEnabled();
    }

    public String getWorkplaceDirectoryPath() {
        return settingsPanel.getWorkplaceDirectoryPath();
    }

    public boolean isEnableSkills() {
        return settingsPanel.isSkillsEnabled();
    }

    public String getSkillsDirectoryPath() {
        return settingsPanel.getSkillsDirectoryPath();
    }

    public AgentApiClient getApiClient() {
        return apiClient;
    }

    private void clearHttpEditors() {
        requestEditor.setRequest(HttpRequest.httpRequest());
        responseEditor.setResponse(HttpResponse.httpResponse());
    }
}
