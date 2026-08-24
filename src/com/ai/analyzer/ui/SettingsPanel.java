package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.core.AgentApiClient;
import com.ai.analyzer.core.PluginSettings;
import com.ai.analyzer.scan.pscan.PassiveScanApiClient;
import com.ai.analyzer.scan.pscan.PassiveScanManager;
import com.ai.analyzer.scan.pscan.PassiveScanTask;
import com.ai.analyzer.agent.skills.Skill;
import com.ai.analyzer.agent.skills.SkillManager;
import com.ai.analyzer.scan.rulesmatch.PreScanFilterManager;
import com.ai.analyzer.ui.active.ActiveAnalysisPanel;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SettingsPanel {
    private final MontoyaApi api;
    private final AgentApiClient apiClient;
    private final PreScanFilterManager preScanFilterManager;
    private PassiveScanManager passiveScanManager;
    private ActiveAnalysisPanel activeAnalysisPanel;
    private final SettingsHost host;

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
    private JTextField ragMcpDocumentsPathField;
    private JTextField workplaceDirectoryField;
    private JCheckBox enableFileSystemAccessCheckBox;
    private JCheckBox enableChromeMcpCheckBox;
    private JTextField chromeMcpUrlField;

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

    private JButton saveSettingsButton;
    private JButton loadSettingsButton;

    public interface SettingsHost {
        ActiveAnalysisPanel getActiveAnalysisPanel();
        void onSettingsApplied();
        void flashStatusMessage(String message);
        void setPromptTextForAllModes(String text);
        String getUserPromptText();
    }

    public SettingsPanel(MontoyaApi api, AgentApiClient apiClient,
            PreScanFilterManager preScanFilterManager,
            PassiveScanManager passiveScanManager,
            ActiveAnalysisPanel activeAnalysisPanel,
            SettingsHost host) {
        this.api = api;
        this.apiClient = apiClient;
        this.preScanFilterManager = preScanFilterManager;
        this.passiveScanManager = passiveScanManager;
        this.activeAnalysisPanel = activeAnalysisPanel;
        this.host = host;
    }

    public void addTabsTo(JTabbedPane tabbedPane) {
        tabbedPane.addTab("配置", createConfigTabPanel());
        tabbedPane.addTab("Cli", createCliTabPanel());
        JPanel skillsPanel = createSkillsTabPanel();
        tabbedPane.addTab("技能", skillsPanel);
    }

    // === Public getters for config values ===

    public String getApiUrl() { return apiUrlField.getText().trim(); }
    public String getApiKey() { return getEffectiveApiKeyFromField(); }
    public String getModel() { return modelField.getText().trim(); }
    public String getCustomParameters() { return customParametersField.getText().trim(); }
    public String getMaxTokens() { return maxTokensField.getText().trim(); }
    public String getTokenBudget() { return tokenBudgetField.getText().trim(); }
    public boolean isSearchEnabled() { return enableSearchCheckBox.isSelected(); }
    public boolean isMcpEnabled() { return enableMcpCheckBox.isSelected(); }
    public String getBurpMcpUrl() { return BurpMcpUrlField.getText().trim(); }
    public String getBurpMcpAuthorization() { return burpMcpAuthorizationField != null ? burpMcpAuthorizationField.getText().trim() : ""; }
    public boolean isRagMcpEnabled() { return enableRagMcpCheckBox.isSelected(); }
    public String getRagMcpDocumentsPath() { return ragMcpDocumentsPathField.getText().trim(); }
    public boolean isFileSystemAccessEnabled() { return enableFileSystemAccessCheckBox.isSelected(); }
    public boolean isChromeMcpEnabled() { return enableChromeMcpCheckBox != null && enableChromeMcpCheckBox.isSelected(); }
    public String getChromeMcpUrl() { return chromeMcpUrlField != null ? chromeMcpUrlField.getText().trim() : ""; }
    public boolean isPreScanEnabled() { return enablePreScanCheckbox != null && enablePreScanCheckbox.isSelected(); }
    public boolean isPythonScriptEnabled() { return enablePythonScriptCheckbox != null && enablePythonScriptCheckbox.isSelected(); }
    public boolean isCliToolEnabled() { return enableCliToolCheckBox != null && enableCliToolCheckBox.isSelected(); }
    public boolean isUnrestrictedCliToolEnabled() { return enableUnrestrictedCliToolCheckBox != null && enableUnrestrictedCliToolCheckBox.isSelected(); }
    public String getCliWhitelist() { return cliWhitelistArea != null ? cliWhitelistArea.getText() : ""; }
    public String getCliToolPrompt() { return cliToolPromptArea != null ? cliToolPromptArea.getText() : ""; }
    public boolean isSkillsEnabled() { return enableSkillsCheckBox != null && enableSkillsCheckBox.isSelected(); }
    public String getSkillsDirectoryPath() { return skillsDirectoryField != null ? skillsDirectoryField.getText().trim() : ""; }
    public SkillManager getSkillManager() { return skillManager; }
    public String getWorkplaceDirectoryPath() { return workplaceDirectoryField != null ? workplaceDirectoryField.getText().trim() : ""; }
    public String getActiveSystemPrompt() { return activeSystemPromptArea != null ? activeSystemPromptArea.getText() : ""; }
    public String getPassiveSystemPrompt() { return passiveSystemPromptArea != null ? passiveSystemPromptArea.getText() : ""; }
    public String getPassiveScanSkipExtensions() { return passiveScanSkipExtensionsArea != null ? passiveScanSkipExtensionsArea.getText() : ""; }
    public String getPassiveScanDomainBlacklist() { return passiveScanDomainBlacklistArea != null ? passiveScanDomainBlacklistArea.getText() : ""; }
    public int getSearchModeIndex() { return searchModeComboBox != null ? searchModeComboBox.getSelectedIndex() : 0; }
    public String getSearchMode() {
        if (searchModeComboBox == null) return "enableSearch";
        return switch (searchModeComboBox.getSelectedIndex()) {
            case 1 -> "tavily";
            case 2 -> "google";
            case 3 -> "duckduckgo";
            default -> "enableSearch";
        };
    }
    public String getTavilyApiKey() { return tavilyApiKeyField != null ? tavilyApiKeyField.getText().trim() : ""; }
    public String getTavilyBaseUrl() { return tavilyBaseUrlField != null ? tavilyBaseUrlField.getText().trim() : ""; }
    public String getGoogleApiKey() { return googleApiKeyField != null ? googleApiKeyField.getText().trim() : ""; }
    public String getGoogleCsi() { return googleCsiField != null ? googleCsiField.getText().trim() : ""; }
    public String getApiProvider() { return (String) apiProviderComboBox.getSelectedItem(); }
    public List<PluginSettings.ApiProfile> getApiProfiles() { return apiProfiles; }
    public boolean isCustomMcpEnabled() { return enableCustomMcpCheckBox != null && enableCustomMcpCheckBox.isSelected(); }
    public String getCustomMcpConfigJson() { return customMcpConfigArea != null ? customMcpConfigArea.getText() : ""; }

    public void setApiKeySecretAndMask(String apiKey) {
        currentApiKeySecret = apiKey != null ? apiKey.trim() : "";
        if (apiKeyField != null) {
            apiKeyField.setText(maskApiKey(currentApiKeySecret));
        }
    }

    public void refreshSkills() {
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

    // === Private helpers ===

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
        skillsTable.getSelectionModel().addListSelectionListener((ListSelectionEvent e) -> {
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
            JOptionPane.showMessageDialog(null, "请先设置 Skills 目录", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File skillDir = new File(dir, "example-scanner");
        if (skillDir.exists()) {
            JOptionPane.showMessageDialog(null, "示例技能目录已存在: " + skillDir.getAbsolutePath(), "提示", JOptionPane.INFORMATION_MESSAGE);
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
            JOptionPane.showMessageDialog(null, "示例技能已创建: " + skillMd.getAbsolutePath(), "成功", JOptionPane.INFORMATION_MESSAGE);
            refreshSkills();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "创建失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
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
        String name = JOptionPane.showInputDialog(null, "配置档案名称:", defaultName);
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
        int result = JOptionPane.showConfirmDialog(null,
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

        // Chrome MCP 配置已隐藏

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
            String json = enabled && customMcpConfigArea != null ? customMcpConfigArea.getText() : "";
            apiClient.setCustomMcpConfigJson(json);
            if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
                passiveScanManager.getApiClient().setCustomMcpConfigJson(json);
            }
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
        validateCustomMcpButton.addActionListener(e -> {
            validateCustomMcpConfig();
            testCustomMcpConnections();
        });
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

    /**
     * 对配置文件中每个已启用且格式合法的自定义 MCP 服务器做实际连通测试
     * （解析配置 → 逐个建立连接并完成 initialize 握手），在后台线程执行避免 UI 卡死。
     * 结果汇总显示在状态/摘要标签上。
     */
    private void testCustomMcpConnections() {
        if (customMcpConfigArea == null) return;
        final String json = customMcpConfigArea.getText();
        final java.util.List<com.ai.analyzer.agent.mcpclient.CustomMcpConfig> configs;
        try {
            configs = com.ai.analyzer.agent.mcpclient.CustomMcpConfigParser.parse(json);
        } catch (Exception e) {
            if (customMcpStatusLabel != null) {
                customMcpStatusLabel.setText("状态：JSON 解析失败，无法测试连通性");
                customMcpStatusLabel.setForeground(Color.RED);
            }
            return;
        }

        final java.util.List<com.ai.analyzer.agent.mcpclient.CustomMcpConfig> targets = new ArrayList<>();
        for (com.ai.analyzer.agent.mcpclient.CustomMcpConfig config : configs) {
            if (config.isEnabled() && config.isValid()) {
                targets.add(config);
            }
        }
        if (targets.isEmpty()) {
            if (customMcpStatusLabel != null) {
                customMcpStatusLabel.setText("状态：没有可测试的已启用配置（请先填写并启用服务器）");
                customMcpStatusLabel.setForeground(Color.RED);
            }
            return;
        }

        if (customMcpStatusLabel != null) {
            customMcpStatusLabel.setText("状态：正在测试 " + targets.size() + " 个服务器连通性...");
            customMcpStatusLabel.setForeground(new Color(255, 140, 0));
        }

        final javax.swing.SwingWorker<Void, String> worker = new javax.swing.SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (com.ai.analyzer.agent.mcpclient.CustomMcpConfig config : targets) {
                    publish(config.getName() + "\u0000" + com.ai.analyzer.agent.mcpclient.AgentScopeMcpManager.testConnection(config));
                }
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                StringBuilder ok = new StringBuilder();
                StringBuilder fail = new StringBuilder();
                int okCount = 0;
                int failCount = 0;
                for (String chunk : chunks) {
                    int sep = chunk.indexOf('\u0000');
                    String name = sep >= 0 ? chunk.substring(0, sep) : "?";
                    String detail = sep >= 0 ? chunk.substring(sep + 1) : chunk;
                    if (detail.isEmpty()) {
                        okCount++;
                        ok.append(name).append("、");
                    } else {
                        failCount++;
                        fail.append(name).append("：").append(detail).append("\n");
                    }
                }
                if (customMcpStatusLabel != null) {
                    if (failCount == 0) {
                        customMcpStatusLabel.setText("状态：连通性测试通过 " + okCount + " 项");
                        customMcpStatusLabel.setForeground(new Color(34, 139, 34));
                    } else if (okCount == 0) {
                        customMcpStatusLabel.setText("状态：全部 " + failCount + " 项连接失败");
                        customMcpStatusLabel.setForeground(Color.RED);
                    } else {
                        customMcpStatusLabel.setText("状态：" + okCount + " 项正常，" + failCount + " 项失败");
                        customMcpStatusLabel.setForeground(new Color(255, 140, 0));
                    }
                }
                if (customMcpSummaryLabel != null) {
                    String text = "";
                    if (okCount > 0) {
                        text += "✓ " + ok.substring(0, ok.length() - 1) + "\n";
                    }
                    if (failCount > 0) {
                        text += "✗ " + fail.substring(0, fail.length() - 1);
                    }
                    customMcpSummaryLabel.setText(text);
                    customMcpSummaryLabel.setToolTipText(text);
                }
            }

            @Override
            protected void done() {
                if (isCancelled()) return;
            }
        };
        worker.execute();
    }

    private boolean confirmSaveWithInvalidMcpConfig() {
        boolean masterSwitchOn = enableCustomMcpCheckBox != null && enableCustomMcpCheckBox.isSelected();
        com.ai.analyzer.util.McpConfigValidator.McpConfigValidationResult result =
                com.ai.analyzer.util.McpConfigValidator.validate(
                        customMcpConfigArea != null ? customMcpConfigArea.getText() : "",
                        masterSwitchOn,
                        UIManager.getColor("Label.disabledForeground"));
        if (result.isSuccess()) return true;
        int choice = JOptionPane.showConfirmDialog(null,
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
        hint.setForeground(Color.GRAY);
        panel.add(hint, gbc);

        return panel;
    }

    public void applyPassiveScanFilters() {
        String extText = passiveScanSkipExtensionsArea.getText().trim();
        String defaultText = PassiveScanTask.getDefaultSkipExtensionsText();
        PassiveScanTask.setCustomSkipExtensions(extText.equals(defaultText) ? null : extText);
        PassiveScanTask.setDomainBlacklist(passiveScanDomainBlacklistArea.getText());
        api.logging().logToOutput("[PassiveScan] 过滤规则已应用");
    }

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

        int result = fileChooser.showOpenDialog(null);
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
            JOptionPane.showMessageDialog(null, "请先设置 Workplace 目录", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File dir = new File(workplace);
        if (!dir.exists() && !dir.mkdirs()) {
            JOptionPane.showMessageDialog(null, "创建目录失败: " + dir.getAbsolutePath(), "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                JOptionPane.showMessageDialog(null, "当前环境不支持打开文件管理器: " + dir.getAbsolutePath(), "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "打开目录失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

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

    private void syncApiConfigToPassiveScan() {
        if (passiveScanManager != null && passiveScanManager.getApiClient() != null) {
            PassiveScanApiClient psClient = passiveScanManager.getApiClient();

            // 基础 API 配置
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

    private void saveSettings() {
        try {
            String effectiveApiKey = getEffectiveApiKeyFromField();
            PluginSettings settings = new PluginSettings(
                apiUrlField.getText().trim(),
                effectiveApiKey,
                modelField.getText().trim(),
                host.getUserPromptText(),
                enableSearchCheckBox.isSelected(),
                enableMcpCheckBox.isSelected(),
                BurpMcpUrlField.getText().trim(),
                enableRagMcpCheckBox.isSelected(),
                "",
                ragMcpDocumentsPathField.getText().trim(),
                false,
                "",
                false,
                ""
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
            ActiveAnalysisPanel activeAnalysisPanel = host.getActiveAnalysisPanel();
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
                    extText.strip().equals(PassiveScanTask.getDefaultSkipExtensionsText().strip()) ? "" : extText);
            }
            if (passiveScanDomainBlacklistArea != null) {
                String blacklist = passiveScanDomainBlacklistArea.getText();
                settings.setPassiveScanDomainBlacklist(
                    blacklist.isBlank() ? PassiveScanTask.getDefaultDomainBlacklistText() : blacklist);
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
            host.flashStatusMessage("设置已保存 ✔");
        } catch (Exception e) {
            host.flashStatusMessage("保存设置失败: " + e.getMessage());
            api.logging().logToError("保存设置失败: " + e.getMessage());
        }
    }

    private void flashStatusMessage(String message) {
        host.flashStatusMessage(message);
    }

    /**
     * 自动加载配置文件（在插件初始化时调用）
     * 优先从当前目录加载，如果不存在则从用户主目录加载
     */
    public void autoLoadSettings() {
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
        com.ai.analyzer.util.TokenUsageTracker.instance().setBudget(settings.getTokenBudgetTokens());
        customParametersField.setText(settings.getCustomParameters());
        apiProfiles.clear();
        apiProfiles.addAll(settings.getApiProfiles());
        refreshApiProfileCombo();
        if (workplaceDirectoryField != null) {
            workplaceDirectoryField.setText(settings.getWorkplaceDirectoryPath());
        }
        ActiveAnalysisPanel activeAnalysisPanel = host.getActiveAnalysisPanel();
        if (activeAnalysisPanel != null) {
            activeAnalysisPanel.setPlanModeSelected(settings.isEnablePlanMode());
        }
        apiClient.setEnablePlanMode(settings.isEnablePlanMode());
        host.setPromptTextForAllModes(settings.getUserPrompt());
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
        ragMcpDocumentsPathField.setText(settings.getRagMcpDocumentsPath());
        ragMcpDocumentsPathField.setEnabled(settings.isEnableRagMcp());

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

        // 更新API客户端配置
        apiClient.setApiProvider(settings.getApiProvider());
        apiClient.setApiUrl(settings.getApiUrl());
        apiClient.setApiKey(settings.getApiKey());
        apiClient.setModel(settings.getModel());
        apiClient.setMaxTokens(settings.getMaxTokens());
        apiClient.setCustomParameters(settings.getCustomParameters());
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
        apiClient.setCustomMcpConfigJson(settings.isEnableCustomMcp() ? settings.getCustomMcpConfigJson() : "");
        apiClient.setEnableFileSystemAccess(settings.isEnableFileSystemAccess());

        apiClient.setWorkplaceDirectoryPath(settings.getWorkplaceDirectoryPath());
        applyWorkplaceToDerivedPaths(true, true);

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
                    ? PassiveScanTask.getDefaultDomainBlacklistText()
                    : savedBlacklist);
        }
        applyPassiveScanFilters();
    }

    private void setPromptTextForAllModes(String text) {
        host.setPromptTextForAllModes(text);
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

            api.logging().logToOutput("设置已加载");
        } catch (Exception e) {
            api.logging().logToError("加载设置失败: " + e.getMessage());
        }
    }

    public void updateApiClientConfigForAnalysis() {
        apiClient.setApiProvider((String) apiProviderComboBox.getSelectedItem());
        apiClient.setApiUrl(apiUrlField.getText().trim());
        apiClient.setApiKey(getEffectiveApiKeyFromField());
        apiClient.setModel(modelField.getText().trim());
        apiClient.setCustomParameters(customParametersField.getText().trim());
        apiClient.setMaxTokens(maxTokensField.getText().trim());
        com.ai.analyzer.util.TokenUsageTracker.instance().setBudget(parseTokenBudget(tokenBudgetField.getText()));
        apiClient.setEnableSearch(enableSearchCheckBox.isSelected());
    }

    public void setPassiveScanManager(PassiveScanManager mgr) {
        this.passiveScanManager = mgr;
    }

    public void setActiveAnalysisPanel(ActiveAnalysisPanel panel) {
        this.activeAnalysisPanel = panel;
    }
}
