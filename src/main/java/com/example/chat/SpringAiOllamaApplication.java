package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.example.chat.service.DocumentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@RequiredArgsConstructor
public class SpringAiOllamaApplication {

    private final DocumentService documentService;
    
    public static void main(String[] args) {
        SpringApplication.run(SpringAiOllamaApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeDocuments() {
        log.info("문서 인덱싱을 비동기적으로 시작합니다...");
        
        documentService.loadDocumentsAsync()
                .thenAccept(count -> log.info("문서 인덱싱 완료: {}개 청크 처리됨", count))
                .exceptionally(throwable -> { 
                    log.error("문서 인덱싱 중 오류 발생", throwable);
                    return null; 
                });
    }
} 