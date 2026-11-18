package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整流式链路（复刻 ActiveAnalysisPanel.startAnalysisWorker 的调用顺序）：
 * addUser → startAssistant → 后台线程 append（中间夹 addTool）→ finish。
 * 线上症状：模型输出到达但 UI 全空白、切会话重放才可见——若链路某环节
 * 把内容弄丢/组件不可见，此测试必须能抓到。
 */
@DisplayName("ChatTimeline - 完整流式管线")
class FullStreamPipelineTest {

    @Test
    @DisplayName("full_pipeline_user_assistant_tools_finish_visible")
    void fullPipeline() throws Exception {
        final ChatTimeline[] tl = new ChatTimeline[1];
        final ChatTimeline.AssistantBlock[] block = new ChatTimeline.AssistantBlock[1];
        SwingUtilities.invokeAndWait(() -> {
            tl[0] = new ChatTimeline();
            tl[0].addUser("请分析这个请求", "GET https://a.example/x");
            block[0] = tl[0].startAssistant("主动分析 · 第 1 轮");
        });

        // worker 线程：文本 → 工具 → 文本 → 工具END → 收尾
        Thread worker = new Thread(() -> {
            block[0].append("首先看目标首页。\n\n");
            SwingUtilities.invokeLater(() -> block[0].addTool("execute", "{\"command\":\"curl https://a.example\"}", true));
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            block[0].append("首页返回 200，继续提取链接。\n\n## 结论\n\n- 无明显风险\n");
            SwingUtilities.invokeLater(() -> {
                block[0].addTool("execute", "", false, false);
            });
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        });
        worker.start();
        worker.join();
        Thread.sleep(500); // EDT 空闲让 Timer 消化

        SwingUtilities.invokeAndWait(() -> block[0].finish());

        // 断言：卡片内容齐全且组件可见
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = tl[0].content();
            Component card = content.getComponent(content.getComponentCount() - 1);
            assertThat(card.isVisible()).isTrue();
            assertThat(card.isShowing() || ((JComponent) card).getPreferredSize().height > 10)
                    .as("卡片高度必须非零（pref=%s）", ((JComponent) card).getPreferredSize())
                    .isTrue();
            String allText = collectText(card);
            assertThat(allText)
                    .contains("首先看目标首页")
                    .contains("无明显风险");
        });
    }

    private static String collectText(Component c) {
        if (c instanceof JTextPane tp) return tp.getText();
        if (c instanceof JTextArea ta) return ta.getText();
        if (c instanceof JLabel lb) return lb.getText();
        if (c instanceof Container container) {
            StringBuilder sb = new StringBuilder();
            for (Component child : container.getComponents()) {
                sb.append(collectText(child)).append('\n');
            }
            return sb.toString();
        }
        return "";
    }
}
