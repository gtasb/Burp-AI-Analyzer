package com.ai.analyzer.ui.active;

import com.ai.analyzer.ui.UIStyles;
import com.ai.analyzer.util.MarkdownRenderer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 主动模式的会话时间线：用户消息、主动分析、整理 agent、工具调用都按消息块排列，
 * 类似 Claude Code / Codex 的会话视图。
 */
public class ChatTimeline extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JPanel content = new ScrollablePanel();
    private final JScrollPane scroll;
    private AssistantBlock currentAssistant;
    private OrganizerBlock currentOrganizer;

    public ChatTimeline() {
        super(new BorderLayout());
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(4, 6, 4, 6));
        scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
    }

    private java.util.List<Component> liveSnapshot;
    private boolean showingHistory;

    public void clear() {
        liveSnapshot = null;
        showingHistory = false;
        content.removeAll();
        currentAssistant = null;
        currentOrganizer = null;
        content.revalidate();
        content.repaint();
    }

    /** 进入历史回看模式：保存当前会话流，稍后可 {@link #restoreLive()} 恢复。 */
    public void showHistoryMode() {
        if (showingHistory) return;
        java.util.List<Component> snapshot = new ArrayList<>();
        for (Component c : content.getComponents()) {
            snapshot.add(c);
        }
        liveSnapshot = snapshot;
        showingHistory = true;
        content.removeAll();
        currentAssistant = null;
        currentOrganizer = null;
        content.revalidate();
        content.repaint();
    }

    /** 退出历史回看，恢复被保存的会话流。没有快照时等于 clear。 */
    public void restoreLive() {
        if (!showingHistory) return;
        showingHistory = false;
        java.util.List<Component> snapshot = liveSnapshot;
        liveSnapshot = null;
        content.removeAll();
        if (snapshot != null) {
            for (Component c : snapshot) {
                content.add(c);
            }
        }
        content.revalidate();
        content.repaint();
    }

    public boolean isShowingHistory() {
        return showingHistory;
    }

    public boolean isEmpty() {
        return content.getComponentCount() == 0;
    }

    /** 左侧强调条配色：用户=蓝（主题前景），agent=中性，报告=品牌蓝 */
    private static final Color ACCENT_USER = new Color(0x2a7de1);
    private static final Color ACCENT_AGENT = new Color(0x8a, 0x93, 0xa2);
    private static final Color ACCENT_ORGANIZER = new Color(0x7a, 0x9e, 0x7a);
    private static final Color ACCENT_REPORT = new Color(0x2a7de1);

    public void addUser(String text, String target) {
        Card card = new Card("你", target, ACCENT_USER);
        JTextArea body = new JTextArea(text == null ? "" : text);
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        UIStyles.setFont(body, 12f, Font.PLAIN);
        body.setForeground(UIStyles.textColor());
        card.setBody(body);
        append(card);
    }

    public void addSystem(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        UIStyles.setFont(label, 11f, Font.PLAIN);
        label.setForeground(UIStyles.hintColor());
        label.setBorder(new EmptyBorder(2, 10, 2, 10));
        append(label);
    }

    /** 开始一条主动分析回复；返回流式句柄。 */
    public AssistantBlock startAssistant(String title) {
        Card card = new Card(title, null, ACCENT_AGENT);
        AssistantBlock block = new AssistantBlock(card);
        currentAssistant = block;
        append(card);
        return block;
    }

    public void finishAssistant() {
        currentAssistant = null;
    }

    /** 整理 agent 的事件行；同一轮共用一个整理块。 */
    public void addOrganizerEvent(String text) {
        if (currentOrganizer == null) {
            Card card = new Card("整理 agent", null, ACCENT_ORGANIZER);
            currentOrganizer = new OrganizerBlock(card);
            append(card);
        }
        currentOrganizer.addEvent(text);
    }

    public void finishOrganizer() {
        currentOrganizer = null;
    }

    /** 渲染整理报告（Markdown）。 */
    public void addReport(String markdown, Path file) {
        currentOrganizer = null;
        Card card = new Card("渗透测试报告", file == null ? null : file.getFileName().toString(),
                ACCENT_REPORT);
        JTextPane body = markdownPane();
        try {
            MarkdownRenderer.renderMarkdown(body, markdown == null ? "" : markdown);
        } catch (Exception e) {
            body.setText(markdown == null ? "" : markdown);
        }
        card.setBody(body);
        appendWithGap(card);
    }

    /** 历史记录回放：一次 run 的用户提示 + 结果。 */
    public void replayRun(String time, String method, String url, String prompt, String result) {
        String target = (method == null ? "" : method + " ")
                + (url == null ? "" : url);
        addSystem("历史记录 · " + (time == null ? "" : time));
        addUser(prompt == null ? "" : prompt, target.trim());
        Card card = new Card("主动分析", null, ACCENT_AGENT);
        JTextPane body = markdownPane();
        try {
            MarkdownRenderer.renderMarkdown(body, result == null || result.isBlank() ? "(无输出)" : result);
        } catch (Exception e) {
            body.setText(result == null ? "" : result);
        }
        card.setBody(body);
        append(card);
    }

    // ================= 内部组件 =================

    private void append(JComponent component) {
        appendWithGap(component);
    }

    private void appendWithGap(JComponent component) {
        boolean pinned = isAtBottom();
        if (content.getComponentCount() > 0) {
            content.add(Box.createVerticalStrut(6));
        }
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(component);
        content.revalidate();
        content.repaint();
        if (pinned) {
            SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar()
                    .setValue(scroll.getVerticalScrollBar().getMaximum()));
        }
    }

    private boolean isAtBottom() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
    }

    private static JTextPane markdownPane() {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setContentType("text/plain");
        pane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        return pane;
    }

    /** 左侧强调条颜色：暗色主题下略微提亮，避免深色底上消失。 */
    private static Color themed(Color accent) {
        Color panel = UIManager.getColor("Panel.background");
        if (panel != null) {
            double lum = 0.299 * panel.getRed() + 0.587 * panel.getGreen() + 0.114 * panel.getBlue();
            if (lum < 128) {
                return accent.brighter();
            }
        }
        return accent;
    }

    /** 消息卡片：标题 + 可选副标题 + 正文。细左边条（CC 风格）取代粗边框盒子。 */
    private static final class Card extends JPanel {
        private final JPanel bodyHolder = new JPanel(new BorderLayout());
        private final JLabel status = new JLabel("");

        Card(String title, String subtitle, Color accent) {
            super(new BorderLayout(4, 4));
            setOpaque(false);
            // 左侧 3px 强调条 + 细上分割感的轻量卡片；背景跟随主题（不叠硬编码色块）
            Color line = themed(accent);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, line),
                    new EmptyBorder(4, 8, 4, 6)));

            JPanel header = new JPanel(new BorderLayout(8, 0));
            header.setOpaque(false);
            JLabel titleLabel = new JLabel(title == null ? "" : title);
            UIStyles.setFont(titleLabel, 11f, Font.BOLD);
            titleLabel.setForeground(UIStyles.textColor());
            header.add(titleLabel, BorderLayout.WEST);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            if (subtitle != null && !subtitle.isBlank()) {
                JLabel sub = new JLabel(subtitle);
                UIStyles.setFont(sub, 11f, Font.PLAIN);
                sub.setForeground(UIStyles.hintColor());
                right.add(sub);
            }
            status.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            status.setForeground(UIStyles.hintColor());
            right.add(status);
            JLabel time = new JLabel(LocalTime.now().format(TIME));
            UIStyles.setFont(time, 10f, Font.PLAIN);
            time.setForeground(UIStyles.hintColor());
            right.add(time);
            header.add(right, BorderLayout.EAST);

            add(header, BorderLayout.NORTH);
            bodyHolder.setOpaque(false);
            add(bodyHolder, BorderLayout.CENTER);
        }

        void setBody(JComponent body) {
            bodyHolder.removeAll();
            bodyHolder.add(body, BorderLayout.CENTER);
            bodyHolder.revalidate();
        }

        void setStatus(String text) {
            status.setText(text == null ? "" : text);
        }
    }

    /** 主动分析消息：流式 Markdown + 思考行 + 工具调用行。 */
    public static final class AssistantBlock {

        /**
         * 单次工具调用的行：状态 + 工具名 + 参数摘要 + 结束时间。
         * 全部纯文本（无 ✓/▸ 等符号字形——部分字体缺字渲染成方框），
         * 参数长文换行显示。
         */
        private static final class ToolRow extends JPanel {
            final String toolName;
            private final JLabel status = new JLabel();
            private final JLabel name = new JLabel();
            private final JTextArea argsArea = new JTextArea();
            private final JLabel time = new JLabel();

            ToolRow(String toolName) {
                super(new BorderLayout(4, 0));
                this.toolName = toolName;
                setOpaque(false);
                setBorder(new EmptyBorder(1, 6, 1, 2));

                JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                head.setOpaque(false);
                status.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
                name.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
                UIStyles.setFont(name, 11f, Font.BOLD);
                name.setText(toolName);
                head.add(status);
                head.add(name);

                argsArea.setEditable(false);
                argsArea.setLineWrap(true);
                argsArea.setWrapStyleWord(true);
                argsArea.setOpaque(false);
                UIStyles.setFont(argsArea, 11f, Font.PLAIN);
                argsArea.setForeground(UIStyles.hintColor());
                argsArea.setBorder(new EmptyBorder(0, 2, 0, 0));

                time.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
                time.setForeground(UIStyles.hintColor());

                JPanel top = new JPanel(new BorderLayout(4, 0));
                top.setOpaque(false);
                top.add(head, BorderLayout.WEST);
                top.add(time, BorderLayout.EAST);

                setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
                top.setAlignmentX(Component.LEFT_ALIGNMENT);
                argsArea.setAlignmentX(Component.LEFT_ALIGNMENT);
                add(top);
                add(argsArea);
            }

            void update(String args, boolean running, boolean failed) {
                status.setText(running ? "调用中" : failed ? "失败" : "完成");
                status.setForeground(failed ? UIStyles.DANGER : running ? UIStyles.hintColor() : UIStyles.textColor());
                name.setForeground(failed ? UIStyles.DANGER : UIStyles.textColor());
                if (args != null && !args.isBlank()) {
                    String compact = args.trim();
                    if (compact.length() > 600) compact = compact.substring(0, 599) + "…";
                    argsArea.setText(compact);
                    argsArea.setVisible(true);
                }
                if (!running) {
                    time.setText(LocalTime.now().format(TIME));
                }
                revalidate();
                repaint();
            }
        }

        private final Card card;
        private final JTextPane body = markdownPane();
        private final JLabel thinking = new JLabel(" ");
        private final JPanel tools = new JPanel();
        private final StringBuilder buffer = new StringBuilder();
        private final javax.swing.Timer renderTimer;
        private volatile boolean dirty;
        /** 已写入纯文本流式区的字符数：流式期间只追加增量（O(1)/帧），避免整段重渲染 */
        private int streamedChars;

        AssistantBlock(Card card) {
            this.card = card;
            for (JComponent c : List.of(body, thinking, tools)) {
                c.setOpaque(false);
            }
            UIStyles.setFont(thinking, 11f, Font.PLAIN);
            thinking.setForeground(UIStyles.hintColor());
            thinking.setBorder(new EmptyBorder(2, 0, 2, 0));
            tools.setLayout(new BoxLayout(tools, BoxLayout.Y_AXIS));
            tools.setBorder(new EmptyBorder(2, 0, 0, 0));

            JPanel stack = new JPanel();
            stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
            stack.setOpaque(false);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            thinking.setAlignmentX(Component.LEFT_ALIGNMENT);
            tools.setAlignmentX(Component.LEFT_ALIGNMENT);
            stack.add(body);
            stack.add(thinking);
            stack.add(tools);
            card.setBody(stack);
            card.setStatus("生成中…");

            renderTimer = new javax.swing.Timer(180, e -> {
                if (dirty) {
                    dirty = false;
                    render();
                }
            });
            renderTimer.setRepeats(true);
            renderTimer.start();
        }

        public void append(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            synchronized (buffer) {
                buffer.append(chunk);
            }
            dirty = true;
        }

        /** 已累积的完整文本（用于 run 历史持久化）。 */
        public String text() {
            synchronized (buffer) {
                return buffer.toString();
            }
        }

        public void setThinking(String text) {
            if (text == null) return;
            String compact = text.replaceAll("\\s+", " ").trim();
            if (compact.length() > 160) compact = compact.substring(0, 159) + "…";
            thinking.setText(compact.isEmpty() ? " " : "思考：" + compact);
        }

        /**
         * 工具调用行：纯文本标记（运行中/完成/失败），同一次调用的 START（含参数）
         * 与 END 更新同一行，参数摘要跟在工具名后面，长参数换行截断。
         */
        public void addTool(String name, String args, boolean running) {
            addTool(name, args, running, false);
        }

        public void addTool(String name, String args, boolean running, boolean failed) {
            String tool = name == null || name.isBlank() ? "tool" : name;
            // 同名工具先复用已有行（一次调用的 START/END 同行更新）；
            // 并行多次同名调用时退化为追加新行
            ToolRow row = null;
            for (int i = tools.getComponentCount() - 1; i >= 0; i--) {
                java.awt.Component c = tools.getComponent(i);
                if (c instanceof ToolRow tr && tr.toolName.equals(tool)) {
                    row = tr;
                    break;
                }
            }
            if (row == null) {
                row = new ToolRow(tool);
                tools.add(row);
            }
            row.update(args, running, failed);
            tools.revalidate();
            tools.repaint();
        }

        public void finish() {
            dirty = true;
            renderFinal();
            renderTimer.stop();
            thinking.setText(" ");
            card.setStatus("完成");
        }

        /** 流式帧：只把新增字符以纯文本追加（Markdown 表格/代码块跨片段无法正确解析，流式期不做全量解析）。 */
        private void render() {
            String snapshot;
            synchronized (buffer) {
                snapshot = buffer.toString();
            }
            if (snapshot.length() <= streamedChars) return;
            String delta = snapshot.substring(streamedChars);
            streamedChars = snapshot.length();
            try {
                body.getDocument().insertString(body.getDocument().getLength(), delta, null);
            } catch (Exception ignored) {
            }
            card.setStatus("生成中… " + snapshot.length() + " 字符");
        }

        /** 收尾：整段按 Markdown 重渲染一次（表格/代码块此时才成型）。 */
        private void renderFinal() {
            String snapshot;
            synchronized (buffer) {
                snapshot = buffer.toString();
            }
            if (snapshot.isEmpty()) return;
            try {
                body.setText("");
                streamedChars = 0;
                MarkdownRenderer.renderMarkdown(body, snapshot);
            } catch (Exception ignored) {
            }
        }
    }

    /** 整理 agent 消息：事件行 + 可选报告。 */
    private static final class OrganizerBlock {
        private final Card card;
        private final JPanel lines = new JPanel();
        private int count;

        OrganizerBlock(Card card) {
            this.card = card;
            lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
            lines.setOpaque(false);
            card.setBody(lines);
            card.setStatus("整理中…");
        }

        void addEvent(String text) {
            String timestamp = LocalTime.now().format(TIME);
            JLabel row = new JLabel("[" + timestamp + "] " + (text == null ? "" : text));
            UIStyles.setFont(row, 11f, Font.PLAIN);
            row.setForeground(UIStyles.textColor());
            row.setBorder(new EmptyBorder(1, 2, 1, 2));
            lines.add(row);
            count++;
            if (count > 200) {
                lines.remove(0);
                count--;
            }
            lines.revalidate();
            card.setStatus("整理中…");
        }
    }

    /** 内容面板跟随视口宽度，避免 BoxLayout 下组件不换行。 */
    private static final class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(visibleRect.height - 40, 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
