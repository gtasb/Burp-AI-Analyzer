package com.ai.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 侧栏流式渲染 Markdown 表格。
 *
 * <p>回归：ChatPanel 之前把流式增量当成独立文档逐段 append
 * （{@code MarkdownRenderer.appendMarkdown(chatArea, delta)}）。而 commonmark
 * 只有在「表头 + 分隔行」同时出现时才把内容识别成 TableBlock，单独解析一段
 * 只有数据行的分片会退化成带竖线的纯文本，于是侧栏里出现
 * 「一个空白行（表头被吞进不可见的内嵌 JTable）+ 下面几行原始 | 竖线文本」。
 *
 * <p>修复：流式渲染改为 {@link MarkdownRenderer#appendMarkdownStreaming}，
 * 每次用「已收到的全部内容」整体重解析并替换 aiMessageStartPos 之后的区域。
 */
@DisplayName("MarkdownRenderer - 流式渲染表格")
class MarkdownRendererTableStreamingTest {

    private static final String MD = "内部网络测绘 —— 完整的生产主机清单：\n\n"
            + "| WATO 目录 | 主机 | 网段 | 数量 |\n"
            + "| --- | --- | --- | --- |\n"
            + "| nginx/(title=sxtx) | 3-73, 3-74 | 192.168.3.0/24 | 12 |\n"
            + "| mifan/ | 2.41, 2.43 | 192.168.2.0/24 | 6 |\n";

    private static final String HEADER = "WATO 目录 | 主机 | 网段 | 数量";
    private static final String ROW1 = "nginx/(title=sxtx) | 3-73, 3-74 | 192.168.3.0/24 | 12";
    private static final String ROW2 = "mifan/ | 2.41, 2.43 | 192.168.2.0/24 | 6";

    @Test
    @DisplayName("should_embed_table_when_rendered_in_one_pass")
    void onePassRendersEmbeddedTable() {
        JTextPane pane = new JTextPane();
        MarkdownRenderer.appendMarkdown(pane, MD);

        List<JTable> tables = findEmbeddedTables(pane);
        assertThat(tables).as("整段渲染应嵌入 JTable").hasSize(1);
        // renderTable 把表头拆成独立的 header 组件，model 里只有数据行
        assertThat(tables.get(0).getRowCount()).isEqualTo(2);
        assertThat(tables.get(0).getValueAt(0, 0)).isEqualTo("nginx/(title=sxtx)");
        assertThat(tables.get(0).getValueAt(1, 2)).isEqualTo("192.168.2.0/24");
        // 原始竖线文本不应留在文档里（那说明退化成了纯文本）
        assertThat(pane.getText()).doesNotContain("| --- |");
    }

    @Test
    @DisplayName("should_converge_to_embedded_table_when_streamed_incrementally")
    void streamingConvergesToEmbeddedTable() {
        JTextPane pane = new JTextPane();
        int startPos = 0;

        // 模拟真实流式：按行逐段喂入（ChatPanel 现在就是这个调用序列）
        String[] lines = MD.split("\n", -1);
        StringBuilder acc = new StringBuilder();
        for (String line : lines) {
            acc.append(line).append('\n');
            MarkdownRenderer.appendMarkdownStreaming(pane, acc.toString(), startPos);
        }

        List<JTable> tables = findEmbeddedTables(pane);
        assertThat(tables).as("流式结束后应只剩一个（且是可见的）表格组件").hasSize(1);
        assertThat(tables.get(0).getRowCount()).as("2 行数据都要在").isEqualTo(2);
        assertThat(tables.get(0).getValueAt(0, 0)).isEqualTo("nginx/(title=sxtx)");
        assertThat(tables.get(0).getValueAt(1, 0)).isEqualTo("mifan/");
        assertThat(pane.getText()).doesNotContain("| --- |");
    }

    @Test
    @DisplayName("should_not_degrade_data_rows_into_pipe_text_after_stream")
    void dataRowsNeverRenderAsPipeText() {
        JTextPane pane = new JTextPane();
        StringBuilder acc = new StringBuilder();
        for (String line : MD.split("\n", -1)) {
            acc.append(line).append('\n');
            MarkdownRenderer.appendMarkdownStreaming(pane, acc.toString(), 0);
        }
        // 表格被组件接管后，文档正文里不应残留任何一行原始竖线
        for (String line : pane.getText().split("\n")) {
            assertThat(line.trim())
                    .as("不应出现残留的竖线文本行: %s", line)
                    .doesNotStartWith("|");
        }
    }

    @Test
    @DisplayName("should_not_parse_headerless_fragment_as_table")
    void headerlessFragmentIsNotATable() {
        // 这正是旧 bug 的机制：只有数据行、没有表头+分隔行时不是表格。
        // 断言这一前提成立，从而说明「必须整体重解析」不是可选项。
        JTextPane pane = new JTextPane();
        MarkdownRenderer.appendMarkdown(pane, "| nginx | 3-73 |\n| mifan | 2.41 |\n");
        assertThat(findEmbeddedTables(pane)).isEmpty();
    }

    @Test
    @DisplayName("should_replace_previous_stream_content_instead_of_appending")
    void streamingReplacesRatherThanAppends() {
        JTextPane pane = new JTextPane();
        // 先渲染一个较长的旧版本
        MarkdownRenderer.appendMarkdownStreaming(pane, "旧内容\n\n" + MD, 0);
        int lenAfterFirst = pane.getDocument().getLength();

        // 再用「同一位置的新内容」覆盖，不应把旧内容留在文档里
        MarkdownRenderer.appendMarkdownStreaming(pane, MD, 0);
        String text = pane.getText();

        assertThat(text).doesNotContain("旧内容");
        assertThat(findEmbeddedTables(pane)).hasSize(1);
        assertThat(pane.getDocument().getLength())
                .as("整体重解析后长度应重新计算，而不是单调增长")
                .isLessThan(lenAfterFirst);
    }

    @Test
    @DisplayName("should_reproduce_old_bug_when_deltas_are_parsed_in_isolation")
    void oldDeltaAppendStrategyIsBroken() {
        // 复刻修复前 ChatPanel 的做法：把每行当独立文档 append。
        // 这个断言是为了证明根因判断成立、并防止有人「优化」回逐段解析。
        JTextPane pane = new JTextPane();
        String[] lines = MD.split("\n", -1);
        for (String line : lines) {
            MarkdownRenderer.appendMarkdown(pane, line + "\n");
        }

        // 表头那一行没有分隔行跟随，不会被识别成表格；数据行同样不是表格
        // => 全都退化成带竖线的纯文本，这正是用户看到的现象
        assertThat(pane.getText())
                .as("逐段解析必然退化成竖线文本")
                .contains("| nginx/(title=sxtx) |");
        assertThat(findEmbeddedTables(pane))
                .as("逐段解析拿不到任何表格组件")
                .isEmpty();
    }

    /** 遍历文档 Element 树，收集所有内嵌的 JTable。
     *
     * <p>用公开 API：{@code renderTable} 是通过
     * {@link javax.swing.text.StyleConstants#ComponentAttribute} 把 JScrollPane
     * 写进样式的，所以直接从 Element 的 AttributeSet 里取该属性即可。
     * （不能用 AbstractElement#getView 或 View#getElement —— 前者包私有，
     * 后者根本不存在。）
     */
    private static List<JTable> findEmbeddedTables(JTextPane pane) {
        List<JTable> found = new ArrayList<>();
        collect(pane.getDocument().getDefaultRootElement(), found);
        return found;
    }

    private static void collect(Element element, List<JTable> out) {
        if (element == null) return;
        Object comp = element.getAttributes().getAttribute(StyleConstants.ComponentAttribute);
        if (comp instanceof JScrollPane sp
                && sp.getViewport() != null
                && sp.getViewport().getView() instanceof JTable jt) {
            out.add(jt);
        }
        for (int i = 0; i < element.getElementCount(); i++) {
            collect(element.getElement(i), out);
        }
    }
}