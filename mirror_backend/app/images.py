"""The only code that calls OpenAI. Images stay in memory, so no temporary files are created."""

import base64
from dataclasses import dataclass

import openai

from . import prompts
from .config import Settings

PORTRAIT = "1024x1536"


@dataclass(frozen=True)
class ImageInput:
    filename: str
    content_type: str
    data: bytes

    def as_upload(self) -> tuple[str, bytes, str]:
        return (self.filename, self.data, self.content_type)


class EmptyResultError(Exception):
    """OpenAI responded successfully without an image."""


class ImageService:
    def __init__(self, client: openai.OpenAI, settings: Settings):
        self._client = client
        self._settings = settings

    def base_model(self, references: list[ImageInput]) -> bytes:
        return self._edit(references, prompts.BASE_MODEL, size=PORTRAIT)

    def extract_garment(self, photo: ImageInput) -> bytes:
        return self._edit([photo], prompts.GARMENT_EXTRACTION, size="auto", background="transparent")

    def try_on(self, model: ImageInput, garment: ImageInput) -> bytes:
        return self._edit([model, garment], prompts.TRY_ON, size=PORTRAIT)

    def _edit(self, images: list[ImageInput], prompt: str, **options) -> bytes:
        response = self._client.images.edit(
            model=self._settings.wadrova_image_model,
            image=[image.as_upload() for image in images],
            prompt=prompt,
            n=1,
            quality=self._settings.wadrova_image_quality,
            output_format="png",
            timeout=self._settings.wadrova_request_timeout_seconds,
            **options,
        )
        encoded = response.data[0].b64_json if response.data else None
        if not encoded:
            raise EmptyResultError
        return base64.b64decode(encoded)
