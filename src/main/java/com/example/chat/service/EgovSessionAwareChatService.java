package com.example.chat.service;

import org.springframework.ai.chat.model.ChatResponse;

import com.example.chat.response.TechnologyResponse;

import reactor.core.publisher.Flux;

/**
 * 세션별 대화 기능을 제공하는 채팅 서비스 인터페이스
 */
public interface EgovSessionAwareChatService {

    /**
     * 세션별 RAG 기반 스트리밍 응답 생성
     */
    Flux<ChatResponse> streamRagResponse(String query, String model);

    /**
     * 세션별 일반 스트리밍 응답 생성
     */
    Flux<ChatResponse> streamSimpleResponse(String query, String model);

    /**
     * JSON 구조화된 출력 - 기술 정보
     *
     * @param query 사용자 질의
     * @return JSON 구조화된 기술 정보 응답
     */
    TechnologyResponse getTechnologyInfoAsJson(String query);
}
