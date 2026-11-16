package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交错布局契约：AssistantBlock 的文本段与工具行按到达顺序交错
 * （CC 风格：一行分析 → 工具行 → 再一行分析），收尾时每段独立 Markdown 渲染。
 */
@DisplayName("ChatTimeline - 文本/工具交错布局")
class ChatTimelineInterleaveTest {

    @Test
    @DisplayName("text_tool_text_interleaves_in_order")
    void interleavesInOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            ChatTimeline.AssistantBlock block = timeline.startAssistant("主动分析 · 第 1 轮");

            block.append("第一段分析结论\n\n");
            block.addTool("execute", "{\"command\":\"curl https://a.example\"}", true);
            block.append("第二段分析结论\n\n## 标题\n\n- 列表项\n");
            block.addTool("execute", "{\"command\":\"curl https://b.example\"}", true);
            block.addTool("execute", "", false, false);
            block.finish();

            // 交错顺序：Text / Tool / Text / Tool / Text（END 复用第二个 Tool）
            java.util.List<Component> flow = childrenOf(block);
            assertThat(flow).hasSize(5);
            assertThat(flow.get(0)).isInstanceOf(JTextPane.class);
            assertThat(((JTextPane) flow.get(0)).getText()).contains("第一段分析结论");
            assertThat(((JTextPane) flow.get(0)).getText()).doesNotContain("第二段");
            assertThat(flow.get(1).getClass().getSimpleName()).isEqualTo("ToolRow");
            assertThat(flow.get(2)).isInstanceOf(JTextPane.class);
            assertThat(((JTextPane) flow.get(2)).getText()).contains("第二段");
            // 第二段收尾后是 Markdown 渲染（标题样式注册到该段的 StyledDocument）
            assertThat(((JTextPane) flow.get(2)).getStyledDocument()
                    .getStyle("header1")).isNotNull();
            assertThat(flow.get(3).getClass().getSimpleName()).isEqualTo("ToolRow");
            // 尾部是 END 后的空文本段（接收后续正文）
            assertThat(flow.get(4)).isInstanceOf(JTextPane.class);
        });
    }

    @Test
    @DisplayName("same_tool_start_end_shares_one_row")
    void startEndShareRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            ChatTimeline.AssistantBlock block = timeline.startAssistant("主动分析");
            block.append("前言\n");
            block.addTool("graph_get_root", "{\"origin\":\"https://a.com\"}", true);
            block.addTool("graph_get_root", "", false, false);
            block.finish();

            java.util.List<Component> flow = childrenOf(block);
            // 只有 1 个 ToolRow：START 与 END 复用
            long toolRows = flow.stream()
                    .filter(c -> c.getClass().getSimpleName().equals("ToolRow")).count();
            assertThat(toolRows).isEqualTo(1);
        });
    }

    /** 反射拿 AssistantBlock 内部 flow 容器的子组件顺序（测试专用通道）。 */
    private static java.util.List<Component> childrenOf(ChatTimeline.AssistantBlock block) {
        try {
            java.lang.reflect.Field f = ChatTimeline.AssistantBlock.class.getDeclaredField("flow");
            f.setAccessible(true);
            JPanel flow = (JPanel) f.get(block);
            java.util.List<Component> out = new java.util.ArrayList<>();
            for (Component c : flow.getComponents()) {
                out.add(c);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
