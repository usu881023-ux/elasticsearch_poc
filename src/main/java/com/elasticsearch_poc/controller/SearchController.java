package com.elasticsearch_poc.controller;

import com.elasticsearch_poc.dto.request.LimitRequestDto;
import com.elasticsearch_poc.dto.request.SearchRequestDto;
import com.elasticsearch_poc.dto.request.SuggestRequestDto;
import com.elasticsearch_poc.dto.response.*;
import com.elasticsearch_poc.service.PopularRecentService;
import com.elasticsearch_poc.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;
    private final PopularRecentService prs;

    public SearchController(SearchService searchService, PopularRecentService prs) {
        this.searchService = searchService;
        this.prs = prs;
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponseDto> search(@ModelAttribute SearchRequestDto request) throws IOException {
        String q = request.getQ();
        String field = request.getField();
        int size = request.getSize() == null ? 10 : request.getSize();
        int page = request.getPage() == null || request.getPage() < 1 ? 1 : request.getPage();
        int from = (page - 1) * size;
        prs.recordQuery(q);
        SearchService.SearchResult sr = searchService.search(q, field, size, from);
        long total = sr.getTotal();
        int totalPages = size > 0 ? (int) Math.max(1, (long) Math.ceil((double) total / size)) : 1;

        SearchResponseDto searchResponseDto = SearchResponseDto.builder()
                .query(q)
                .size(size)
                .page(page)
                .total(total)
                .totalPages(totalPages)
                .results(sr.getResults())
                .build();

        return ResponseEntity.ok(searchResponseDto);
    }

    @GetMapping("/popular")
    public ResponseEntity<PopularResponseDto> popular(@ModelAttribute LimitRequestDto request) {
        int limit = request.getLimit() == null ? 10 : request.getLimit();
        List<PopularItemDto> items = prs.getPopular(limit).stream()
                .map(m -> new PopularItemDto((String) m.get("keyword"), ((Number) m.getOrDefault("count", 0)).intValue()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(new PopularResponseDto(items));
    }

    @GetMapping("/recent")
    public ResponseEntity<RecentResponseDto> recent(@ModelAttribute LimitRequestDto request) {
        int limit = request.getLimit() == null ? 10 : request.getLimit();
        List<RecentItemDto> items = prs.getRecent(limit).stream()
                .map(m -> new RecentItemDto((String) m.get("keyword"), ((Number) m.getOrDefault("ts", 0L)).longValue()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(new RecentResponseDto(items));
    }

    @GetMapping("/suggest")
    public ResponseEntity<SuggestResponseDto> suggest(@ModelAttribute SuggestRequestDto request) {
        String prefix = request.getPrefix();
        int limit = request.getLimit() == null ? 8 : request.getLimit();
        String pfx = prefix == null ? "" : prefix.trim();
        if (pfx.isEmpty()) {
            // Return a mix of recent and popular when no prefix
            Set<String> mixed = new LinkedHashSet<>();
            prs.getRecent(limit).forEach(m -> mixed.add((String) m.get("keyword")));
            prs.getPopular(limit).forEach(m -> mixed.add((String) m.get("keyword")));
            List<String> list = mixed.stream().limit(limit).collect(Collectors.toList());
            return ResponseEntity.ok(new SuggestResponseDto(list));
        }
        // Try Elasticsearch-based suggestions first
        try {
            List<String> esSuggest = searchService.suggest(pfx, limit);
            if (esSuggest != null && !esSuggest.isEmpty()) {
                return ResponseEntity.ok(new SuggestResponseDto(esSuggest));
            }
        } catch (Exception ignored) {
            // fall back silently
        }
        // Fallback: local recent + popular prefix filtering
        String lower = pfx.toLowerCase();
        Set<String> set = new LinkedHashSet<>();
        prs.getRecent(50).stream()
                .map(m -> (String) m.get("keyword"))
                .filter(k -> k != null && k.toLowerCase().startsWith(lower))
                .forEach(set::add);
        prs.getPopular(50).stream()
                .map(m -> (String) m.get("keyword"))
                .filter(k -> k != null && k.toLowerCase().startsWith(lower))
                .forEach(set::add);
        List<String> list = set.stream().limit(limit).collect(Collectors.toList());
        return ResponseEntity.ok(new SuggestResponseDto(list));
    }
}
