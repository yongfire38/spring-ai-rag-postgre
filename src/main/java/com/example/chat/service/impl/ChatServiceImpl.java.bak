package com.example.chat.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import com.example.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.memory.ChatMemory;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final Advisor retrievalAugmentationAdvisor;
    private final ChatClient ollamaChatClient;

    @Override
    public Flux<ChatResponse> streamRagResponse(String query, String conversationId) {
        log.info("RAG 기반 스트리밍 질의 수신 (컨텍스트): {}, 세션: {}", query, conversationId);
        try {
            return ollamaChatClient.prompt()
                .advisors(retrievalAugmentationAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(query).stream().chatResponse();
        } catch (Exception e) {
            log.error("AI 스트리밍 응답 생성 중 오류 발생", e);
            return Flux.error(e);
        }
    }
    
    @Override
    public Flux<ChatResponse> streamSimpleResponse(String query, String conversationId) {
        log.info("일반 스트리밍 질의 수신 (컨텍스트): {}, 세션: {}", query, conversationId);
        try {
            // Chat Memory 어드바이저만 추가 (RAG 없음)
            return ollamaChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(query).stream().chatResponse();
        } catch (Exception e) {
            log.error("AI 스트리밍 응답 생성 중 오류 발생", e);
            return Flux.error(e);
        }
    }
}