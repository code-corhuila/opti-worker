# opti-worker

Everything that happens without anyone asking it: expiring stale quotations and flagging
overdue optical controls. Same hexagonal shape as an `-api` (Annex D); what changes is the
inbound adapter — a scheduler instead of HTTP.

Part of the OptiView distributed system (team `opti`). Governance and documentation live in
[`opti-docs`](https://github.com/code-corhuila/opti-docs).

## Jobs

| Job | Rule | Uses |
|---|---|---|
| `expire-stale-orders` | Cancels quotations older than `PENDING_TTL` (releases their reserved stock) | `sales-api` to list, the **workflow's** `cancel-order` saga to cancel (it also touches products) |
| `flag-overdue-controls` | Flags active patients whose last control is older than `CONTROL_MAX_AGE` (HU-04) | `customers-api` |

Plain Java, no framework: `worker-core` has no dependency at all, `worker-adapters` has the
scheduler, the health endpoint and the one HTTP client, `worker-app` is the composition root.

## Rules every job keeps

1. **Safe to run twice.** Cancelling an order and flagging a patient are both idempotent on the
   side that owns the data; running the same job twice, or two overlapping runs, changes nothing extra.
2. **One failing element never stops the batch.** It is counted and logged with the run's
   `traceId`, and stays for the next run.
3. **Every run is bounded**: at most `BATCH_SIZE` elements, and it stops before `RUN_TIMEOUT`.
4. **Its own credential.** Every call carries `Authorization: Bearer $SERVICE_TOKEN`, issued by
   the identity service, never versioned.
5. **One correlation id per run**, sent on every call and written on every log line.
6. **Bounded retries** in the HTTP client, exponential back-off with full jitter, only for
   network failures, `429` and `5xx` — never for a `4xx`.
7. **No business interface.** Only `GET /health`, which also shows the last run of each job.

## Configuration

See `.env.example`. `SERVICE_TOKEN`, the domain URLs, `EXPIRE_EVERY`/`PENDING_TTL`,
`CONTROLS_EVERY`/`CONTROL_MAX_AGE`, `BATCH_SIZE`/`RUN_TIMEOUT`, `HTTP_TIMEOUT`/`HTTP_ATTEMPTS`.
A misconfigured value stops the process at start, naming the variable — not at 3 a.m.

## Run

The whole platform is started from `opti-infra`. Alone:

```bash
cp .env.example .env
mvn -B verify
docker compose --env-file .env -f deploy/compose.yml build
curl http://localhost:8080/health   # reachable only inside the platform network otherwise
```

## Depends on

`opti-customers-api`, `opti-sales-api` and `opti-workflow` (only through their published APIs,
never through their databases), and the service credential issued by `opti-auth-api`.
