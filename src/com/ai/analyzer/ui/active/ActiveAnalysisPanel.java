package com.ai.analyzer.ui.active;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import com.ai.analyzer.core.AgentApiClient;
import com.ai.analyzer.core.RequestData;
import com.ai.analyzer.scan.pscan.ScanResult;
import com.ai.analyzer.util.HttpFormatter;
import com.ai.analyzer.util.MarkdownRenderer;
import com.ai.analyzer.util.TokenUsageTracker;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主动分析面板：请求分析、批量分析、快捷任务、目标速览、分析历史、
 * 结果工具（复制/发送 Intruder）以及 Plan Mode 切换与 HITL 批准对话框。
 * 与 AIAnalyzerTab 通过依赖注入解耦：
 * - bind(): 注入 apiClient、API 配置更新器、状态监听、被动数据源
 * - setActiveMode(): 切换当前渲染目标（主动/被动结果区）
 */
public class ActiveAnalysisPanel extends JPanel {

    private final MontoyaApi api;

    // ========== 依赖注入 ==========
    private AgentApiClient apiClient;
    private Runnable apiClientConfigUpdater;
    private AnalysisStateListener stateListener;
    private PassiveDataSource dataSource;

    // ========== 渲染目标（由 AIAnalyzerTab 随模式切换） ==========
    private boolean activeModeSelected = true;
    private JTextPane targetPane;
    private JTextArea promptArea;

    // ========== UI 组件 ==========
    private JTextPane activeModeResultTextPane;
    private JTextArea activeModePromptArea;
    private JComboBox<String> planModeComboBox;
    private JLabel targetInfoLabel;
    private JList<AnalysisHistoryStore.AnalysisHistoryEntry> analysisHistoryList;
    private DefaultListModel<AnalysisHistoryStore.AnalysisHistoryEntry> analysisHistoryModel;
    private JTextArea behaviorTextArea;
    private JScrollPane behaviorScrollPane;
    /** 模型行为订阅（add/remove 成对使用，避免覆盖侧栏 ChatPanel 的订阅） */
    private final java.util.function.Consumer<String> modelBehaviorConsumer =
            behavior -> SwingUtilities.invokeLater(() -> handleModelBehavior(behavior));
    /** 进行中的思考增量缓冲（THINKING 为流式增量块，累积后整行重写，避免每块一行刷屏） */
    private final StringBuilder currentThinking = new StringBuilder();
    /** 当前"思考"行在文档中的起始偏移，-1 表示当前没有进行中的思考行 */
    private int thinkingLineStart = -1;

    // ========== 分析状态 ==========
    private boolean isAnalyzing = false;
    private SwingWorker<Void, String> currentWorker;
    private volatile int analysisRunId = 0;

    private final AnalysisHistoryStore historyStore = new AnalysisHistoryStore();

    public ActiveAnalysisPanel(MontoyaApi api) {
        this.api = api;
        buildUi();
    }

    public void bind(AgentApiClient client, Runnable configUpdater, AnalysisStateListener listener, PassiveDataSource ds) {
        this.apiClient = client;
        this.apiClientConfigUpdater = configUpdater;
        this.stateListener = listener;
        this.dataSource = ds;
        setupAgentHITL();
    }

    /** 模式切换：目标渲染区与提示词输入区随模式变化 */
    public void setActiveMode(boolean active, JTextPane target, JTextArea prompt) {
        this.activeModeSelected = active;
        this.targetPane = target;
        this.promptArea = prompt;
    }

    public JTextPane getResultPane() {
        return activeModeResultTextPane;
    }

    public JTextArea getPromptArea() {
        return activeModePromptArea;
    }

    public boolean isAnalyzing() {
        return isAnalyzing;
    }

    public boolean getPlanModeSelected() {
        return planModeComboBox != null && planModeComboBox.getSelectedIndex() == 1;
    }

    public void setPlanModeSelected(boolean planMode) {
        if (planModeComboBox != null) {
            planModeComboBox.setSelectedIndex(planMode ? 1 : 0);
        }
    }

    // ========== UI 构建 ==========

