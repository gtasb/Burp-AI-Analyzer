package com.ai.analyzer.ui.active;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import com.ai.analyzer.core.AgentApiClient;
import com.ai.analyzer.core.RequestData;
import com.ai.analyzer.scan.pscan.ScanResult;
import com.ai.analyzer.ui.UIStyles;
import com.ai.analyzer.util.HttpFormatter;
import com.ai.analyzer.util.TokenUsageTracker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * 主动分析面板（会话式 UI）。
 *
 * <p>信息结构参考 Claude Code / Codex：
 * <ul>
 *   <li>顶部状态栏：目标、阶段、轮次、自动继续、模式、历史</li>
 *   <li>中部会话流：用户消息 / 主动分析 / 整理 agent / 工具调用 / 报告，按消息块排列</li>
 *   <li>底部输入区：Enter 发送，Shift+Enter 换行</li>
 * </ul>
 */
public class ActiveAnalysisPanel extends JPanel {

    private final MontoyaApi api;

    // ========== 依赖注入 ==========
    private AgentApiClient apiClient;
    private Runnable apiClientConfigUpdater;
    private AnalysisStateListener stateListener;
    private PassiveDataSource dataSource;

    // ========== 渲染 / 交互 ==========
    private boolean activeModeSelected = true;
    private volatile HttpRequestResponse pendingDirectRequest;
    private JTextArea promptArea;
    private JTextArea activeModePromptArea;
    /** 被动模式输入框（用户在被动视图里输入提示词的地方） */
    private JTextArea passivePromptArea;

    private final ChatTimeline timeline = new ChatTimeline();
    private ChatTimeline.AssistantBlock currentAssistant;

    private JComboBox<String> planModeComboBox;
    private JComboBox<com.ai.analyzer.core.AnalysisRunStore.Run> runHistoryCombo;
    private JCheckBox autoContinueCheckBox;
    private JLabel targetLabel;
    private JLabel targetDetailLabel;
    private JLabel phaseLabel;
    private JLabel roundLabel;
    private JLabel loopPolicyLabel;
    private JLabel tokenLabel;
    private JButton interjectButton;
    private boolean updatingRunHistory;

    private java.util.function.BooleanSupplier autoContinueGetter;
    private java.util.function.Consumer<Boolean> autoContinueSetter;

    // ========== 分析状态 ==========
    private boolean isAnalyzing = false;
    private SwingWorker<Void, String> currentWorker;
    private volatile int analysisRunId = 0;
    private int activeRound = 0;
    private volatile java.util.function.Consumer<AgentApiClient.CompletedRun> onRunCompleted;
    private volatile Runnable onRunStarted;

    /** 上一轮上下文，供自动继续复用同一请求 */
    private volatile String lastHttpContent = "";
    private volatile String lastTargetDesc = "";
    private volatile String lastDetail = "";
    private volatile String lastMethod = null;
    private volatile String lastUrl = null;
    private volatile boolean lastHasRequest = false;
    /** 本轮是否由整理 agent 自动继续触发（true 时不重复注入侧栏摘要） */
    private boolean nextRunAuto;

    /** 主动分析的思考增量（节流后显示在消息块里） */
    private final StringBuilder currentThinking = new StringBuilder();
    private static final int MAX_THINKING_CHARS = 8_000;
    private static final long THINKING_RENDER_MS = 200;
    private long lastThinkingRenderAt;

    private final java.util.function.Consumer<String> modelBehaviorConsumer =
            behavior -> SwingUtilities.invokeLater(() -> handleModelBehavior(behavior));

    public ActiveAnalysisPanel(MontoyaApi api) {
        this.api = api;
        buildUi();
        refreshRunHistory();
    }

    public void bind(AgentApiClient client, Runnable configUpdater,
                     AnalysisStateListener listener, PassiveDataSource ds) {
        this.apiClient = client;
        this.apiClientConfigUpdater = configUpdater;
        this.stateListener = listener;
        this.dataSource = ds;
        setupAgentHITL();
    }

