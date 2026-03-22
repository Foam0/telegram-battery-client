#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later

# Usage: uvicorn main:app


import uvloop

uvloop.install()

from starlette.applications import Starlette
from starlette.responses import Response
from starlette.routing import Route

import httpx


async def topic(request):
    path = request.path_params["path"]

    body = await request.body()

    headers = httpx.Headers({
        "TTL": "2592000", # 30 days
        "Content-Encoding": "aes128gcm", # Fake this encoding to be web push compliant
        "Urgency": "high"
    })

    # Forward the request to the target URL
    async with httpx.AsyncClient(timeout=15.0) as client:
        upstream_response = await client.post(
            url=path,
            data=body,
            headers=headers,
        )

    return Response(
        content=upstream_response.content, status_code=upstream_response.status_code
    )


async def aesgcm(request):
    """POST /aesgcm?e=<url-encoded-endpoint>

    Serializes WebPush aesgcm headers (Encryption, Crypto-Key) into the body before
    forwarding to the UnifiedPush endpoint. UP distributors strip HTTP headers, so
    this embeds them in the body for client-side decryption.

    Body format sent to the UP endpoint:
      aesgcm\\n
      Encryption: <value>\\n
      Crypto-Key: <value>\\n
      <original binary ciphertext>
    """
    endpoint = request.query_params.get("e")
    if not endpoint:
        return Response(status_code=400)

    body = await request.body()

    encryption = request.headers.get("encryption", "")
    crypto_key = request.headers.get("crypto-key", "")

    # Prepend headers as text lines before the binary ciphertext
    new_body = (
        b"aesgcm\n"
        + f"Encryption: {encryption}\n".encode()
        + f"Crypto-Key: {crypto_key}\n".encode()
        + body
    )

    async with httpx.AsyncClient(timeout=15.0) as client:
        upstream_response = await client.post(
            url=endpoint,
            data=new_body,
            headers=httpx.Headers({
                "TTL": "2592000",
                "Urgency": "high",
                "Content-Encoding": "aes128gcm",  # required by WebPush-compliant distributors
            }),
        )

    print(f"aesgcm → {endpoint} status={upstream_response.status_code}", flush=True)
    # WebPush requires 201 Created on success; normalize any 2xx from the
    # upstream distributor (e.g. ntfy returns 200) to avoid Telegram backoff.
    # Include a Location header as required by the spec.
    if upstream_response.is_success:
        location = upstream_response.headers.get("location", endpoint)
        return Response(status_code=201, headers={"location": location})
    return Response(content=upstream_response.content, status_code=upstream_response.status_code)


app = Starlette(
    routes=[
        Route("/aesgcm", aesgcm, methods=["POST"]),
        Route("/{path:path}", topic, methods=["PUT"]),
    ],
)
