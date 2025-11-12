package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.model.PluginSettings;
import com.ai.analyzer.model.RequestData;
import com.ai.analyzer.api.QianwenApiClient;
import com.ai.analyzer.utils.HttpSyntaxHighlighter;
import com.ai.analyzer.utils.MarkdownRenderer;
// import com.example.ai.analyzer.Tools.ToolDefinitions;
// import com.example.ai.analyzer.Tools.ToolExecutor;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import burp.api.montoya.http.message.HttpRequestResponse;

public class AIAnalyzerTab extends JPanel {
    private final MontoyaApi api;
    private final QianwenApiClient apiClient;
    
    // UI组件
    private JTextField apiUrlField;
    private JTextField apiKeyField;
    private JTextField modelField;
    private JTable requestListTable;
    private DefaultTableModel requestTableModel;
    private JTextPane requestTextPane;
    private JTextPane responseTextPane;
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

    public AIAnalyzerTab(MontoyaApi api) {
        this.api = api;
        this.apiClient = new QianwenApiClient(
            api,
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            ""
        );
        
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
    }

    private void initializeUI() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 创建主分割面板
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setDividerLocation(400);

        // 左侧面板
        JPanel leftPanel = new JPanel(new BorderLayout());
        
        // 配置面板
        JPanel configPanel = createConfigPanel();
        leftPanel.add(configPanel, BorderLayout.NORTH);
        
        // 请求列表面板
        JPanel requestListPanel = createRequestListPanel();
        leftPanel.add(requestListPanel, BorderLayout.CENTER);
        
        // 右侧面板
        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setDividerLocation(300);

        // 请求面板
        JPanel requestPanel = createRequestPanel();
        rightSplitPane.setTopComponent(requestPanel);

        // 结果面板
        JPanel resultPanel = createResultPanel();
        rightSplitPane.setBottomComponent(resultPanel);

        mainSplitPane.setLeftComponent(leftPanel);
        mainSplitPane.setRightComponent(rightSplitPane);

        add(mainSplitPane, BorderLayout.CENTER);

