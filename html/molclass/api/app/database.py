from sqlalchemy import URL, create_engine
from sqlalchemy.orm import sessionmaker, declarative_base
from app.config import settings


def _engine(host, port, database, username, password, pool_size):
    url = URL.create(
        "mysql+pymysql",
        username=username,
        password=password,
        host=host,
        port=port,
        database=database,
        query={"charset": "utf8mb4"},
    )
    return create_engine(
        url,
        pool_size=pool_size,
        max_overflow=2,
        pool_recycle=1800,
        pool_pre_ping=True,
        pool_timeout=5,
        connect_args={"init_command": "SET time_zone = '+00:00'"},
    )


engine = _engine(
    settings.db_host,
    settings.db_port,
    settings.db_name,
    settings.db_user,
    settings.db_pass,
    4,
)
v3_engine = _engine(
    settings.v3_db_host,
    settings.v3_db_port,
    settings.v3_db_name,
    settings.v3_db_user,
    settings.v3_db_pass,
    8,
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
V3SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=v3_engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_v3_db():
    db = V3SessionLocal()
    try:
        yield db
    finally:
        db.close()
