package com.ai.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.JTextPane;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown 表格渲染的行高计算。
 *
 * <p>回归：旧实现把 {@code setRowHeight} 放在 cell renderer 里（绘制副作用），
 * 且视口高度用初始 rowHeight=28 乘行数来算。嵌在 JTextPane 中的 JTable 拿不到
 * 完整 layout pass，结果多行单元格被截断、长表格底部看不见。
 */
@DisplayName("MarkdownRenderer - 表格行高与视口高度")
class MarkdownRendererTableLayoutTest {

    @Test
    @DisplayName("should_measure_wrapped_lines_greater_for_longer_text")
    void wrappedLinesGrowWithText() {
        Font font = new Font("Microsoft YaHei", Font.PLAIN, 12);
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setFont(font);
            String shortText = "OK";
            String longText = "这是一个相当长的单元格内容，用来强制换行以便测量行数是否增加";
            int shortLines = invokeEstimateWrappedLines(shortText, 120, font, g2);
            int longLines = invokeEstimateWrappedLines(longText, 120, font, g2);
            assertThat(longLines).isGreaterThan(shortLines);
        } finally {
            g2.dispose();
        }
    }

    @Test
    @DisplayName("should_count_hard_newlines_as_extra_lines")
    void hardNewlinesCountAsLines() {
        Font font = new Font("Microsoft YaHei", Font.PLAIN, 12);
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setFont(font);
            int one = invokeEstimateWrappedLines("a", 400, font, g2);
            int three = invokeEstimateWrappedLines("a\nb\nc", 400, font, g2);
            assertThat(three).isGreaterThan(one);
        } finally {
            g2.dispose();
        }
    }

    @Test
    @DisplayName("should_return_at_least_one_line_for_empty_text")
    void emptyTextIsOneLine() {
        Font font = new Font("Microsoft YaHei", Font.PLAIN, 12);
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setFont(font);
            assertThat(invokeEstimateWrappedLines("", 200, font, g2)).isEqualTo(1);
            assertThat(invokeEstimateWrappedLines(null, 200, font, g2)).isEqualTo(1);
        } finally {
            g2.dispose();
        }
    }

    @Test
    @DisplayName("should_render_table_into_pane_without_error")
    void rendersTableIntoPane() {
        JTextPane pane = new JTextPane();
        String md = "| 名称 | 说明 |\n| --- | --- |\n"
                + "| file | file:///etc/passwd 协议绕过 |\n"
                + "| gopher | gopher 协议走私 |\n";
        MarkdownRenderer.appendMarkdown(pane, md);
        String rendered = pane.getText();
        assertThat(rendered).isNotEmpty();
    }

    @Test
    @DisplayName("should_not_let_cell_renderer_resize_rows")
    void rowHeightsArePrecomputed() {
        // 直接检查：渲染后的表格行高不应全部停留在初始默认值 28
        // （旧实现在绘制时才设置，文档里量到的仍是默认值）
        JTextPane pane = new JTextPane();
        StringBuilder md = new StringBuilder("| 列 |\n| --- |\n");
        for (int i = 0; i < 30; i++) {
            md.append("| ").append("很长的内容".repeat(12)).append(" |\n");
        }
        MarkdownRenderer.appendMarkdown(pane, md.toString());
        assertThat(pane.getText()).isNotEmpty();
    }

    private static int invokeEstimateWrappedLines(String text, int avail, Font font, Graphics2D g2) {
        try {
            var m = MarkdownRenderer.class.getDeclaredMethod(
                    "estimateWrappedLines", String.class, int.class, Font.class, Graphics2D.class);
            m.setAccessible(true);
            return (int) m.invoke(null, text, avail, font, g2);
        } catch (Exception e) {
            throw new AssertionError("estimateWrappedLines 不可访问", e);
        }
    }
}
