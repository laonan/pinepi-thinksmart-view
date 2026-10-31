from contextlib import asynccontextmanager, AsyncExitStack

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.staticfiles import StaticFiles
from .link_test import LinkTest

MAX_FRAME_BYTES = 4096


def create_app(*, diagnostics: bool = False, link_test: LinkTest | None = None, discovery=None, broadcast_mid=None, media_directory=None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app):
        async with AsyncExitStack() as stack:
            if discovery is not None:
                await stack.enter_async_context(discovery)
            if broadcast_mid is not None:
                await stack.enter_async_context(broadcast_mid)
            yield

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    if media_directory is not None:
        # Explicit public-to-trusted-LAN media directory only, never the config/state root.
        app.mount('/media', StaticFiles(directory=media_directory, follow_symlink=False), name='media')

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok", "service": "pinepi-hub", "stage": "foundation"}

    if diagnostics:
        # Synthetic transport probe only; not a device/message/business endpoint.
        @app.websocket("/_diagnostics/echo")
        async def echo(websocket: WebSocket) -> None:
            await websocket.accept()
            try:
                while True:
                    event = await websocket.receive()
                    if event["type"] == "websocket.disconnect":
                        return
                    text = event.get("text")
                    if text is None:
                        await websocket.close(code=1003)
                        return
                    if len(text.encode("utf-8")) > MAX_FRAME_BYTES:
                        await websocket.close(code=1009)
                        return
                    await websocket.send_text(text)
            except WebSocketDisconnect:
                return

    if link_test is not None:
        link_test.attach(app)
    return app

