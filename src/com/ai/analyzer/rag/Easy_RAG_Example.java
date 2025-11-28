package com.ai.analyzer.rag;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
//import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
//import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import com.ai.analyzer.Agent.Assistant;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Easy_RAG_Example {
                
    private static final QwenStreamingChatModel streamingChatModel = QwenStreamingChatModel.builder()
                .apiKey("sk-f5ab54a742f64f12a4b7213b0011f44c") //key泄露了，我删掉了
                .baseUrl("https://dashscope.aliyuncs.com/api/v1")
                .modelName("qwen-max")
                .build();

    /**
     * This example demonstrates how to implement an "Easy RAG" (Retrieval-Augmented Generation) application.
     * By "easy" we mean that we won't dive into all the details about parsing, splitting, embedding, etc.
     * All the "magic" is hidden inside the "langchain4j-easy-rag" module.
     * <p>
     * If you want to learn how to do RAG without the "magic" of an "Easy RAG", see {@link Naive_RAG_Example}.
     */

    public static void main(String[] args) throws Exception {
        System.out.println("开始加载文档...");

        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:**.{md}");

        // First, let's load documents that we want to use for RAG
        List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively(
                "E:\\HackTools\\develop\\Ai_anaylzer\\PayloadsAllTheThings-master",
                pathMatcher);
        //List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively("E:\\HackTools\\develop\\Ai_anaylzer\\PayloadsAllTheThings-master\\Denial of Service");
        System.out.println("加载了 " + documents.size() + " 个文档");

        // Second, let's create an assistant that will have access to our documents
        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatModel(streamingChatModel) // 添加流式聊天模型
                //.chatMemory(MessageWindowChatMemory.withMaxMessages(10)) // it should remember 10 latest messages
                .contentRetriever(createContentRetriever(documents)) // it should have access to our documents
                .build();
        System.out.println("创建了 Assistant 服务");

        // Lastly, let's start the conversation with the assistant. We can ask questions like:
        // - Can I cancel my reservation?
        // - I had an accident, should I pay extra?
        System.out.println("发送查询: ");
        TokenStream tokenStream = assistant.chat(Collections.singletonList(
                new UserMessage("讲讲命令注入漏洞?")));
        
        CompletableFuture<AiMessage> future = new CompletableFuture<>();
        tokenStream.onPartialResponse((String partialResponse) -> {
            System.out.print(partialResponse);
        })
                  //.onComplete(future::complete)
                  .onError(future::completeExceptionally)
                  .start();
        
        try {
            AiMessage message = future.get(120, TimeUnit.SECONDS); // 增加超时时间到120秒
            System.out.println("\n完整响应: " + message.text());
        } catch (Exception e) {
            System.err.println("\n发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static ContentRetriever createContentRetriever(List<Document> documents) {
        System.out.println("创建基于Embedding的ContentRetriever");
        // 使用 embedding model 和 embedding store 创建基于向量检索的 ContentRetriever
        // 使用 ONNX 实现的轻量级嵌入模型
        EmbeddingModel embeddingModel = new OnnxEmbeddingModel(
                "E:\\HackTools\\develop\\all-minilm-l6-v2.onnx",
                "E:\\HackTools\\develop\\all-minilm-l6-v2-tokenizer.json",
                PoolingMode.MEAN
        );
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        
        // 将文档转换为 embedding 并存储在 embedding store 中
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
        ingestor.ingest(documents);
        
        // 创建基于 embedding store 的 ContentRetriever
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.0)
                .build();
    }
}