package com.elasticsearch_poc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final ElasticsearchClient esClient;

    @Value("${elasticsearch.index}")
    private String indexName;

    @Value("${elasticsearch.suggestField:suggest}")
    private String suggestField;

    @Value("${elasticsearch.suggestTextField:goods_name}")
    private String suggestTextField;

    public SearchService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public static class SearchResult {
        private final long total;
        private final List<Map<String, Object>> results;
        public SearchResult(long total, List<Map<String, Object>> results) {
            this.total = total;
            this.results = results;
        }
        public long getTotal() { return total; }
        public List<Map<String, Object>> getResults() { return results; }
    }

    // 한글 초성(ㄱ-ㅎ)만으로 구성되었는지 간단 체크 (공백 허용)
    private boolean isChosungLike(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            // Hangul Compatibility Jamo range: 0x3131 (ㄱ) ~ 0x314E (ㅎ)
            if (ch < '\u3131' || ch > '\u314E') {
                return false;
            }
        }
        return true;
    }

    public SearchResult search(String keyword, String field, int size, int from) throws IOException {
        final int pageSize = (size <= 0) ? 10 : size;
        final int start = Math.max(0, from);
        String q = (keyword == null || keyword.isBlank()) ? "*" : keyword.trim();

        Query query;
        if ("*".equals(q)) {
            // 전체 검색
            query = Query.of(qb -> qb.matchAll(m -> m));
        } else {
            // 필드 선택이 있으면 해당 필드 위주로 검색, 없으면 기존 멀티매치
            if (field != null && !field.isBlank()) {
                String f = field.trim();
                query = switch (f) {
                    case "goods_name_chosung" -> Query.of(qb -> qb
                            .matchPhrasePrefix(mpp -> mpp
                                    .field("goods_name_chosung")
                                    .query(q)
                            )
                    );
                    case "goods_name" -> Query.of(qb -> qb
                            .match(m -> m
                                    .field("goods_name")
                                    .query(q)
                                    .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
                            )
                    );
                    case "key_word" -> Query.of(qb -> qb
                            .match(m -> m
                                    .field("key_word")
                                    .query(q)
                                    .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
                            )
                    );
                    case "goods_code" -> Query.of(qb -> qb
                            .match(m -> m
                                    .field("goods_code")
                                    .query(q)
                            )
                    );
                    default -> Query.of(qb -> qb
                            .multiMatch(mm -> mm
                                    .query(q)
                                    .fields("goods_name^2", "goods_name_chosung^3", "description", "category")
                                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                                    .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
                            )
                    );
                };
            } else {
                query = Query.of(qb -> qb
                        .multiMatch(mm -> mm
                                .query(q)
                                .fields("goods_name^2", "goods_name_chosung^3", "description", "category")
                                .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                                .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
                        )
                );
            }
        }

        SearchRequest request = SearchRequest.of(sr -> sr
                .index(indexName)
                .from(start)
                .size(pageSize)
                .query(query)
        );

        // Log request parameters
        logRequest("search", request, keyword, field, size, from);

        long startTime = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> response = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(request, Map.class);
        long elapsedTime = System.currentTimeMillis() - startTime;

        long total = 0L;
        if (response.hits() != null && response.hits().total() != null) {
            total = response.hits().total().value();
        }
        List<Map<String, Object>> list = Objects.requireNonNull(response.hits()).hits().stream()
                .map(hit -> (Map<String, Object>) hit.source())
                .collect(Collectors.toList());

        // Log response parameters
        logResponse("search", response, total, list.size(), elapsedTime);

        return new SearchResult(total, list);
    }

    public List<String> suggest(String prefix, int limit) throws IOException {
        String pfx = prefix == null ? "" : prefix.trim();
        int size = limit <= 0 ? 8 : limit;
        if (pfx.isEmpty()) return List.of();

        // 1) Try completion suggester on configured field (if available)
        List<String> out = new ArrayList<>();
        try {
            SearchRequest req = SearchRequest.of(sr -> sr
                    .index(indexName)
                    .suggest(s -> s
                            .suggesters("auto-suggest", fs -> fs
                                    .prefix(pfx)
                                    .completion(c -> c
                                            .field(suggestField)
                                            .skipDuplicates(true)
                                            .size(size)
                                    )
                            )
                    )
                    .size(0)
            );

            // Log request parameters
            logRequest("suggest-completion", req, prefix, limit);

            long startTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            SearchResponse<Map<String, Object>> resp = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(req, Map.class);
            long elapsedTime = System.currentTimeMillis() - startTime;

            // Log response parameters
            logResponse("suggest-completion", resp, 0, out.size(), elapsedTime);

            if (resp.suggest() != null && resp.suggest().containsKey("auto-suggest")) {
                var sugList = resp.suggest().get("auto-suggest");
                if (sugList != null) {
                    for (var sug : sugList) {
                        if (sug.completion() == null) continue;
                        for (var opt : sug.completion().options()) {
                            if (opt.text() != null && !opt.text().isEmpty()) {
                                String t = opt.text();
                                if (!out.contains(t)) out.add(t);
                                if (out.size() >= size) break;
                            }
                        }
                        if (out.size() >= size) break;
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore and fallback to text-based suggestion
        }
        if (!out.isEmpty()) return out;

        // 2) Fallback: prefix search supporting 초성(goods_name_chosung) 과 본문(goods_name)
        boolean chosung = isChosungLike(pfx);
        Query prefixQuery = chosung
                ? Query.of(qb -> qb
                        .matchPhrasePrefix(mpp -> mpp
                                .field("goods_name_chosung")
                                .query(pfx)
                        )
                )
                : Query.of(qb -> qb
                        .bool(b -> b
                                .should(s1 -> s1.matchPhrasePrefix(mpp -> mpp.field("goods_name").query(pfx)))
                                .should(s2 -> s2.matchPhrasePrefix(mpp -> mpp.field("goods_name_chosung").query(pfx)))
                        )
                );

        SearchRequest fallbackReq = SearchRequest.of(sr -> sr
                .index(indexName)
                .size(size)
                .query(prefixQuery)
                .source(src -> src
                        .filter(f -> f
                                .includes("goods_name", suggestTextField)
                        )
                )
        );

        // Log request parameters
        logRequest("suggest-fallback", fallbackReq, prefix, limit);

        long startTime = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> fbResp = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(fallbackReq, Map.class);
        long elapsedTime = System.currentTimeMillis() - startTime;
        Set<String> unique = new LinkedHashSet<>();
        fbResp.hits().hits().forEach(hit -> {
            Object src = hit.source();
            if (src instanceof Map) {
                Object name = ((Map<?, ?>) src).get("goods_name");
                Object val = name != null ? name : ((Map<?, ?>) src).get(suggestTextField);
                if (val instanceof String) {
                    String t = (String) val;
                    if (!t.isBlank()) unique.add(t);
                }
            }
        });
        List<String> result = new ArrayList<>(unique).stream().limit(size).collect(Collectors.toList());

        // Log response parameters
        logResponse("suggest-fallback", fbResp, fbResp.hits().total() != null ? fbResp.hits().total().value() : 0, result.size(), elapsedTime);

        return result;
    }

    private void logRequest(String operation, SearchRequest request, Object... params) {
        log.info("=== Elasticsearch {} Request ===", operation);
        log.info("Index: {}", request.index() != null ? String.join(",", request.index()) : "N/A");
        if (request.from() != null) {
            log.info("From: {}", request.from());
        }
        if (request.size() != null) {
            log.info("Size: {}", request.size());
        }
        try {
            if (request.query() != null) {
                log.info("Query Type: {}", request.query()._kind());
            }
        } catch (Exception e) {
            log.debug("Could not get query type: {}", e.getMessage());
        }
        try {
            if (request.suggest() != null) {
                log.info("Has Suggest: true");
            }
        } catch (Exception e) {
            log.debug("Could not check suggest: {}", e.getMessage());
        }
        if (params != null && params.length > 0) {
            log.info("Parameters: {}", java.util.Arrays.toString(params));
        }
        log.info("================================");
    }

    private void logResponse(String operation, SearchResponse<?> response, long total, int resultCount, long elapsedTime) {
        log.info("=== Elasticsearch {} Response ===", operation);
        log.info("Total Hits: {}", total);
        log.info("Returned Results: {}", resultCount);
        log.info("Elapsed Time: {} ms", elapsedTime);
        log.info("ES Took: {} ms", response.took());
        if (response.shards() != null) {
            log.info("Total Shards: {}, Successful: {}, Failed: {}", 
                    response.shards().total(), 
                    response.shards().successful(), 
                    response.shards().failed());
        }
        log.info("=================================");
    }
}
