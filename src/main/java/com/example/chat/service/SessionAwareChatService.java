package com.example.chat.service;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 세션별 대화 기능을 제공하는 채팅 서비스 인터페이스
 */
public interface SessionAwareChatService {
    
    /**
     * 세션별 RAG 기반 스트리밍 응답 생성
     */
    Flux<ChatResponse> streamRagResponse(String query, String model);
    
    /**
     * 세션별 일반 스트리밍 응답 생성
     */
    Flux<ChatResponse> streamSimpleResponse(String query, String model);
}
