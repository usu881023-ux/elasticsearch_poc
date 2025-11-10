#!/bin/bash

# Elasticsearch search_log 인덱스 생성 스크립트

ES_HOST="${ES_HOST:-http://localhost:9200}"
INDEX_NAME="search_log"

echo "🔧 Elasticsearch 연결 확인: $ES_HOST"

# Elasticsearch 연결 확인
if ! curl -s "$ES_HOST" > /dev/null; then
    echo "❌ Elasticsearch에 연결할 수 없습니다: $ES_HOST"
    exit 1
fi

echo "✅ Elasticsearch 연결 성공"

# 인덱스 존재 여부 확인
if curl -s -o /dev/null -w "%{http_code}" "$ES_HOST/$INDEX_NAME" | grep -q "200"; then
    echo "⚠️  인덱스가 이미 존재합니다: $INDEX_NAME"
    read -p "삭제 후 재생성하시겠습니까? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "🗑️  기존 인덱스 삭제 중..."
        curl -X DELETE "$ES_HOST/$INDEX_NAME"
        echo
    else
        echo "취소되었습니다."
        exit 0
    fi
fi

# 인덱스 생성
echo "📝 search_log 인덱스 생성 중..."

curl -X PUT "$ES_HOST/$INDEX_NAME" -H 'Content-Type: application/json' -d'
{
  "mappings": {
    "properties": {
      "keyword": {
        "type": "text",
        "fields": {
          "keyword": {
            "type": "keyword"
          }
        }
      },
      "userId": {
        "type": "keyword"
      },
      "timestamp": {
        "type": "date",
        "format": "strict_date_optional_time||epoch_millis"
      }
    }
  },
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "index": {
      "max_result_window": 10000
    }
  }
}
'

echo
echo "✅ search_log 인덱스 생성 완료!"
echo
echo "인덱스 정보 확인:"
curl -X GET "$ES_HOST/$INDEX_NAME?pretty"
