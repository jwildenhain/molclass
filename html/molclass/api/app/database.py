from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base
from app.config import settings

# Construct the SQLAlchemy database connection string using dynamic configurations
DATABASE_URL = f"mysql+pymysql://{settings.db_user}:{settings.db_pass}@{settings.db_host}/{settings.db_name}?charset=utf8mb4"

# Create a database engine with solid connection pooling parameters
engine = create_engine(
    DATABASE_URL,
    pool_size=10,
    max_overflow=20,
    pool_recycle=3600,      # Recycle connections after an hour
    pool_pre_ping=True      # Verify connection validity on check-out
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

# Request-scoped database session dependency
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
