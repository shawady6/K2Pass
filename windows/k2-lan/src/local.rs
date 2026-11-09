//! Loopback server for the browser extension on the same PC (127.0.0.1 only, never reachable from the network):
//!   GET /info  -> {"app":"k2pass","ext":"<extension version shipped with this app>","hasVault":bool}
//!   GET /vault -> the encrypted vault file
//! Who may ask (checked from headers browsers set and web pages cannot forge):
//!   * Host must be 127.0.0.1:<port> or localhost:<port> (blocks DNS-rebinding pages);
//!   * Origin is an allowed extension origin, OR there is no Origin and `Sec-Fetch-Site: none` — which is exactly what an extension
//!     with host permission sends (any web page sends same-origin/same-site/cross-site and, for fetch, an Origin).
//! The file served is encrypted anyway; this just keeps random pages and tabs from even reading it.
use std::sync::{Arc, Mutex};

pub const LOCAL_PORT: u16 = 8767;

/// Chrome/Brave/Edge extension id derived from the `key` field of its manifest (base64 DER public key): first 16 bytes of the
/// SHA-256, each nibble mapped to a..p.
pub fn extension_id(key_b64: &str) -> Option<String> {
    use base64::Engine;
    use sha2::{Digest, Sha256};
    let der = base64::engine::general_purpose::STANDARD.decode(key_b64.trim()).ok()?;
    let h = Sha256::digest(&der);
    Some(h[..16].iter().flat_map(|b| [b >> 4, b & 15]).map(|n| (b'a' + n) as char).collect())
}

pub struct LocalServer {
    server: Arc<tiny_http::Server>,
    pub port: u16,
}

impl LocalServer {
    pub fn start(port: u16, allowed_origins: Vec<String>, vault: Arc<Mutex<Option<String>>>, ext_version: String) -> Result<LocalServer, String> {
        let server = tiny_http::Server::http(("127.0.0.1", port)).map_err(|e| e.to_string())?;
        let port = server.server_addr().to_ip().map(|a| a.port()).unwrap_or(port);
        let server = Arc::new(server);
        let s2 = server.clone();
        std::thread::spawn(move || {
            for req in s2.incoming_requests() {
                let headers: Vec<(String, String)> = req.headers().iter().map(|h| (h.field.as_str().as_str().to_ascii_lowercase(), h.value.as_str().to_string())).collect();
                let hdr = |n: &str| headers.iter().find(|(k, _)| k == &n.to_ascii_lowercase()).map(|(_, v)| v.clone());
                let origin = hdr("Origin").unwrap_or_default();
                let host_ok = matches!(hdr("Host").as_deref(), Some(h) if h == format!("127.0.0.1:{port}") || h == format!("localhost:{port}"));
                let allowed = host_ok
                    && (allowed_origins.iter().any(|o| *o == origin) || (origin.is_empty() && hdr("Sec-Fetch-Site").as_deref() == Some("none")));
                let path = req.url().split('?').next().unwrap_or("").to_string();
                let (code, body, ctype) = if !allowed {
                    (403, "forbidden".to_string(), "text/plain")
                } else {
                    match (req.method(), path.as_str()) {
                        (tiny_http::Method::Get, "/info") => {
                            let has = vault.lock().unwrap().is_some();
                            (200, format!("{{\"app\":\"k2pass\",\"ext\":\"{ext_version}\",\"hasVault\":{has}}}"), "application/json")
                        }
                        (tiny_http::Method::Get, "/vault") => match vault.lock().unwrap().clone() {
                            Some(v) => (200, v, "application/json"),
                            None => (404, "no vault".to_string(), "text/plain"),
                        },
                        _ => (404, "not found".to_string(), "text/plain"),
                    }
                };
                let mut r = tiny_http::Response::from_string(body).with_status_code(code);
                r.add_header(tiny_http::Header::from_bytes("Content-Type", ctype).unwrap());
                r.add_header(tiny_http::Header::from_bytes("Cache-Control", "no-store").unwrap());
                if allowed && !origin.is_empty() {
                    r.add_header(tiny_http::Header::from_bytes("Access-Control-Allow-Origin", origin.as_str()).unwrap());
                    r.add_header(tiny_http::Header::from_bytes("Vary", "Origin").unwrap());
                }
                let _ = req.respond(r);
            }
        });
        Ok(LocalServer { server, port })
    }

