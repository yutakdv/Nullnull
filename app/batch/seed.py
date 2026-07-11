"""서울 MVP 시드 적재 CLI — `python -m app.batch.seed [--force]`"""
import argparse

from app import seed_data
from app.database import Base, SessionLocal, engine


def main() -> None:
    parser = argparse.ArgumentParser(description="널널 시드 데이터 적재")
    parser.add_argument("--force", action="store_true", help="기존 데이터 삭제 후 재적재")
    args = parser.parse_args()

    Base.metadata.create_all(engine)
    with SessionLocal() as db:
        result = seed_data.run(db, force=args.force)
    if result.get("skipped"):
        print(f"이미 시드가 있어요(스팟 {result['spots']}개). --force로 재적재할 수 있어요.")
    else:
        print(f"시드 적재 완료 — 스팟 {result['spots']}개, 스냅샷 {result['snapshots']}건")


if __name__ == "__main__":
    main()
