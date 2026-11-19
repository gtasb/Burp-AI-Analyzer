package com.ai.analyzer.ui;

import com.ai.analyzer.graph.GraphDatabase;
import com.ai.analyzer.graph.GraphStore;
import com.ai.analyzer.graph.GraphTypes.Edge;
import com.ai.analyzer.graph.GraphTypes.EdgeType;
import com.ai.analyzer.graph.GraphTypes.FactStatus;
import com.ai.analyzer.graph.GraphTypes.Node;
import com.ai.analyzer.graph.GraphTypes.NodeType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 资产图谱右侧栏：上半部分是选中节点的详情（含 Agent 的「是否值得继续」评估），
 * 下半部分是站点/节点笔记，人类可以直接增删改，Agent 通过 graph_* 工具读取同一份数据。
 *
 * <p>视觉约束：只使用 Burp 原生控件配色，不引入红/蓝强调色；笔记区用一张表承载
 * （而不是堆叠的列表 + 大编辑框 + 提示条），减少空白与割裂感。
 */
public class GraphSidebarPanel extends JPanel {

    private final Runnable onGraphChanged;

    // 节点详情用纯文本布局而不是 JEditorPane/HTML：Swing 的 HTML 引擎字体偏粗偏大，
    // 且每次选中节点都重建整个文档树，是拖拽/点选卡顿的主要来源之一
    private final JTextArea detailArea = new JTextArea();
    private final DefaultListModel<NoteItem> noteModel = new DefaultListModel<>();
    private final JList<NoteItem> noteList = new JList<>(noteModel);
    private final JTextArea noteEditor = new JTextArea(4, 30);
    private final JLabel noteStatus = new JLabel(" ");
    private final JLabel noteScope = new JLabel(" ");
    private final JCheckBox onlyCurrent = new JCheckBox("仅当前节点");
    private final JButton saveButton = new JButton("保存");
    private final JButton deleteButton = new JButton("删除");
    private final JButton newButton = new JButton("新增笔记");
    private final CardLayout listCards = new CardLayout();
    private final JPanel listCard = new JPanel(listCards);

    private GraphViewerPanel.GraphData data;
    private Node currentNode;
    private Long editingNoteId;
    private boolean loadingSelection;

