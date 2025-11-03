package com.elasticsearch_poc.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SuggestRequestDto {
    private String prefix;
    private Integer limit;
}