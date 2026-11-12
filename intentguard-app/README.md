# IntentGuard

An intent firewall for AI agents. You tell IntentGuard what you asked the agent to do; every action the
agent then wants to take (send an email, run a command, make a payment…) is scored against that intent
**before it runs** and is **allowed**, held for your **approval**, or **blocked**. It catches prompt-injection
hijacks (e.g. a malicious email telling the agent to forward your API key).

- **Backend:** Java 21, no external dependencies (JDK built-in HTTP server, hand-written JSON). REST API + in-memory audit trail.
- **Frontend:** React 18 + Vite.

## Run (development)

Requirements: JDK 21+, Node 18+.

```bash
# terminal 1 – API on http://localhost:8080
cd backend && ./build.sh run

# terminal 2 – UI on http://localhost:5173 (proxies /api to :8080)
cd frontend && npm install && npm run dev
```

## Run (single process)

```bash
cd frontend && npm install && npm run build
cd ../backend && FRONTEND_DIR=../frontend/dist ./build.sh run     # open http://localhost:8080
```

`./build.sh jar` builds `intentguard.jar` (`java -jar intentguard.jar`).

## Run with Docker

From the repository root, build and start the application (API and frontend) with:

```bash
docker compose up --build
```

Open <http://localhost:3000>. The UI and API are served by the same container; the
API is available at <http://localhost:3000/api/health>. To stop it, run
`docker compose down`.

For remote debugging on port 5005, use:

```bash
docker compose -f compose.yaml -f compose.debug.yaml up --build
```

## Tests

```bash
cd backend && ./build.sh test      # 86 checks: JSON, intent parser, policy engine, scenarios, HTTP API
```

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | API port |
| `CORS_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Comma-separated allowed browser origins |
| `FRONTEND_DIR` | unset | If set, serves the built React app from this folder |

## API

| Method & path | Body | Result |
|---|---|---|
| `GET /api/health` | – | `{"status":"ok"}` |
| `GET /api/scenarios` | – | Demo scenarios |
| `POST /api/parse-intent` | `{"intent"}` | Allowed action types, recipients, domains, files, amounts |
| `POST /api/evaluate` | `{"intent","mode","action":{"tool","target","content","amount","source"}}` | `{score 0-100, decision allow/confirm/block, findings[], entry}` (also recorded in the audit trail) |
| `GET /api/audit` · `GET /api/audit/stats` | – | Audit entries · counts |
| `POST /api/audit/{id}/resolve` | `{"approved":true}` | Approve/deny an action awaiting approval |
| `DELETE /api/audit` | – | Clear audit trail |

`mode` is `strict`, `balanced` (default) or `relaxed`. `source` is `user`, `agent`, `email`, `webpage`,
`document` or `tool_output` (the last four are treated as untrusted).

## How decisions are made

Risk starts at 0 and rises when: the action type was never asked for (+55); the instruction came from untrusted
content (+30); the recipient / domain / file / payee isn't one you named; a file path is sensitive (`~/.ssh`, `.env`…);
outbound content contains a credential; a shell command is dangerous (`rm -rf`, `curl … | sh`); a payment exceeds the
authorised amount. Score ≥ 70 → block, ≥ 30 → needs approval, else allow. Deletions and payments always need
approval; credential exfiltration and sensitive-path access are always blocked.

## Limits

Decisions come from deterministic rules over the intent text, not an LLM, so unusual phrasing can be missed.
The audit trail is in memory and is lost on restart. There is no authentication; run it on localhost or behind your own gateway.
