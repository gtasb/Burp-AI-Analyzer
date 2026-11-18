package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatTimeline - 流式收尾 Markdown 渲染")
class StreamRenderTest {

    @Test
    @DisplayName("finish_renders_each_section_as_markdown")
    void finishRendersMarkdown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            ChatTimeline.AssistantBlock block = timeline.startAssistant("主动分析 · 第 1 轮");

            // 模拟流式：文本 → 工具 → 文本
            block.append("## 发现\n\n第一段结论，含 **加粗**。\n\n");
            block.addTool("execute", "{\"command\":\"curl x\"}", true);
            block.addTool("execute", "", false, false);
            block.append("## 验证\n\n第二段结论。\n\n- 项目一\n- 项目二\n");
            block.finish();

            // finish 后：每段都应被渲染为 styled 文本
            // 判定方式：header 样式被应用（styled doc 的字体大小与段落不同），
            // 或段落内容仍存在。至少文本不能丢。
            java.util.List<Component> flow = childrenOf(block);
            JTextPane sec1 = (JTextPane) flow.get(0);
            JTextPane sec2 = (JTextPane) flow.get(2);
            assertThat(sec1.getText()).contains("第一段结论");
            assertThat(sec2.getText()).contains("第二段结论");
            // 样式生效验证：sec1 里存在字号 != 常规字号的 run（标题/加粗被渲染）
            boolean hasStyledRun1 = hasNonPlainStyle(sec1);
            boolean hasStyledRun2 = hasNonPlainStyle(sec2);
            System.out.println("[test] sec1 styled=" + hasStyledRun1 + " sec2 styled=" + hasStyledRun2);
            assertThat(hasStyledRun1).as("第一段应渲染出样式（标题/加粗）").isTrue();
            assertThat(hasStyledRun2).as("第二段应渲染出样式").isTrue();
        });
    }

    private static boolean hasNonPlainStyle(JTextPane pane) {
        javax.swing.text.StyledDocument doc = pane.getStyledDocument();
        javax.swing.text.Style regular = doc.getStyle("regular");
        java.awt.Font base = pane.getFont();
        int len = doc.getLength();
        for (int i = 0; i < len; i++) {
            javax.swing.text.Element el = doc.getCharacterElement(i);
            Object attr = el.getAttributes().getAttribute(javax.swing.text.StyleConstants.FontSize);
            Object bold = el.getAttributes().getAttribute(javax.swing.text.StyleConstants.Bold);
            if (bold instanceof Boolean b && b) return true;
            if (attr instanceof Integer size && base != null && size != base.getSize()) return true;
        }
        return false;
    }

    private static java.util.List<Component> childrenOf(ChatTimeline.AssistantBlock block) {
        try {
            java.lang.reflect.Field f = ChatTimeline.AssistantBlock.class.getDeclaredField("flow");
            f.setAccessible(true);
            JPanel flow = (JPanel) f.get(block);
            java.util.List<Component> out = new java.util.ArrayList<>();
            for (Component c : flow.getComponents()) out.add(c);
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
