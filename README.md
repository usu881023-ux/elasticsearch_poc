ElasticSearch 검색 활용을 위한 개인 POC 프로젝트
# 검색 로그 시스템 가이드

## 📌 개요

이 프로젝트는 Elasticsearch, Kafka, Spring Boot를 활용한 실시간 검색 로그 수집 및 인기 검색어 분석 시스템입니다.

## 🏗️ 아키텍처

```
사용자 검색 요청
    ↓
SearchController (/api/search)
    ↓
SearchLogProducer → Kafka (search-log 토픽)
    ↓
SearchLogConsumer
    ├─→ PopularRecentService (인메모리 - 실시간)
    └─→ Elasticsearch (search_log 인덱스 - 영구 저장)
         ↓
    인기/최근 검색어 조회 API
```

## 🚀 시작하기

### 1. 사전 요구사항

- Java 21
- Elasticsearch 8.x (localhost:9200)
- Kafka 3.x (localhost:9092)
- Gradle 8.x

### 2. Elasticsearch 실행

```bash
# Docker로 실행하는 경우
docker run -d --name elasticsearch \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  elasticsearch:8.15.3
```

### 3. Kafka 실행

```bash
# Docker로 실행하는 경우
docker-compose up -d
```

또는 로컬 Kafka 실행:
```bash
# Zookeeper 실행
bin/zookeeper-server-start.sh config/zookeeper.properties

# Kafka 실행
bin/kafka-server-start.sh config/server.properties
```

### 4. 애플리케이션 실행

```bash
./gradlew bootRun
```

## 📊 인덱스 초기화

애플리케이션 시작 시 자동으로 `search_log` 인덱스가 생성됩니다.

수동으로 생성하려면:
```bash
curl -X PUT "localhost:9200/search_log" -H 'Content-Type: application/json' -d @src/main/resources/elasticsearch/create_search_log_index.json
```

## 🔧 설정

### application.properties

```properties
# Elasticsearch
elasticsearch.host=http://localhost:9200
elasticsearch.index=oracle_products
elasticsearch.search-log-index=search_log

# Kafka
spring.kafka.bootstrap-servers=localhost:9092
kafka.topic.search-log=search-log

# 인기/최근 검색어 데이터 소스 선택
# true: Elasticsearch (영구 저장, 분산 환경 지원, 서버 재시작 시 데이터 유지)
# false: 인메모리 (빠른 실시간 조회, 단일 서버, 서버 재시작 시 데이터 손실)
popular.use-elasticsearch=true
```

## 🌐 API 엔드포인트

### 1. 검색 API
```bash
GET /api/search?q=검색어&page=1&size=10

# 예시
curl "http://localhost:8080/api/search?q=노트북&page=1&size=10"
```

**동작**:
- 상품 검색 수행
- 검색어를 Kafka로 전송 (비동기)
- Kafka Consumer가 Elasticsearch에 저장

### 2. 인기 검색어 조회
```bash
GET /api/popular?limit=10

# 예시
curl "http://localhost:8080/api/popular?limit=10"
```

**응답 예시**:
```json
{
  "items": [
    {"keyword": "노트북", "count": 150},
    {"keyword": "마우스", "count": 120},
    {"keyword": "키보드", "count": 95}
  ]
}
```

### 3. 최근 검색어 조회
```bash
GET /api/recent?limit=10

# 예시
curl "http://localhost:8080/api/recent?limit=10"
```

**응답 예시**:
```json
{
  "items": [
    {"keyword": "노트북", "ts": 1699612345678},
    {"keyword": "마우스", "ts": 1699612340123},
    {"keyword": "키보드", "ts": 1699612335567}
  ]
}
```

### 4. 자동완성 (Suggest)
```bash
GET /api/suggest?prefix=노트&limit=8

# 예시
curl "http://localhost:8080/api/suggest?prefix=노트&limit=8"
```

## 📈 Elasticsearch 쿼리 예시

### 전체 검색 로그 조회
```bash
curl -X GET "localhost:9200/search_log/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {
    "match_all": {}
  },
  "sort": [
    {"timestamp": {"order": "desc"}}
  ],
  "size": 10
}
'
```

### 특정 키워드 검색 로그 조회
```bash
curl -X GET "localhost:9200/search_log/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {
    "term": {
      "keyword.keyword": "노트북"
    }
  }
}
'
```

### 인기 검색어 집계 (Top 10)
```bash
curl -X GET "localhost:9200/search_log/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "popular_keywords": {
      "terms": {
        "field": "keyword.keyword",
        "size": 10
      }
    }
  }
}
'
```

### 시간대별 검색량 집계
```bash
curl -X GET "localhost:9200/search_log/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "search_over_time": {
      "date_histogram": {
        "field": "timestamp",
        "calendar_interval": "hour"
      }
    }
  }
}
'
```

## 🧪 테스트

### 통합 테스트 실행
```bash
./gradlew test --tests SearchLogIntegrationTest
```

### 수동 테스트

1. **검색 로그 생성**
```bash
# 여러 번 검색하여 로그 생성
for i in {1..10}; do
  curl "http://localhost:8080/api/search?q=노트북"
  sleep 1
done
```

2. **Kafka 토픽 확인**
```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic search-log --from-beginning
```

3. **Elasticsearch 데이터 확인**
```bash
curl "localhost:9200/search_log/_count?pretty"
```

4. **인기 검색어 확인**
```bash
curl "http://localhost:8080/api/popular?limit=5"
```

## 🔍 모니터링

### Kafka 토픽 상태 확인
```bash
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic search-log
```

### Consumer Group 상태 확인
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group search-log-group
```

### Elasticsearch 인덱스 상태 확인
```bash
curl "localhost:9200/_cat/indices/search_log?v"
```

## 🎯 성능 최적화

### 1. Kafka 설정
```properties
# Producer
spring.kafka.producer.acks=1
spring.kafka.producer.retries=3
spring.kafka.producer.batch-size=16384

# Consumer
spring.kafka.consumer.max-poll-records=500
spring.kafka.consumer.fetch-min-size=1
```

### 2. Elasticsearch 설정
```properties
# 인덱스 성능 향상
elasticsearch.search-log-index.refresh_interval=5s
elasticsearch.search-log-index.number_of_shards=3
elasticsearch.search-log-index.number_of_replicas=1
```

### 3. 하이브리드 모드 활용
```properties
# 빠른 실시간 조회가 필요한 경우
popular.use-elasticsearch=false

# 영구 저장 및 정확한 통계가 필요한 경우
popular.use-elasticsearch=true
```

## 🐛 트러블슈팅

### Kafka 연결 실패
```
Error: Connection to node -1 could not be established
```
**해결**: Kafka 서버가 실행 중인지 확인
```bash
netstat -an | grep 9092
```

### Elasticsearch 연결 실패
```
Error: Connection refused: localhost/127.0.0.1:9200
```
**해결**: Elasticsearch 서버 상태 확인
```bash
curl localhost:9200
```

### Consumer가 메시지를 소비하지 않음
**해결**: Consumer Group 리셋
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group search-log-group --reset-offsets --to-earliest \
  --topic search-log --execute
```

## 📚 참고 자료

- [Elasticsearch Java Client](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html)
- [Spring Kafka](https://spring.io/projects/spring-kafka)
- [Kafka Documentation](https://kafka.apache.org/documentation/)

## 📝 라이센스

MIT License