        // 按钮面板
        JPanel buttonPanel = createButtonPanel();
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel createConfigPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("API配置（通义千问）"));

        // API配置
        JPanel apiConfigPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        apiConfigPanel.add(new JLabel("API URL:"));
        apiUrlField = new JTextField("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", 25);
        apiConfigPanel.add(apiUrlField);

        apiConfigPanel.add(new JLabel("API Key:"));
        apiKeyField = new JTextField("", 20);
        apiConfigPanel.add(apiKeyField);

        apiConfigPanel.add(new JLabel("Model:"));
        modelField = new JTextField("qwen-max", 10);
        apiConfigPanel.add(modelField);

        panel.add(apiConfigPanel, BorderLayout.CENTER);
        
        // 设置按钮
        JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        saveSettingsButton = new JButton("保存设置");
        loadSettingsButton = new JButton("加载设置");
        
        saveSettingsButton.addActionListener(e -> saveSettings());
        loadSettingsButton.addActionListener(e -> loadSettings());
        
        settingsPanel.add(saveSettingsButton);
        settingsPanel.add(loadSettingsButton);
        
        panel.add(settingsPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createRequestListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("请求列表"));

        // 创建表格
        String[] columnNames = {"ID", "方法", "URL", "时间", "有响应"};
        requestTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        requestListTable = new JTable(requestTableModel);
        requestListTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestListTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selectedRow = requestListTable.getSelectedRow();
                if (selectedRow >= 0 && selectedRow < requestList.size()) {
                    RequestData requestData = requestList.get(selectedRow);
                    updateRequestDisplay(requestData);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(requestListTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        // 请求列表按钮
        JPanel requestListButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        deleteRequestButton = new JButton("删除选中");
        clearAllRequestsButton = new JButton("清空所有");
        
        deleteRequestButton.addActionListener(e -> deleteSelectedRequest());
        clearAllRequestsButton.addActionListener(e -> clearAllRequests());

        requestListButtonPanel.add(deleteRequestButton);
        requestListButtonPanel.add(clearAllRequestsButton);
        
        panel.add(requestListButtonPanel, BorderLayout.SOUTH);

        return panel;
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
        requestTextPane = new JTextPane();
        requestTextPane.setEditable(false);
        requestTextPane.setFont(new Font("Consolas", Font.PLAIN, 12));
        requestTextPane.setContentType("text/plain");
        requestTextPane.setBackground(Color.WHITE);
        requestTextPane.setForeground(Color.BLACK);
        JScrollPane requestScrollPane = new JScrollPane(requestTextPane);
        requestPanel.add(requestScrollPane, BorderLayout.CENTER);

        // 右侧：响应区域
        JPanel responsePanel = new JPanel(new BorderLayout());
        responsePanel.setBorder(BorderFactory.createTitledBorder("HTTP响应"));
        responseTextPane = new JTextPane();
        responseTextPane.setEditable(false);
        responseTextPane.setFont(new Font("Consolas", Font.PLAIN, 12));
        responseTextPane.setContentType("text/plain");
        responseTextPane.setBackground(Color.WHITE);
        responseTextPane.setForeground(Color.BLACK);
        JScrollPane responseScrollPane = new JScrollPane(responseTextPane);
        responsePanel.add(responseScrollPane, BorderLayout.CENTER);

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
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));

        analyzeButton = new JButton("开始分析");
        clearButton = new JButton("清空结果");
        stopButton = new JButton("停止");
        
        analyzeButton.addActionListener(e -> performAnalysis());
        clearButton.addActionListener(e -> clearResults());
        stopButton.addActionListener(e -> stopAnalysis());
        
        stopButton.setEnabled(false); // 初始状态禁用

        panel.add(analyzeButton);
        panel.add(clearButton);
        panel.add(stopButton);

        return panel;
    }

    private void updateRequestDisplay(RequestData requestData) {
        HttpSyntaxHighlighter.highlightHttp(requestTextPane, requestData.getRequest());
        if (requestData.getResponse() != null && !requestData.getResponse().trim().isEmpty()) {
            HttpSyntaxHighlighter.highlightHttp(responseTextPane, requestData.getResponse());
        } else {
            responseTextPane.setText("");
        }
    }

    private void refreshRequestTable() {
        requestTableModel.setRowCount(0);
        for (RequestData requestData : requestList) {
            Object[] row = {
                requestData.getId(),
                requestData.getMethod(),
                requestData.getUrl(),
                requestData.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                requestData.hasResponse() ? "是" : "否"
            };
            requestTableModel.addRow(row);
        }
    }

    private void performAnalysis() {
        if (isAnalyzing) {
            return;
        }

        // 允许没有选择请求时也能进行分析（自由对话模式）
        RequestData requestData = null;
        int selectedRow = requestListTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < requestList.size()) {
            requestData = requestList.get(selectedRow);
        }

        String userPrompt = userPromptArea.getText().trim();
        if (userPrompt.isEmpty()) {
            if (requestData != null) {
            userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
            } else {
                userPrompt = "你好，我能为你做什么？";
            }
        }

        // 更新API客户端配置
        apiClient.setApiUrl(apiUrlField.getText().trim());
        apiClient.setApiKey(apiKeyField.getText().trim());
        apiClient.setModel(modelField.getText().trim());
        
        // 如果没有选择请求，提示用户
        if (requestData == null) {
            api.logging().logToOutput("当前没有选择请求，将以自由对话模式进行分析");
        }

        isAnalyzing = true;
        analyzeButton.setEnabled(false);
        analyzeButton.setText("分析中...");
        stopButton.setEnabled(true); // 启用停止按钮
        
        String finalUserPrompt = userPrompt;
        final RequestData finalRequestData = requestData; // 创建final引用供内部类使用
        currentWorker = new SwingWorker<Void, String>() {
            private StringBuilder fullResponse = new StringBuilder();
            private int aiMessageStartPos = 0; // 记录AI消息开始位置（分析开始时resultTextPane被清空，所以从0开始）

            @Override
            protected Void doInBackground() throws Exception {
                try {
                    // 构建HTTP内容（与QianwenApiClient的buildAnalysisContent逻辑一致）
                    StringBuilder httpBuilder = new StringBuilder();
                    if (finalRequestData != null) {
                        httpBuilder.append("=== HTTP请求 ===\n");
                        httpBuilder.append(finalRequestData.getRequest());
                        if (finalRequestData.hasResponse()) {
                            httpBuilder.append("\n\n=== HTTP响应 ===\n");
                            httpBuilder.append(finalRequestData.getResponse());
                        }
                    }
                    String httpContent = httpBuilder.toString();
                    
                    // 在开始分析前清空结果面板
                    SwingUtilities.invokeLater(() -> {
                        resultTextPane.setText("");
                        aiMessageStartPos = 0;
                    });
                    
                    apiClient.analyzeRequestStream(
                        httpContent,
                        finalUserPrompt,
                        chunk -> {
                            // 将chunk添加到缓冲区
                            fullResponse.append(chunk);
                            
                            // 流式输出时，实时进行Markdown解析和渲染
                            // 使用invokeAndWait确保最后一个chunk的渲染完成
                            try {
                                SwingUtilities.invokeAndWait(() -> {
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
            javax.swing.text.StyledDocument doc = resultTextPane.getStyledDocument();
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
            javax.swing.text.StyledDocument doc = resultTextPane.getStyledDocument();
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
            currentWorker.cancel(true);
            isAnalyzing = false;
            stopButton.setEnabled(false);
            analyzeButton.setEnabled(true);
            analyzeButton.setText("开始分析");
            
            // 添加中断提示
            try {
                javax.swing.text.StyledDocument doc = resultTextPane.getStyledDocument();
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
        resultTextPane.setText("");
    }

    private void deleteSelectedRequest() {
        int selectedRow = requestListTable.getSelectedRow();
        if (selectedRow >= 0) {
            requestList.remove(selectedRow);
            refreshRequestTable();
                requestTextPane.setText("");
                responseTextPane.setText("");
            resultTextPane.setText("");
        }
    }

    private void clearAllRequests() {
        int result = JOptionPane.showConfirmDialog(this, "确定要清空所有请求吗？", "确认", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            requestList.clear();
            refreshRequestTable();
            requestTextPane.setText("");
            responseTextPane.setText("");
            resultTextPane.setText("");
        }
    }

    private void saveSettings() {
        try {
            PluginSettings settings = new PluginSettings(
                apiUrlField.getText().trim(),
                apiKeyField.getText().trim(),
                modelField.getText().trim(),
                userPromptArea.getText().trim()
            );

            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(new java.io.FileOutputStream("ai_analyzer_settings.dat"));
            oos.writeObject(settings);
            oos.close();

            //JOptionPane.showMessageDialog(this, "设置已保存", "成功", JOptionPane.INFORMATION_MESSAGE);
            api.logging().logToOutput("设置已保存");
        } catch (Exception e) {
            //JOptionPane.showMessageDialog(this, "保存设置失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            api.logging().logToError("保存设置失败: " + e.getMessage());
        }
    }

    private void loadSettings() {
        try {
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.FileInputStream("ai_analyzer_settings.dat"));
            PluginSettings settings = (PluginSettings) ois.readObject();
            ois.close();

            apiUrlField.setText(settings.getApiUrl());
            apiKeyField.setText(settings.getApiKey());
            modelField.setText(settings.getModel());
            userPromptArea.setText(settings.getUserPrompt());

            //JOptionPane.showMessageDialog(this, "设置已加载", "成功", JOptionPane.INFORMATION_MESSAGE);
            api.logging().logToOutput("设置已加载");
        } catch (Exception e) {
            //JOptionPane.showMessageDialog(this, "加载设置失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            api.logging().logToError("加载设置失败: " + e.getMessage());
        }
    }

    public void addRequestFromHttpRequestResponse(String method, String url, HttpRequestResponse requestResponse) {
        try {
            // 使用UTF-8编码获取请求内容，正确处理中文字符
            byte[] requestBytes = requestResponse.request().toByteArray().getBytes();
            String request = new String(requestBytes, java.nio.charset.StandardCharsets.UTF_8);
            
            String response = null;
            if (requestResponse.response() != null) {
                byte[] responseBytes = requestResponse.response().toByteArray().getBytes();
                response = new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8);
            }

            RequestData requestData = new RequestData(nextRequestId++, method, url, request, response);
            requestList.add(requestData);
            refreshRequestTable();

            // 直接设置到文本区域并应用语法高亮
            HttpSyntaxHighlighter.highlightHttp(requestTextPane, request);
            if (response != null) {
                HttpSyntaxHighlighter.highlightHttp(responseTextPane, response);
            } else {
                responseTextPane.setText("");
            }

            api.logging().logToOutput("请求已添加到AI分析器: " + method + " " + url);
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
    
    /**
     * 处理工具调用
     */
    /* Tools call 相关代码已注释
    private void handleToolCall(QianwenApiClient.ToolCall toolCall) {
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
