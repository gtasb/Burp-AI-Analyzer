package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;

import java.awt.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具输出折叠：TOOL_OUTPUT 挂到最近同名工具行，默认折叠为单行摘要，
 * 点开显示全文（限高滚动）；无输出时不显示链接。
 */
@DisplayName("ChatTimeline - 工具输出折叠（TOOL_OUTPUT）")
class ChatTimelineToolOutputTest {

    @Test
    @DisplayName("tool_output_attaches_to_matching_row_collapsed_by_default")
    void outputAttachesAndCollapsed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            ChatTimeline.AssistantBlock block = timeline.startAssistant("主动分析");
            block.append("先探测一下目标\n");
            block.addTool("curl_send_request", "{\"url\":\"https://x.example\"}", true);
            block.addTool("curl_send_request", "", false, false);
            String output = "HTTP/2 200\nServer: nginx\n<body quite long...>".repeat(20);
            block.addToolOutput("curl_send_request", output);
            block.finish();

            JComponent row = findToolRow(block, "curl_send_request");
            assertThat(row).isNotNull();
            // 折叠态高度应远小于全文高度（单行摘要）
            assertThat(row.getPreferredSize().height).isLessThan(120);
            // 输出全文存在内部（折叠但未丢）
            String state = rowState(row);
            assertThat(state).contains("HTTP/2 200");
        });
    }

    @Test
    @DisplayName("tool_output_without_output_hides_link")
    void noOutputNoLink() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            ChatTimeline.AssistantBlock block = timeline.startAssistant("主动分析");
            block.addTool("execute", "{\"command\":\"ls\"}", true);
            block.addTool("execute", "", false, false);
            block.finish();
            JComponent row = findToolRow(block, "execute");
            assertThat(rowState(row)).doesNotContain("▸ 输出");
        });
    }

    @Test
    @DisplayName("tool_output_of_unknown_tool_is_dropped")
    void unknownToolOutputDropped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            ChatTimeline.AssistantBlock block = timeline.startAssistant("主动分析");
            block.append("一段文字\n");
            block.addTool("execute", "", false, false);
            // 配对终止（前面有非空文本段）：不应把输出错挂到 execute 行
            block.addToolOutput("curl_send_request", "leak");
            block.finish();
            JComponent row = findToolRow(block, "execute");
            assertThat(rowState(row)).doesNotContain("leak");
        });
    }

    private static JComponent findToolRow(ChatTimeline.AssistantBlock block, String tool) {
        try {
            java.lang.reflect.Field f = ChatTimeline.AssistantBlock.class.getDeclaredField("flow");
            f.setAccessible(true);
            JPanel flow = (JPanel) f.get(block);
            for (Component c : flow.getComponents()) {
                if (c.getClass().getSimpleName().equals("ToolRow")) {
                    try {
                        java.lang.reflect.Field nf = c.getClass().getDeclaredField("toolName");
                        nf.setAccessible(true);
                        if (tool.equals(nf.get(c))) return (JComponent) c;
                    } catch (Exception ignored) {
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 汇总行内 JTextArea 文本与按钮文本（跨 args/output 两个折叠区）。 */
    private static String rowState(JComponent row) {
        StringBuilder sb = new StringBuilder();
        collect(row, sb);
        return sb.toString();
    }

    private static void collect(java.awt.Container c, StringBuilder sb) {
        for (Component x : c.getComponents()) {
            if (x instanceof JTextArea ta) sb.append(ta.getText()).append('\n');
            if (x instanceof AbstractButton b) sb.append('[').append(b.getText()).append(']');
            if (x instanceof java.awt.Container child) collect(child, sb);
        }
    }
}
