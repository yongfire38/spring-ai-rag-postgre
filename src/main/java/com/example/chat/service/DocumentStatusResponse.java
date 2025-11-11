package com.example.chat.service;

/**
 * 문서 처리 상태 응답 DTO
 * 
 * @param processing 현재 처리 중인지 여부
 * @param totalCount 이번 처리에서 로드된 총 문서 수
 * @param processedCount 이번 처리에서 완료된 청크 수
 * @param changedCount 이번 처리에서 변경된 청크 수
 * @param totalDbCount DB에 저장된 총 청크 수 (기존 데이터)
 */
public record DocumentStatusResponse(
    boolean processing,
    int totalCount,
    int processedCount,
    int changedCount,
    long totalDbCount
) {
    // 기존 생성자에 대한 호환성 유지
    public DocumentStatusResponse(boolean processing, int processedCount, int totalCount) {
        this(processing, processedCount, totalCount, 0, 0);
    }
} 