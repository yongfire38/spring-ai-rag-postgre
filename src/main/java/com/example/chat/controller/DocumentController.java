package com.example.chat.controller;

import org.springframework.web.bind.annotation.*;
import com.example.chat.service.DocumentService;
import com.example.chat.service.DocumentStatusResponse;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.chat.repository.DocumentMetadataRepository;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentMetadataRepository metadataRepository;

    @GetMapping("/status")
    public DocumentStatusResponse getStatus() {
        log.debug("문서 상태 조회 요청");
        long totalDbCount = metadataRepository.count();
        DocumentStatusResponse response = new DocumentStatusResponse(
                documentService.isProcessing(),
                documentService.getTotalCount(),
                documentService.getProcessedCount(),
                documentService.getChangedCount(),
                totalDbCount
        );
        log.debug("문서 상태 응답: {}", response);
        return response;
    }

    @PostMapping("/reindex")
    public String reindexDocuments() {
        log.info("문서 재인덱싱 요청 수신");
        try {
            documentService.loadDocumentsAsync()
                    .thenAccept(count -> log.info("재인덱싱 완료: {}개 청크 처리됨", count))
                    .exceptionally(throwable -> {
                        log.error("재인덱싱 중 오류 발생", throwable);
                        return null;
                    });
            log.info("비동기 재인덱싱 요청 성공");
            return "문서 재인덱싱이 시작되었습니다.";
        } catch (Exception e) {
            log.error("재인덱싱 요청 처리 중 오류", e);
            return "재인덱싱 요청 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFiles(@RequestParam("files") MultipartFile[] files) {
        Map<String, Object> result = documentService.uploadFiles(files);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }
} 