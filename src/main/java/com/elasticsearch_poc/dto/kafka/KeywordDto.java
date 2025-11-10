package com.elasticsearch_poc.dto.kafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class KeywordDto {
    private String userId;
    private String keyword;
    private LocalDateTime timestamp;
}