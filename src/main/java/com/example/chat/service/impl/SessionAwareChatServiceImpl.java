package com.example.chat.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Service;

import com.example.chat.context.SessionContext;
import com.example.chat.config.RagConfig;
import com.example.chat.config.rag.transformers.EgovCompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import com.example.chat.service.SessionAwareChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionAwareChatServiceImpl implements SessionAwareChatService {

    private final ChatClient ollamaChatClient;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final EgovCompressionQueryTransformer compressionTransformer;
    private final VectorStoreDocumentRetriever vectorStoreDocumentRetriever;
    

    @Override
    public Flux<ChatResponse> streamRagResponse(String query, String model) {
        String sessionId = SessionContext.getCurrentSessionId();
        log.info("세션별 RAG 기반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", query, model, sessionId);

        try {
            log.debug("세션 {} RAG 응답 생성 시작", sessionId);
            validateSessionId(sessionId);

            // 히스토리 압축 적용 (일반 질의와 동일한 단일처리)
            String compressedQuery = compressQueryWithHistory(query, sessionId);

            // 압축된 질문으로 ChatClient RequestSpec 생성
            ChatClientRequestSpec requestSpec = createRequestSpec(compressedQuery, model);

            // RAG 어드바이저 생성 (QueryTransformer 없이 DocumentRetriever만 사용)
            log.info("RAG 어드바이저 생성 시작 - 세션: {}, 압축된 질문: '{}'", sessionId, compressedQuery);
            Advisor ragAdvisor = RagConfig.createRagAdvisor(vectorStoreDocumentRetriever);
            log.info("RAG 어드바이저 생성 완료 - 세션: {}", sessionId);

            log.info("RAG 스트리밍 시작 - 세션: {}, 압축된 질문: '{}'", sessionId, compressedQuery);
            
            // ChatMemory 어드바이저와 RAG 어드바이저 적용
            return requestSpec
                    .advisors(messageChatMemoryAdvisor, ragAdvisor)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .chatResponse();

        } catch (Exception e) {
            log.error("세션별 RAG 스트리밍 응답 생성 중 오류 발생 - 세션: {}", sessionId, e);
            return Flux.error(e);
        }
    }

    /**
     * 세션별 일반 스트리밍 응답 생성
     */
    @Override
    public Flux<ChatResponse> streamSimpleResponse(String query, String model) {
        String sessionId = SessionContext.getCurrentSessionId();
        log.info("세션별 일반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", query, model, sessionId);

        try {
            log.debug("세션 {} 일반 응답 생성 시작", sessionId);

            // 히스토리 압축 적용
            String compressedQuery = compressQueryWithHistory(query, sessionId);

            // 압축된 질문으로 ChatClient RequestSpec 생성
            ChatClient.ChatClientRequestSpec requestSpec = createRequestSpec(compressedQuery, model);

            // ChatMemory 어드바이저만 적용 (RAG 없음)
            return requestSpec
                    .advisors(messageChatMemoryAdvisor)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .chatResponse();

        } catch (Exception e) {
            log.error("세션별 일반 스트리밍 응답 생성 중 오류 발생 - 세션: {}", sessionId, e);
            return Flux.error(e);
        }
    }
    

    /**
     * ChatClient RequestSpec을 생성하는 공통 메서드
     *
     * @param query 사용자 질문
     * @param model 모델 이름 (null 가능)
     * @return 구성된 ChatClient.ChatClientRequestSpec
     */
    private ChatClient.ChatClientRequestSpec createRequestSpec(String query, String model) {
        ChatClient.ChatClientRequestSpec requestSpec = ollamaChatClient.prompt().user(query);

        if (model != null && !model.trim().isEmpty()) {
            requestSpec = requestSpec.options(ChatOptions.builder()
                .model(model)
                .temperature(0.3)
                .build());
            log.debug("지정된 모델로 응답 생성: {}", model);
        } else {
            log.debug("기본 모델로 응답 생성");
        }

        return requestSpec;
    }

    /**
     * 세션 ID 검증 및 경고 로깅
     */
    private void validateSessionId(String sessionId) {
        if ("default".equals(sessionId)) {
            log.warn("세션 ID가 'default'로 설정됨 - 세션 관리에 문제가 있을 수 있습니다");
        }
    }

    /**
     * 히스토리 기반 질문 압축
     *
     * @param query 원본 질문
     * @param sessionId 세션 ID
     * @return 압축된 질문 (압축 실패 시 원본 질문)
     */
    private String compressQueryWithHistory(String query, String sessionId) {
        try {
            log.info("일반 채팅 히스토리 압축 시작 - 세션 ID: {}, 원본 질문: {}", sessionId, query);
            Query originalQuery = Query.builder().text(query).build();
            Query compressedQueryObj = compressionTransformer.transformWithSessionId(originalQuery, sessionId);
            String compressedQuery = compressedQueryObj.text();
            log.info("일반 채팅 히스토리 압축 완료 - 원본: '{}' → 압축: '{}'", query, compressedQuery);
            return compressedQuery;
        } catch (Exception e) {
            log.warn("일반 채팅 히스토리 압축 중 오류 발생, 원본 질문 사용: {}", e.getMessage());
            return query;
        }
    }
}
