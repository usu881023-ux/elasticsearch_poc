package com.elasticsearch_poc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 인기 검색어 및 최근 검색어 관리 서비스
 * 하이브리드 방식: 인메모리(실시간) + Elasticsearch(영구 저장 및 통계)
 */
@Service
public class PopularRecentService {

    private static final Logger log = LoggerFactory.getLogger(PopularRecentService.class);

    // 인메모리 저장소 (빠른 실시간 조회용)
    private final ConcurrentHashMap<String, AtomicInteger> popularCounts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<SearchEntry> recent = new ConcurrentLinkedDeque<>();
    private final int recentMax = 100;

    private final ElasticsearchClient esClient;
    
    @Value("${elasticsearch.search-log-index:search_log}")
    private String searchLogIndex;

    @Value("${popular.use-elasticsearch:true}")
    private boolean useElasticsearch;

    public PopularRecentService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    /**
     * 검색어 기록 (인메모리)
     */
    public void recordQuery(String q) {
        if (q == null || q.isBlank()) return;
        String keyword = q.trim();
        
        // 인메모리 통계 업데이트
        popularCounts.computeIfAbsent(keyword, k -> new AtomicInteger(0)).incrementAndGet();
        recent.addFirst(new SearchEntry(keyword, Instant.now().toEpochMilli()));
        
        while (recent.size() > recentMax) {
            recent.removeLast();
        }
    }

    /**
     * 인기 검색어 조회
     * - useElasticsearch=true: Elasticsearch 집계 사용 (영구 데이터 기반)
     * - useElasticsearch=false: 인메모리 데이터 사용 (실시간)
     */
    public List<Map<String, Object>> getPopular(int limit) {
        int lim = limit <= 0 ? 10 : limit;
        
        if (useElasticsearch) {
            try {
                return getPopularFromElasticsearch(lim);
            } catch (Exception e) {
                log.error("Elasticsearch 인기 검색어 조회 실패, 인메모리 데이터 사용: {}", e.getMessage());
                return getPopularFromMemory(lim);
            }
        } else {
            return getPopularFromMemory(lim);
        }
    }

    /**
     * 최근 검색어 조회
     * - useElasticsearch=true: Elasticsearch에서 조회 (영구 데이터 기반)
     * - useElasticsearch=false: 인메모리 데이터 사용 (실시간)
     */
    public List<Map<String, Object>> getRecent(int limit) {
        int lim = limit <= 0 ? recentMax : Math.min(limit, recentMax);
        
        if (useElasticsearch) {
            try {
                return getRecentFromElasticsearch(lim);
            } catch (Exception e) {
                log.error("Elasticsearch 최근 검색어 조회 실패, 인메모리 데이터 사용: {}", e.getMessage());
                return getRecentFromMemory(lim);
            }
        } else {
            return getRecentFromMemory(lim);
        }
    }

    /**
     * Elasticsearch Terms Aggregation을 통한 인기 검색어 조회
     */
    private List<Map<String, Object>> getPopularFromElasticsearch(int limit) {
        try {
            SearchResponse<Void> response = esClient.search(s -> s
                .index(searchLogIndex)
                .size(0)  // 문서 자체는 필요 없음
                .aggregations("popular_keywords", a -> a
                    .terms(t -> t
                        .field("keyword.keyword")
                        .size(limit)
                    )
                ),
                Void.class
            );

            List<Map<String, Object>> result = new ArrayList<>();
            
            if (response.aggregations() != null && 
                response.aggregations().get("popular_keywords") != null) {
                
                var termsAgg = response.aggregations().get("popular_keywords").sterms();
                
                for (StringTermsBucket bucket : termsAgg.buckets().array()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("keyword", bucket.key().stringValue());
                    item.put("count", bucket.docCount());
                    result.add(item);
                }
            }

            log.info("📊 Elasticsearch 인기 검색어 조회: {} 건", result.size());
            return result;
            
        } catch (Exception e) {
            log.error("Elasticsearch 인기 검색어 조회 실패: {}", e.getMessage(), e);
            throw new RuntimeException("인기 검색어 조회 실패", e);
        }
    }

    /**
     * Elasticsearch에서 최근 검색어 조회
     */
    private List<Map<String, Object>> getRecentFromElasticsearch(int limit) {
        try {
            SearchResponse<Map> response = esClient.search(s -> s
                .index(searchLogIndex)
                .size(limit)
                .sort(so -> so
                    .field(f -> f
                        .field("timestamp")
                        .order(SortOrder.Desc)
                    )
                ),
                Map.class
            );

            List<Map<String, Object>> result = new ArrayList<>();
            
            if (response.hits() != null && response.hits().hits() != null) {
                response.hits().hits().forEach(hit -> {
                    if (hit.source() != null) {
                        Map<String, Object> source = hit.source();
                        Map<String, Object> item = new HashMap<>();
                        item.put("keyword", source.get("keyword"));
                        item.put("ts", source.get("timestamp"));
                        result.add(item);
                    }
                });
            }

            log.info("📊 Elasticsearch 최근 검색어 조회: {} 건", result.size());
            return result;
            
        } catch (Exception e) {
            log.error("Elasticsearch 최근 검색어 조회 실패: {}", e.getMessage(), e);
            throw new RuntimeException("최근 검색어 조회 실패", e);
        }
    }

    /**
     * 인메모리 기반 인기 검색어 조회
     */
    private List<Map<String, Object>> getPopularFromMemory(int limit) {
        return popularCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, AtomicInteger>>comparingInt(e -> e.getValue().get()).reversed())
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("keyword", e.getKey());
                    m.put("count", e.getValue().get());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * 인메모리 기반 최근 검색어 조회
     */
    private List<Map<String, Object>> getRecentFromMemory(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        int i = 0;
        for (SearchEntry se : new ArrayDeque<>(recent)) {
            if (i++ >= limit) break;
            Map<String, Object> m = new HashMap<>();
            m.put("keyword", se.keyword);
            m.put("ts", se.ts);
            list.add(m);
        }
        return list;
    }

    private record SearchEntry(String keyword, long ts) {}
}
