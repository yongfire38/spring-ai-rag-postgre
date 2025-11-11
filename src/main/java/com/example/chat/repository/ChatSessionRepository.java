package com.example.chat.repository;

import com.example.chat.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 채팅 세션 JPA Repository
 */
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {
    
    /**
     * 모든 세션을 마지막 메시지 시간 순으로 조회
     */
    @Query("SELECT s FROM ChatSessionEntity s ORDER BY s.lastMessageAt DESC")
    List<ChatSessionEntity> findAllOrderByLastMessageAtDesc();
    
    /**
     * 세션 존재 여부 확인
     */
    boolean existsBySessionId(String sessionId);
}
