# Wadrova Mirror development backend

A small FastAPI server that keeps the OpenAI key off the phone. It's for local development only, and it's independent of the Gradle build.

Images are held in memory for the length of a request and aren't written to disk. Image data, credentials, and OpenAI error bodies aren't logged.

## Safety switches

Paid generation runs only when all of these are set in `.env`:

| Setting | Without it |
|---|---|
| `WADROVA_DEV_TOKEN` | Every request except `/health` is refused |
| `OPENAI_API_KEY` | Image endpoints return `api_key_not_configured` |
| `WADROVA_LIVE_INFERENCE=true` | Image endpoints return `live_inference_disabled` |

## Setup

```bash
cd mirror_backend
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
cp .env.example .env
```

## Enable live generation

Only do this when you're ready for paid requests. In `.env`, set `OPENAI_API_KEY`, set `WADROVA_DEV_TOKEN` to the output of `openssl rand -hex 32`, and set `WADROVA_LIVE_INFERENCE=true`. Restart the backend after any change.

To turn generation off again, set `WADROVA_LIVE_INFERENCE=false` and restart.

## Run

```bash
# Emulator or simulator
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8787

# Physical phone on your own Wi-Fi. This listens on your network without HTTPS, so use trusted networks only.
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8787
```

The app's address depends on the device:

| Device | Backend address in Mirror Settings |
|---|---|
| iOS simulator | `http://127.0.0.1:8787` |
| Android emulator or USB phone | `http://127.0.0.1:8787`, after `adb reverse tcp:8787 tcp:8787` |
| iPhone on Wi-Fi | `http://YOUR-MAC-NAME.local:8787`; find the name with `scutil --get LocalHostName` |

Release Android builds don't allow plain HTTP.

## Manual workflows

Each **Send to OpenAI** is one paid request. The backend doesn't retry automatically.

First, open **Mirror**, then **Mirror Settings**, and enter the backend address and your `WADROVA_DEV_TOKEN`. Tap **Save and Check Connection**, which doesn't contact OpenAI. It should say "Connected and ready."

1. **Create a model:** In Mirror, tap **Create Your Model**, add 3 to 5 photos, tick the confirmation, tap **Create Model**, then **Send to OpenAI**. Review the result, then tap **Approve Model**.
2. **Remove a background:** Edit one of your garments that has a saved photo. Tap **Remove Background**, then **Send to OpenAI**. Review the result, then tap **Use Extracted Image**.
3. **Try on a garment:** With an approved model, open Mirror and tap **Try On a Garment**. Choose a piece, tap **Try It On**, then **Send to OpenAI**. Review the result, then tap **Accept Preview**.

## Endpoints

All except `/health` need the `X-Wadrova-Dev-Token` header. Image endpoints take multipart form data and return a PNG.

| Endpoint | Form fields |
|---|---|
| `GET /health` | None |
| `GET /v1/status` | None; reports local configuration without contacting OpenAI |
| `POST /v1/images/base-model` | `images`, 3 to 5 files with the primary reference first |
| `POST /v1/images/garment-extraction` | `image` |
| `POST /v1/images/try-on` | `model_image`, `garment_image` |

Errors are JSON: `{"code": "...", "message": "..."}`.

## Test

These never contact OpenAI. They use a fake service or a mock HTTP transport.

```bash
.venv/bin/python -m pytest
```
