# OpenZL gRPC Metrics Demo

This demo runs a Quarkus gRPC service that uses the OpenZL extension and emits
Micrometer metrics. Prometheus scrapes the metrics and Grafana visualizes them.

## What it does

- A gRPC service (`MetricsService`) is annotated with `@OpenZLGrpcService`, so
  requests and responses are transparently encoded with OpenZL.
- A scheduler generates steady + burst traffic to populate metrics.
- Prometheus scrapes `/q/metrics` and Grafana shows encode/decode, training, compression ratio, and baseline-vs-trained comparisons.
- The demo sends both a baseline stream (training disabled) and an improving stream (training enabled and retrained every 100 samples up to 5k).

## Run with Docker Compose

From the repo root:

```bash
docker compose -f demo/docker-compose.yml up --build
```

Open:
- Grafana: http://localhost:3000 (admin / admin)
- Prometheus: http://localhost:9090
- Quarkus metrics: http://localhost:8080/q/metrics

## Notes

- The demo app depends on `openzl-jni` with the `linux_amd64` classifier by
  default. Override with `-Dopenzl.classifier=macos_arm64` or `windows_amd64`
  when building locally on another OS.