    pub fn stop(&self) {
        self.server.unblock();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpStream;

    fn get_h(port: u16, path: &str, host: &str, extra: &[(&str, &str)]) -> (u16, String) {
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        let o: String = extra.iter().map(|(k, v)| format!("{k}: {v}\r\n")).collect();
        s.write_all(format!("GET {path} HTTP/1.1\r\nHost: {host}\r\n{o}Connection: close\r\n\r\n").as_bytes()).unwrap();
        let mut out = String::new();
        s.read_to_string(&mut out).unwrap();
        (out.split_whitespace().nth(1).unwrap().parse().unwrap(), out.split("\r\n\r\n").nth(1).unwrap_or("").to_string())
    }
    fn get(port: u16, path: &str, origin: Option<&str>) -> (u16, String) {
        match origin {
            Some(o) => get_h(port, path, &format!("127.0.0.1:{port}"), &[("Origin", o)]),
            None => get_h(port, path, &format!("127.0.0.1:{port}"), &[]),
        }
    }

    #[test]
    fn extension_id_matches_chrome() {
        // key and id computed independently (Python) from the same manifest key
        let m: serde_json::Value = serde_json::from_str(include_str!("../../../extension/manifest.json")).unwrap();
        assert_eq!(extension_id(m["key"].as_str().unwrap()).unwrap(), "oaajbmighjeafhmeigedfoegbfojjenb");
        assert!(extension_id("%%%").is_none());
    }

    #[test]
    fn serves_only_the_allowed_extension_origin() {
        let slot = Arc::new(Mutex::new(None));
        let ok = "chrome-extension://abc";
        let srv = LocalServer::start(0, vec![ok.into()], slot.clone(), "0.7.0".into()).unwrap();
        assert_eq!(get(srv.port, "/info", None).0, 403, "no origin and no Sec-Fetch-Site");
        assert_eq!(get(srv.port, "/info", Some("https://evil.example")).0, 403);
        assert_eq!(get(srv.port, "/vault", Some("chrome-extension://other")).0, 403);
        assert_eq!(get(srv.port, "/info", Some(ok)), (200, r#"{"app":"k2pass","ext":"0.7.0","hasVault":false}"#.into()));
        assert_eq!(get(srv.port, "/vault", Some(ok)).0, 404);
        *slot.lock().unwrap() = Some(r#"{"v":1,"wk":"a","data":"b"}"#.into());
        assert_eq!(get(srv.port, "/vault", Some(ok)), (200, r#"{"v":1,"wk":"a","data":"b"}"#.into()));
        assert!(get(srv.port, "/info", Some(ok)).1.contains("\"hasVault\":true"));
        // what a real extension sends: no Origin, Sec-Fetch-Site: none
        let host = format!("127.0.0.1:{}", srv.port);
        assert_eq!(get_h(srv.port, "/info", &host, &[("Sec-Fetch-Site", "none")]).0, 200);
        // what pages send
        assert_eq!(get_h(srv.port, "/info", &host, &[("Sec-Fetch-Site", "cross-site")]).0, 403);
        assert_eq!(get_h(srv.port, "/info", &host, &[("Sec-Fetch-Site", "same-origin")]).0, 403);
        // DNS rebinding: attacker.example resolving to 127.0.0.1
        assert_eq!(get_h(srv.port, "/info", &format!("attacker.example:{}", srv.port), &[("Sec-Fetch-Site", "none")]).0, 403);
        srv.stop();
    }
}
