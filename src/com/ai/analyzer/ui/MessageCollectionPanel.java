package com.ai.analyzer.ui;

import burp.api.montoya.http.message.HttpRequestResponse;
import com.ai.analyzer.util.MessageCollectionStore;
import com.ai.analyzer.util.MessageCollectionStore.StoreEntry;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 侧栏「待分析报文」收藏列表面板。
 * 数据来自共享的 {@link MessageCollectionStore}（多个侧栏协同）。
 * 支持：收藏当前报文、勾选多条批量发送给 AI、移除/清空、双击切换显示该报文。
 */
public class MessageCollectionPanel extends JPanel {

    private final ChatPanel chatPanel;
    private final Supplier<HttpRequestResponse> currentSupplier;
    private final Consumer<HttpRequestResponse> displayConsumer;
    private final JTable table;
    private final MessageTableModel tableModel;
    private final JButton addButton;
    private final JButton sendSelectedButton;
    private final JButton removeSelectedButton;
    private final JButton clearButton;
    private final JLabel statusLabel;
    private final Runnable storeListener;

    public MessageCollectionPanel(ChatPanel chatPanel,
                                  Supplier<HttpRequestResponse> currentSupplier,
                                  Consumer<HttpRequestResponse> displayConsumer) {
        this.chatPanel = chatPanel;
        this.currentSupplier = currentSupplier;
        this.displayConsumer = displayConsumer;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("待分析报文"));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        addButton = new JButton("＋收藏当前");
        sendSelectedButton = new JButton("发送选中→AI");
        removeSelectedButton = new JButton("移除选中");
        clearButton = new JButton("清空");
        buttonRow.add(addButton);
        buttonRow.add(sendSelectedButton);
        buttonRow.add(removeSelectedButton);
        buttonRow.add(clearButton);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(Color.GRAY);
        buttonRow.add(statusLabel);

        tableModel = new MessageTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setMaxWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(320);
        table.getColumnModel().getColumn(3).setMaxWidth(60);
        table.getColumnModel().getColumn(4).setMaxWidth(70);

        add(buttonRow, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        addButton.addActionListener(e -> addCurrent());
        sendSelectedButton.addActionListener(e -> sendSelected());
        removeSelectedButton.addActionListener(e -> removeSelected());
        clearButton.addActionListener(e -> clearAll());
        table.getSelectionModel().addListSelectionListener(e -> updateButtonState());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    int row = table.convertRowIndexToModel(table.getSelectedRow());
                    HttpRequestResponse rr = tableModel.getEntry(row).getRequestResponse();
                    if (rr != null && displayConsumer != null) {
                        displayConsumer.accept(rr);
                    }
                }
            }
        });

        storeListener = () -> SwingUtilities.invokeLater(this::refresh);
        MessageCollectionStore.addListener(storeListener);
        refresh();
    }

    private void addCurrent() {
        if (currentSupplier == null) {
            return;
        }
        HttpRequestResponse current = currentSupplier.get();
        if (current == null) {
            statusLabel.setText("当前没有可收藏的报文");
            return;
        }
        if (MessageCollectionStore.add(current)) {
            statusLabel.setText("已收藏");
        } else {
            statusLabel.setText("已在列表中或已满(" + MessageCollectionStore.MAX_ITEMS + ")");
        }
        refresh();
    }

    private void sendSelected() {
        int[] rows = table.getSelectedRows();
        List<HttpRequestResponse> selected = new ArrayList<>();
        if (rows.length == 0) {
            // 未选中任何条目时"发送选中"实际会发送全部，容易误操作，需确认
            int total = MessageCollectionStore.snapshot().size();
            if (total == 0) {
                statusLabel.setText("列表为空，无可发送的报文");
                return;
            }
            int choice = JOptionPane.showConfirmDialog(this,
                    "未选中任何条目。确定将全部 " + total + " 条报文发送给 AI 吗？",
                    "发送全部报文",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            for (StoreEntry entry : MessageCollectionStore.snapshot()) {
                selected.add(entry.getRequestResponse());
            }
        } else {
            for (int row : rows) {
                StoreEntry entry = tableModel.getEntry(table.convertRowIndexToModel(row));
                selected.add(entry.getRequestResponse());
            }
        }
        if (selected.isEmpty() || chatPanel == null) {
            return;
        }
        statusLabel.setText("已发送 " + selected.size() + " 条报文给 AI");
        chatPanel.sendBatchAnalysis(selected);
    }

    private void removeSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        List<HttpRequestResponse> toRemove = new ArrayList<>();
        for (int row : rows) {
            toRemove.add(tableModel.getEntry(table.convertRowIndexToModel(row)).getRequestResponse());
        }
        int removed = MessageCollectionStore.removeAll(toRemove);
        statusLabel.setText("已移除 " + removed + " 条");
        refresh();
    }

    private void clearAll() {
        MessageCollectionStore.clear();
        statusLabel.setText("已清空");
        refresh();
    }

    private void updateButtonState() {
        boolean hasSelection = table.getSelectedRowCount() > 0;
        removeSelectedButton.setEnabled(hasSelection);
    }

    private void refresh() {
        tableModel.reload(MessageCollectionStore.snapshot());
        sendSelectedButton.setEnabled(tableModel.getRowCount() > 0);
        removeSelectedButton.setEnabled(table.getSelectedRowCount() > 0);
        clearButton.setEnabled(tableModel.getRowCount() > 0);
        statusLabel.setText("共 " + tableModel.getRowCount() + " 条");
    }

    /** 移除监听器，避免侧栏实例被回收后仍被通知 */
    public void dispose() {
        MessageCollectionStore.removeListener(storeListener);
    }

    private static final class MessageTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"#", "方法", "URL", "状态", "时间"};
        private final List<StoreEntry> entries = new ArrayList<>();

        void reload(List<StoreEntry> snapshot) {
            entries.clear();
            entries.addAll(snapshot);
            fireTableDataChanged();
        }

        StoreEntry getEntry(int row) {
            return entries.get(row);
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            StoreEntry entry = entries.get(rowIndex);
            HttpRequestResponse rr = entry.getRequestResponse();
            if (rr == null) {
                return "-";
            }
            try {
                switch (columnIndex) {
                    case 0:
                        return rowIndex + 1;
                    case 1:
                        return rr.request() != null ? rr.request().method() : "-";
                    case 2:
                        return rr.request() != null ? rr.request().url() : "-";
                    case 3:
                        return rr.response() != null ? String.valueOf(rr.response().statusCode()) : "-";
                    case 4:
                        return entry.getTimeText();
                    default:
                        return "";
                }
            } catch (Exception e) {
                return "-";
            }
        }
    }
}
