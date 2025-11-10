#!/bin/bash

# Kafka search-log 토픽 생성 스크립트

KAFKA_HOST="${KAFKA_HOST:-localhost:9092}"
TOPIC_NAME="search-log"
PARTITIONS="${PARTITIONS:-3}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-1}"

echo "🔧 Kafka 토픽 생성"
echo "  - Kafka Broker: $KAFKA_HOST"
echo "  - Topic: $TOPIC_NAME"
echo "  - Partitions: $PARTITIONS"
echo "  - Replication Factor: $REPLICATION_FACTOR"
echo

# Kafka 설치 경로 확인 (일반적인 경로들)
KAFKA_BIN=""
if [ -d "/opt/kafka/bin" ]; then
    KAFKA_BIN="/opt/kafka/bin"
elif [ -d "/usr/local/kafka/bin" ]; then
    KAFKA_BIN="/usr/local/kafka/bin"
elif [ -d "$HOME/kafka/bin" ]; then
    KAFKA_BIN="$HOME/kafka/bin"
else
    echo "⚠️  Kafka bin 디렉토리를 찾을 수 없습니다."
    echo "Docker를 사용하는 경우 다음 명령어를 실행하세요:"
    echo "docker exec -it kafka kafka-topics.sh --create \\"
    echo "  --bootstrap-server $KAFKA_HOST \\"
    echo "  --topic $TOPIC_NAME \\"
    echo "  --partitions $PARTITIONS \\"
    echo "  --replication-factor $REPLICATION_FACTOR"
    exit 1
fi

# 토픽 존재 여부 확인
echo "📋 기존 토픽 확인..."
if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_HOST --list | grep -q "^$TOPIC_NAME$"; then
    echo "⚠️  토픽이 이미 존재합니다: $TOPIC_NAME"
    read -p "삭제 후 재생성하시겠습니까? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "🗑️  기존 토픽 삭제 중..."
        $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_HOST --delete --topic $TOPIC_NAME
        sleep 2
    else
        echo "취소되었습니다."
        exit 0
    fi
fi

# 토픽 생성
echo "📝 토픽 생성 중..."
$KAFKA_BIN/kafka-topics.sh --create \
  --bootstrap-server $KAFKA_HOST \
  --topic $TOPIC_NAME \
  --partitions $PARTITIONS \
  --replication-factor $REPLICATION_FACTOR

echo
echo "✅ 토픽 생성 완료!"
echo
echo "토픽 정보 확인:"
$KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_HOST --describe --topic $TOPIC_NAME
