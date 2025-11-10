package com.elasticsearch_poc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Elasticsearch 인덱스 초기화 서비스
 * 애플리케이션 시작 시 search_log 인덱스가 없으면 자동 생성
 */
@Service
public class ElasticsearchInitService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchInitService.class);
    private static final String SEARCH_LOG_INDEX = "search_log";
    
    private final ElasticsearchClient esClient;

    @Value("${elasticsearch.search-log-index:search_log}")
    private String searchLogIndex;

    public ElasticsearchInitService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    /**
     * 애플리케이션 시작 후 search_log 인덱스 확인 및 생성
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeSearchLogIndex() {
        try {
            if (!indexExists(searchLogIndex)) {
                createSearchLogIndex();
                log.info("✅ search_log 인덱스가 성공적으로 생성되었습니다.");
            } else {
                log.info("ℹ️ search_log 인덱스가 이미 존재합니다.");
            }
        } catch (Exception e) {
            log.error("❌ search_log 인덱스 초기화 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * 인덱스 존재 여부 확인
     */
    private boolean indexExists(String indexName) throws IOException {
        ExistsRequest request = ExistsRequest.of(e -> e.index(indexName));
        BooleanResponse response = esClient.indices().exists(request);
        return response.value();
    }

    /**
     * search_log 인덱스 생성
     */
    private void createSearchLogIndex() throws IOException {
        CreateIndexRequest request = CreateIndexRequest.of(c -> c
            .index(searchLogIndex)
            .mappings(m -> m
                .properties("keyword", p -> p
                    .text(t -> t
                        .fields("keyword", f -> f
                            .keyword(k -> k)
                        )
                    )
                )
                .properties("userId", p -> p
                    .keyword(k -> k)
                )
                .properties("timestamp", p -> p
                    .date(d -> d
                        .format("strict_date_optional_time||epoch_millis")
                    )
                )
            )
            .settings(s -> s
                .numberOfShards("1")
                .numberOfReplicas("1")
                .maxResultWindow(10000)
            )
        );

        esClient.indices().create(request);
        log.info("🔧 search_log 인덱스 생성 완료: {}", searchLogIndex);
    }
}
