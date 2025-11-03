package com.elasticsearch_poc.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host:http://localhost:9200}")
    private String elasticHost;

    @Bean(destroyMethod = "close")
    public RestClient restClient() {
        // elasticHost format: http://host:port
        String hostOnly = elasticHost.replace("http://", "").replace("https://", "");
        String[] parts = hostOnly.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;
        String scheme = elasticHost.startsWith("https") ? "https" : "http";
        return RestClient.builder(new HttpHost(host, port, scheme)).build();
    }

    @Bean(destroyMethod = "close")
    public RestClientTransport restClientTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
