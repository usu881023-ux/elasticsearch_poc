package com.elasticsearch_poc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SearchService {

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

    public SearchResult search(String keyword, int size, int from) throws IOException {
        final int pageSize = (size <= 0) ? 10 : size;
        final int start = Math.max(0, from);
        String q = (keyword == null || keyword.isBlank()) ? "*" : keyword.trim();

        Query query;
        if ("*".equals(q)) {
            // 전체 검색
            query = Query.of(qb -> qb.matchAll(m -> m));
        } else {
            boolean chosung = isChosungLike(q);
            // goods_name 과 goods_name_chosung 모두 검색
            query = Query.of(qb -> qb
                    .multiMatch(mm -> mm
                            .query(q)
                            .fields("goods_name^2", "goods_name_chosung^3", "description", "category")
                            .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                            .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
                    )
            );
        }

        SearchRequest request = SearchRequest.of(sr -> sr
                .index(indexName)
                .from(start)
                .size(pageSize)
                .query(query)
        );

        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> response = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(request, Map.class);
        long total = 0L;
        if (response.hits() != null && response.hits().total() != null) {
            total = response.hits().total().value();
        }
        List<Map<String, Object>> list = response.hits().hits().stream()
                .map(hit -> (Map<String, Object>) hit.source())
                .collect(Collectors.toList());
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

            @SuppressWarnings("unchecked")
            SearchResponse<Map<String, Object>> resp = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(req, Map.class);

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

        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> fbResp = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(fallbackReq, Map.class);
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
        return new ArrayList<>(unique).stream().limit(size).collect(Collectors.toList());
    }
}