    public GraphSidebarPanel(Runnable onGraphChanged) {
        this.onGraphChanged = onGraphChanged != null ? onGraphChanged : () -> {
        };
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(0, 8, 0, 0));
        setMinimumSize(new Dimension(300, 320));

        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setOpaque(false);
        UIStyles.setFont(detailArea, 12f, Font.PLAIN);
        detailArea.setForeground(UIStyles.textColor());
        detailArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.setBorder(UIStyles.scrollBorder());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled("节点详情", detailScroll), buildNotesPanel());
        split.setResizeWeight(0.35);
        split.setDividerSize(6);
        split.setContinuousLayout(true);
        split.setBorder(new EmptyBorder(0, 0, 0, 0));
        split.setDividerLocation(0.35);
        add(split, BorderLayout.CENTER);

        renderDetail(null);
    }

    private static JPanel titled(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(new EmptyBorder(0, 0, 4, 0));
        JLabel label = new JLabel(title);
        UIStyles.setFont(label, 12f, Font.BOLD);
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildNotesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(new EmptyBorder(4, 0, 0, 0));

        // 表头一行：标题 + 过滤 + 新增
        JPanel header = new JPanel(new BorderLayout(6, 0));
        JLabel title = new JLabel("笔记");
        UIStyles.setFont(title, 12f, Font.BOLD);
        header.add(title, BorderLayout.WEST);
        onlyCurrent.setSelected(false);
        onlyCurrent.setFocusable(false);
        UIStyles.setFont(onlyCurrent, 11f, Font.PLAIN);
        onlyCurrent.addActionListener(e -> refreshNoteList());
        UIStyles.styleSecondary(newButton);
        newButton.setMargin(new Insets(2, 10, 2, 10));
        newButton.addActionListener(e -> startNewNote());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(onlyCurrent);
        right.add(newButton);
        header.add(right, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        // 笔记列表 + 编辑器在一个圆角框里，避免“空白大块”
        noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        noteList.setFixedCellHeight(24);
        UIStyles.setFont(noteList, 11f, Font.PLAIN);
        noteList.setBorder(new EmptyBorder(2, 6, 2, 6));
        // 列表项只显示单行摘要：长笔记正文在下方编辑框里编辑，避免条目被撑成多行
        noteList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String text = value == null ? "" : value.toString();
                setText(text.length() > 80 ? text.substring(0, 79) + "…" : text);
                setBorder(new EmptyBorder(0, 4, 0, 4));
                return this;
            }
        });
        noteList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || loadingSelection) return;
            NoteItem item = noteList.getSelectedValue();
            if (item != null) {
                editingNoteId = item.node().id();
                noteEditor.setText(String.valueOf(item.node().prop("content")));
                noteEditor.setCaretPosition(0);
                deleteButton.setEnabled(true);
                updateHint();
            }
        });

        noteEditor.setLineWrap(true);
        noteEditor.setWrapStyleWord(true);
        noteEditor.setRows(3);
        noteEditor.setBorder(new EmptyBorder(6, 6, 6, 6));
        UIStyles.setFont(noteEditor, 12f, Font.PLAIN);
        JScrollPane editorScroll = new JScrollPane(noteEditor);
        editorScroll.setBorder(null);
        editorScroll.setPreferredSize(new Dimension(10, 100));

        JScrollPane listScroll = new JScrollPane(noteList);
        listScroll.setBorder(null);
        listScroll.setViewportBorder(null);

        // 列表与编辑框之间用可拖动分隔条：编辑长笔记时可以自由放大输入区
        listCard.add(listScroll, "list");
        JLabel emptyLabel = new JLabel("暂无笔记", SwingConstants.CENTER);
        emptyLabel.setForeground(UIStyles.hintColor());
        UIStyles.setFont(emptyLabel, 12f, Font.PLAIN);
        listCard.add(emptyLabel, "empty");

        JSplitPane notesSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listCard, editorScroll);
        notesSplit.setResizeWeight(0.55);
        notesSplit.setDividerSize(6);
        notesSplit.setContinuousLayout(true);
        notesSplit.setBorder(UIStyles.scrollBorder());
        notesSplit.setDividerLocation(0.55);
        panel.add(notesSplit, BorderLayout.CENTER);

        // 底行：左侧作用域/状态，右侧保存/删除（原生按钮配色）
        UIStyles.styleSecondary(saveButton);
        saveButton.addActionListener(e -> saveNote());
        UIStyles.styleSecondary(deleteButton);
        deleteButton.addActionListener(e -> deleteNote());
        deleteButton.setEnabled(false);

        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.setBorder(new EmptyBorder(2, 2, 0, 2));
        noteScope.setForeground(UIStyles.hintColor());
        UIStyles.setFont(noteScope, 11f, Font.PLAIN);
        noteStatus.setForeground(UIStyles.hintColor());
        UIStyles.setFont(noteStatus, 11f, Font.PLAIN);
        JPanel scopeWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scopeWrap.add(noteScope);
        scopeWrap.add(noteStatus);
        bottom.add(scopeWrap, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(saveButton);
        buttons.add(deleteButton);
        bottom.add(buttons, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    // ================= 数据入口 =================

    void setData(GraphViewerPanel.GraphData data) {
        this.data = data;
        boolean stillPresent = currentNode != null && data != null
                && data.nodes().stream().anyMatch(n -> n.id() == currentNode.id());
        if (!stillPresent) {
            currentNode = null;
        }
        refreshNoteList();
        renderDetail(currentNode);
    }

    void showNode(Node node) {
        this.currentNode = node;
        renderDetail(node);
        refreshNoteList();
        deleteButton.setEnabled(false);
    }

    /** 当前选中节点 id（刷新图谱后用于恢复选中），无选中时为 0。 */
    long selectedNodeId() {
        return currentNode == null ? 0 : currentNode.id();
    }

    /** 画布选中变化时同步（供仅当前节点过滤使用）。 */
    void setCurrentNode(Node node) {
        this.currentNode = node;
    }

    private void refreshNoteList() {
        loadingSelection = true;
        try {
            noteModel.clear();
            editingNoteId = null;
            noteEditor.setText("");
            deleteButton.setEnabled(false);
            if (data != null) {
                for (Node n : data.notes()) {
                    if (onlyCurrent.isSelected() && currentNode != null && noteTarget(n) != currentNode.id()) {
                        continue;
                    }
                    noteModel.addElement(new NoteItem(n));
                }
            }
        } finally {
            loadingSelection = false;
        }
        listCards.show(listCard, noteModel.isEmpty() ? "empty" : "list");
        updateHint();
    }

    private static long noteTarget(Node n) {
        Object t = n.prop("target");
        return t instanceof Number num ? (long) num.doubleValue() : 0;
    }

    private void updateHint() {
        if (data == null) {
            noteScope.setText(" ");
            return;
        }
        if (editingNoteId != null) {
            noteScope.setText("编辑 v" + editingVersion());
        } else if (currentNode != null) {
            noteScope.setText("挂到: " + GraphViewerPanel.typeLabel(currentNode.type()) + " " + currentNode.name());
        } else {
            noteScope.setText("站点笔记");
        }
    }

    private String editingVersion() {
        if (data == null || editingNoteId == null) return "?";
        for (Node n : data.notes()) {
            if (n.id() == editingNoteId) return String.valueOf(GraphDatabase.noteVersion(n));
        }
        return "?";
    }

    // ================= 写操作 =================

    private void startNewNote() {
        editingNoteId = null;
        noteList.clearSelection();
        noteEditor.setText("");
        deleteButton.setEnabled(false);
        noteStatus.setText(" ");
        noteEditor.requestFocusInWindow();
        updateHint();
    }

    private void saveNote() {
        if (data == null || data.rootId().isBlank()) {
            noteStatus.setText("请先选择站点");
            return;
        }
        String content = noteEditor.getText() == null ? "" : noteEditor.getText().trim();
        if (content.isEmpty()) {
            noteStatus.setText("内容为空");
            return;
        }
        try {
            if (editingNoteId != null) {
                GraphStore.getInstance().updateNote(editingNoteId, content, "human");
                noteStatus.setText("已保存");
            } else {
                Long target = currentNode == null ? null : currentNode.id();
                GraphStore.NoteResult r = GraphStore.getInstance()
                        .addNote(data.rootId(), target, content, "note", "human", "human");
                noteStatus.setText(r.duplicate() ? "内容与已有笔记重复，未重复写入" : "已新增");
            }
            onGraphChanged.run();
        } catch (Exception e) {
            noteStatus.setText("保存失败: " + e.getMessage());
        }
    }

    private void deleteNote() {
        if (editingNoteId == null) return;
        int choice = JOptionPane.showConfirmDialog(this, "删除这条笔记？该操作不可恢复。",
                "删除笔记", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        try {
            GraphStore.getInstance().deleteNote(editingNoteId);
            editingNoteId = null;
            noteStatus.setText("已删除");
            onGraphChanged.run();
        } catch (Exception e) {
            noteStatus.setText("删除失败: " + e.getMessage());
        }
    }

    // ================= 节点详情 =================

    private void renderDetail(Node n) {
        StringBuilder sb = new StringBuilder();
        if (n == null) {
            sb.append("点击画布上的节点查看详情；\n虚线圈「…」表示被折叠的同类节点，点击可展开。");
            detailArea.setText(sb.toString());
            detailArea.setCaretPosition(0);
            return;
        }

        Object snippet = n.prop("snippet");
        String full = snippet == null ? "" : String.valueOf(snippet);
        if (!full.isBlank()) {
            // name 是建节点时截断到 60 字符的摘要（"证据: <前缀>…"），snippet 是全文：
            // 有全文时第一行只打类型标签，不再重复截断版；name 即全文时才回显
            if (full.equals(n.name())) {
                sb.append(GraphViewerPanel.typeLabel(n.type())).append(" · ").append(n.name()).append('\n');
            } else {
                sb.append(GraphViewerPanel.typeLabel(n.type())).append('\n')
                  .append(full).append('\n');
            }
        } else {
            sb.append(GraphViewerPanel.typeLabel(n.type())).append(" · ").append(n.name()).append('\n');
        }
        sb.append('#').append(n.id()).append(" · ").append(n.status());
        if (n.status() != FactStatus.OBSERVED) {
            sb.append(String.format(java.util.Locale.ROOT, " · 置信 %.2f", n.confidence()));
        }
        sb.append(" · 来源 ").append(n.sourceType()).append('\n');

        // （全文已在上方显示：证据类节点 name 截断、snippet 是全文，不重复输出）

        String assess = GraphViewerPanel.assessmentText(n);
        sb.append('\n');
        if (assess.isEmpty()) {
            sb.append("Agent 尚未评估该节点是否值得继续。");
        } else {
            sb.append("Agent 判断：").append(assess);
        }

        Map<NodeType, Integer> counts = neighborCounts(n);
        if (!counts.isEmpty()) {
            sb.append("\n\n关联：");
            counts.entrySet().stream()
                    .sorted(Map.Entry.<NodeType, Integer>comparingByValue().reversed())
                    .forEach(e -> sb.append(GraphViewerPanel.typeLabel(e.getKey()))
                            .append(' ').append(e.getValue()).append("  "));
        }

        java.util.List<String> params = neighborNames(n, NodeType.PARAMETER, 12);
        if (!params.isEmpty()) {
            sb.append("\n参数：").append(String.join("、", params));
        }

        String fact = latestFactSummary(n);
        if (!fact.isEmpty()) {
            sb.append("\n最近分析：").append(fact);
        }

        int noteCount = 0;
        if (data != null) {
            for (Node note : data.notes()) {
                if (noteTarget(note) == n.id()) noteCount++;
            }
        }
        sb.append('\n').append(noteCount == 0 ? "该节点暂无笔记" : "该节点有 " + noteCount + " 条笔记（见下方）");

        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private Map<NodeType, Integer> neighborCounts(Node n) {
        Map<NodeType, Integer> counts = new EnumMap<>(NodeType.class);
        if (data == null) return counts;
        Map<Long, Node> byId = new HashMap<>();
        for (Node x : data.nodes()) byId.put(x.id(), x);
        for (Edge e : data.edges()) {
            long other = e.sourceNodeId() == n.id() ? e.targetNodeId()
                    : e.targetNodeId() == n.id() ? e.sourceNodeId() : -1;
            if (other < 0) continue;
            Node o = byId.get(other);
            if (o == null || o.type() == NodeType.NOTE) continue;
            counts.merge(o.type(), 1, Integer::sum);
        }
        return counts;
    }

    private java.util.List<String> neighborNames(Node n, NodeType type, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (data == null) return out;
        Map<Long, Node> byId = new HashMap<>();
        for (Node x : data.nodes()) byId.put(x.id(), x);
        for (Edge e : data.edges()) {
            long other = e.sourceNodeId() == n.id() ? e.targetNodeId()
                    : e.targetNodeId() == n.id() ? e.sourceNodeId() : -1;
            if (other < 0) continue;
            Node o = byId.get(other);
            if (o == null || o.type() != type) continue;
            Object location = o.prop("location");
            String label = location == null ? o.name() : o.name() + "(" + location + ")";
            if (!out.contains(label)) out.add(label);
            if (out.size() >= max) break;
        }
        return out;
    }

    private String latestFactSummary(Node n) {
        if (data == null) return "";
        Map<Long, Node> byId = new HashMap<>();
        for (Node x : data.nodes()) byId.put(x.id(), x);
        Node latest = null;
        for (Edge e : data.edges()) {
            if (e.relationType() != EdgeType.COVERS || e.targetNodeId() != n.id()) continue;
            Node fact = byId.get(e.sourceNodeId());
            if (fact != null && (latest == null || fact.id() > latest.id())) {
                latest = fact;
            }
        }
        if (latest == null) return "";
        Object summary = latest.prop("summary");
        return summary == null ? "" : String.valueOf(summary);
    }

    private record NoteItem(Node node) {
        @Override
        public String toString() {
            Object kind = node.prop("kind");
            Object author = node.prop("author");
            return "[v" + GraphDatabase.noteVersion(node) + " · " + kind + " · " + author + "] " + node.name();
        }
    }
}
