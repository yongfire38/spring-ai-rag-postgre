package com.example.chat.service.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.Objects;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

import com.example.chat.service.DocumentService;
import com.example.chat.repository.DocumentMetadataRepository;
import com.example.chat.model.DocumentMetadata;
import com.example.chat.util.DocumentHashUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final PgVectorStore pgVectorStore;

    private static final int BATCH_SIZE = 100;

    private final TextSplitter textSplitter;

    private final Executor executor;

    private final DocumentMetadataRepository metadataRepository;

    @Value("${spring.ai.document.path}")
    private String documentPath;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger changedCount = new AtomicInteger(0);

    private final ConcurrentHashMap<String, String> hashCache = new ConcurrentHashMap<>();

    @Override
    public boolean isProcessing() {
        return isProcessing.get();
    }

    @Override
    public int getProcessedCount() {
        return processedCount.get();
    }

    @Override
    public int getTotalCount() {
        return totalCount.get();
    }

    @Override
    public int getChangedCount() {
        return changedCount.get();
    }
    
    @Override
    public CompletableFuture<Integer> loadDocumentsAsync() {
        if (isProcessing.get()) {
            log.warn("이미 문서 처리가 진행 중입니다.");
            return CompletableFuture.completedFuture(0);
        }
        log.info("비동기 문서 로딩 시작");
        isProcessing.set(true);
        processedCount.set(0);
        totalCount.set(0);
        changedCount.set(0);

        // 단계별 비동기 체이닝
        return CompletableFuture.supplyAsync(this::loadMarkdownDocuments, executor)
            .exceptionally(throwable -> {
                log.error("문서 로딩 중 오류 발생", throwable);
                isProcessing.set(false);
                throw new RuntimeException("문서 로딩 실패", throwable);
            })
            .thenApplyAsync(documents -> {
                totalCount.set(documents.size());
                log.info("총 {}개의 문서를 로드했습니다.", documents.size());
                return documents;
            }, executor)
            // 분할 먼저 수행
            .thenApplyAsync(textSplitter::split, executor)
            // 분할된 청크에 chunk_index 부여
            .thenApplyAsync(this::assignChunkIndexes, executor)
            .exceptionally(throwable -> {
                log.error("문서 분할 또는 chunk_index 부여 중 오류 발생", throwable);
                isProcessing.set(false);
                throw new RuntimeException("문서 분할 또는 chunk_index 부여 실패", throwable);
            })
            // 분할된 청크 기준으로 변경 감지
            .thenApplyAsync(documents -> {
                // 2단계: 변경된 문서만 필터링
                List<Document> changedDocuments = filterChangedDocuments(documents);
                changedCount.set(changedDocuments.size());
                log.info("총 {}개의 문서 중 {}개의 변경된 문서를 처리합니다.",
                        documents.size(), changedDocuments.size());
                return changedDocuments;
            }, executor)
            .exceptionally(throwable -> {
                log.error("문서 필터링 중 오류 발생", throwable);
                isProcessing.set(false);
                throw new RuntimeException("문서 필터링 실패", throwable);
            })
            .thenApplyAsync(changedDocuments -> {
                log.info("{}개 청크만 변경되어 처리합니다.", changedDocuments.size());
                return changedDocuments;
            }, executor)
            .exceptionally(throwable -> {
                log.error("변경된 문서 로깅 중 오류 발생", throwable);
                isProcessing.set(false);
                throw new RuntimeException("변경된 문서 로깅 실패", throwable);
            })
            .thenApplyAsync(this::processChunksInSmallBatches, executor)
            .exceptionally(throwable -> {
                log.error("청크 처리 중 오류 발생", throwable);
                isProcessing.set(false);
                throw new RuntimeException("청크 처리 실패", throwable);
            })
            .handle((result, ex) -> {
                isProcessing.set(false);
                if (ex != null) {
                    log.error("비동기 문서 처리 중 오류 발생", ex);
                    throw new RuntimeException("문서 처리 중 오류 발생", ex);
                }
                log.info("비동기 문서 로딩 완료: 총 {}개 문서 중 {}개 변경, {}개 청크 처리됨",
                    totalCount.get(), changedCount.get(), result);
                return result;
            });
    }
    
    private int processChunksInSmallBatches(List<Document> splitDocuments) {
        if (splitDocuments == null || splitDocuments.isEmpty()) {
            return 0;
        }
        List<Document> processedDocuments = new ArrayList<>();
        Map<String, Integer> chunkCountByDocument = new ConcurrentHashMap<>();
        int total = splitDocuments.size();
        for (Document chunk : splitDocuments) {
            try {
                Optional<Document> processedChunk = processChunkWithIndex(chunk, chunkCountByDocument);
                if (processedChunk.isPresent()) {
                    Document doc = processedChunk.get();
                    processedDocuments.add(doc);
                    int current = processedCount.incrementAndGet();
                    if (current % 10 == 0 || current == total) {
                        log.info("진행률: {}/{} ({}%)", current, total, (current * 100) / total);
                    }
                }
            } catch (Exception e) {
                log.error("청크 처리 중 오류 발생: {}", chunk.getId(), e);
            }
        }
        try {
            List<Document> batch = new ArrayList<>(BATCH_SIZE);
            for (Document doc : processedDocuments) {
                batch.add(doc);
                if (batch.size() >= BATCH_SIZE) {
                    pgVectorStore.add(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                pgVectorStore.add(batch);
            }
            // 인덱싱 후 메타데이터 갱신 (filename, chunkIndex 기준 upsert)
            List<DocumentMetadata> metadataToSave = new ArrayList<>();
            for (Document doc : processedDocuments) {
                String filename = (String) doc.getMetadata().get("source");
                int chunkIndex = (int) doc.getMetadata().getOrDefault("chunk_index", 0);
                
                // 캐시된 해시값 사용 (중복 계산 방지)
                String cacheKey = filename + "_" + chunkIndex;
                String hash = hashCache.get(cacheKey);
                if (hash == null) {
                    // 캐시에 없으면 계산 (안전장치)
                    hash = DocumentHashUtil.calculateHash(doc.getText());
                    log.warn("해시 캐시 미스: filename={}, chunkIndex={}", filename, chunkIndex);
                }
                
                log.debug("메타데이터 준비: filename={}, chunkIndex={}", filename, chunkIndex);
                
                Optional<DocumentMetadata> existing = metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);
                if (existing.isPresent()) {
                    DocumentMetadata meta = existing.get();
                    meta.setContentHash(hash);
                    meta.setIndexedAt(LocalDateTime.now());
                    metadataToSave.add(meta);
                } else {
                    DocumentMetadata meta = new DocumentMetadata(null, filename, chunkIndex, hash, LocalDateTime.now());
                    metadataToSave.add(meta);
                }
            }
            
            // 배치로 메타데이터 저장
            if (!metadataToSave.isEmpty()) {
                metadataRepository.saveAll(metadataToSave);
                metadataRepository.flush();  // 한 번만 flush 호출
                log.info("메타데이터 배치 저장 완료: {}개", metadataToSave.size());
            }
            
            // 해시 캐시 정리
            hashCache.clear();
            
            log.info("총 {}개 청크 처리 완료", processedDocuments.size());
            return processedDocuments.size();
        } catch (Exception e) {
            log.error("문서 처리 중 오류 발생", e);
            return 0;
        }
    }

    private List<Document> loadMarkdownDocuments() {
        List<Document> documents = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(documentPath);
            log.info("{}개의 마크다운 파일을 찾았습니다.", resources.length);
            for (Resource resource : resources) {
                try {
                    String filename = resource.getFilename();
                    if (filename == null) {
                        log.warn("파일명이 null입니다: {}", resource.getDescription());
                        continue;
                    }
                    log.info("파일 처리 시작: {}", filename);
                    String content = readResourceContent(resource);
                    if (content == null || content.trim().isEmpty()) {
                        log.warn("빈 파일 건너뜀: {}", filename);
                        continue;
                    }
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", filename);
                    metadata.put("type", "markdown");
                    String docId = UUID.randomUUID().toString();
                    Document doc = new Document(docId, content, metadata);
                    documents.add(doc);
                    log.info("문서 로드 완료: {}, 크기: {}바이트", filename, content.length());
                } catch (IOException e) {
                    log.error("파일 읽기 오류: {}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.error("리소스 검색 중 오류 발생", e);
        }
        return documents;
    }

    private String readResourceContent(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private Optional<Document> processChunkWithIndex(Document chunk, Map<String, Integer> chunkCountByDocument) {
        return getSafeText(chunk).map(chunkText -> {
            String source = (String) chunk.getMetadata().get("source");
            String stableId = "";
            if (source != null && !source.isEmpty()) {
                stableId = source.replaceAll("\\.md$", "").replaceAll("[^a-zA-Z0-9가-힣]", "_");
            } else {
                stableId = "unknown_document";
            }
            int chunkIndex = chunkCountByDocument.getOrDefault(stableId, 0) + 1;
            chunkCountByDocument.put(stableId, chunkIndex);
            
            // UUID 형식으로 Document ID 생성
            String stableChunkId = UUID.randomUUID().toString();
            
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("original_document_id", stableId);
            metadata.put("chunk_index", chunkIndex);
            return new Document(stableChunkId, chunkText, metadata);
        });
    }
    
    private Optional<String> getSafeText(Document document) {
        try {
            return Optional.ofNullable(document.getText());
        } catch (Exception e) {
            log.warn("Document에서 텍스트 추출 실패", e);
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Object> uploadFiles(MultipartFile[] files) {
        Map<String, Object> result = new HashMap<>();
        String uploadDir = "C:/workspace-test/upload/data";
        int maxFiles = 5;
        long maxFileSize = 10 * 1024 * 1024; // 10MB
        long maxTotalSize = 30 * 1024 * 1024; // 30MB
        // 유효성 검사
        if (files.length == 0) {
            result.put("success", false);
            result.put("message", "업로드할 파일이 없습니다.");
            return result;
        }
        if (files.length > maxFiles) {
            result.put("success", false);
            result.put("message", "최대 5개 파일만 업로드할 수 있습니다.");
            return result;
        }
        long totalSize = 0;
        for (MultipartFile file : files) {
            if (!Objects.requireNonNull(file.getOriginalFilename()).toLowerCase().endsWith(".md")) {
                result.put("success", false);
                result.put("message", "마크다운(.md) 파일만 업로드 가능합니다.");
                return result;
            }
            if (file.getSize() > maxFileSize) {
                result.put("success", false);
                result.put("message", file.getOriginalFilename() + " 파일이 10MB를 초과합니다.");
                return result;
            }
            totalSize += file.getSize();
        }
        if (totalSize > maxTotalSize) {
            result.put("success", false);
            result.put("message", "전체 파일 용량이 30MB를 초과합니다.");
            return result;
        }
        // 업로드 디렉토리 생성
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();
        try {
            for (MultipartFile file : files) {
                Path dest = Path.of(uploadDir, file.getOriginalFilename());
                Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "파일 저장 중 오류 발생: " + e.getMessage());
            return result;
        }
        result.put("success", true);
        result.put("message", "업로드 성공");
        return result;
    }
    
    // 변경된 청크만 필터링 (filename, chunkIndex 기준)
    private List<Document> filterChangedDocuments(List<Document> splitDocuments) {
        return splitDocuments.stream()
            .filter(this::isDocumentChanged)
            .collect(Collectors.toList());
    }

    // 청크가 변경되었는지 해시값으로 판단 (filename, chunkIndex 기준)
    private boolean isDocumentChanged(Document chunk) {
        String filename = (String) chunk.getMetadata().get("source");
        int chunkIndex = (int) chunk.getMetadata().getOrDefault("chunk_index", 0);
        String content = chunk.getText();
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        String newHash = DocumentHashUtil.calculateHash(content);
        
        // 해시값을 캐시에 저장 (processChunksInSmallBatches에서 재사용)
        String cacheKey = filename + "_" + chunkIndex;
        hashCache.put(cacheKey, newHash);
        
        Optional<DocumentMetadata> existing = metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);
        if (existing.isPresent()) {
            String oldHash = existing.get().getContentHash();
            if (oldHash != null && oldHash.equals(newHash)) {
                log.debug("청크 '{}' (index {}) 변경 없음 (해시: {})", filename, chunkIndex, newHash);
                return false;
            }
        }
        return true;
    }

    // 분할된 청크에 chunk_index 부여
    private List<Document> assignChunkIndexes(List<Document> splitDocuments) {
        Map<String, Integer> chunkCountByDocument = new HashMap<>();
        List<Document> result = new ArrayList<>();
        for (Document chunk : splitDocuments) {
            String source = (String) chunk.getMetadata().get("source");
            String stableId = (source != null && !source.isEmpty())
                ? source.replaceAll("\\.md$", "").replaceAll("[^a-zA-Z0-9가-힣]", "_")
                : "unknown_document";
            int chunkIndex = chunkCountByDocument.getOrDefault(stableId, 0) + 1;
            chunkCountByDocument.put(stableId, chunkIndex);

            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("chunk_index", chunkIndex);

            result.add(new Document(chunk.getId(), chunk.getText(), metadata));
        }
        return result;
    }
} 