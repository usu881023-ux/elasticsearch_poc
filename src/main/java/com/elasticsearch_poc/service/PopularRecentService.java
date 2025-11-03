package com.elasticsearch_poc.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class PopularRecentService {

    private final ConcurrentHashMap<String, AtomicInteger> popularCounts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<SearchEntry> recent = new ConcurrentLinkedDeque<>();

    private final int recentMax = 20;

    public void recordQuery(String q) {
        if (q == null || q.isBlank()) return;
        String keyword = q.trim();
        popularCounts.computeIfAbsent(keyword, k -> new AtomicInteger(0)).incrementAndGet();
        recent.addFirst(new SearchEntry(keyword, Instant.now().toEpochMilli()));
        while (recent.size() > recentMax) {
            recent.removeLast();
        }
    }

    public List<Map<String, Object>> getPopular(int limit) {
        int lim = limit <= 0 ? 10 : limit;
        return popularCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, AtomicInteger>>comparingInt(e -> e.getValue().get()).reversed())
                .limit(lim)
                .map(e -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("keyword", e.getKey());
                    m.put("count", e.getValue().get());
                    return m;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getRecent(int limit) {
        int lim = limit <= 0 ? recentMax : Math.min(limit, recentMax);
        List<Map<String, Object>> list = new ArrayList<>();
        int i = 0;
        for (SearchEntry se : new ArrayDeque<>(recent)) {
            if (i++ >= lim) break;
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("keyword", se.keyword);
            m.put("ts", se.ts);
            list.add(m);
        }
        return list;
    }

    private record SearchEntry(String keyword, long ts) {}
}