    private void buildUi() {
        setLayout(new BorderLayout(0, 5));

        // ---- 顶部区域：模式切换 + 工具按钮 / 快捷任务 / 目标速览（三层叠放，统一放入 NORTH） ----
        JPanel topArea = new JPanel();
        topArea.setLayout(new BoxLayout(topArea, BoxLayout.Y_AXIS));

        JPanel modeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        modeBar.add(new JLabel("Agent模式:"));
        planModeComboBox = new JComboBox<>(new String[]{"普通模式", "计划模式(Plan Mode)"});
        planModeComboBox.setToolTipText("计划模式：Agent 先进行只读调查并写出计划，提交 plan_exit 时弹出批准确认框，您批准后才开始执行。适合复杂渗透测试任务；普通模式直接执行。");
        planModeComboBox.addActionListener(e -> handlePlanModeChanged());
        modeBar.add(planModeComboBox);

        JButton copyResultButton = new JButton("复制结果");
        copyResultButton.setToolTipText("将当前 AI 分析结果全文复制到剪贴板");
        copyResultButton.addActionListener(e -> copyAnalysisResult());
        modeBar.add(copyResultButton);
        JButton copyRequestButton = new JButton("复制请求");
        copyRequestButton.setToolTipText("将当前选中的请求/响应报文复制到剪贴板");
        copyRequestButton.addActionListener(e -> copyCurrentRequest());
        modeBar.add(copyRequestButton);
        JButton sendIntruderButton = new JButton("发送到 Intruder");
        sendIntruderButton.setToolTipText("将当前选中的请求发送到 Intruder 进行自动化测试");
        sendIntruderButton.addActionListener(e -> sendSelectedToIntruder());
        modeBar.add(sendIntruderButton);
        JButton batchAnalyzeButton = new JButton("批量分析");
        batchAnalyzeButton.setToolTipText("将请求列表中选中的多个请求一次性提交给 Agent 归纳分析");
        batchAnalyzeButton.addActionListener(e -> performBatchAnalysis());
        modeBar.add(batchAnalyzeButton);
        JButton pasteMessageButton = new JButton("粘贴报文");
        pasteMessageButton.setToolTipText("粘贴原始 HTTP 请求/响应报文进行分析（无需先在请求列表中选中）");
        pasteMessageButton.addActionListener(e -> showPasteMessageDialog());
        modeBar.add(pasteMessageButton);
        topArea.add(modeBar);

        // ---- 快捷任务行：常见渗透测试任务一键填充提示词并执行 ----
        JPanel quickBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        quickBar.setBorder(BorderFactory.createTitledBorder("快捷任务"));
        for (QuickTaskPresets.QuickTask task : QuickTaskPresets.defaults()) {
            quickBar.add(createQuickTaskButton(task.label(), task.prompt()));
        }
        topArea.add(quickBar);

        // ---- 目标上下文速览条 ----
        JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        targetInfoLabel = new JLabel("未选择请求 — 自由对话模式");
        targetInfoLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        infoBar.add(targetInfoLabel);
        topArea.add(infoBar);

        add(topArea, BorderLayout.NORTH);

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

        // ---- 右侧：分析历史列表 ----
        analysisHistoryModel = new DefaultListModel<>();
        analysisHistoryList = new JList<>(analysisHistoryModel);
        analysisHistoryList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        analysisHistoryList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AnalysisHistoryStore.AnalysisHistoryEntry entry) {
                    setText("<html>" + escapeHtml(entry.timestamp()) + " — " + escapeHtml(entry.targetDesc()) + "</html>");
                    setToolTipText(escapeHtml(entry.prompt()));
                }
                return this;
            }
        });
        analysisHistoryList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    showAnalysisHistoryEntry(analysisHistoryList.getSelectedIndex());
                }
            }
        });
        JScrollPane historyScroll = new JScrollPane(analysisHistoryList);
        historyScroll.setBorder(BorderFactory.createTitledBorder("分析历史（双击回看）"));
        JButton clearHistoryButton = new JButton("清空历史");
        clearHistoryButton.addActionListener(e -> {
            historyStore.clear();
            analysisHistoryModel.clear();
        });
        JPanel historyPanel = new JPanel(new BorderLayout(2, 2));
        historyPanel.add(historyScroll, BorderLayout.CENTER);
        historyPanel.add(clearHistoryButton, BorderLayout.SOUTH);

        // ---- 右侧第二个页签：模型行为（thinking / 工具调用实时流） ----
        behaviorTextArea = new JTextArea();
        behaviorTextArea.setEditable(false);
        behaviorTextArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        behaviorTextArea.setLineWrap(true);
        behaviorTextArea.setWrapStyleWord(true);
        applyEditorTheme(behaviorTextArea);
        behaviorScrollPane = new JScrollPane(behaviorTextArea);
        behaviorScrollPane.setBorder(BorderFactory.createTitledBorder("模型行为（思考/工具调用）"));
        behaviorScrollPane.setVisible(true);
        JPanel behaviorPanel = new JPanel(new BorderLayout());
        behaviorPanel.add(behaviorScrollPane, BorderLayout.CENTER);

        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("分析历史", historyPanel);
        rightTabs.addTab("模型行为", behaviorPanel);

        JSplitPane resultSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, resultScrollPane, rightTabs);
        resultSplit.setDividerLocation(680);
        resultSplit.setResizeWeight(1.0);
        resultSplit.setContinuousLayout(true);
        add(resultSplit, BorderLayout.CENTER);

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

        // Enter 发送，Shift+Enter 换行
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
        add(promptPanel, BorderLayout.SOUTH);
    }

    private JButton createQuickTaskButton(String label, String prompt) {
        JButton button = new JButton(label);
        button.setToolTipText("填充提示词并立即分析：" + prompt);
        button.addActionListener(e -> {
            if (isAnalyzing) {
                JOptionPane.showMessageDialog(this, "当前正在分析中，请等待完成或点击停止");
                return;
            }
            activeModePromptArea.setText(prompt);
            performAnalysis();
        });
        return button;
    }

    private void applyEditorTheme(JTextComponent component) {
        component.setBackground(UIManager.getColor("TextArea.background"));
        component.setForeground(UIManager.getColor("TextArea.foreground"));
        component.setCaretColor(UIManager.getColor("TextArea.caretForeground"));
    }

    // ========== Plan Mode 切换与 HITL 批准 ==========

    private void handlePlanModeChanged() {
        if (planModeComboBox == null || apiClient == null) return;
        boolean planMode = planModeComboBox.getSelectedIndex() == 1;
        apiClient.setEnablePlanMode(planMode);
        api.logging().logToOutput(planMode
            ? "已切换到计划模式：Agent 将先写计划并在执行前请求批准"
            : "已切换到普通模式：Agent 直接执行");
    }

    private void setupAgentHITL() {
        apiClient.setRequireConfirmHandler((toolName, summary) -> {
            final boolean[] approved = {false};
            try {
                SwingUtilities.invokeAndWait(() -> {
                    String html = "<html><body style='width:460px;font-family:Microsoft YaHei'>"
                        + "<h3>Agent 请求批准</h3>"
                        + "<p style='font-size:11px;color:#666'>Agent 在计划模式下暂停，等待您确认后再继续执行：</p>"
                        + "<table cellpadding='2'>"
                        + "<tr><td style='font-weight:bold'>工具</td><td>:</td><td style='word-break:break-all'>" + escapeHtml(toolName) + "</td></tr>"
                        + "</table>"
                        + "<p style='font-size:11px;color:#666'>计划摘要/待执行内容：</p>"
                        + "<pre style='background:#f4f4f4;border:1px solid #ddd;padding:8px;white-space:pre-wrap;word-break:break-all'>" + escapeHtml(summary) + "</pre>"
                        + "<p style='font-size:11px;color:#666'>批准：按计划继续执行；拒绝：Agent 回到计划阶段修改方案。</p>"
                        + "</body></html>";
                    int option = JOptionPane.showConfirmDialog(
                        null, html, "计划批准确认", JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                    approved[0] = option == JOptionPane.YES_OPTION;
                });
            } catch (Exception ex) {
                api.logging().logToError(ex);
                approved[0] = false;
            }
            return approved[0];
        });
    }

    // ========== 分析入口 ==========

    /** 分析当前选中请求（或自由对话）。由 AIAnalyzerTab 的按钮/被动模式共用。 */
    public void performAnalysis() {
        if (isAnalyzing) {
            return;
        }
        if (apiClient == null) {
            return;
        }

        // 允许没有选择请求时也能进行分析（自由对话模式）
        Object selection = null;
        int viewRow = (!activeModeSelected && dataSource != null) ? dataSource.getSelectedViewRow() : -1;
        if (viewRow >= 0) {
            selection = dataSource.selectionForViewRow(viewRow);
        }
        RequestData requestData = selection instanceof RequestData rd ? rd : null;
        ScanResult scanResult = selection instanceof ScanResult sr ? sr : null;

        String userPrompt = (promptArea != null ? promptArea.getText().trim() : "");
        if (userPrompt.isEmpty()) {
            if (requestData != null || scanResult != null) {
                userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
            } else {
                userPrompt = "";
            }
        }

        // 构造分析报文与目标速览
        String httpContent = "";
        if (scanResult != null && scanResult.getRequestResponse() != null) {
            httpContent = HttpFormatter.formatHttpRequestResponse(scanResult.getRequestResponse());
        } else if (requestData != null) {
            httpContent = requestData.getFullRequestResponse();
        }
        String targetDesc;
        String detail;
        if (scanResult != null) {
            targetDesc = scanResult.getMethod() + " " + scanResult.getShortUrl();
            detail = "Host: " + scanResult.getHost() + " · 报文 " + httpContent.length() + " 字节";
        } else if (requestData != null) {
            String url = requestData.getUrl();
            targetDesc = requestData.getMethod() + " " + (url.length() > 100 ? url.substring(0, 100) + "..." : url);
            String host = extractHost(url);
            detail = (host.isEmpty() ? "" : "Host: " + host + " · ") + "报文 " + httpContent.length() + " 字节";
        } else {
            targetDesc = "自由对话模式";
            detail = "未选择请求";
        }
        updateTargetInfo(targetDesc, detail);

        if (requestData == null && scanResult == null) {
            api.logging().logToOutput("当前没有选择请求，将以自由对话模式进行分析");
        }

        if (apiClientConfigUpdater != null) {
            apiClientConfigUpdater.run();
        }
        startAnalysisWorker(httpContent, userPrompt, targetDesc, detail);
    }

    private void performBatchAnalysis() {
        if (isAnalyzing) {
            JOptionPane.showMessageDialog(this, "当前正在分析中，请等待完成或点击停止");
            return;
        }
        if (apiClient == null) {
            return;
        }
        List<Map.Entry<Object, String>> targets = collectSelectedTargets();
        if (targets.size() < 2) {
            JOptionPane.showMessageDialog(this, "请在请求列表中选择至少 2 个请求进行批量分析");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是 ").append(targets.size()).append(" 个待分析的 HTTP 请求/响应（按顺序编号）：\n\n");
        int idx = 1;
        for (Map.Entry<Object, String> entry : targets) {
            sb.append("========== 请求 ").append(idx++).append(" ==========\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        String httpContent = sb.toString();

        String userPrompt = (promptArea != null ? promptArea.getText().trim() : "");
        if (userPrompt.isEmpty()) {
            userPrompt = "请逐一分析以上 " + targets.size() + " 个请求存在的安全漏洞，按风险等级排序归纳，并给出利用条件与验证建议";
        }
        String targetDesc = "批量分析 " + targets.size() + " 个请求";
        String batchDetail = "总报文 " + httpContent.length() + " 字符";
        updateTargetInfo(targetDesc, batchDetail);

        if (apiClientConfigUpdater != null) {
            apiClientConfigUpdater.run();
        }
        startAnalysisWorker(httpContent, userPrompt, targetDesc, batchDetail);
    }

    private void startAnalysisWorker(String httpContent, String userPrompt, String targetDesc, String detail) {
        isAnalyzing = true;
        final int runId = ++analysisRunId;
        notifyStateChanged();

        if (apiClient != null) {
            apiClient.addModelBehaviorConsumer(modelBehaviorConsumer);
        }
        if (behaviorTextArea != null) {
            behaviorTextArea.setText("");
        }

        final boolean isActiveMode = activeModeSelected;
        if (isActiveMode && activeModePromptArea != null) {
            activeModePromptArea.setText("");
        }

        final JTextPane targetResultPane = targetPane;
        String finalUserPrompt = userPrompt;
        String finalHttpContent = httpContent;
        String finalTargetDesc = targetDesc;
        String finalDetail = detail;
        final long startTime = System.currentTimeMillis();

        currentWorker = new SwingWorker<Void, String>() {
            private StringBuilder fullResponse = new StringBuilder();
            private int aiMessageStartPos = 0;

            @Override
            protected Void doInBackground() throws Exception {
                try {
                    final boolean httpTooLong = !finalHttpContent.isEmpty()
                        && finalHttpContent.length() > HttpFormatter.DEFAULT_MAX_LENGTH;
                    final int httpOrigLen = finalHttpContent.length();

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
                                        "HTTP内容过长（" + httpOrigLen + " 字符），完整报文已缓存，提示词仅含预览与 fileId\n\n", warnStyle);
                                } catch (Exception ignored) {}
                            }
                            aiMessageStartPos = targetResultPane.getStyledDocument().getLength();
                        });
                    } catch (Exception e) {
                        aiMessageStartPos = 0;
                    }

                    final long[] lastRenderTime = {0L};
                    final long RENDER_INTERVAL_MS = 120;

                    apiClient.analyzeRequestStream(
                        finalHttpContent,
                        finalUserPrompt,
                        chunk -> {
                            if (isCancelled() || !isAnalyzing || runId != analysisRunId) return;

                            fullResponse.append(chunk);

                            long now = System.currentTimeMillis();

                            if (now - lastRenderTime[0] < RENDER_INTERVAL_MS) return;
                            lastRenderTime[0] = now;
                            String snapshot = fullResponse.toString();

                            SwingUtilities.invokeLater(() -> {
                                if (isCancelled() || !isAnalyzing || runId != analysisRunId) return;
                                try {
                                    MarkdownRenderer.appendMarkdownStreaming(targetResultPane, snapshot, aiMessageStartPos);
                                    if (isResultPaneAtBottom(targetResultPane)) {
                                        targetResultPane.setCaretPosition(targetResultPane.getStyledDocument().getLength());
                                    }
                                } catch (Exception e) {
                                    api.logging().logToError("流式Markdown渲染失败: " + e.getMessage());
                                }
                            });
                        }
                    );

                    String finalContent = fullResponse.toString();
                    if (!finalContent.isEmpty() && !isCancelled() && isAnalyzing && runId == analysisRunId) {
                        SwingUtilities.invokeLater(() -> {
                            if (isCancelled() || !isAnalyzing || runId != analysisRunId) return;
                            try {
                                StyledDocument doc = targetResultPane.getStyledDocument();
                                int currentLength = doc.getLength();
                                if (currentLength > aiMessageStartPos) {
                                    doc.remove(aiMessageStartPos, currentLength - aiMessageStartPos);
                                }
                                MarkdownRenderer.appendMarkdown(targetResultPane, finalContent);
                                scrollResultToEndIfAtBottom(targetResultPane);
                            } catch (Exception e) {
                                api.logging().logToError("最终Markdown渲染失败: " + e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    if (isCancelled() || runId != analysisRunId) return null;
                    SwingUtilities.invokeLater(() -> {
                        appendToResult(targetResultPane, "分析过程中出现错误: " + e.getMessage());
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    if (runId != analysisRunId) return;
                    if (!fullResponse.toString().isBlank()) {
                        recordAnalysisHistory(finalTargetDesc, finalUserPrompt, fullResponse.toString());
                    }
                    updateTargetInfo(finalTargetDesc, buildStatusSuffix(finalDetail, startTime));
                } catch (Exception e) {
                    if (!isCancelled() && runId == analysisRunId) {
                        SwingUtilities.invokeLater(() -> {
                            appendToResult(targetResultPane, "分析过程中出现错误: " + e.getMessage());
                        });
                    }
                } finally {
                    if (runId == analysisRunId) {
                        if (apiClient != null) {
                            apiClient.removeModelBehaviorConsumer(modelBehaviorConsumer);
                        }
                        isAnalyzing = false;
                        notifyStateChanged();
                    }
                }
            }
        };

        currentWorker.execute();
    }

    /** 状态条追加：耗时 + 模型 + 本次会话 token 用量 */
    private String buildStatusSuffix(String detail, long startTime) {
        long elapsedMs = System.currentTimeMillis() - startTime;
        String elapsed = elapsedMs < 1000 ? elapsedMs + " ms" : String.format("%.1f s", elapsedMs / 1000.0);
        StringBuilder sb = new StringBuilder();
        if (detail != null && !detail.isEmpty()) {
            sb.append(detail).append(" | ");
        }
        sb.append("耗时 ").append(elapsed);
        if (apiClient != null) {
            String model = apiClient.getModel();
            if (model != null && !model.isBlank()) {
                sb.append(" · 模型 ").append(model);
            }
        }
        sb.append(" · ").append(TokenUsageTracker.instance().snapshot().summary());
        return sb.toString();
    }

    /** 从 URL 提取 host（去掉协议与端口，失败返回空串） */
    private static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost() != null ? uri.getHost() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public void stopAnalysis() {
        if (currentWorker != null && !currentWorker.isDone()) {
            if (apiClient != null) {
                apiClient.cancelStreaming();
                apiClient.removeModelBehaviorConsumer(modelBehaviorConsumer);
            }
            analysisRunId++;
            currentWorker.cancel(true);
            isAnalyzing = false;
            notifyStateChanged();

            try {
                StyledDocument doc = targetPane.getStyledDocument();
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

    public void clearResults() {
        if (currentWorker != null && !currentWorker.isDone()) {
            if (apiClient != null) {
                apiClient.cancelStreaming();
                apiClient.removeModelBehaviorConsumer(modelBehaviorConsumer);
            }
            analysisRunId++;
            currentWorker.cancel(true);
            isAnalyzing = false;
            notifyStateChanged();
        }
        if (targetPane != null) {
            targetPane.setText("");
        }
        if (activeModeResultTextPane != null && activeModeResultTextPane != targetPane) {
            activeModeResultTextPane.setText("");
        }
        if (apiClient != null) {
            apiClient.clearContext();
        }
    }

    // ========== 目标速览 / 分析历史 / 结果工具 ==========

    /** 收集请求表中所有选中行的 (ScanResult/RequestData, httpContent) */
    private List<Map.Entry<Object, String>> collectSelectedTargets() {
        List<Map.Entry<Object, String>> targets = new ArrayList<>();
        if (dataSource == null) return targets;
        List<Object> selections = dataSource.getSelectedSelections();
        for (Object selection : selections) {
            String content = "";
            String label = "";
            if (selection instanceof ScanResult sr && sr.getRequestResponse() != null) {
                content = HttpFormatter.formatHttpRequestResponse(sr.getRequestResponse());
                label = sr.getMethod() + " " + sr.getShortUrl();
            } else if (selection instanceof RequestData rd) {
                content = rd.getFullRequestResponse();
                String url = rd.getUrl();
                label = rd.getMethod() + " " + (url.length() > 80 ? url.substring(0, 80) + "..." : url);
            }
            if (content != null && !content.isBlank()) {
                targets.add(Map.entry(selection, content + "\n\n--- 目标: " + label + " ---"));
            }
        }
        return targets;
    }

    private void updateTargetInfo(String targetDesc, String detail) {
        if (targetInfoLabel == null) return;
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b style='color:#2a7de1'>").append(escapeHtml(targetDesc)).append("</b>");
        if (detail != null && !detail.isEmpty()) {
            sb.append(" &nbsp;<span style='color:#666;font-size:11px'>").append(escapeHtml(detail)).append("</span>");
        }
        sb.append("</html>");
        targetInfoLabel.setText(sb.toString());
    }

    // ========== 模型行为可观测（thinking / 工具调用实时流） ==========

    private void appendBehaviorLog(String line) {
        if (behaviorTextArea == null) return;
        behaviorTextArea.append(line + "\n");
        scrollBehaviorToBottom();
    }

    private void scrollBehaviorToBottom() {
        if (behaviorScrollPane == null) return;
        JScrollBar vbar = behaviorScrollPane.getVerticalScrollBar();
        boolean atBottom = vbar.getMaximum() - vbar.getValue() - vbar.getVisibleAmount() < 80;
        if (atBottom) {
            behaviorTextArea.setCaretPosition(behaviorTextArea.getDocument().getLength());
        }
    }

    /** 模型行为流：TYPE|detail（THINKING / TOOL_START / TOOL_END），与 ChatPanel 相同格式 */
    private void handleModelBehavior(String message) {
        if (message == null || message.isEmpty()) return;
        int sep = message.indexOf('|');
        String type = sep >= 0 ? message.substring(0, sep) : "OTHER";
        String detail = sep >= 0 ? message.substring(sep + 1) : message;
        String timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        switch (type) {
            case "THINKING" -> {
                // 流式增量块：累积到当前"思考"行并整行重写，而不是每块新起一行
                if (detail != null && !detail.isEmpty()) {
                    currentThinking.append(detail);
                    renderThinkingLine(timestamp);
                }
            }
            case "TOOL_START" -> {
                flushThinking();
                appendBehaviorLog("[" + timestamp + "] 调用工具: " + detail);
            }
            case "TOOL_END" -> {
                flushThinking();
                boolean failed = detail.endsWith("|failed");
                String tool = failed ? detail.substring(0, detail.length() - 7) : detail;
                appendBehaviorLog("[" + timestamp + "] " + (failed ? "工具失败: " : "工具完成: ") + tool);
            }
            default -> {
                flushThinking();
                appendBehaviorLog("[" + timestamp + "] " + detail);
            }
        }
    }

    /** 把累积的思考缓冲渲染为一行（首次追加，后续整行重写） */
    private void renderThinkingLine(String timestamp) {
        if (behaviorTextArea == null) return;
        String compact = currentThinking.toString().replaceAll("\\s+", " ").trim();
        if (compact.length() > 200) {
            compact = compact.substring(0, 200) + "...";
        }
        String line = "[" + timestamp + "] 思考: " + compact;
        try {
            javax.swing.text.Document doc = behaviorTextArea.getDocument();
            if (thinkingLineStart < 0) {
                thinkingLineStart = doc.getLength();
                doc.insertString(doc.getLength(), line + "\n", null);
            } else {
                doc.remove(thinkingLineStart, doc.getLength() - thinkingLineStart);
                doc.insertString(thinkingLineStart, line + "\n", null);
            }
        } catch (Exception e) {
            thinkingLineStart = -1;
        }
        scrollBehaviorToBottom();
    }

    /** 思考段落结束：固化当前行，重置缓冲 */
    private void flushThinking() {
        currentThinking.setLength(0);
        thinkingLineStart = -1;
    }

    // ========== 粘贴报文分析 ==========

    private void showPasteMessageDialog() {
        if (isAnalyzing) {
            JOptionPane.showMessageDialog(this, "当前正在分析中，请等待完成或点击停止");
            return;
        }
        JTextArea rawArea = new JTextArea(20, 60);
        rawArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        rawArea.setLineWrap(false);
        JScrollPane rawScroll = new JScrollPane(rawArea);
        rawScroll.setBorder(BorderFactory.createTitledBorder("粘贴原始 HTTP 请求/响应报文"));
        JPanel dialogPanel = new JPanel(new BorderLayout(5, 5));
        dialogPanel.add(rawScroll, BorderLayout.CENTER);
        dialogPanel.add(new JLabel("支持粘贴 Burp/抓包工具复制的完整报文（含请求行与请求头，可包含响应）"), BorderLayout.NORTH);
        int option = JOptionPane.showConfirmDialog(this, dialogPanel, "粘贴报文分析",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (option != JOptionPane.OK_OPTION) {
            return;
        }
        String raw = rawArea.getText();
        if (raw == null || raw.isBlank()) {
            JOptionPane.showMessageDialog(this, "报文内容为空");
            return;
        }
        String targetDesc = "粘贴报文分析";
        String host = "";
        try {
            HttpRequest parsed = HttpRequest.httpRequest(raw.trim());
            if (parsed != null && parsed.url() != null && !parsed.url().isBlank()) {
                targetDesc = parsed.method() + " " + parsed.url();
                host = extractHost(parsed.url());
            }
        } catch (Exception ignored) {
            // 解析失败（如带响应或非标准报文），使用原始文本分析
        }
        String detail = (host.isEmpty() ? "" : "Host: " + host + " · ") + "报文 " + raw.length() + " 字节";
        String userPrompt = (activeModePromptArea != null ? activeModePromptArea.getText().trim() : "");
        if (userPrompt.isEmpty()) {
            userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
        }
        if (apiClientConfigUpdater != null) {
            apiClientConfigUpdater.run();
        }
        startAnalysisWorker(raw.trim(), userPrompt, targetDesc, detail);
    }

    private void recordAnalysisHistory(String targetDesc, String prompt, String result) {
        if (analysisHistoryModel == null) return;
        historyStore.add(targetDesc, prompt, result);
        analysisHistoryModel.addElement(historyStore.get(historyStore.size() - 1));
        while (analysisHistoryModel.size() > AnalysisHistoryStore.MAX_ENTRIES) {
            analysisHistoryModel.removeElementAt(0);
        }
    }

    private void showAnalysisHistoryEntry(int index) {
        if (index < 0 || index >= historyStore.size()) return;
        AnalysisHistoryStore.AnalysisHistoryEntry entry = historyStore.get(index);
        try {
            StyledDocument doc = activeModeResultTextPane.getStyledDocument();
            javax.swing.text.Style histStyle = doc.addStyle("historyHeader", null);
            javax.swing.text.StyleConstants.setForeground(histStyle, new Color(120, 120, 120));
            javax.swing.text.StyleConstants.setBold(histStyle, true);
            doc.insertString(doc.getLength(), "\n\n========== 历史回看 " + entry.timestamp() + " — " + entry.targetDesc() + " ==========\n\n", histStyle);
            MarkdownRenderer.appendMarkdown(activeModeResultTextPane, entry.result());
            activeModeResultTextPane.setCaretPosition(doc.getLength());
        } catch (Exception ex) {
            api.logging().logToError("历史回看失败: " + ex.getMessage());
        }
    }

    private void copyAnalysisResult() {
        String text = activeModeResultTextPane == null ? "" : activeModeResultTextPane.getText();
        if (text.isBlank()) {
            JOptionPane.showMessageDialog(this, "当前没有可复制的分析结果");
            return;
        }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new java.awt.datatransfer.StringSelection(text), null);
        api.logging().logToOutput("分析结果已复制到剪贴板");
    }

    private void copyCurrentRequest() {
        String content = firstSelectedHttpContent();
        if (content == null || content.isBlank()) {
            JOptionPane.showMessageDialog(this, "当前没有选中的请求可复制");
            return;
        }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new java.awt.datatransfer.StringSelection(content), null);
        api.logging().logToOutput("请求报文已复制到剪贴板");
    }

    private void sendSelectedToIntruder() {
        if (dataSource == null) {
            return;
        }
        HttpRequestResponse rr = dataSource.selectedRequestResponse();
        if (rr == null || rr.request() == null) {
            JOptionPane.showMessageDialog(this, "当前没有选中的请求可发送");
            return;
        }
        try {
            api.intruder().sendToIntruder(rr.request());
            api.logging().logToOutput("请求已发送到 Intruder");
        } catch (Exception ex) {
            api.logging().logToError("发送到 Intruder 失败: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "发送到 Intruder 失败: " + ex.getMessage());
        }
    }

    private String firstSelectedHttpContent() {
        if (dataSource == null) return null;
        int viewRow = dataSource.getSelectedViewRow();
        if (viewRow < 0) return null;
        Object selection = dataSource.selectionForViewRow(viewRow);
        if (selection instanceof ScanResult sr && sr.getRequestResponse() != null) {
            return HttpFormatter.formatHttpRequestResponse(sr.getRequestResponse());
        }
        if (selection instanceof RequestData rd) {
            return rd.getFullRequestResponse();
        }
        return null;
    }

    // ========== 渲染工具 ==========

    private static boolean isResultPaneAtBottom(JTextPane pane) {
        JScrollPane scrollPane = findScrollPane(pane);
        if (scrollPane == null) return true;
        javax.swing.JScrollBar vbar = scrollPane.getVerticalScrollBar();
        return vbar.getValue() + vbar.getVisibleAmount() >= vbar.getMaximum() - 20;
    }

    private static void scrollResultToEndIfAtBottom(JTextPane pane) {
        JScrollPane scrollPane = findScrollPane(pane);
        if (scrollPane == null) return;
        javax.swing.JScrollBar vbar = scrollPane.getVerticalScrollBar();
        if (vbar.getValue() + vbar.getVisibleAmount() >= vbar.getMaximum() - 20) {
            vbar.setValue(vbar.getMaximum());
        }
    }

    private static JScrollPane findScrollPane(Component c) {
        Component parent = c.getParent();
        while (parent != null) {
            if (parent instanceof JScrollPane scrollPane) {
                return scrollPane;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static void appendToResult(JTextPane pane, String text) {
        try {
            StyledDocument doc = pane.getStyledDocument();
            javax.swing.text.Style regularStyle = doc.addStyle("regular", null);
            javax.swing.text.StyleConstants.setFontFamily(regularStyle, "Microsoft YaHei");
            javax.swing.text.StyleConstants.setFontSize(regularStyle, 12);
            Color textColor = UIManager.getColor("TextArea.foreground");
            javax.swing.text.StyleConstants.setForeground(regularStyle, textColor != null ? textColor : Color.BLACK);
            doc.insertString(doc.getLength(), text + "\n", regularStyle);
            scrollResultToEndIfAtBottom(pane);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void notifyStateChanged() {
        if (stateListener != null) {
            stateListener.onAnalysisStateChanged(isAnalyzing);
        }
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
