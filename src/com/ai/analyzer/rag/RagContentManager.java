package com.ai.analyzer.rag;

import burp.api.montoya.MontoyaApi;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * 负责将 RAG 文档加载并构建可复用的 ContentRetriever（基于 Easy RAG 流程）。
 * 文档会在初始化时一次性向量化，并常驻内存供后续 Assistant 复用。
 */
public class RagContentManager {

    private static final String DEFAULT_GLOB = "glob:**.{md,markdown}";

    private MontoyaApi api;
    private List<Document> documents;
    private ContentRetriever contentRetriever;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private final PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher(DEFAULT_GLOB);

    public RagContentManager(MontoyaApi api) {
        this.api = api;
    }

    public void updateApi(MontoyaApi api) {
        this.api = api;
    }

    public boolean load(String ragDocumentsPath) {
        clear();

        String normalizedPath = ragDocumentsPath != null ? ragDocumentsPath.trim() : "";

        List<Document> loadedDocuments = loadDocuments(normalizedPath);
        if (loadedDocuments.isEmpty()) {
            logError("未找到任何 RAG 文档，路径: " + normalizedPath);
            return false;
        }

        // 尝试使用向量检索，失败时回退到简单关键词检索
        if (!tryInitEmbeddingRetriever(loadedDocuments)) {
            logInfo("向量检索初始化失败，回退到简单关键词检索");
            contentRetriever = new SimpleDocumentContentRetriever(loadedDocuments, 5);
            logInfo("RAG 内容检索器已创建（关键词检索，回退模式）");
        }

        documents = loadedDocuments;
        return true;
    }

    /**
     * 尝试初始化基于向量的内容检索器
     * @return 初始化成功返回 true，失败返回 false
     */
    private boolean tryInitEmbeddingRetriever(List<Document> loadedDocuments) {
        try {
/*             embeddingModel = new OnnxEmbeddingModel(
                    "E:\\HackTools\\develop\\all-minilm-l6-v2.onnx",
                    "E:\\HackTools\\develop\\all-minilm-l6-v2-tokenizer.json",
                    PoolingMode.MEAN
            ); */
            embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            embeddingStore = new InMemoryEmbeddingStore<>();
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build();
            ingestor.ingest(loadedDocuments);

            contentRetriever = EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .maxResults(5)
                    .minScore(0.0)
                    .build();

            logInfo("RAG 内容检索器已创建（向量检索）");
            return true;
        } catch (Exception e) {
            logError("向量检索初始化失败: " + e.getMessage());
            // 清理可能部分初始化的资源
            embeddingModel = null;
            embeddingStore = null;
            contentRetriever = null;
            return false;
        }
    }

    public boolean isReady() {
        return contentRetriever != null;
    }

    public ContentRetriever getContentRetriever() {
        return contentRetriever;
    }

    public void clear() {
        documents = null;
        contentRetriever = null;
        embeddingStore = null;
        embeddingModel = null;
    }

    private List<Document> loadDocuments(String normalizedPath) {
        List<Document> docs = FileSystemDocumentLoader.loadDocumentsRecursively(normalizedPath, pathMatcher);
        if (docs == null || docs.isEmpty()) {
            docs = FileSystemDocumentLoader.loadDocumentsRecursively(normalizedPath);
        }
        return docs != null ? docs : Collections.emptyList();
    }

    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[RagContentManager] " + message);
        }
    }

    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[QianwenApiClient] " + message);
        }
    }

    /**
     * 从 JAR 中提取 DJL tokenizers 资源到文件系统，返回缓存目录路径
     */
    private String ensureDjlResourcesExtracted() {
        try {
            // 使用用户目录下的 .djl.ai 作为缓存目录
            String userHome = System.getProperty("user.home");
            Path cacheDir = Paths.get(userHome, ".djl.ai", "cache", "native", "lib");
            Files.createDirectories(cacheDir);

            // 提取 tokenizers.properties
            String resourcePath = "native/lib/tokenizers.properties";
            InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
            
            if (resourceStream == null) {
                // 尝试从不同的 classloader 查找
                resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
            }

            if (resourceStream != null) {
                Path targetFile = cacheDir.resolve("tokenizers.properties");
                if (!Files.exists(targetFile)) {
                    try (FileOutputStream fos = new FileOutputStream(targetFile.toFile())) {
                        resourceStream.transferTo(fos);
                    }
                    logInfo("已提取 tokenizers.properties 到: " + targetFile);
                }
                resourceStream.close();
                return cacheDir.getParent().getParent().toString(); // 返回 .djl.ai/cache
            } else {
                logError("无法从 JAR 中找到 tokenizers.properties，路径: " + resourcePath);
                return null;
            }
        } catch (Exception e) {
            logError("提取 DJL 资源失败: " + e.getMessage());
            return null;
        }
    }
}

