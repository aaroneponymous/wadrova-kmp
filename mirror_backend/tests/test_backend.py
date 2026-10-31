"""Every test uses a fake service or a mock HTTP transport, so none can reach OpenAI."""

import base64
import json

import httpx
import openai
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.images import ImageService
from app.main import create_app

TOKEN = "test-token"
HEADERS = {"X-Wadrova-Dev-Token": TOKEN}
JPEG = b"\xff\xd8\xff" + b"jpeg-data"
PNG = b"\x89PNG\r\n\x1a\n" + b"png-data"
RESULT = b"\x89PNG\r\n\x1a\n" + b"generated"


def settings(**overrides) -> Settings:
    values = dict(
        _env_file=None,
        openai_api_key="sk-fake-for-tests",
        wadrova_live_inference=True,
        wadrova_dev_token=TOKEN,
    )
    return Settings(**{**values, **overrides})


class FakeService:
    def __init__(self, error=None):
        self.calls = []
        self.error = error

    def _result(self, name, *args):
        self.calls.append((name, args))
        if self.error:
            raise self.error
        return RESULT

    def base_model(self, references):
        return self._result("base_model", references)

    def extract_garment(self, photo):
        return self._result("extract_garment", photo)

    def try_on(self, model, garment):
        return self._result("try_on", model, garment)


def client_with(service=None, **overrides):
    service = service or FakeService()
    app = create_app(settings(**overrides), lambda _: service)
    return TestClient(app), service


def references(count):
    return [("images", (f"{i}.jpg", JPEG, "image/jpeg")) for i in range(count)]


ENDPOINT_CALLS = [
    ("/v1/images/base-model", lambda: references(3)),
    ("/v1/images/garment-extraction", lambda: [("image", ("g.jpg", JPEG, "image/jpeg"))]),
    (
        "/v1/images/try-on",
        lambda: [("model_image", ("m.png", PNG, "image/png")), ("garment_image", ("g.jpg", JPEG, "image/jpeg"))],
    ),
]


@pytest.mark.parametrize("path,files", ENDPOINT_CALLS)
def test_requires_the_development_token(path, files):
    client, service = client_with()
    assert client.post(path, files=files()).status_code == 401
    assert client.post(path, files=files(), headers={"X-Wadrova-Dev-Token": "wrong"}).status_code == 401
    assert service.calls == []


@pytest.mark.parametrize("path,files", ENDPOINT_CALLS)
def test_refuses_everything_when_no_token_is_configured(path, files):
    client, service = client_with(wadrova_dev_token="")
    response = client.post(path, files=files(), headers=HEADERS)
    assert response.status_code == 503
    assert response.json()["code"] == "token_not_configured"
    assert service.calls == []


@pytest.mark.parametrize("path,files", ENDPOINT_CALLS)
def test_live_inference_is_off_unless_enabled(path, files):
    client, service = client_with(wadrova_live_inference=False)
    response = client.post(path, files=files(), headers=HEADERS)
    assert response.status_code == 503
    assert response.json()["code"] == "live_inference_disabled"
    assert service.calls == []


def test_live_inference_defaults_to_off():
    assert Settings(_env_file=None).wadrova_live_inference is False


@pytest.mark.parametrize("path,files", ENDPOINT_CALLS)
def test_reports_a_missing_api_key(path, files):
    client, service = client_with(openai_api_key="")
    response = client.post(path, files=files(), headers=HEADERS)
    assert response.status_code == 503
    assert response.json()["code"] == "api_key_not_configured"
    assert service.calls == []


@pytest.mark.parametrize("path,files", ENDPOINT_CALLS)
def test_returns_private_png_with_generation_details(path, files):
    client, service = client_with()
    response = client.post(path, files=files(), headers=HEADERS)
    assert response.status_code == 200
    assert response.content == RESULT
    assert response.headers["content-type"] == "image/png"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-generation-model"] == "gpt-image-2.5-sunburst"
    assert response.headers["x-generation-quality"] == "high"
    assert len(service.calls) == 1


@pytest.mark.parametrize("count", [0, 1, 2, 6])
def test_base_model_requires_three_to_five_references(count):
    client, service = client_with()
    response = client.post("/v1/images/base-model", files=references(count) or None, headers=HEADERS)
    assert response.status_code in (400, 422)
    assert service.calls == []


def test_base_model_keeps_reference_order():
    client, service = client_with()
    files = [
        ("images", ("a.jpg", JPEG + b"first", "image/jpeg")),
        ("images", ("b.png", PNG, "image/png")),
        ("images", ("c.jpg", JPEG + b"third", "image/jpeg")),
    ]
    client.post("/v1/images/base-model", files=files, headers=HEADERS)
    sent = service.calls[0][1][0]
    assert [image.data for image in sent] == [JPEG + b"first", PNG, JPEG + b"third"]
    assert [image.filename for image in sent] == ["reference-0.jpeg", "reference-1.png", "reference-2.jpeg"]


def test_try_on_sends_model_before_garment():
    client, service = client_with()
    client.post(ENDPOINT_CALLS[2][0], files=ENDPOINT_CALLS[2][1](), headers=HEADERS)
    model, garment = service.calls[0][1]
    assert model.data == PNG
    assert garment.data == JPEG


def test_rejects_files_that_are_not_images():
    client, service = client_with()
    response = client.post(
        "/v1/images/garment-extraction",
        files=[("image", ("x.jpg", b"not an image", "image/jpeg"))],
        headers=HEADERS,
    )
    assert response.status_code == 415
    assert service.calls == []


