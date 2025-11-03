package com.elasticsearch_poc.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponseDto {
    private String query;
    private int size;
    private int page;          // 1-based page number
    private long total;        // total hits
    private int totalPages;    // total pages based on size
    private List<Map<String, Object>> results;
}