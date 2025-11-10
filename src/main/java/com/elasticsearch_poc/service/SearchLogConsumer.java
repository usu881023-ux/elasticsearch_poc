package com.elasticsearch_poc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.elasticsearch_poc.dto.kafka.KeywordDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@Service
public class SearchLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(SearchLogConsumer.class);
    
    private final PopularRecentService popularRecentService;
    private final ElasticsearchClient esClient;

    @Value("${elasticsearch.search-log-index:search_log}")
    private String searchLogIndex;

    public SearchLogConsumer(PopularRecentService popularRecentService, 
                           ElasticsearchClient esClient) {
        this.popularRecentService = popularRecentService;
        this.esClient = esClient;
    }

    /**
     * Kafka의 search-log 토픽에서 검색 로그를 소비하여 인기 검색어 통계 업데이트
     * @param keywordDto 검색 로그 데이터
     */
    @KafkaListener(topics = "${kafka.topic.search-log:search-log}", 
                   groupId = "search-log-group",
                   containerFactory = "kafkaListenerContainerFactory")
    public void consumeSearchLog(KeywordDto keywordDto) {
        try {
            String keyword = keywordDto.getKeyword();
            if (keyword == null || keyword.isBlank()) {
                log.warn("빈 검색어가 수신되었습니다: {}", keywordDto);
                return;
            }

            // 1. PopularRecentService를 통해 인기 검색어 통계 업데이트 (인메모리 - 실시간용)
            popularRecentService.recordQuery(keyword);
            
            // 2. Elasticsearch search_log 인덱스에 영구 저장
            saveToElasticsearch(keywordDto);
            
            log.info("✅ 검색 로그 처리 완료: keyword={}, userId={}, timestamp={}",
                    keyword, keywordDto.getUserId(), keywordDto.getTimestamp());
        } catch (Exception e) {
            log.error("❌ 검색 로그 처리 중 오류 발생: keywordDto={}, error={}", 
                    keywordDto, e.getMessage(), e);
        }
    }

    /**
     * Elasticsearch search_log 인덱스에 검색 로그 저장
     * @param keywordDto 검색 로그 데이터
     */
    private void saveToElasticsearch(KeywordDto keywordDto) throws IOException {
        Map<String, Object> document = new HashMap<>();
        document.put("keyword", keywordDto.getKeyword());
        document.put("userId", keywordDto.getUserId());
        
        // LocalDateTime을 Instant로 변환하여 저장 (Elasticsearch 호환)
        if (keywordDto.getTimestamp() != null) {
            document.put("timestamp", keywordDto.getTimestamp()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli());
        }

        esClient.index(i -> i
            .index(searchLogIndex)
            .document(document)
        );

        log.info("📝 Elasticsearch 저장 완료: index={}, keyword={}",
                searchLogIndex, keywordDto.getKeyword());
    }
}

