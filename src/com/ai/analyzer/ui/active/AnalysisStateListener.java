package com.ai.analyzer.ui.active;

/**
 * 分析状态监听：底部"开始分析/停止"按钮等外部组件据此切换启用状态。
 */
public interface AnalysisStateListener {

    void onAnalysisStateChanged(boolean analyzing);
}
