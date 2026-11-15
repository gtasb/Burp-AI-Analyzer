package com.ai.analyzer.ui;

import com.ai.analyzer.graph.GraphStore;
import com.ai.analyzer.graph.GraphTypes.EdgeType;
import com.ai.analyzer.graph.GraphTypes.Node;
import com.ai.analyzer.graph.GraphTypes.NodeType;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 人类在图谱里手工新增节点：用于把 Burp 之外已验证/怀疑的资产补进图，
 * 并通过「附加笔记」给 Agent 留下下一步牵引。
 */
public class GraphNodeDialog extends JDialog {

    private static final NodeType[] CREATABLE = {
            NodeType.INTERFACE, NodeType.PARAMETER, NodeType.TECHNOLOGY, NodeType.FEATURE,
            NodeType.BACKEND_HYPOTHESIS, NodeType.VULN_CLASS, NodeType.CANDIDATE,
            NodeType.MODULE, NodeType.FUNCTION, NodeType.BUSINESS_ENTITY, NodeType.SERVICE,
            NodeType.RESOURCE, NodeType.EVIDENCE
    };

    private static final EdgeType[] RELATIONS = {
            EdgeType.RELATED_TO, EdgeType.SUGGESTS, EdgeType.HAS_PARAMETER, EdgeType.CALLS,
            EdgeType.INDICATES, EdgeType.SUPPORTS, EdgeType.EXPOSES, EdgeType.BELONGS_TO,
            EdgeType.USES_TECHNOLOGY, EdgeType.SAME_MODULE
    };

    private final String rootId;
    private final JComboBox<NodeType> typeCombo = new JComboBox<>(CREATABLE);
    private final JTextField nameField = new JTextField(32);
    private final JComboBox<NodeChoice> parentCombo = new JComboBox<>();
    private final JComboBox<EdgeType> relationCombo = new JComboBox<>(RELATIONS);
    private final JCheckBox verifiedBox = new JCheckBox("我已验证（计入「已测」）");
    private final JTextArea noteArea = new JTextArea(4, 32);
    private final JLabel hint = new JLabel(" ");
    private Node created;

    public GraphNodeDialog(Window owner, String rootId, Node selected, Map<Long, Node> nodes) {
        super(owner, "新增节点", ModalityType.APPLICATION_MODAL);
        this.rootId = rootId;

        parentCombo.addItem(new NodeChoice(null, "（不挂载，仅挂在站点下）"));
        List<Node> all = new ArrayList<>(nodes.values());
        all.sort(Comparator.comparingLong(Node::id));
        for (Node n : all) {
            parentCombo.addItem(new NodeChoice(n, "#" + n.id() + " " + GraphViewerPanel.typeLabel(n.type())
                    + " " + n.name()));
        }
        if (selected != null) {
            for (int i = 0; i < parentCombo.getItemCount(); i++) {
                NodeChoice choice = parentCombo.getItemAt(i);
                if (choice.node() != null && choice.node().id() == selected.id()) {
                    parentCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        nameField.setToolTipText("接口可填完整 URL，或「POST /api/x」形式");
        verifiedBox.setToolTipText("勾选后记为 CONFIRMED（计入已测）；不勾选记为推测，画布上显示为虚线");
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        UIStyles.setFont(noteArea, 12f, Font.PLAIN);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        addRow(form, c, 0, "类型:", typeCombo);
        addRow(form, c, 1, "名称:", nameField);
        addRow(form, c, 2, "挂到:", parentCombo);
        addRow(form, c, 3, "关系:", relationCombo);
        addRow(form, c, 4, "", verifiedBox);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        form.add(new JScrollPane(noteArea), c);
        c.weighty = 0;
        c.gridy = 6;
        hint.setForeground(UIStyles.hintColor());
        form.add(hint, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton ok = new JButton("创建");
        UIStyles.stylePrimary(ok);
        ok.addActionListener(e -> createNode());
        JButton cancel = new JButton("取消");
        UIStyles.styleSecondary(cancel);
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        buttons.add(ok);

        typeCombo.addActionListener(e -> updateHint());
        updateHint();

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(ok);
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
    }

    private void updateHint() {
        NodeType type = (NodeType) typeCombo.getSelectedItem();
        if (type == NodeType.INTERFACE) {
            hint.setText("接口可填 https://host/api/x，或「POST /api/x」（自动拼到当前站点）");
        } else if (type == NodeType.PARAMETER) {
            hint.setText("参数名，例如 orderId；建议挂到一个接口节点下");
        } else {
            hint.setText("不勾选「我已验证」时按推测处理，画布显示为空心/虚线");
        }
    }

    private void createNode() {
        NodeType type = (NodeType) typeCombo.getSelectedItem();
        NodeChoice parent = (NodeChoice) parentCombo.getSelectedItem();
        try {
            created = GraphStore.getInstance().addHumanNode(rootId, type,
                    nameField.getText(),
                    parent == null || parent.node() == null ? 0 : parent.node().id(),
                    (EdgeType) relationCombo.getSelectedItem(),
                    verifiedBox.isSelected(),
                    noteArea.getText());
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "创建失败: " + ex.getMessage(),
                    "新增节点", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 返回新建的节点；取消时为 null。 */
    public Node createdNode() {
        return created;
    }

    private record NodeChoice(Node node, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
