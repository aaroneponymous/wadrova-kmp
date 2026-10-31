from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    wadrova_live_inference: bool = False
    wadrova_dev_token: str = ""
    wadrova_image_model: str = "gpt-image-2.5-sunburst"
    wadrova_image_quality: str = "high"
    wadrova_request_timeout_seconds: float = 240.0
