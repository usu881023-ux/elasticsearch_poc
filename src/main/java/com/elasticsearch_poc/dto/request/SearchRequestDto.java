package com.elasticsearch_poc.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequestDto {
    private String q;
    private Integer size;
    private Integer page; // 1-based page number
    private String field; // 선택한 검색 필드 (optional)
}