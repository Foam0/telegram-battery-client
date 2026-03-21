// SPDX-License-Identifier: GPL-2.0-or-later

use axum::{
    body::{Body, Bytes},
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderName, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{post, put},
    Router,
};
use axum_macros::debug_handler;
use reqwest::Client;
use serde::Deserialize;

/// PUT /<url> — original PUT-to-POST proxy (kept for backward compatibility)
#[debug_handler]
async fn put_proxy(State(client): State<Client>, Path(path): Path<String>, body: Bytes) -> Response {
    forward(client, path, body, vec![]).await
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

    // Prepend headers as text lines before the binary ciphertext
    let mut new_body: Vec<u8> = Vec::new();
    new_body.extend_from_slice(b"aesgcm\n");
    new_body.extend_from_slice(format!("Encryption: {}\n", encryption).as_bytes());
    new_body.extend_from_slice(format!("Crypto-Key: {}\n", crypto_key).as_bytes());
    new_body.extend_from_slice(&body);

    let response = forward(client, params.e.clone(), new_body, vec![]).await;
    let upstream_status = response.status();
    eprintln!("aesgcm → {} status={}", params.e, upstream_status);
    // WebPush requires 201 Created on success; normalize any 2xx from the
    // upstream distributor (e.g. ntfy returns 200) to avoid Telegram backoff.
    // Include a Location header as required by the spec.
    if upstream_status.is_success() && upstream_status != StatusCode::CREATED {
        let location = response
            .headers()
            .get("location")
            .and_then(|v| v.to_str().ok())
            .unwrap_or(&params.e)
            .to_owned();
        return (
            StatusCode::CREATED,
            [(
                HeaderName::from_static("location"),
                HeaderValue::from_str(&location).unwrap_or_else(|_| HeaderValue::from_static("")),
            )],
        )
            .into_response();
    }
    response
}

async fn forward(
    client: Client,
    endpoint: String,
    body: impl Into<reqwest::Body>,
    _extra_headers: Vec<(&str, &str)>,
) -> Response {
    let reqwest_response = match client
        .post(&endpoint)
        .body(body)
        .header("TTL", "2592000")
        .header("Urgency", "high")
        .header("Content-Encoding", "aes128gcm") // required by WebPush-compliant distributors
        .send()
        .await
    {
        Ok(res) => res,
        Err(err) => {
            eprintln!("Request to {} failed: {}", endpoint, err);
            return (StatusCode::INTERNAL_SERVER_ERROR, Body::empty()).into_response();
        }
    };

    let mut response_builder = Response::builder().status(reqwest_response.status().as_u16());

    // Map headers from reqwest to axum
    if let Some(headers_mut) = response_builder.headers_mut() {
        for (name, value) in reqwest_response.headers() {
            let name = HeaderName::from_bytes(name.as_ref()).unwrap();
            let value = HeaderValue::from_bytes(value.as_ref()).unwrap();
            headers_mut.insert(name, value);
        }
    }

    response_builder
        .body(Body::from_stream(reqwest_response.bytes_stream()))
        .unwrap()
}

#[tokio::main]
async fn main() {
    let client = Client::new();
    let app = Router::new()
        .route("/aesgcm", post(aesgcm))
        .route("/{*path}", put(put_proxy))
        .with_state(client);

    let listener = tokio::net::TcpListener::bind("127.0.0.1:8001").await.unwrap();
    println!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}
