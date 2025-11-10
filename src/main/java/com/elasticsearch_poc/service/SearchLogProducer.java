package com.elasticsearch_poc.service;

import com.elasticsearch_poc.dto.kafka.KeywordDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Service
public class SearchLogProducer {

    private static final Logger log = LoggerFactory.getLogger(SearchLogProducer.class);
    
    private final KafkaTemplate<String, KeywordDto> kafkaTemplate;
    
    @Value("${kafka.topic.search-log:search-log}")
    private String searchLogTopic;

    public SearchLogProducer(KafkaTemplate<String, KeywordDto> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 검색어를 Kafka의 search-log 토픽으로 전송
     * @param keyword 검색어
     * @param userId 사용자 ID (선택사항, null 가능)
     */
    public void sendSearchLog(String keyword, String userId) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }

        KeywordDto keywordDto = new KeywordDto(
                userId != null ? userId : "anonymous",
                keyword.trim(),
                LocalDateTime.now()
        );

        try {
            CompletableFuture<SendResult<String, KeywordDto>> future = 
                    kafkaTemplate.send(searchLogTopic, keyword, keywordDto);
            
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("검색 로그 전송 성공: keyword={}, topic={}, partition={}, offset={}",
                            keyword, searchLogTopic, 
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("검색 로그 전송 실패: keyword={}, error={}", keyword, ex.getMessage(), ex);
                }
            });
        } catch (Exception e) {
            log.error("검색 로그 전송 중 예외 발생: keyword={}, error={}", keyword, e.getMessage(), e);
        }
    }
}

