package com.ai.analyzer.ui;

import com.ai.analyzer.ui.active.ActiveAnalysisPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JTextPane;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主动分析面板的渲染目标绝不能为 null。
 *
 * <p>回归：{@code targetPane} 只在 {@code setActiveMode()} 里赋值，而该方法仅由
 * AIAnalyzerTab 的模式 combo action 触发。构造时 combo 先 {@code setSelectedItem}
 * 再 {@code addActionListener}，action 不会触发；「开始分析」按钮又挂在两种模式都可见的
 * mainPanel 上。于是被动模式下点「开始分析」时 targetPane 仍是 null，
 * {@code performAnalysis()} 捕获它之后：
 * <ul>
 *   <li>每个流式 tick 抛 {@code IllegalArgumentException: textPane cannot be null}</li>
 *   <li>最后一次渲染抛 {@code NPE: targetResultPane.getStyledDocument()}</li>
 * </ul>
 * 结果整条回复一帧都不渲染（用户看到「两边都不渲染了」）。
 */
@DisplayName("ActiveAnalysisPanel - 渲染目标非空")
class ActiveAnalysisPanelTargetPaneTest {

    private static ActiveAnalysisPanel newPanel() {
        return new ActiveAnalysisPanel(null);
    }

    private static JTextPane resolve(ActiveAnalysisPanel panel) throws Exception {
        var m = ActiveAnalysisPanel.class.getDeclaredMethod("resolveTargetPane");
        m.setAccessible(true);
        return (JTextPane) m.invoke(panel);
    }

    @Test
    @DisplayName("should_resolve_before_setActiveMode_is_ever_called")
    void resolvesWithoutModeSwitch() throws Exception {
        // 复现根因：从未切过模式
        ActiveAnalysisPanel panel = newPanel();
        assertThat(resolve(panel))
                .as("未调用 setActiveMode 时也必须回落到主动模式自己的结果区")
                .isNotNull()
                .isSameAs(panel.getResultPane());
    }

    @Test
    @DisplayName("should_fall_back_when_setActiveMode_receives_null_target")
    void survivesNullTarget() throws Exception {
        ActiveAnalysisPanel panel = newPanel();
        panel.setActiveMode(true, null, null);
        assertThat(resolve(panel))
                .as("即使被传入 null 目标也必须回落到自带结果区")
                .isNotNull();
    }

    @Test
    @DisplayName("should_honour_explicit_target_when_provided")
    void honoursExplicitTarget() throws Exception {
        ActiveAnalysisPanel panel = newPanel();
        JTextPane custom = new JTextPane();
        panel.setActiveMode(false, custom, null);
        assertThat(resolve(panel)).isSameAs(custom);
    }

    @Test
    @DisplayName("should_never_return_null_across_mode_transitions")
    void neverNullAcrossTransitions() throws Exception {
        ActiveAnalysisPanel panel = newPanel();
        assertThat(resolve(panel)).isNotNull();

        JTextPane passive = new JTextPane();
        panel.setActiveMode(false, passive, null);
        assertThat(resolve(panel)).isSameAs(passive);

        panel.setActiveMode(true, panel.getResultPane(), panel.getPromptArea());
        assertThat(resolve(panel)).isNotNull();

        panel.setActiveMode(false, null, null);
        assertThat(resolve(panel)).isNotNull();
    }
}