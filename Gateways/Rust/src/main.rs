// SPDX-License-Identifier: GPL-2.0-or-later

use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Path, Query, State},
    http::{HeaderName, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{post, put},
    Router,
};
use axum::body::Bytes;
use axum::http::HeaderMap;
use axum_macros::debug_handler;
use listenfd::ListenFd;
use reqwest::Client;
use serde::Deserialize;
use std::time::Duration;

/// PUT /<url> — original PUT-to-POST proxy (kept for backward compatibility)
#[debug_handler]
async fn put_proxy(State(client): State<Client>, Path(path): Path<String>, body: Bytes) -> Response {
    match forward(&client, &path, body).await {
        Ok(upstream) => {
            let status = StatusCode::from_u16(upstream.status().as_u16())
                .unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
            (status, Body::from_stream(upstream.bytes_stream())).into_response()
        }
        Err(e) => e,
    }
}

#[derive(Deserialize)]
struct AesgcmParams {
    e: String,
}

/// POST /aesgcm?e=<url-encoded-endpoint>
///
/// Serializes WebPush aesgcm headers (Encryption, Crypto-Key) into the body before
/// forwarding to the UnifiedPush endpoint. This is required because UP distributors
/// strip HTTP headers, making client-side aesgcm decryption impossible without this step.
///
/// Body format sent to the UP endpoint:
///   aesgcm\n
///   Encryption: <value>\n
///   Crypto-Key: <value>\n
///   <original binary ciphertext>
#[debug_handler]
async fn aesgcm(
    State(client): State<Client>,
    Query(params): Query<AesgcmParams>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let encryption = headers
        .get("encryption")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let crypto_key = headers
        .get("crypto-key")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    // Prepend headers as text lines before the binary ciphertext.
    // Pre-allocate the exact capacity to avoid reallocations and format! heap churn.
    let capacity = 7  // "aesgcm\n"
        + 12 + encryption.len() + 1  // "Encryption: " + value + "\n"
        + 12 + crypto_key.len() + 1  // "Crypto-Key: " + value + "\n"
        + body.len();
    let mut new_body = Vec::with_capacity(capacity);
    new_body.extend_from_slice(b"aesgcm\n");
    new_body.extend_from_slice(b"Encryption: ");
    new_body.extend_from_slice(encryption.as_bytes());
    new_body.push(b'\n');
    new_body.extend_from_slice(b"Crypto-Key: ");
    new_body.extend_from_slice(crypto_key.as_bytes());
    new_body.push(b'\n');
    new_body.extend_from_slice(&body);

    let upstream = match forward(&client, &params.e, new_body).await {
        Ok(r) => r,
        Err(e) => return e,
    };

    let upstream_status = upstream.status();
    eprintln!("aesgcm → {} status={}", params.e, upstream_status);

    if upstream_status.is_success() {
        // Normalize any 2xx to 201 Created per WebPush spec to avoid Telegram backoff
        // (e.g. ntfy returns 200). Prefer location from upstream; fall back to endpoint URL.
        let location_val = upstream
            .headers()
            .get("location")
            .and_then(|v| HeaderValue::from_bytes(v.as_bytes()).ok())
            .or_else(|| HeaderValue::from_str(&params.e).ok())
            .unwrap_or_else(|| HeaderValue::from_static(""));
        return (
            StatusCode::CREATED,
            [(HeaderName::from_static("location"), location_val)],
        )
            .into_response();
    }

    let status = StatusCode::from_u16(upstream_status.as_u16())
        .unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    (status, Body::from_stream(upstream.bytes_stream())).into_response()
}

/// Forward `body` via POST to `endpoint` with WebPush headers.
/// Returns the raw reqwest response on success, or an error Response on network failure.
async fn forward(
    client: &Client,
    endpoint: &str,
    body: impl Into<reqwest::Body>,
) -> Result<reqwest::Response, Response> {
    client
        .post(endpoint)
        .body(body)
        .header("TTL", "2592000")
        .header("Urgency", "high")
        .header("Content-Encoding", "aes128gcm") // required by WebPush-compliant distributors
        .send()
        .await
        .map_err(|err| {
            eprintln!("Request to {endpoint} failed: {err}");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        })
}

#[tokio::main]
async fn main() {
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(15))
        .build()
        .unwrap();
    let app = Router::new()
        .route("/aesgcm", post(aesgcm))
        .route("/{*path}", put(put_proxy))
        .layer(DefaultBodyLimit::max(65536)) // 64 KiB — push payloads are small
        .with_state(client);

    let listener = {
        let mut listenfd = ListenFd::from_env();
        match listenfd.take_tcp_listener(0).unwrap() {
            Some(std_listener) => {
                std_listener.set_nonblocking(true).unwrap();
                tokio::net::TcpListener::from_std(std_listener).unwrap()
            }
            None => tokio::net::TcpListener::bind("127.0.0.1:8001").await.unwrap(),
        }
    };
    println!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}
