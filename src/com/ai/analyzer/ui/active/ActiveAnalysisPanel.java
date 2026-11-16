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
    private JComboBox<com.ai.analyzer.core.SessionStore.Session> sessionCombo;
    private JCheckBox autoContinueCheckBox;
    /** 折叠开关：收起头部工具区，把整屏让给对话时间线（CC 风格） */
    private JToggleButton collapseButton;
    private JPanel headerExpandedPanel;
    private JPanel statusBar;
    private JLabel targetLabel;
    private JLabel targetDetailLabel;
    private JLabel phaseLabel;
    private JLabel roundLabel;
    private JLabel loopPolicyLabel;
    private JLabel tokenLabel;
    private JButton interjectButton;
    private boolean updatingSessionCombo;
    /** 当前会话 id（= AgentApiClient 的 sessionId；null = 尚未开过会话） */
    private volatile String currentSessionId;

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
        refreshSessionCombo();
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

    /** workplace 变更后刷新会话列表（外部入口，保留旧方法名语义）。 */
    public void reloadRunHistory() {
        refreshSessionCombo();
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
        setLayout(new BorderLayout(0, 0));
        setBorder(new EmptyBorder(0, 0, 0, 0));

        // 头部：始终可见一行状态摘要（折叠钮+目标+阶段+轮次+token），
        // 其下是可折叠的控件行（详情/自动继续/模式/历史/清空）。
        // 点击折叠钮收起控件行，纵向空间全部让给对话时间线（CC 风格）。
        add(buildCollapsibleHeader(), BorderLayout.NORTH);

        add(timeline, BorderLayout.CENTER);

        add(buildComposer(), BorderLayout.SOUTH);
    }

    private JComponent buildCollapsibleHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 0));
        header.setOpaque(false);

        statusBar = buildStatusBar();
        header.add(statusBar, BorderLayout.CENTER);

        headerExpandedPanel = buildExpandedPanel();
        header.add(headerExpandedPanel, BorderLayout.SOUTH);
        return header;
    }

    /** 始终可见的一行：折叠钮 + 目标 + 阶段 + 轮次 + token。 */
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(2, 4, 2, 4));

        collapseButton = new JToggleButton("▾");
        collapseButton.setToolTipText("折叠工具区（把整个面板高度让给对话）");
        collapseButton.setFont(collapseButton.getFont().deriveFont(11f));
        collapseButton.setMargin(new Insets(0, 2, 0, 2));
        collapseButton.setFocusable(false);
        collapseButton.addActionListener(e -> {
            boolean collapsed = collapseButton.isSelected();
            collapseButton.setText(collapsed ? "▸" : "▾");
            collapseButton.setToolTipText(collapsed
                    ? "展开工具区（目标详情/自动继续/模式/历史）"
                    : "折叠工具区（把整个面板高度让给对话）");
            headerExpandedPanel.setVisible(!collapsed);
            revalidate();
            repaint();
        });
        bar.add(collapseButton, BorderLayout.WEST);

        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        info.setOpaque(false);
        targetLabel = new JLabel("未选择请求 — 自由对话模式");
        UIStyles.setFont(targetLabel, 12f, Font.BOLD);
        targetLabel.setForeground(new Color(0x2a7de1));
        info.add(targetLabel);
        phaseLabel = new JLabel("阶段：空闲");
        UIStyles.setFont(phaseLabel, 11f, Font.PLAIN);
        phaseLabel.setForeground(UIStyles.hintColor());
        info.add(phaseLabel);
        roundLabel = new JLabel("轮次：0");
        UIStyles.setFont(roundLabel, 11f, Font.PLAIN);
        roundLabel.setForeground(UIStyles.hintColor());
        info.add(roundLabel);
        tokenLabel = new JLabel(TokenUsageTracker.instance().snapshot().summary());
        UIStyles.setFont(tokenLabel, 10f, Font.PLAIN);
        tokenLabel.setForeground(UIStyles.hintColor());
        info.add(tokenLabel);
        bar.add(info, BorderLayout.CENTER);
        return bar;
    }

    /** 可折叠的控件行：目标详情 / 自动继续 / 计划模式 / 会话切换 / 新会话。 */
    private JPanel buildExpandedPanel() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(0, 22, 2, 4));

        targetDetailLabel = new JLabel("");
        UIStyles.setFont(targetDetailLabel, 11f, Font.PLAIN);
        targetDetailLabel.setForeground(UIStyles.hintColor());
        targetDetailLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(targetDetailLabel);
        row.add(Box.createHorizontalStrut(10));

        autoContinueCheckBox = new JCheckBox("自动继续", true);
        autoContinueCheckBox.setToolTipText("每轮结束后由整理 agent 判断是否继续；关闭后只整理入图并出报告");
        autoContinueCheckBox.setFocusable(false);
        autoContinueCheckBox.setAlignmentY(Component.CENTER_ALIGNMENT);
        UIStyles.setFont(autoContinueCheckBox, 11f, Font.PLAIN);
        autoContinueCheckBox.addActionListener(e -> {
            if (autoContinueSetter != null) autoContinueSetter.accept(autoContinueCheckBox.isSelected());
            updateLoopPolicyLabel();
        });
        row.add(autoContinueCheckBox);
        row.add(Box.createHorizontalStrut(6));

        planModeComboBox = new JComboBox<>(new String[]{"普通", "计划"});
        planModeComboBox.setToolTipText("计划模式：Agent 先只读调查并写计划，提交 plan_exit 时请求批准");
        planModeComboBox.setMaximumSize(new Dimension(64, 24));
        planModeComboBox.setPreferredSize(new Dimension(64, 24));
        planModeComboBox.setAlignmentY(Component.CENTER_ALIGNMENT);
        planModeComboBox.addActionListener(e -> handlePlanModeChanged());
        row.add(planModeComboBox);
        row.add(Box.createHorizontalStrut(6));

        sessionCombo = new JComboBox<>();
        sessionCombo.setMaximumSize(new Dimension(260, 24));
        sessionCombo.setPreferredSize(new Dimension(260, 24));
        sessionCombo.setAlignmentY(Component.CENTER_ALIGNMENT);
        sessionCombo.setToolTipText("会话列表（最新在前）：切换到任意历史会话继续聊，上下文保留");
        sessionCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                java.awt.Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof com.ai.analyzer.core.SessionStore.Session s) {
                    // 下拉条目换行显示：标题一行，时间+轮次一行
                    setToolTipText(s.title() + "\n" + s.createdTime() + " · " + s.turnCount() + " 轮");
                }
                return c;
            }
        });
        sessionCombo.addActionListener(e -> {
            if (updatingSessionCombo) return;
            if (isAnalyzing) {
                // 运行中拒绝切换并弹回当前选择
                refreshSessionCombo();
                timeline.addSystem("分析进行中，暂不能切换会话");
                return;
            }
            Object selected = sessionCombo.getSelectedItem();
            if (selected instanceof com.ai.analyzer.core.SessionStore.Session s) {
                switchToSession(s);
            }
        });
        row.add(sessionCombo);
        row.add(Box.createHorizontalStrut(6));

        JButton newSessionButton = new JButton("＋ 会话");
        newSessionButton.setToolTipText("新开一个会话（当前会话保留在列表中，可随时切回）");
        newSessionButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        UIStyles.styleSecondary(newSessionButton);
        newSessionButton.addActionListener(e -> startNewSession());
        row.add(newSessionButton);
        row.add(Box.createHorizontalStrut(6));

        JButton deleteSessionButton = new JButton("删除");
        deleteSessionButton.setToolTipText("删除当前选中的会话（不可恢复）");
        deleteSessionButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        UIStyles.styleSecondary(deleteSessionButton);
        deleteSessionButton.addActionListener(e -> deleteCurrentSession());
        row.add(deleteSessionButton);
        row.add(Box.createHorizontalGlue());
        return row;
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
        right.setOpaque(false);
        interjectButton = new JButton("插话");
        interjectButton.setToolTipText("不打断当前分析，向正在运行的 agent 补充一条消息（Enter 发送 · Shift+Enter 换行）");
        UIStyles.styleSecondary(interjectButton);
        interjectButton.setEnabled(false);
        interjectButton.addActionListener(e -> sendFromComposer());
        right.add(interjectButton);
        panel.add(right, BorderLayout.EAST);
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
                        // 整理 agent 只处理真实渗透测试轮（有绑定请求）；自由对话/
                        // 提问轮交给它只会触发与用户意图无关的全站侦察，纯属浪费 token。
                        // hasRequest == false 的轮不交接，整理循环直接跳过。
                        if (cb != null && apiClient != null && hasRequest) {
                            AgentApiClient.CompletedRun completedRun = apiClient.getLastCompletedRun();
                            if (completedRun != null) {
                                SwingUtilities.invokeLater(() -> cb.accept(completedRun));
                            } else if (nextRunAuto) {
                                // 自动继续轮必然 hasRequest；拿不到交接说明本轮被取消/出错，
                                // 明确收掉整理循环，避免挂着「整理中」假状态
                                SwingUtilities.invokeLater(() -> timeline.addSystem(
                                        "本轮未产生交接信息，自动继续已终止"));
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

    /** 刷新会话下拉框（最新在前；选中当前会话）。 */
    private void refreshSessionCombo() {
        if (sessionCombo == null) return;
        updatingSessionCombo = true;
        try {
            sessionCombo.removeAllItems();
            String current = currentSessionId;
            com.ai.analyzer.core.SessionStore.Session toSelect = null;
            for (com.ai.analyzer.core.SessionStore.Session s
                    : com.ai.analyzer.core.SessionStore.getInstance().list()) {
                sessionCombo.addItem(s);
                if (current != null && s.id().equals(current)) toSelect = s;
            }
            sessionCombo.setSelectedItem(toSelect);
        } finally {
            updatingSessionCombo = false;
        }
    }

    /** 切到历史会话：重放该会话的轮次到时间线，并把 agent 切回同一 sessionId 续聊。 */
    private void switchToSession(com.ai.analyzer.core.SessionStore.Session s) {
        if (s == null || apiClient == null) return;
        if (s.id().equals(currentSessionId)) return;
        if (!apiClient.switchSession(s.id())) {
            refreshSessionCombo();
            return;
        }
        currentSessionId = s.id();
        timeline.clear();
        currentThinking.setLength(0);
        activeRound = 0;
        setLoopRound(0);
        setOrganizerPhase("会话 · " + s.title());
        com.ai.analyzer.core.SessionStore.SessionDetail detail =
                com.ai.analyzer.core.SessionStore.getInstance().detail(s.id());
        if (detail != null && !detail.turns().isEmpty()) {
            timeline.addSystem("已切换会话（上下文保留，可继续对话）");
            for (com.ai.analyzer.core.SessionStore.Turn t : detail.turns()) {
                timeline.replayRun(t.time() > 0
                        ? java.time.LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(t.time()),
                                java.time.ZoneId.systemDefault()).format(
                                java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        : "", t.method(), t.url(), t.prompt(), t.result());
            }
            activeRound = detail.turns().size();
            setLoopRound(activeRound);
        } else {
            timeline.addSystem("已切换到空会话（" + s.title() + "）");
        }
        if (api != null) api.logging().logToOutput("[Session] 已切换会话: " + s.title());
        notifyStateChanged();
    }

    /** 新会话：不再销毁旧会话（保留在下拉里可切回），只换 sessionId + 清空时间线。 */
    public void startNewSession() {
        if (isAnalyzing) {
            timeline.addSystem("分析进行中，先停止再新开会话");
            return;
        }
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
        String title = nextSessionTitle();
        currentSessionId = apiClient != null
                ? apiClient.beginTrackedSession(title) : "untracked-" + java.util.UUID.randomUUID();
        if (apiClient != null) {
            // beginTrackedSession 只换 id；重置运行时状态（挂起确认/工具缓冲），
            // 但不清长期记忆目录——上下文随 sessionId 天然隔离
            apiClient.startNewSession();
            // startNewSession 又生成了新 id，覆盖回登记过的那个
            apiClient.switchSession(currentSessionId);
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
        refreshSessionCombo();
        if (api != null) api.logging().logToOutput("已新开会话（旧会话保留，可从下拉切回）");
        notifyStateChanged();
    }

    private String nextSessionTitle() {
        String target = lastTargetDesc == null ? "" : lastTargetDesc.trim();
        if (target.isEmpty() || "自由对话模式".equals(target)) {
            return "自由对话 " + java.time.LocalTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        return target.length() > 40 ? target.substring(0, 39) + "…" : target;
    }

    /** 删除当前选中会话（下拉里选中的那个，不一定是正在聊的）。 */
    private void deleteCurrentSession() {
        Object selected = sessionCombo == null ? null : sessionCombo.getSelectedItem();
        if (!(selected instanceof com.ai.analyzer.core.SessionStore.Session s)) {
            timeline.addSystem("先在下拉里选中要删除的会话");
            return;
        }
        boolean isCurrent = s.id().equals(currentSessionId);
        if (isCurrent && isAnalyzing) {
            timeline.addSystem("正在分析当前会话，先停止再删除");
            return;
        }
        int confirm = javax.swing.JOptionPane.showConfirmDialog(this,
                "删除会话「" + s.title() + "」？不可恢复。", "确认删除",
                javax.swing.JOptionPane.YES_NO_OPTION);
        if (confirm != javax.swing.JOptionPane.YES_OPTION) return;
        com.ai.analyzer.core.SessionStore.getInstance().deleteSession(s.id());
        if (isCurrent) {
            // 删的是当前会话：顺延到新会话
            if (apiClient != null) {
                apiClient.startNewSession();
                currentSessionId = apiClient.getCurrentSessionId();
            }
            timeline.clear();
            activeRound = 0;
            setLoopRound(0);
            setOrganizerPhase("空闲");
        }
        refreshSessionCombo();
    }

    private void recordRunAsync(String method, String url, String prompt, String result) {
        if (result == null || result.isBlank()) return;
        // 会话归属：没开过会话（插件启动后直接分析）时按 target 建一个
        final String sessionId = ensureSessionForRun(method, url);
        Thread t = new Thread(() -> {
            try {
                com.ai.analyzer.core.SessionStore.getInstance().addTurn(sessionId,
                        sessionTitle(method, url), method, url, prompt, result);
            } catch (Exception ignored) {
            }
            SwingUtilities.invokeLater(this::refreshSessionCombo);
        }, "analysis-run-save");
        t.setDaemon(true);
        t.start();
    }

    /** 确保当前轮落在某个会话里：有 sessionId 用之；没有则新建并同步给 agent。 */
    private String ensureSessionForRun(String method, String url) {
        String sid = currentSessionId;
        if (sid != null && !sid.isBlank()) return sid;
        String title = sessionTitle(method, url);
        sid = apiClient != null
                ? apiClient.beginTrackedSession(title)
                : "untracked-" + java.util.UUID.randomUUID();
        currentSessionId = sid;
        return sid;
    }

    private String sessionTitle(String method, String url) {
        String target = (method == null ? "" : method + " ") + (url == null ? "" : url);
        if (target.isBlank()) {
            return "自由对话 " + java.time.LocalTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        return target.length() > 40 ? target.substring(0, 39) + "…" : target;
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
