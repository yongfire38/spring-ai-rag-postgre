package com.example.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅 세션 정보를 담는 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime lastMessageAt;
}
