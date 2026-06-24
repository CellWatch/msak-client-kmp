#!/bin/sh
set -eu

IMAGE_NAME="${IMAGE_NAME:-msak-android-local-fixture}"
CONTAINER_NAME="${CONTAINER_NAME:-msak-android-local-fixture}"
MSAK_DIR="${MSAK_DIR:-../msak}"
HOST_HTTP_PORT="${HOST_HTTP_PORT:-8080}"
HOST_UDP_PORT="${HOST_UDP_PORT:-1053}"

if [ ! -d "$MSAK_DIR" ]; then
  echo "MSAK_DIR does not exist: $MSAK_DIR" >&2
  exit 1
fi

running_id="$(docker ps -q -f "name=^${CONTAINER_NAME}$" || true)"
if [ -n "$running_id" ]; then
  echo "MSAK docker fixture already running: $CONTAINER_NAME"
else
  existing_id="$(docker ps -aq -f "name=^${CONTAINER_NAME}$" || true)"
  if [ -n "$existing_id" ]; then
    docker rm -f "$CONTAINER_NAME" >/dev/null
  fi

  docker build -t "$IMAGE_NAME" "$MSAK_DIR"
  docker run -d \
    --name "$CONTAINER_NAME" \
    -p "${HOST_HTTP_PORT}:8080" \
    -p "${HOST_UDP_PORT}:1053/udp" \
    "$IMAGE_NAME" >/dev/null
fi

attempt=0
while [ "$attempt" -lt 30 ]; do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${HOST_HTTP_PORT}/throughput/v1/download" || true)"
  if [ "$code" != "000" ]; then
    echo "MSAK docker fixture is reachable on http://127.0.0.1:${HOST_HTTP_PORT}"
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 1
done

echo "MSAK docker fixture did not become reachable on port ${HOST_HTTP_PORT}" >&2
docker logs "$CONTAINER_NAME" || true
exit 1
