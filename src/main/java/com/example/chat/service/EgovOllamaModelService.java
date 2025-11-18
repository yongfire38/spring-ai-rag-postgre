package com.example.chat.service;

import java.util.List;

/**
 * Ollama 모델 관리 서비스 인터페이스
 */
public interface EgovOllamaModelService {
    
    /**
     * Ollama가 사용 가능한지 확인
     */
    boolean isOllamaAvailable();
    
    /**
     * 설치된 모델 목록 조회
     */
    List<String> getInstalledModels();
}
