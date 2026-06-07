package com.ai.analyzer.Tools;

import com.ai.analyzer.utils.ArtifactCache;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool facade for reading oversized data cached by other tools or HTTP prompt preparation.
 */
public class ArtifactCacheTools {

    @Tool(name = "read_cached_artifact", value = {
            "按行读取已缓存的大文本数据，适用于 MCP、网页抓取、长 HTTP 报文等返回 fileId 的场景。",
            "startLine 为 1-based 行号；lineCount 建议 50-200，过大时会自动限制。"
    })
    public String readCachedArtifact(
            @P("缓存返回的 fileId") String fileId,
            @P("起始行号，1-based") int startLine,
            @P("读取行数，建议 50-200") int lineCount
    ) {
        try {
            return ArtifactCache.readLines(fileId, startLine, lineCount);
        } catch (Exception e) {
            return "读取缓存失败: " + e.getMessage();
        }
    }

    @Tool(name = "describe_cached_artifact", value = {
            "查看缓存文件的路径、大小和总行数，用于决定下一步读取范围。"
    })
    public String describeCachedArtifact(@P("缓存返回的 fileId") String fileId) {
        try {
            return ArtifactCache.describe(fileId);
        } catch (Exception e) {
            return "查看缓存失败: " + e.getMessage();
        }
    }
}