    public void setActiveMode(boolean active, JTextArea prompt) {
        this.activeModeSelected = active;
        // 主动模式固定绑回自己的输入区；否则从 passive 切回来后 promptArea 还指着
        // 被动输入框，performAnalysis 读到空文本、把用户输入换成默认提示词
        if (prompt != null && !active) {
            this.passivePromptArea = prompt;
            this.promptArea = prompt;
        } else if (active) {
            this.promptArea = activeModePromptArea;
        }
    }

    public JTextArea getPromptArea() {
        return activeModePromptArea;
    }

    public void reloadRunHistory() {
        refreshRunHistory();
    }

    public boolean isAnalyzing() {
        return isAnalyzing;
    }

    public void setOnRunCompleted(java.util.function.Consumer<AgentApiClient.CompletedRun> callback) {
        this.onRunCompleted = callback;
    }

    public void setOnRunStarted(Runnable callback) {
        this.onRunStarted = callback;
    }

    public boolean continueWithInstruction(String instruction) {
        if (isAnalyzing || apiClient == null) return false;
        String prompt = instruction == null ? "" : instruction.trim();
        if (prompt.isEmpty()) return false;
        if (lastHttpContent.isEmpty() && "自由对话模式".equals(lastTargetDesc)) {
            if (activeModePromptArea != null) activeModePromptArea.setText(prompt);
            return false;
        }
        nextRunAuto = true;
        try {
            startAnalysisWorker(lastHttpContent, prompt, lastTargetDesc, lastDetail,
                    lastMethod, lastUrl, lastHasRequest);
        } finally {
            nextRunAuto = false;
        }
        return true;
    }

    public boolean getPlanModeSelected() {
        return planModeComboBox != null && planModeComboBox.getSelectedIndex() == 1;
    }

    public void setPlanModeSelected(boolean planMode) {
        if (planModeComboBox != null) {
            planModeComboBox.setSelectedIndex(planMode ? 1 : 0);
        }
    }

    // ================= UI =================

    private void buildUi() {
        setLayout(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(2, 4, 2, 4));

        // 单行工具条：目标/阶段/轮次/自动继续/模式/历史压进一行，纵向空间全部留给对话流
        add(buildToolbar(), BorderLayout.NORTH);

        add(timeline, BorderLayout.CENTER);

        add(buildComposer(), BorderLayout.SOUTH);
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(new EmptyBorder(1, 4, 1, 4));

        targetLabel = new JLabel("未选择请求 — 自由对话模式");
        UIStyles.setFont(targetLabel, 12f, Font.BOLD);
        targetLabel.setForeground(new Color(0x2a7de1));
        bar.add(targetLabel);
        targetDetailLabel = new JLabel("");
        UIStyles.setFont(targetDetailLabel, 11f, Font.PLAIN);
        targetDetailLabel.setForeground(UIStyles.hintColor());
        bar.add(targetDetailLabel);
        bar.addSeparator();

        phaseLabel = new JLabel("阶段：空闲");
        UIStyles.setFont(phaseLabel, 12f, Font.BOLD);
        phaseLabel.setForeground(new Color(0x2a7de1));
        bar.add(phaseLabel);
        roundLabel = new JLabel("轮次：0");
        UIStyles.setFont(roundLabel, 11f, Font.PLAIN);
        bar.add(roundLabel);
        bar.addSeparator();

        autoContinueCheckBox = new JCheckBox("自动继续", true);
        autoContinueCheckBox.setToolTipText("每轮结束后由整理对象判断是否继续；关闭后只整理入图并出报告");
        autoContinueCheckBox.setFocusable(false);
        UIStyles.setFont(autoContinueCheckBox, 11f, Font.PLAIN);
        autoContinueCheckBox.addActionListener(e -> {
            if (autoContinueSetter != null) autoContinueSetter.accept(autoContinueCheckBox.isSelected());
            updateLoopPolicyLabel();
        });
        bar.add(autoContinueCheckBox);

        planModeComboBox = new JComboBox<>(new String[]{"普通", "计划模式"});
        planModeComboBox.setToolTipText("计划模式：Agent 先只读调查并写计划，提交 plan_exit 时请求批准");
        planModeComboBox.addActionListener(e -> handlePlanModeChanged());
        bar.add(planModeComboBox);
        bar.addSeparator();

        runHistoryCombo = new JComboBox<>();
        runHistoryCombo.setPreferredSize(new Dimension(240, runHistoryCombo.getPreferredSize().height));
        runHistoryCombo.addActionListener(e -> {
            if (updatingRunHistory) return;
            if (isAnalyzing) {
                refreshRunHistory();
                return;
            }
            Object selected = runHistoryCombo.getSelectedItem();
            if (selected instanceof com.ai.analyzer.core.AnalysisRunStore.Run run) {
                showHistoricalRun(run);
            } else {
                exitHistoryView();
            }
        });
        bar.add(runHistoryCombo);

        JButton clearHistoryButton = new JButton("清空");
        clearHistoryButton.setToolTipText("清空主动分析历史");
        UIStyles.styleSecondary(clearHistoryButton);
        clearHistoryButton.addActionListener(e -> {
            com.ai.analyzer.core.AnalysisRunStore.getInstance().clear();
            refreshRunHistory();
        });
        bar.add(clearHistoryButton);

        return bar;
    }

