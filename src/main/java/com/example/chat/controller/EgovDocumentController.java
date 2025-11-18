package com.example.chat.controller;

import org.springframework.web.bind.annotation.*;
import com.example.chat.service.EgovDocumentService;
import com.example.chat.response.DocumentStatusResponse;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@CrossOrigin
public class EgovDocumentController {

    private final EgovDocumentService documentService;

    @GetMapping("/status")
    public DocumentStatusResponse getStatus() {
        return documentService.getStatusResponse();
    }

    @PostMapping("/reindex")
    public String reindexDocuments() {
        return documentService.reindexDocuments();
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFiles(@RequestParam("files") MultipartFile[] files) {
        Map<String, Object> result = documentService.uploadMarkdownFiles(files);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }
} 