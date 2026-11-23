# Bingwa TalkSasa SMS backend

This service is the only component that holds the TalkSasa API token. The Android app talks to this service over HTTPS; it never receives the TalkSasa provider token.

## Required environment variables

- `TALKSASA_API_TOKEN`: TalkSasa bearer token from the provider portal.
- `TALKSASA_SENDER_ID`: approved sender ID.
- `BINGWA_SMS_ADMIN_KEY`: long random secret used to authorize SMS administration requests.
- `PORT`: optional, defaults to 8080.
- `SQLITE_PATH`: optional, defaults to `backend/data/sms.sqlite`. Use a persistent disk in production.

Do not put real values in this repository, APK, build config, or source control. Configure them in the hosting provider's encrypted environment settings.

## Deploy

1. Create a Render web service from this repository using the checked-in `render.yaml` blueprint, or deploy `backend/Dockerfile` to another Node 20 container host.
2. Attach persistent storage at `/var/data`.
3. Set `TALKSASA_API_TOKEN` and `TALKSASA_SENDER_ID` in the host dashboard.
4. Generate a long random `BINGWA_SMS_ADMIN_KEY`; do not reuse the TalkSasa token.
5. Confirm `GET /health` returns `ok: true` and `configured: true`.
6. In Bingwa SMS Centre choose TalkSasa and enter the HTTPS backend URL plus the admin key. The admin key is encrypted in Android Keystore-backed storage.

## Backend routes

All routes except `GET /health` require `Authorization: Bearer <BINGWA_SMS_ADMIN_KEY>`.

- `GET /v1/sms/me` account profile (TalkSasa `GET /me`)
- `GET /v1/sms/balance` balance (TalkSasa `GET /balance`)
- `GET /v1/sms/dashboard` local counters, balance, provider records and recent local submissions
- `GET /v1/sms/history` local submission log
- `POST /v1/sms/send` plain or hashed SMS; requires `Idempotency-Key`
- `GET /v1/sms/message/:uid` retrieve a provider message
- `POST /v1/sms/refresh-status` refresh known provider UIDs via `GET /sms/:uid`
- `GET /v1/sms/provider-records` provider records via `GET /sms`
- `GET /v1/sms/contacts` provider contact groups via `GET /contacts`
- `POST /v1/sms/campaign` campaign using the supplied `contact_list_id` example; requires `Idempotency-Key`
- `GET /v1/sms/audit` administrative audit log

## Important API-contract limits

The supplied integration notes do not define the request bodies for creating contact groups or adding contacts. Those write operations intentionally return HTTP 501 rather than guessing provider payloads. The app's existing Bingwa-local contact groups and CSV import remain available.

The supplied notes show both a campaign parameter warning and an example using `contact_list_id`. The implementation uses that example field and displays the caveat in the UI; verify this against the authenticated TalkSasa portal before sending a real campaign.

The provider's message lookup is used to refresh delivery status only when a UID was returned. HTTP 202 means accepted for processing, never delivered. If the provider returns no UID, delivery cannot be polled for that submission.

## Security and operations

- Use HTTPS only.
- Restrict access to the admin key and rotate it if exposed.
- Configure the provider token only in host environment variables.
- The included rate limiter is a per-admin-key, per-process limit; put a production reverse proxy/WAF in front of the service for IP-level controls and additional abuse protection.
- SQLite requires persistent storage and a single service instance; use a managed database before horizontally scaling.
- Provider timeouts can leave an ambiguous outcome; idempotency keys prevent repeated requests from creating a second local job, but the provider must honor its own semantics.
- Review `/v1/sms/audit` and host logs; do not log bearer tokens.
