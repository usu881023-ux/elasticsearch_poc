package com.elasticsearch_poc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.elasticsearch_poc.dto.kafka.KeywordDto;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 검색 로그 통합 테스트
 * Kafka Producer -> Consumer -> Elasticsearch 전체 플로우 테스트
 */
@SpringBootTest
@TestPropertySource(properties = {
    "popular.use-elasticsearch=true",
    "spring.kafka.bootstrap-servers=localhost:9092"
})
class SearchLogIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchLogIntegrationTest.class);

    @Autowired
    private KafkaTemplate<String, KeywordDto> kafkaTemplate;

    @Autowired
    private PopularRecentService popularRecentService;

    @Autowired
    private ElasticsearchClient esClient;

    @Value("${elasticsearch.search-log-index:search_log}")
    private String searchLogIndex;

    @Value("${kafka.topic.search-log:search-log}")
    private String searchLogTopic;

    /**
     * 전체 플로우 테스트
     * 1. Kafka Producer로 검색 로그 전송
     * 2. Consumer가 수신하여 Elasticsearch에 저장
     * 3. Elasticsearch에서 데이터 확인
     * 4. 인기 검색어 조회 테스트
     */
    @Test
    void testSearchLogFlow() throws Exception {
        // Given: 테스트 검색어
        String testKeyword = "테스트검색어_" + System.currentTimeMillis();
        KeywordDto keywordDto = new KeywordDto("testUser", testKeyword, LocalDateTime.now());

        log.info("=== 검색 로그 통합 테스트 시작 ===");
        log.info("테스트 검색어: {}", testKeyword);

        // When: Kafka로 검색 로그 전송
        kafkaTemplate.send(searchLogTopic, testKeyword, keywordDto).get();
        log.info("✅ 1단계: Kafka 메시지 전송 완료");

        // Kafka Consumer 처리 대기 (비동기)
        TimeUnit.SECONDS.sleep(3);
        log.info("✅ 2단계: Consumer 처리 대기 완료");

        // Then: Elasticsearch에서 데이터 확인
        SearchResponse<Map> response = esClient.search(s -> s
            .index(searchLogIndex)
            .query(q -> q
                .term(t -> t
                    .field("keyword.keyword")
                    .value(testKeyword)
                )
            )
            .size(1),
            Map.class
        );

        assertNotNull(response.hits());
        assertTrue(response.hits().total().value() > 0, 
            "Elasticsearch에 검색 로그가 저장되어야 합니다");
        
        Map<String, Object> source = response.hits().hits().get(0).source();
        assertNotNull(source);
        assertEquals(testKeyword, source.get("keyword"));
        assertEquals("testUser", source.get("userId"));
        
        log.info("✅ 3단계: Elasticsearch 저장 확인 완료");
        log.info("저장된 데이터: {}", source);

        // 인기 검색어 조회 테스트
        List<Map<String, Object>> popularList = popularRecentService.getPopular(10);
        assertNotNull(popularList);
        log.info("✅ 4단계: 인기 검색어 조회 완료 ({} 건)", popularList.size());
        
        if (!popularList.isEmpty()) {
            log.info("인기 검색어 Top 5:");
            popularList.stream().limit(5).forEach(item -> 
                log.info("  - {} ({}회)", item.get("keyword"), item.get("count"))
            );
        }

        // 최근 검색어 조회 테스트
        List<Map<String, Object>> recentList = popularRecentService.getRecent(10);
        assertNotNull(recentList);
        log.info("✅ 5단계: 최근 검색어 조회 완료 ({} 건)", recentList.size());

        log.info("=== 검색 로그 통합 테스트 완료 ===");
    }

    /**
     * 인기 검색어 집계 테스트
     */
    @Test
    void testPopularKeywordsAggregation() throws Exception {
        // Given: 여러 검색어를 반복 전송
        String[] keywords = {"스프링부트", "카프카", "엘라스틱서치", "스프링부트", "카프카"};
        
        for (String keyword : keywords) {
            KeywordDto dto = new KeywordDto("testUser", keyword, LocalDateTime.now());
            kafkaTemplate.send(searchLogTopic, keyword, dto).get();
        }

        // Consumer 처리 대기
        TimeUnit.SECONDS.sleep(3);

        // When: 인기 검색어 조회
        List<Map<String, Object>> popularList = popularRecentService.getPopular(5);

        // Then: 결과 검증
        assertNotNull(popularList);
        assertFalse(popularList.isEmpty());
        
        log.info("=== 인기 검색어 집계 테스트 결과 ===");
        popularList.forEach(item -> 
            log.info("{}: {}회", item.get("keyword"), item.get("count"))
        );
    }
}
