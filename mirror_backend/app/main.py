"""Local development backend for Wadrova Mirror. It keeps the OpenAI key off the phone."""

import logging
import secrets
from collections.abc import Callable

import openai
from fastapi import Depends, FastAPI, Header, Request, UploadFile
from fastapi.responses import JSONResponse, Response

from .config import Settings
from .images import EmptyResultError, ImageInput, ImageService

# The SDK's HTTP client can log request details at lower levels.
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("openai").setLevel(logging.WARNING)
logger = logging.getLogger("wadrova.mirror")

MAX_IMAGE_BYTES = 20 * 1024 * 1024
MIN_REFERENCES = 3
MAX_REFERENCES = 5


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message


def _image_content_type(data: bytes) -> str | None:
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


async def _read_image(upload: UploadFile, name: str) -> ImageInput:
    data = await upload.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise ApiError(413, "image_too_large", "An image is larger than 20 MB.")
    content_type = _image_content_type(data)
    if content_type is None:
        raise ApiError(415, "unsupported_image", "Images must be JPEG, PNG, or WebP.")
    extension = content_type.split("/")[1]
    return ImageInput(f"{name}.{extension}", content_type, data)


def _default_service(settings: Settings) -> ImageService:
    return ImageService(openai.OpenAI(api_key=settings.openai_api_key, max_retries=0), settings)


def create_app(
    settings: Settings | None = None,
    service_factory: Callable[[Settings], ImageService] = _default_service,
) -> FastAPI:
    settings = settings or Settings()
    app = FastAPI(title="Wadrova Mirror development backend", docs_url=None, redoc_url=None, openapi_url=None)

    @app.exception_handler(ApiError)
    async def handle_api_error(_: Request, error: ApiError) -> JSONResponse:
        return JSONResponse(status_code=error.status, content={"code": error.code, "message": error.message})

    def require_token(x_wadrova_dev_token: str = Header(default="")) -> None:
        if not settings.wadrova_dev_token:
            raise ApiError(503, "token_not_configured", "Set WADROVA_DEV_TOKEN on the backend.")
        if not secrets.compare_digest(x_wadrova_dev_token.encode(), settings.wadrova_dev_token.encode()):
            raise ApiError(401, "invalid_token", "The app's development token doesn't match the backend.")

    def require_live_service() -> ImageService:
        if not settings.openai_api_key:
            raise ApiError(503, "api_key_not_configured", "Set OPENAI_API_KEY on the backend.")
        if not settings.wadrova_live_inference:
            raise ApiError(503, "live_inference_disabled", "Set WADROVA_LIVE_INFERENCE=true on the backend.")
        return service_factory(settings)

    def generate(operation: str, call: Callable[[], bytes]) -> Response:
        """Logs only the operation and failure type, never image data or error bodies."""
        try:
            image = call()
        except openai.APITimeoutError:
            logger.warning("%s failed: timeout", operation)
            raise ApiError(504, "openai_timeout", "OpenAI took too long to respond.") from None
        except openai.RateLimitError:
            logger.warning("%s failed: rate limit", operation)
            raise ApiError(429, "openai_rate_limited", "OpenAI rate limit or quota reached.") from None
        except openai.AuthenticationError:
            logger.warning("%s failed: authentication", operation)
            raise ApiError(502, "openai_auth_failed", "OpenAI rejected the API key.") from None
        except openai.PermissionDeniedError:
            logger.warning("%s failed: permission", operation)
            raise ApiError(502, "openai_permission_denied", "This API key can't use the image model.") from None
        except openai.BadRequestError:
            logger.warning("%s failed: rejected request", operation)
            raise ApiError(422, "openai_rejected", "OpenAI rejected this request or its images.") from None
        except openai.APIConnectionError:
            logger.warning("%s failed: connection", operation)
            raise ApiError(502, "openai_unreachable", "Couldn't reach OpenAI.") from None
        except openai.APIError:
            logger.warning("%s failed: API error", operation)
            raise ApiError(502, "openai_error", "OpenAI returned an error.") from None
        except EmptyResultError:
            logger.warning("%s failed: empty result", operation)
            raise ApiError(502, "openai_empty_result", "OpenAI returned no image.") from None
        return Response(
            content=image,
            media_type="image/png",
            headers={
                "Cache-Control": "no-store",
                "X-Generation-Model": settings.wadrova_image_model,
                "X-Generation-Quality": settings.wadrova_image_quality,
            },
        )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/v1/status", dependencies=[Depends(require_token)])
    def status() -> dict[str, object]:
        """Reports local configuration only. It doesn't contact OpenAI."""
        return {
            "api_key_configured": bool(settings.openai_api_key),
            "live_inference_enabled": settings.wadrova_live_inference,
            "model": settings.wadrova_image_model,
            "quality": settings.wadrova_image_quality,
        }

    @app.post("/v1/images/base-model", dependencies=[Depends(require_token)])
    async def base_model(images: list[UploadFile], service: ImageService = Depends(require_live_service)) -> Response:
        if not MIN_REFERENCES <= len(images) <= MAX_REFERENCES:
            raise ApiError(400, "invalid_reference_count", "Send 3 to 5 reference photographs.")
        references = [await _read_image(image, f"reference-{index}") for index, image in enumerate(images)]
        return generate("base-model", lambda: service.base_model(references))

    @app.post("/v1/images/garment-extraction", dependencies=[Depends(require_token)])
    async def garment_extraction(image: UploadFile, service: ImageService = Depends(require_live_service)) -> Response:
        photo = await _read_image(image, "garment")
        return generate("garment-extraction", lambda: service.extract_garment(photo))

    @app.post("/v1/images/try-on", dependencies=[Depends(require_token)])
    async def try_on(
        model_image: UploadFile,
        garment_image: UploadFile,
        service: ImageService = Depends(require_live_service),
    ) -> Response:
        model = await _read_image(model_image, "model")
        garment = await _read_image(garment_image, "garment")
        return generate("try-on", lambda: service.try_on(model, garment))

    return app


app = create_app()
