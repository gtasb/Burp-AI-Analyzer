package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式渲染 Timer 契约：后台线程 append 后，EDT 空闲时 renderTimer 必须把
 * 文本写进当前 TextSection（真实 UI 的流式路径）。
 * 此前线上症状：模型 51 chunk 全部到达（buffer 有内容），UI 一个字不显示，
 * 只有切会话走重放路径才可见——即 Timer→render→appendDelta 链路断了。
 */
@DisplayName("ChatTimeline - 流式渲染 Timer")
class StreamingRenderTimerTest {

    @Test
    @DisplayName("background_appends_reach_textsection_via_timer")
    void backgroundAppendsRenderViaTimer() throws Exception {
        // 在 EDT 上构造（真实场景：startAssistant 在 EDT 调用）
        final ChatTimeline[] tl = new ChatTimeline[1];
        final ChatTimeline.AssistantBlock[] block = new ChatTimeline.AssistantBlock[1];
        SwingUtilities.invokeAndWait(() -> {
            tl[0] = new ChatTimeline();
            block[0] = tl[0].startAssistant("test");
        });

        // 后台线程模拟 SwingWorker 的 onChunk（真实场景：worker 线程）
        Thread worker = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                block[0].append("stream-chunk-" + i + " ");
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
        });
        worker.start();
        worker.join();

        // 等 EDT 空闲 + Timer tick（180ms 周期）：轮询最多 3 秒
        long deadline = System.currentTimeMillis() + 3000;
        int len = 0;
        while (System.currentTimeMillis() < deadline) {
            final int[] snapshot = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                Component c = tl[0].content().getComponent(tl[0].content().getComponentCount() - 1);
                snapshot[0] = findTextLength(c);
            });
            len = snapshot[0];
            if (len >= "stream-chunk-0 ".length() * 10 - 5) break;
            Thread.sleep(120);
        }

        assertThat(len)
                .as("后台 append 后 Timer 必须把文本渲染进 TextSection（实际 %d 字符）", len)
                .isGreaterThan(50);
    }

    private static int findTextLength(Component c) {
        if (c instanceof JTextPane tp) {
            return tp.getDocument().getLength();
        }
        if (c instanceof Container container) {
            int total = 0;
            for (Component child : container.getComponents()) {
                total += findTextLength(child);
            }
            return total;
        }
        return 0;
    }
}
