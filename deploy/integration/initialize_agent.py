"""Initialize the pinned Agent storage before starting concurrent workers."""

import asyncio
import os

import psycopg

from app.harness.checkpoint import open_checkpointer
from app.runtime.fencing import FencedPostgresSaver
from app.runtime.inbox import PostgresInbox


async def initialize() -> None:
    url = os.environ["AGENT_INBOX_DB_URL"]
    await asyncio.to_thread(PostgresInbox(url).setup)
    async with open_checkpointer(url, saver_type=FencedPostgresSaver):
        pass
    print("AGENT_STORAGE_READY", flush=True)


if __name__ == "__main__":
    try:
        asyncio.run(initialize())
    except (psycopg.Error, OSError, ValueError):
        raise SystemExit("AGENT_STORAGE_NOT_READY") from None
