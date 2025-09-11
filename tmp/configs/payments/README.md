# Obsinity Example Config — Services/Event-Centric Layout

Targeted structure:

```text
services/
  payments/
    events/
      http-request/
        event.yaml
        metrics/
          counters/
            http_requests_by_status_code.yaml
            http_requests_5xx_fold.yaml
          histograms/
            http_request_latency_ms.yaml
      auth-decision/
        event.yaml
```

- Service is implied by the folder name under `services/` — no top-level service.yaml.
- Metrics are **scoped per-event** under `events/<event>/metrics/...`.
- The 5xx folded counter demonstrates grouping 500–599 as `http.status_code_group="5xx"`.
