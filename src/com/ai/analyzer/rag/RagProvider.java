package com.ai.analyzer.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import burp.api.montoya.MontoyaApi;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;

/**
 * RAG (Retrieval-Augmented Generation) 提供者
 * 基于 LangChain4j 的 Easy RAG 实现
 * 
 * 参考文档: https://docs.langchain4j.dev/tutorials/rag#easy-rag
 */
public class RagProvider {
    
    private final MontoyaApi api;
    private EmbeddingStore<TextSegment> embeddingStore;
    private ContentRetriever contentRetriever;
    private boolean isInitialized = false;
    
    /**
     * 构造函数
     * @param api Burp API 引用，用于日志输出
     */
    public RagProvider(MontoyaApi api) {
        this.api = api;
        // 使用内存 Embedding Store（简单场景）
        this.embeddingStore = new InMemoryEmbeddingStore<>();
    }
    
    /**
     * 从指定目录加载文档并构建 RAG 索引
     * 
     * @param documentsPath 文档目录路径
     * @return 是否成功加载
     */
    public boolean loadDocuments(String documentsPath) {
        try {
            if (api != null) {
                api.logging().logToOutput("[RagProvider] 开始加载文档: " + documentsPath);
            }
            
            // 使用 FileSystemDocumentLoader 加载文档
            // 这会自动使用 Apache Tika 解析各种文档类型（PDF, Word, HTML 等）
            List<Document> documents = FileSystemDocumentLoader.loadDocuments(documentsPath);

            
            if (documents == null || documents.isEmpty()) {
                if (api != null) {
                    api.logging().logToOutput("[RagProvider] 警告: 未找到任何文档");
                }
                return false;
            }
            
            if (api != null) {
                api.logging().logToOutput("[RagProvider] 成功加载 " + documents.size() + " 个文档");
            }
            
            // 使用 EmbeddingStoreIngestor 处理文档
            // 这会自动：
            // 1. 将文档分割成较小的片段（每个片段最多 300 tokens，重叠 30 tokens）
            // 2. 使用默认的 EmbeddingModel (bge-small-en-v1.5) 将片段转换为向量
            // 3. 存储到 EmbeddingStore 中
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .build();
            
            ingestor.ingest(documents);
            
            if (api != null) {
                api.logging().logToOutput("[RagProvider] 文档已成功索引到 Embedding Store");
            }
            
            // 创建 ContentRetriever
            createContentRetriever();
            
            isInitialized = true;
            return true;
            
        } catch (Exception e) {
            if (api != null) {
                api.logging().logToError("[RagProvider] 加载文档失败: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }
    }
    
    /**
     * 递归加载指定目录及其子目录中的所有文档
     * 
     * @param documentsPath 文档目录路径
     * @return 是否成功加载
     */
    public boolean loadDocumentsRecursively(String documentsPath) {
        try {
            if (api != null) {
                api.logging().logToOutput("[RagProvider] 开始递归加载文档: " + documentsPath);
            }

            // 使用 loadDocumentsRecursively 方法加载所有子目录中的文档（使用传入的目录）
            List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively(documentsPath);
            
            if (documents == null || documents.isEmpty()) {
                if (api != null) {
                    api.logging().logToOutput("[RagProvider] 警告: 未找到任何文档");
                }
                return false;
            }
            
            if (api != null) {
                api.logging().logToOutput("[RagProvider] 成功加载 " + documents.size() + " 个文档");
            }
            
            // 使用 EmbeddingStoreIngestor 处理文档
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .build();
            
            ingestor.ingest(documents);
            
            if (api != null) {
                api.logging().logToOutput("[RagProvider] 文档已成功索引到 Embedding Store");
            }
            
            // 创建 ContentRetriever
            createContentRetriever();
            
            isInitialized = true;
            return true;
            
        } catch (Exception e) {
            if (api != null) {
                api.logging().logToError("[RagProvider] 递归加载文档失败: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }
    }

    
    /**
     * 创建 ContentRetriever
     * 使用默认的 EmbeddingModel（通过 SPI 自动加载）
     */
    private void createContentRetriever() {
        try {
            // EmbeddingStoreContentRetriever 会自动通过 SPI 加载默认的 EmbeddingModel
            // 默认使用 bge-small-en-v1.5 模型
            this.contentRetriever = EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .maxResults(3) // 最多返回 3 个相关文档片段
                    .minScore(0.6) // 最小相似度分数（0.0-1.0）
                    .build();
            
            if (api != null) {
                api.logging().logToOutput("[RagProvider] ContentRetriever 已创建（使用默认 EmbeddingModel）");
            }
        } catch (Exception e) {
            if (api != null) {
                api.logging().logToError("[RagProvider] 创建 ContentRetriever 失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 使用自定义 EmbeddingModel 创建 ContentRetriever
     * 
     * @param embeddingModel 自定义的 EmbeddingModel
     * @param maxResults 最多返回的结果数量
     * @param minScore 最小相似度分数（0.0-1.0）
     */
    public void createContentRetriever(EmbeddingModel embeddingModel, int maxResults, double minScore) {
        try {
            this.contentRetriever = EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build();
            
            if (api != null) {
                api.logging().logToOutput("[RagProvider] ContentRetriever 已创建（使用自定义 EmbeddingModel）");
            }
        } catch (Exception e) {
            if (api != null) {
                api.logging().logToError("[RagProvider] 创建 ContentRetriever 失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 获取 ContentRetriever
     * 如果未初始化，返回 null
     * 
     * @return ContentRetriever 实例，如果未初始化则返回 null
     */
    public ContentRetriever getContentRetriever() {
        if (!isInitialized || contentRetriever == null) {
            if (api != null) {
                api.logging().logToOutput("[RagProvider] 警告: ContentRetriever 未初始化，请先调用 loadDocuments()");
            }
            return null;
        }
        return contentRetriever;
    }
    
    /**
     * 检查 RAG 是否已初始化
     * 
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    /**
     * 清空 Embedding Store 并重置状态
     */
    public void clear() {
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        this.contentRetriever = null;
        this.isInitialized = false;
        
        if (api != null) {
            api.logging().logToOutput("[RagProvider] RAG 索引已清空");
        }
    }
    
    /**
     * 获取 Embedding Store（用于高级用法）
     * 
     * @return EmbeddingStore 实例
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }

    public static void main(String[] args) {
        List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively("E:\\HackTools\\develop\\PluginExample\\untitled\\PayloadsAllTheThings-master");
        //InMemoryEmbeddingStore<TextSegment> store = null;
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor.ingest(documents, store); // throws AssertionError;
//        if (store.toString() != null) {
//            System.out.println("[RagProvider] 索引成功");
//        }
    }
}

