package com.ai.analyzer.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 轻量级内容检索器：按关键词命中次数为文档打分
 * 作为嵌入式检索不可用时的回退方案
 */
public class SimpleDocumentContentRetriever implements ContentRetriever {

    private static final int MAX_SEGMENT_CHARS = 1200;
    private static final int MIN_SEGMENT_CHARS = 400;

    private final List<Document> documents;
    private final List<DocumentSegment> segments;
    private final int maxResults;

    public SimpleDocumentContentRetriever(List<Document> documents) {
        this(documents, 5);
    }

    public SimpleDocumentContentRetriever(List<Document> documents, int maxResults) {
        this.documents = documents != null ? new ArrayList<>(documents) : Collections.emptyList();
        this.segments = buildSegments(this.documents);
        this.maxResults = Math.max(1, maxResults);
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (segments.isEmpty()) {
            return Collections.emptyList();
        }

        String queryText = query != null && query.text() != null
                ? query.text().toLowerCase(Locale.ROOT)
                : "";

        List<ScoredDocument> scored = new ArrayList<>();
        for (DocumentSegment segment : segments) {
            double score = scoreSegment(segment.textSegment().text(), queryText);
            if (score > 0) {
                scored.add(new ScoredDocument(segment, score));
            }
        }

        if (scored.isEmpty()) {
            // 若未匹配到任何关键词，直接返回前 maxResults 个分片
            return segments.stream()
                    .limit(maxResults)
                    .map(segment -> Content.from(segment.textSegment()))
                    .collect(Collectors.toList());
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(maxResults)
                .map(scoredDoc -> Content.from(scoredDoc.segment().textSegment()))
                .collect(Collectors.toList());
    }

    private List<DocumentSegment> buildSegments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        List<DocumentSegment> result = new ArrayList<>();
        for (Document document : docs) {
            if (document == null || document.text() == null || document.text().isBlank()) {
                continue;
            }

            Metadata metadata = document.metadata() != null ? document.metadata() : new Metadata();
            for (String chunk : splitIntoChunks(document.text())) {
                if (chunk.isBlank()) {
                    continue;
                }
                TextSegment textSegment = TextSegment.from(chunk, metadata);
                result.add(new DocumentSegment(textSegment));
            }
        }
        return result;
    }

    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        String[] blocks = text.split("\\r?\\n\\s*\\r?\\n");
        StringBuilder buffer = new StringBuilder();

        for (String rawBlock : blocks) {
            String block = rawBlock.strip();
            if (block.isEmpty()) {
                continue;
            }

            if (block.length() >= MAX_SEGMENT_CHARS) {
                flushBuffer(buffer, chunks);
                chunks.addAll(splitByLength(block));
                continue;
            }

            if (buffer.length() > 0 && buffer.length() + block.length() + 2 > MAX_SEGMENT_CHARS) {
                flushBuffer(buffer, chunks);
            }

            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(block);
        }

        flushBuffer(buffer, chunks);

        if (chunks.isEmpty() && !text.isBlank()) {
            chunks.add(text.strip());
        }

        return chunks;
    }

    private void flushBuffer(StringBuilder buffer, List<String> chunks) {
        if (buffer.length() == 0) {
            return;
        }
        if (buffer.length() >= MIN_SEGMENT_CHARS || chunks.isEmpty()) {
            chunks.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private List<String> splitByLength(String block) {
        List<String> slices = new ArrayList<>();
        int start = 0;
        int length = block.length();
        while (start < length) {
            int end = Math.min(start + MAX_SEGMENT_CHARS, length);
            slices.add(block.substring(start, end));
            start = end;
        }
        return slices;
    }

    private double scoreSegment(String documentText, String queryText) {
        if (queryText.isEmpty()) {
            return 1.0; // 没有查询时默认分
        }

        String docLower = documentText.toLowerCase(Locale.ROOT);
        String[] keywords = queryText.split("\\s+");
        double score = 0.0;
        for (String keyword : keywords) {
            if (keyword.isEmpty()) {
                continue;
            }
            if (docLower.contains(keyword)) {
                score += 1.0;
            }
        }
        return score;
    }

    private record DocumentSegment(TextSegment textSegment) {}
    private record ScoredDocument(DocumentSegment segment, double score) {}
}

