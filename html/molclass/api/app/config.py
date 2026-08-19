import os
import xml.etree.ElementTree as ET
from pathlib import Path


def _find_config_file():
    current_dir = os.path.abspath(os.path.dirname(__file__))
    for _ in range(6):
        candidate = os.path.join(current_dir, "molclass.conf.xml")
        if os.path.exists(candidate):
            return candidate
        current_dir = os.path.dirname(current_dir)
    candidate = os.getenv("MOLCLASS_CONFIG_FILE")
    return candidate if candidate and os.path.exists(candidate) else None


def _boolean(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _integer(name: str, default: int, minimum: int = 1) -> int:
    value = int(os.getenv(name, str(default)))
    if value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return value


class Settings:
    def __init__(self):
        root = None
        config_path = _find_config_file()
        if config_path:
            root = ET.parse(config_path).getroot()

        def xml_value(name: str, fallback: str) -> str:
            if root is None:
                return fallback
            element = root.find(name)
            return element.text.strip() if element is not None and element.text else fallback

        self.db_host = os.getenv("MOLCLASS_LEGACY_DB_HOST", xml_value("hostname", "127.0.0.1"))
        self.db_port = _integer("MOLCLASS_LEGACY_DB_PORT", 3306)
        self.db_name = os.getenv("MOLCLASS_LEGACY_DB_NAME", xml_value("database", "molclass_legacy"))
        self.db_user = os.getenv("MOLCLASS_LEGACY_DB_USER", xml_value("ro_user", "molclass_user"))
        self.db_pass = os.getenv("MOLCLASS_LEGACY_DB_PASSWORD", xml_value("ro_password", ""))

        self.v3_db_host = os.getenv("MOLCLASS_V3_DB_HOST", os.getenv("MOLCLASS_DB_HOST", "127.0.0.1"))
        self.v3_db_port = _integer("MOLCLASS_V3_DB_PORT", 3306)
        self.v3_db_name = os.getenv("MOLCLASS_V3_SCHEMA", "molclass_v3")
        self.v3_db_user = os.getenv("MOLCLASS_DB_USER", "")
        self.v3_db_pass = os.getenv("MOLCLASS_DB_PASSWORD", "")
        self.model_approval_enabled = _boolean("MOLCLASS_MODEL_APPROVAL_ENABLED", False)
        self.model_review_token = os.getenv("MOLCLASS_MODEL_REVIEW_TOKEN", "")
        self.approval_db_user = os.getenv("MOLCLASS_APPROVAL_DB_USER", "")
        self.approval_db_pass = os.getenv("MOLCLASS_APPROVAL_DB_PASSWORD", "")
        self.model_approval_timeout_seconds = _integer(
            "MOLCLASS_MODEL_APPROVAL_TIMEOUT_SECONDS", 120
        )
        self.approval_repo_root = Path(
            os.getenv("MOLCLASS_REPO_ROOT", str(Path(__file__).resolve().parents[4]))
        ).resolve()

        self.upload_root = Path(os.getenv("MOLCLASS_UPLOAD_ROOT", "uploads/v3")).resolve()
        self.max_upload_bytes = _integer("MOLCLASS_MAX_UPLOAD_BYTES", 2 * 1024 * 1024 * 1024)
        self.upload_retention_days = _integer("MOLCLASS_UPLOAD_RETENTION_DAYS", 30)
        self.legacy_api_enabled = _boolean("MOLCLASS_LEGACY_API_ENABLED", False)
        origins = os.getenv(
            "MOLCLASS_ALLOWED_ORIGINS",
            "http://127.0.0.1:3000,http://localhost:3000",
        )
        self.allowed_origins = [origin.strip() for origin in origins.split(",") if origin.strip()]

        self.molclass_email = os.getenv(
            "MOLCLASS_EMAIL", xml_value("molclassemail", "molclass@localhost")
        )
        self.website = os.getenv(
            "MOLCLASS_WEBSITE", xml_value("website", "http://127.0.0.1:3000")
        )
        raw_tools = xml_value("toolsdir", "./tools/sdftools/")
        if config_path and raw_tools.startswith("./"):
            self.tools_dir = os.path.abspath(
                os.path.join(os.path.dirname(config_path), raw_tools[2:])
            )
        else:
            self.tools_dir = os.path.abspath(raw_tools)


settings = Settings()