    private JPanel buildComposer() {
        JPanel panel = new JPanel(new BorderLayout(4, 2));
        panel.setBorder(UIStyles.scrollBorder());

        activeModePromptArea = new JTextArea(2, 60);
        activeModePromptArea.setLineWrap(true);
        activeModePromptArea.setWrapStyleWord(true);
        UIStyles.setFont(activeModePromptArea, 13f, Font.PLAIN);
        applyEditorTheme(activeModePromptArea);
        activeModePromptArea.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendFromComposer();
                }
            }
        });
        promptArea = activeModePromptArea;
        JScrollPane promptScroll = new JScrollPane(activeModePromptArea);
        promptScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        promptScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(promptScroll, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        interjectButton = new JButton("插话");
        interjectButton.setToolTipText("不打断当前分析，向正在运行的 agent 补充一条消息");
        UIStyles.styleSecondary(interjectButton);
        interjectButton.setEnabled(false);
        interjectButton.setToolTipText("不打断当前分析，向正在运行的 agent 补充一条消息（Enter 发送 · Shift+Enter 换行）");
        interjectButton.addActionListener(e -> sendFromComposer());
        right.add(interjectButton);
        tokenLabel = new JLabel(TokenUsageTracker.instance().snapshot().summary());
        UIStyles.setFont(tokenLabel, 10f, Font.PLAIN);
        tokenLabel.setForeground(UIStyles.hintColor());
        right.add(tokenLabel);
        panel.add(right, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * 输入框发送：空闲时开始分析，运行中则把内容作为插话注入当前 agent。
     */
    private void sendFromComposer() {
        String text = activeModePromptArea == null ? "" : activeModePromptArea.getText().trim();
        if (isAnalyzing) {
            if (text.isEmpty()) return;
            if (apiClient != null && apiClient.interject(text)) {
                timeline.addUser(text, "运行中插话");
                activeModePromptArea.setText("");
            } else {
                timeline.addSystem("插话失败：当前没有可插入的运行实例");
            }
            return;
        }
        performAnalysis();
    }

    private void applyEditorTheme(JTextComponent component) {
        component.setBackground(UIManager.getColor("TextArea.background"));
        component.setForeground(UIManager.getColor("TextArea.foreground"));
        component.setCaretColor(UIManager.getColor("TextArea.caretForeground"));
    }

    private void handlePlanModeChanged() {
        if (planModeComboBox == null || apiClient == null) return;
        boolean planMode = planModeComboBox.getSelectedIndex() == 1;
        apiClient.setEnablePlanMode(planMode);
        api.logging().logToOutput(planMode
                ? "已切换到计划模式：Agent 将先写计划并在执行前请求批准"
                : "已切换到普通模式：Agent 直接执行");
    }

    private void setupAgentHITL() {
        apiClient.setRequireConfirmHandler((toolName, summary) -> true);
    }

    // ================= 分析入口 =================

    public void performAnalysis() {
        if (isAnalyzing || apiClient == null) return;

        if (apiClient.isAnalysisInFlight()) {
            timeline.addSystem(AgentApiClient.CONCURRENT_ANALYSIS_MESSAGE);
            return;
        }

        Runnable started = onRunStarted;
        if (started != null) started.run();

        HttpRequestResponse direct = pendingDirectRequest;
        pendingDirectRequest = null;
        if (direct != null) {
            String prompt = promptSourceText();
            String httpContent = HttpFormatter.formatHttpRequestResponse(direct);
            String method = direct.request() != null ? direct.request().method() : "";
            String url = "";
            try {
                try { url = direct.request().url(); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            String targetDesc = method + " " + (url.length() > 100 ? url.substring(0, 100) + "..." : url);
            String detail = "报文 " + httpContent.length() + " 字节";
            updateTargetInfo(targetDesc, detail);
            if (isEmpty(prompt)) prompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
            if (apiClientConfigUpdater != null) apiClientConfigUpdater.run();
            startAnalysisWorker(httpContent, prompt, targetDesc, detail, method, url, true);
            return;
        }

        Object selection = null;
        int viewRow = (!activeModeSelected && dataSource != null) ? dataSource.getSelectedViewRow() : -1;
        if (viewRow >= 0) {
            selection = dataSource.selectionForViewRow(viewRow);
        }
        RequestData requestData = selection instanceof RequestData rd ? rd : null;
        ScanResult scanResult = selection instanceof ScanResult sr ? sr : null;

        String userPrompt = promptSourceText();
        if (userPrompt.isEmpty()) {
            userPrompt = (requestData != null || scanResult != null)
                    ? "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议" : "";
        }

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
        String method = scanResult != null ? scanResult.getMethod()
                : requestData != null ? requestData.getMethod() : null;
        String url = scanResult != null ? scanResult.getUrl()
                : requestData != null ? requestData.getUrl() : null;
        boolean hasRequest = method != null && url != null;
        startAnalysisWorker(httpContent, userPrompt, targetDesc, detail, method, url, hasRequest);
    }

    private void startAnalysisWorker(String httpContent, String userPrompt, String targetDesc,
                                     String detail, String method, String url, boolean hasRequest) {
        isAnalyzing = true;
        lastHttpContent = httpContent == null ? "" : httpContent;
        lastTargetDesc = targetDesc == null ? "" : targetDesc;
        lastDetail = detail == null ? "" : detail;
        lastMethod = method;
        lastUrl = url;
        lastHasRequest = hasRequest;
        if (apiClient != null) {
            apiClient.setNextRunContext(method, url, hasRequest);
            if (nextRunAuto) {
                apiClient.setNextRunIsAutoRound();
            }
            apiClient.clearLastCompletedRun();
        }
        final int runId = ++analysisRunId;
        activeRound++;
        setLoopRound(activeRound);
        setOrganizerPhase("主动分析中（第 " + activeRound + " 轮）");
        notifyStateChanged();

        if (apiClient != null) {
            apiClient.addModelBehaviorConsumer(modelBehaviorConsumer);
        }
        if (activeModePromptArea != null) {
            activeModePromptArea.setText("");
        }

        if (timeline.isShowingHistory()) {
            exitHistoryView();
        }
        timeline.addUser(userPrompt == null ? "" : userPrompt, targetDesc);
        if (httpContent != null && httpContent.length() > HttpFormatter.DEFAULT_MAX_LENGTH) {
            timeline.addSystem("HTTP 内容过长（" + httpContent.length() + " 字符），完整报文已缓存，提示词仅含预览与 fileId");
        }
        currentAssistant = timeline.startAssistant("主动分析 · 第 " + activeRound + " 轮");
        final ChatTimeline.AssistantBlock block = currentAssistant;
        final long startTime = System.currentTimeMillis();

        currentWorker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try {
                    apiClient.analyzeRequestStream(
                            httpContent == null ? "" : httpContent,
                            userPrompt == null ? "" : userPrompt,
                            chunk -> {
                                if (isCancelled() || !isAnalyzing || runId != analysisRunId) return;
                                block.append(chunk);
                            });
                } catch (Exception e) {
                    if (isCancelled() || runId != analysisRunId) return null;
                    SwingUtilities.invokeLater(() -> timeline.addSystem("分析错误: " + e.getMessage()));
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    if (runId != analysisRunId) return;
                    if (!isCancelled()) {
                        block.finish();
                        timeline.finishAssistant();
                        currentAssistant = null;
                        String result = block.text();
                        recordRunAsync(method, url, userPrompt, result);
                        java.util.function.Consumer<AgentApiClient.CompletedRun> cb = onRunCompleted;
                        if (cb != null && apiClient != null) {
                            AgentApiClient.CompletedRun completedRun = apiClient.getLastCompletedRun();
                            if (completedRun != null) {
                                SwingUtilities.invokeLater(() -> cb.accept(completedRun));
                            }
                        }
                    }
                    updateTargetInfo(targetDesc, buildStatusSuffix(detail, startTime));
                } catch (Exception e) {
                    if (!isCancelled() && runId == analysisRunId) {
                        SwingUtilities.invokeLater(() -> timeline.addSystem("分析错误: " + e.getMessage()));
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

    private String buildStatusSuffix(String detail, long startTime) {
        long elapsedMs = System.currentTimeMillis() - startTime;
        String elapsed = elapsedMs < 1000 ? elapsedMs + " ms" : String.format("%.1f s", elapsedMs / 1000.0);
        StringBuilder sb = new StringBuilder();
        if (detail != null && !detail.isEmpty()) sb.append(detail).append(" | ");
        sb.append("耗时 ").append(elapsed);
        if (apiClient != null && apiClient.getModel() != null && !apiClient.getModel().isBlank()) {
            sb.append(" · 模型 ").append(apiClient.getModel());
        }
        sb.append(" · ").append(TokenUsageTracker.instance().snapshot().summary());
        return sb.toString();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 读取用户实际输入的提示词：主动流程（activeModeSelected）读主动输入框，
     * 被动流程（从被动视图点「开始分析」）读被动输入框。两个字段都可能为 null（测试环境）。
     */
    private String promptSourceText() {
        JTextArea src = activeModeSelected ? activeModePromptArea : passivePromptArea;
        if (src == null) src = promptArea;
        return src == null ? "" : src.getText().trim();
    }

    public void analyzeDirectRequest(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) return;
        this.pendingDirectRequest = requestResponse;
        performAnalysis();
    }

    private static String extractHost(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost() != null ? uri.getHost() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ================= 运行控制 =================

    public void stopAnalysis() {
        if (currentWorker != null && !currentWorker.isDone()) {
            if (apiClient != null) {
                apiClient.cancelStreaming();
                apiClient.removeModelBehaviorConsumer(modelBehaviorConsumer);
            }
            analysisRunId++;
            currentWorker.cancel(true);
            isAnalyzing = false;
            if (currentAssistant != null) {
                currentAssistant.finish();
                timeline.finishAssistant();
                currentAssistant = null;
            }
            timeline.addSystem("[输出已中断]");
            notifyStateChanged();
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
        if (currentAssistant != null) {
            currentAssistant.finish();
            currentAssistant = null;
        }
        timeline.clear();
        if (apiClient != null) apiClient.clearContext();
    }

    public void startNewSession() {
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
        if (apiClient != null) {
            apiClient.startNewSession();
        }
        if (currentAssistant != null) {
            currentAssistant.finish();
            currentAssistant = null;
        }
        timeline.clear();
        currentThinking.setLength(0);
        activeRound = 0;
        setLoopRound(0);
        setOrganizerPhase("空闲");
        api.logging().logToOutput("已新开会话");
    }

    // ================= 状态 / 事件 =================

    private void updateTargetInfo(String targetDesc, String detail) {
        SwingUtilities.invokeLater(() -> {
            if (targetLabel != null) {
                targetLabel.setText(targetDesc == null || targetDesc.isEmpty() ? "自由对话模式" : targetDesc);
            }
            if (targetDetailLabel != null) {
                targetDetailLabel.setText(detail == null ? "" : detail);
            }
        });
    }

    public void bindAutoContinue(java.util.function.BooleanSupplier getter,
                                 java.util.function.Consumer<Boolean> setter) {
        this.autoContinueGetter = getter;
        this.autoContinueSetter = setter;
        boolean enabled = getter == null || getter.getAsBoolean();
        if (autoContinueCheckBox != null) autoContinueCheckBox.setSelected(enabled);
        updateLoopPolicyLabel();
    }

    private void updateLoopPolicyLabel() {
        if (loopPolicyLabel != null) {
            loopPolicyLabel.setText("自动继续：" + (autoContinueCheckBox != null && autoContinueCheckBox.isSelected() ? "开" : "关"));
        }
    }

    public void setOrganizerPhase(String phase) {
        SwingUtilities.invokeLater(() -> {
            if (phaseLabel != null && phase != null) phaseLabel.setText("阶段：" + phase);
            if (tokenLabel != null) tokenLabel.setText(TokenUsageTracker.instance().snapshot().summary());
        });
    }

    public void setLoopRound(int round) {
        SwingUtilities.invokeLater(() -> {
            if (roundLabel != null) roundLabel.setText("轮次：" + Math.max(0, round));
        });
    }

    public void appendOrganizerEvent(String text) {
        if (text == null || text.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            timeline.addOrganizerEvent("[" + timestamp + "] " + text.replaceAll("\\s+", " ").trim());
        });
    }

    public void showOrganizerReport(String markdown, java.nio.file.Path file) {
        if (markdown == null || markdown.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            timeline.finishOrganizer();
            timeline.addReport(markdown, file);
            if (tokenLabel != null) tokenLabel.setText(TokenUsageTracker.instance().snapshot().summary());
        });
    }

    /** 刷新历史下拉框（最新在前，默认不选中）。 */
    private void refreshRunHistory() {
        if (runHistoryCombo == null) return;
        updatingRunHistory = true;
        try {
            runHistoryCombo.removeAllItems();
            for (com.ai.analyzer.core.AnalysisRunStore.Run run
                    : com.ai.analyzer.core.AnalysisRunStore.getInstance().list()) {
                runHistoryCombo.addItem(run);
            }
            runHistoryCombo.setSelectedIndex(-1);
        } finally {
            updatingRunHistory = false;
        }
    }

    private void showHistoricalRun(com.ai.analyzer.core.AnalysisRunStore.Run run) {
        if (run == null) return;
        // 历史回看不销毁当前会话流：快照保存，取消选择/新分析时恢复
        timeline.showHistoryMode();
        timeline.replayRun(run.time(), run.method(), run.url(), run.prompt(), run.result());
        timeline.addSystem("（历史回看中 — 选择框清空或开始新分析可返回当前会话）");
        updateTargetInfo("历史记录 · " + run.time(), "");
    }

    /** 离开历史回看：恢复当前会话流。 */
    private void exitHistoryView() {
        if (timeline.isShowingHistory()) {
            timeline.restoreLive();
        }
    }

    private void recordRunAsync(String method, String url, String prompt, String result) {
        if (result == null || result.isBlank()) return;
        Thread t = new Thread(() -> {
            try {
                com.ai.analyzer.core.AnalysisRunStore.getInstance().add(method, url, prompt, result);
            } catch (Exception ignored) {
            }
            SwingUtilities.invokeLater(this::refreshRunHistory);
        }, "analysis-run-save");
        t.setDaemon(true);
        t.start();
    }

    // ================= 模型行为流 =================

    private void handleModelBehavior(String message) {
        if (message == null || message.isEmpty() || currentAssistant == null) return;
        int sep = message.indexOf('|');
        String type = sep >= 0 ? message.substring(0, sep) : "OTHER";
        String detail = sep >= 0 ? message.substring(sep + 1) : message;
        switch (type) {
            case "THINKING" -> {
                if (detail == null || detail.isEmpty()) return;
                currentThinking.append(detail);
                if (currentThinking.length() > MAX_THINKING_CHARS) {
                    currentThinking.delete(0, currentThinking.length() - MAX_THINKING_CHARS);
                }
                long now = System.currentTimeMillis();
                if (now - lastThinkingRenderAt >= THINKING_RENDER_MS) {
                    lastThinkingRenderAt = now;
                    currentAssistant.setThinking(currentThinking.toString());
                }
            }
            case "TOOL_START" -> currentAssistant.addTool(detail, true);
            case "TOOL_END" -> {
                boolean failed = detail.endsWith("|failed");
                String tool = failed ? detail.substring(0, detail.length() - 7) : detail;
                currentAssistant.addTool(tool, false);
            }
            default -> { }
        }
    }

    private void notifyStateChanged() {
        if (interjectButton != null) {
            interjectButton.setEnabled(isAnalyzing);
        }
        if (stateListener != null) {
            stateListener.onAnalysisStateChanged(isAnalyzing);
        }
    }
}
