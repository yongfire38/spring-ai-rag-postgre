package com.example.chat.config.rag.transformers;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EgovCompressionQueryTransformer {

    private final ChatMemory chatMemory;
    private final ChatClient chatClient;
    
    public EgovCompressionQueryTransformer(ChatMemory chatMemory, ChatClient chatClient) {
        this.chatMemory = chatMemory;
        this.chatClient = chatClient;
    }

    
    /**
     * 세션 ID를 직접 전달받아 히스토리 압축을 수행하는 메서드
     */
    public Query transformWithSessionId(@NonNull Query query, String sessionId) {
        log.info("히스토리 압축 시작 - 세션 ID: {}, 원본 질문: {}", sessionId, query.text());
        
        // 세션 ID가 유효하지 않은 경우 히스토리 압축 건너뛰기
        if (sessionId == null || sessionId.trim().isEmpty() || "default".equals(sessionId)) {
            log.warn("유효하지 않은 세션 ID: {} - 히스토리 압축 건너뛰기", sessionId);
            return query;
        }
        
        String originalQuery = query.text();
        log.debug("원본 질문: {}", originalQuery);
        
        // 질문이 너무 짧거나 불완전한 경우 히스토리 압축을 건너뛰고 원본 반환
        if (isIncompleteQuery(originalQuery)) {
            log.info("불완전한 질문으로 판단, 히스토리 압축 건너뛰기: {}", originalQuery);
            return query;
        }
        
        // 세션 히스토리 조회
        List<Message> conversationHistory;
        try {
            conversationHistory = chatMemory.get(sessionId);
            
            if (conversationHistory.isEmpty()) {
                log.info("대화 히스토리가 없음: {}", sessionId);
                return query;
            }
            
            log.info("대화 히스토리 발견: {} - {} 개 메시지", sessionId, conversationHistory.size());
            
        } catch (Exception e) {
            log.warn("세션 {} 히스토리 조회 중 오류 발생 - 히스토리 압축 건너뛰기: {}", sessionId, e.getMessage());
            return query;
        }
        
        // 대화 히스토리 분석 및 로깅
        log.info("대화 히스토리 분석 시작 - {} 개 메시지", conversationHistory.size());
        for (int i = 0; i < conversationHistory.size(); i++) {
            Message msg = conversationHistory.get(i);
            String content = msg.getText();
            String preview = content.length() > 100 ? content.substring(0, 100) + "..." : content;
            log.info("히스토리 {}: {} - {}", i + 1, msg.getClass().getSimpleName(), preview);
        }
        
        // 대화 히스토리를 Query 객체로 변환
        Query queryWithHistory = Query.builder()
            .text(query.text())
            .history(conversationHistory)
            .build();
        
        // Spring AI 기본 CompressionQueryTransformer 사용
        // temperature를 낮춰서 더 정확하고 일관된 압축 결과 생성
        CompressionQueryTransformer compressionTransformer = CompressionQueryTransformer.builder()
            .chatClientBuilder(chatClient.mutate()
                .defaultOptions(ChatOptions.builder()
                    .temperature(0.00) // 공식 문서 권장: 정확한 질문 압축을 위해 낮은 temperature 사용
                    .build()))
            .build();
        
        Query compressedQuery = compressionTransformer.transform(queryWithHistory);
        String compressedText = compressedQuery.text();
        
        log.info("압축 전 원본 텍스트: '{}'", compressedText);
        
        // <think> 블록 제거
        if (compressedText.contains("<think>")) {
            log.info("<think> 블록 감지됨, 제거 시작");
            // <think> 블록 이후의 실제 질문만 추출
            String[] parts = compressedText.split("</think>");
            if (parts.length > 1) {
                compressedText = parts[1].trim();
                log.info("</think> 이후 텍스트 추출: '{}'", compressedText);
            } else {
                // </think>가 없는 경우 <think> 이후 부분만 추출
                int thinkIndex = compressedText.indexOf("<think>");
                if (thinkIndex != -1) {
                    compressedText = compressedText.substring(thinkIndex + 7).trim();
                    log.info("<think> 이후 텍스트 추출: '{}'", compressedText);
                }
            }
        } else {
            log.info("<think> 블록 없음, 원본 텍스트 사용: '{}'", compressedText);
        }
        
        log.info("최종 압축된 질문: '{}'", compressedText);

        // 정리된 텍스트로 Query 재생성
        // history를 유지하여 downstream RAG 컴포넌트가 맥락을 활용할 수 있도록 함
        Query finalQuery = Query.builder()
            .text(compressedText)
            .history(conversationHistory)  // 히스토리 보존
            .build();

        return finalQuery;
    }
    
    /**
     * 질문이 불완전한지 확인
     * 너무 짧거나, 맥락이 필요한 질문인지 판단
     */
    private boolean isIncompleteQuery(String query) {
        if (query == null || query.trim().length() < 5) {
            return true;
        }
        
        // 매우 짧은 질문만 불완전으로 판단
        String[] words = query.trim().split("\\s+");
        if (words.length < 2) {
            return true;
        }
        
        // 단일 단어나 매우 짧은 질문만 불완전으로 판단
        if (query.trim().length() < 5) {
            return true;
        }
        
        return false;
    }
}