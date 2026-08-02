package com.ai.analyzer.ui.active;

import burp.api.montoya.http.message.HttpRequestResponse;
import com.ai.analyzer.core.RequestData;
import com.ai.analyzer.scan.pscan.ScanResult;

import java.util.List;

/**
 * 被动扫描数据源接口：让 ActiveAnalysisPanel 从请求列表中获取选中目标，
 * 由 AIAnalyzerTab 实现，避免面板直接依赖表格/管理器结构。
 */
public interface PassiveDataSource {

    /** 当前选中行（view row），无选中返回 -1 */
    int getSelectedViewRow();

    /**
     * 给定 view row 返回对应目标对象：
     * ScanResult（扫描结果）或 RequestData（手动添加的请求），未命中返回 null。
     */
    Object selectionForViewRow(int viewRow);

    /** 所有选中行的目标对象列表（用于批量分析） */
    List<Object> getSelectedSelections();

    /** 当前选中请求的原始 HttpRequestResponse（用于发送到 Intruder 等），无选中返回 null */
    HttpRequestResponse selectedRequestResponse();
}
