package com.example.chat.service.impl;

import com.example.chat.dto.ChatSession;
import com.example.chat.entity.ChatSessionEntity;
import com.example.chat.repository.ChatSessionRepository;
import com.example.chat.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMemory chatMemory;
    private final ChatClient chatClient;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public ChatSession createNewSession() {
        String sessionId = "session_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now();

        // JPA Entity로 세션 생성
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setSessionId(sessionId);
        entity.setTitle("새 채팅");
        entity.setCreatedAt(now);
        entity.setLastMessageAt(now);
        
        chatSessionRepository.save(entity);

        log.debug("새 채팅 세션 생성: {}", sessionId);
        return new ChatSession(sessionId, "새 채팅", now, now);
    }

    @Override
    public List<ChatSession> getAllSessions() {
        List<ChatSessionEntity> entities = chatSessionRepository.findAllOrderByLastMessageAtDesc();
        
        return entities.stream()
                .map(entity -> new ChatSession(
                    entity.getSessionId(),
                    entity.getTitle(),
                    entity.getCreatedAt(),
                    entity.getLastMessageAt()
                ))
                .toList();
    }

    @Override
    public ChatSession getSession(String sessionId) {
        return chatSessionRepository.findById(sessionId)
                .map(entity -> new ChatSession(
                    entity.getSessionId(),
                    entity.getTitle(),
                    entity.getCreatedAt(),
                    entity.getLastMessageAt()
                ))
                .orElse(null);
    }

    @Override
    public void deleteSession(String sessionId) {
        log.info("세션 삭제 시작: {}", sessionId);
        
        // 1. spring_ai_chat_memory 테이블에서 해당 세션의 모든 메시지 삭제
        try {
            String deleteMemorySql = "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?";
            int deletedMessages = jdbcTemplate.update(deleteMemorySql, sessionId);
            log.info("세션 {} 메시지 삭제 완료: {} 개", sessionId, deletedMessages);
        } catch (Exception e) {
            log.error("세션 {} 메시지 삭제 중 오류 발생: {}", sessionId, e.getMessage());
        }
        
        // 2. ChatMemory에서도 삭제 시도 (추가 보장)
        try {
            chatMemory.clear(sessionId);
            log.debug("ChatMemory에서 세션 {} 삭제 완료", sessionId);
        } catch (Exception e) {
            log.warn("ChatMemory에서 세션 {} 삭제 실패: {}", sessionId, e.getMessage());
        }
        
        // 3. 세션 정보 삭제
        try {
            chatSessionRepository.deleteById(sessionId);
            log.info("세션 정보 삭제 완료: {}", sessionId);
        } catch (Exception e) {
            log.error("세션 정보 삭제 중 오류 발생: {}", sessionId, e.getMessage());
        }
        
        log.info("세션 삭제 완료: {}", sessionId);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return chatSessionRepository.existsBySessionId(sessionId);
    }

    @Override
    public List<Message> getSessionMessages(String sessionId) {
        try {
            return chatMemory.get(sessionId);
        } catch (Exception e) {
            log.warn("세션 {} 메시지 조회 실패: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateSessionTitle(String sessionId, String title) {
        chatSessionRepository.findById(sessionId)
                .ifPresent(entity -> {
                    entity.setTitle(title);
                    chatSessionRepository.save(entity);
                    log.debug("세션 제목 업데이트: {} -> {}", sessionId, title);
                });
    }

    @Override
    public String generateSessionTitle(String firstMessage) {
        try {
            String prompt = "다음 메시지를 기반으로 간단한 채팅 제목을 생성해주세요 (최대 20자): " + firstMessage;
            
            String title = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
            
            // 제목 길이 제한 및 정리
            if (title != null && title.length() > 20) {
                title = title.substring(0, 20);
            }
            if (title != null) {
                title = title.replaceAll("[\\r\\n]", "").trim();
            }
            
            return (title == null || title.isEmpty()) ? "새 채팅" : title;
        } catch (Exception e) {
            log.warn("세션 제목 생성 실패: {}", e.getMessage());
            return "새 채팅";
        }
    }

    @Override
    public void updateLastMessageTime(String sessionId) {
        chatSessionRepository.findById(sessionId)
                .ifPresent(entity -> {
                    entity.setLastMessageAt(LocalDateTime.now());
                    chatSessionRepository.save(entity);
                    log.debug("세션 마지막 메시지 시간 업데이트: {}", sessionId);
                });
    }
}