def test_rejects_oversized_images():
    client, service = client_with()
    huge = JPEG + b"0" * (20 * 1024 * 1024)
    response = client.post("/v1/images/garment-extraction", files=[("image", ("x.jpg", huge, "image/jpeg"))], headers=HEADERS)
    assert response.status_code == 413
    assert service.calls == []


def _openai_error(cls, status):
    request = httpx.Request("POST", "https://api.openai.com/v1/images/edits")
    return cls("secret detail from openai", response=httpx.Response(status, request=request), body=None)


@pytest.mark.parametrize(
    "error,status,code",
    [
        (openai.APITimeoutError(request=httpx.Request("POST", "http://x")), 504, "openai_timeout"),
        (openai.APIConnectionError(request=httpx.Request("POST", "http://x")), 502, "openai_unreachable"),
        (_openai_error(openai.RateLimitError, 429), 429, "openai_rate_limited"),
        (_openai_error(openai.AuthenticationError, 401), 502, "openai_auth_failed"),
        (_openai_error(openai.PermissionDeniedError, 403), 502, "openai_permission_denied"),
        (_openai_error(openai.BadRequestError, 400), 422, "openai_rejected"),
        (_openai_error(openai.InternalServerError, 500), 502, "openai_error"),
    ],
)
def test_maps_openai_failures_without_exposing_details(error, status, code, caplog):
    client, _ = client_with(FakeService(error))
    response = client.post(ENDPOINT_CALLS[1][0], files=ENDPOINT_CALLS[1][1](), headers=HEADERS)
    assert response.status_code == status
    assert response.json()["code"] == code
    assert "secret detail" not in response.text
    assert "secret detail" not in caplog.text


def test_status_reports_configuration_without_secrets():
    client, service = client_with()
    assert client.get("/v1/status").status_code == 401
    body = client.get("/v1/status", headers=HEADERS).json()
    assert body == {
        "api_key_configured": True,
        "live_inference_enabled": True,
        "model": "gpt-image-2.5-sunburst",
        "quality": "high",
    }
    assert service.calls == []
    assert "sk-fake" not in json.dumps(body)


def test_health_reveals_nothing_and_docs_are_off():
    client, _ = client_with()
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404


# Request construction, using the real SDK over a mock transport.


class CapturedEdits:
    def __init__(self, response=None):
        self.requests = []
        self.response = response or {"created": 1, "data": [{"b64_json": base64.b64encode(RESULT).decode()}]}

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return httpx.Response(200, json=self.response)


def real_service(captured, **overrides):
    client = openai.OpenAI(
        api_key="sk-fake-for-tests",
        max_retries=0,
        http_client=httpx.Client(transport=httpx.MockTransport(captured)),
    )
    return ImageService(client, settings(**overrides))


def form_fields(request):
    body = request.read()
    boundary = request.headers["content-type"].split("boundary=")[1].encode()
    fields = {}
    files = []
    for part in body.split(b"--" + boundary):
        if b"name=" not in part:
            continue
        headers, _, value = part.partition(b"\r\n\r\n")
        value = value.rstrip(b"\r\n-")
        name = headers.split(b'name="')[1].split(b'"')[0].decode()
        if b"filename=" in headers:
            files.append((name, value))
        else:
            fields[name] = value.decode()
    return fields, files


def test_sdk_request_for_base_model():
    captured = CapturedEdits()
    from app.images import ImageInput

    inputs = [ImageInput(f"r{i}.jpeg", "image/jpeg", JPEG + bytes([i])) for i in range(3)]
    assert real_service(captured).base_model(inputs) == RESULT

    request = captured.requests[0]
    assert str(request.url).endswith("/images/edits")
    fields, files = form_fields(request)
    assert fields["model"] == "gpt-image-2.5-sunburst"
    assert fields["n"] == "1"
    assert fields["size"] == "1024x1536"
    assert fields["quality"] == "high"
    assert fields["output_format"] == "png"
    assert "background" not in fields
    assert "head to toe" in fields["prompt"]
    assert [value for _, value in files] == [input.data for input in inputs]


def test_sdk_request_for_garment_extraction_asks_for_transparency():
    captured = CapturedEdits()
    from app.images import ImageInput

    real_service(captured).extract_garment(ImageInput("g.jpeg", "image/jpeg", JPEG))
    fields, files = form_fields(captured.requests[0])
    assert fields["background"] == "transparent"
    assert fields["output_format"] == "png"
    assert "transparent" in fields["prompt"]
    assert len(files) == 1


def test_sdk_request_for_try_on_is_portrait_with_model_first():
    captured = CapturedEdits()
    from app.images import ImageInput

    real_service(captured).try_on(ImageInput("m.png", "image/png", PNG), ImageInput("g.jpeg", "image/jpeg", JPEG))
    fields, files = form_fields(captured.requests[0])
    assert fields["size"] == "1024x1536"
    assert "background" not in fields
    assert [value for _, value in files] == [PNG, JPEG]


def test_model_is_configurable():
    captured = CapturedEdits()
    from app.images import ImageInput

    real_service(captured, wadrova_image_model="gpt-image-2.5-flare").extract_garment(
        ImageInput("g.jpeg", "image/jpeg", JPEG)
    )
    assert form_fields(captured.requests[0])[0]["model"] == "gpt-image-2.5-flare"


def test_empty_response_is_an_error():
    from app.images import EmptyResultError, ImageInput

    captured = CapturedEdits({"created": 1, "data": []})
    with pytest.raises(EmptyResultError):
        real_service(captured).extract_garment(ImageInput("g.jpeg", "image/jpeg", JPEG))
