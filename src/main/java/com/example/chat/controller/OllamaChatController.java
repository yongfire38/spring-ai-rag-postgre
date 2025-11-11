package com.example.chat.controller;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.chat.context.SessionContext;
import com.example.chat.dto.ChatSession;
import com.example.chat.service.ChatSessionService;
import com.example.chat.service.SessionAwareChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OllamaChatController {

    private final OllamaChatModel chatModel;
    private final SessionAwareChatService sessionAwareChatService;
    private final ChatSessionService chatSessionService;

    @GetMapping("/ai/generate")
    public Map<String, String> generate(
            @RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        return Map.of("generation", this.chatModel.call(message));
    }

    @GetMapping("/ai/generateStream")
    public Flux<ChatResponse> generateStream(
            @RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        Prompt prompt = new Prompt(new UserMessage(message));
        return this.chatModel.stream(prompt);
    }

    /**
     * RAG 기반 스트리밍 응답 생성
     */
    @GetMapping("/ai/rag/stream")
    public Flux<ChatResponse> streamRagResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.info("RAG 기반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);
        
        // 세션 컨텍스트 설정
        if (sessionId != null && !sessionId.isEmpty()) {
            log.debug("세션 ID 검증 시작: {}", sessionId);
            if (chatSessionService.sessionExists(sessionId)) {
                log.debug("유효한 세션 ID 확인: {}", sessionId);
                SessionContext.setCurrentSessionId(sessionId);
                
                // 첫 메시지인 경우 세션 제목 업데이트
                List<Message> history = chatSessionService.getSessionMessages(sessionId);
                if (history.isEmpty()) {
                    log.debug("첫 메시지로 판단, 세션 제목 생성: {}", sessionId);
                    String title = chatSessionService.generateSessionTitle(message);
                    chatSessionService.updateSessionTitle(sessionId, title);
                } else {
                    log.debug("기존 세션 메시지 발견: {} - {} 개", sessionId, history.size());
                    // 마지막 메시지 시간 업데이트
                    chatSessionService.updateLastMessageTime(sessionId);
                }
            } else {
                log.warn("존재하지 않는 세션 ID: {}, 기본 세션으로 처리", sessionId);
                // 존재하지 않는 세션 ID인 경우 기본 세션으로 처리
                SessionContext.setCurrentSessionId(ChatMemory.DEFAULT_CONVERSATION_ID);
            }
        } else {
            log.warn("세션 ID가 제공되지 않음, 기본 세션으로 처리");
            // 세션 ID가 없는 경우 기본 세션으로 처리
            SessionContext.setCurrentSessionId(ChatMemory.DEFAULT_CONVERSATION_ID);
        }
        
        String currentSessionId = SessionContext.getCurrentSessionId();
        log.debug("현재 세션 컨텍스트 설정됨: {}", currentSessionId);
        
        return sessionAwareChatService.streamRagResponse(message, model)
                .doFinally(signalType -> {
                    // 스트리밍 완료 후 컨텍스트 정리
                    SessionContext.clear();
                    log.debug("SessionContext 정리 완료 - 세션: {}, 신호: {}", sessionId, signalType);
                });
    }

    /**
     * 일반 스트리밍 응답 생성
     */
    @GetMapping("/ai/simple/stream")
    public Flux<ChatResponse> streamSimpleResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.info("일반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);
        
        // 세션 컨텍스트 설정
        if (sessionId != null && !sessionId.isEmpty()) {
            if (chatSessionService.sessionExists(sessionId)) {
                SessionContext.setCurrentSessionId(sessionId);
                
                // 첫 메시지인 경우 세션 제목 업데이트
                List<Message> history = chatSessionService.getSessionMessages(sessionId);
                if (history.isEmpty()) {
                    String title = chatSessionService.generateSessionTitle(message);
                    chatSessionService.updateSessionTitle(sessionId, title);
                } else {
                    // 마지막 메시지 시간 업데이트
                    chatSessionService.updateLastMessageTime(sessionId);
                }
            } else {
                log.warn("존재하지 않는 세션 ID: {}, 기본 세션으로 처리", sessionId);
                // 존재하지 않는 세션 ID인 경우 기본 세션으로 처리
                SessionContext.setCurrentSessionId(ChatMemory.DEFAULT_CONVERSATION_ID);
            }
        } else {
            // 세션 ID가 없는 경우 기본 세션으로 처리
            SessionContext.setCurrentSessionId(ChatMemory.DEFAULT_CONVERSATION_ID);
        }
        
        // 일반 스트리밍 응답 생성 (RAG 없이)
        return sessionAwareChatService.streamSimpleResponse(message, model)
                .doFinally(signalType -> {
                    // 스트리밍 완료 후 컨텍스트 정리
                    SessionContext.clear();
                    log.debug("SessionContext 정리 완료 - 세션: {}, 신호: {}", sessionId, signalType);
                });
    }

    /**
     * 새로운 채팅 세션 생성
     */
    @GetMapping("/api/sessions/new")
    public ChatSession createNewSession() {
        log.info("새 채팅 세션 생성 요청");
        return chatSessionService.createNewSession();
    }

    /**
     * 모든 채팅 세션 목록 조회
     */
    @GetMapping("/api/sessions")
    public List<ChatSession> getAllSessions() {
        log.info("채팅 세션 목록 조회 요청");
        return chatSessionService.getAllSessions();
    }

    /**
     * 특정 세션 조회
     */
    @GetMapping("/api/sessions/{sessionId}")
    public ChatSession getSession(@RequestParam String sessionId) {
        log.info("채팅 세션 조회 요청: {}", sessionId);
        return chatSessionService.getSession(sessionId);
    }

    /**
     * 세션 삭제
     */
    @GetMapping("/api/sessions/{sessionId}/delete")
    public Map<String, String> deleteSession(@RequestParam String sessionId) {
        log.info("채팅 세션 삭제 요청: {}", sessionId);
        chatSessionService.deleteSession(sessionId);
        return Map.of("status", "success", "message", "세션이 삭제되었습니다");
    }
}