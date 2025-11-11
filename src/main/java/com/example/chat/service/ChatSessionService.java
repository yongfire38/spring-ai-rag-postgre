package com.example.chat.service;

import com.example.chat.dto.ChatSession;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 채팅 세션 관리 서비스 인터페이스
 */
public interface ChatSessionService {
    
    /**
     * 새로운 채팅 세션 생성
     */
    ChatSession createNewSession();
    
    /**
     * 모든 세션 목록 조회
     */
    List<ChatSession> getAllSessions();
    
    /**
     * 특정 세션 조회
     */
    ChatSession getSession(String sessionId);
    
    /**
     * 세션 삭제
     */
    void deleteSession(String sessionId);
    
    /**
     * 세션 존재 여부 확인
     */
    boolean sessionExists(String sessionId);
    
    /**
     * 세션의 메시지 목록 조회
     */
    List<Message> getSessionMessages(String sessionId);
    
    /**
     * 세션 제목 업데이트
     */
    void updateSessionTitle(String sessionId, String title);
    
    /**
     * 세션 제목 자동 생성
     */
    String generateSessionTitle(String firstMessage);
    
    /**
     * 마지막 메시지 시간 업데이트
     */
    void updateLastMessageTime(String sessionId);
}
