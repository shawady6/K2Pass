//! K2Pass LAN receiver (Windows side). The phone PUSHES its encrypted vault file here.
//!   GET  /n                     -> 32-hex single-use nonce (valid 30 s)
//!   POST /push?a=<nonce>.<hex>  -> body = vault file; hex = HMAC-SHA256(secret, "p|" + nonce)
//! Auth is checked against every paired secret in constant time; the secret itself never travels.
pub mod client;
use base64::Engine;
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::Sha256;
use std::collections::HashMap;
use std::io::Read;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;

pub const PORT: u16 = 8766;
const MAX_BODY: u64 = 20 * 1024 * 1024;
const NONCE_TTL: Duration = Duration::from_secs(30);

pub fn mac_hex(secret: &[u8], msg: &str) -> String {
    let mut m = Hmac::<Sha256>::new_from_slice(secret).expect("hmac key");
    m.update(msg.as_bytes());
    hex::encode(m.finalize().into_bytes())
}

pub fn decode_secret(b64url: &str) -> Option<Vec<u8>> {
    let v = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(b64url.trim()).ok()?;
    (v.len() == 32).then_some(v)
}

pub fn new_secret() -> String {
    let mut b = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut b);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

fn authorised(secrets: &[Vec<u8>], nonce: &str, given: &str) -> bool {
    let msg = format!("p|{nonce}");
    let mut ok = false;
    for s in secrets {
        ok |= bool::from(mac_hex(s, &msg).as_bytes().ct_eq(given.as_bytes()));
    }
    ok
}

pub struct Receiver {
    server: Arc<tiny_http::Server>,
    pub port: u16,
}

impl Receiver {
    /// Starts listening on 0.0.0.0:`port` (0 = any free port). `on_vault(text)` runs for every authorised push.
    pub fn start(port: u16, secrets: Vec<Vec<u8>>, on_vault: impl Fn(String) + Send + Sync + 'static) -> Result<Receiver, String> {
        let server = tiny_http::Server::http(("0.0.0.0", port)).map_err(|e| e.to_string())?;
        let port = server.server_addr().to_ip().map(|a| a.port()).unwrap_or(port);
        let server = Arc::new(server);
        let s2 = server.clone();
        let nonces: Arc<Mutex<HashMap<String, Instant>>> = Arc::new(Mutex::new(HashMap::new()));
        std::thread::spawn(move || {
            for mut req in s2.incoming_requests() {
                let url = req.url().to_string();
                let (path, query) = url.split_once('?').unwrap_or((url.as_str(), ""));
                let respond = |req: tiny_http::Request, code: u16, body: &str| {
                    let _ = req.respond(tiny_http::Response::from_string(body).with_status_code(code));
                };
                match (req.method(), path) {
                    (tiny_http::Method::Get, "/n") => {
                        let mut b = [0u8; 16];
                        rand::thread_rng().fill_bytes(&mut b);
                        let n = hex::encode(b);
                        let mut m = nonces.lock().unwrap();
                        m.retain(|_, t| t.elapsed() < NONCE_TTL);
                        m.insert(n.clone(), Instant::now());
                        drop(m);
                        respond(req, 200, &n);
                    }
                    (tiny_http::Method::Post, "/push") => {
                        let a = query.split('&').find_map(|kv| kv.strip_prefix("a=")).unwrap_or("");
                        let (nonce, given) = a.split_once('.').unwrap_or(("", ""));
                        let fresh = nonces.lock().unwrap().remove(nonce).map(|t| t.elapsed() < NONCE_TTL).unwrap_or(false);
                        if !fresh || !authorised(&secrets, nonce, given) {
                            std::thread::sleep(Duration::from_millis(400));
                            respond(req, 403, "denied");
                            continue;
                        }
                        let mut body = String::new();
                        if req.as_reader().take(MAX_BODY + 1).read_to_string(&mut body).is_err() || body.len() as u64 > MAX_BODY {
                            respond(req, 413, "bad body");
                            continue;
                        }
                        if !(body.contains("\"wk\"") && body.contains("\"data\"")) {
                            respond(req, 400, "not a vault");
                            continue;
                        }
                        on_vault(body);
                        respond(req, 200, "ok");
                    }
                    _ => respond(req, 404, "not found"),
                }
            }
        });
        Ok(Receiver { server, port })
    }

    pub fn stop(&self) {
        self.server.unblock();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read as _, Write};
    use std::net::TcpStream;

    fn http(port: u16, req: &str) -> (u16, String) {
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.write_all(req.as_bytes()).unwrap();
        let mut out = String::new();
        s.read_to_string(&mut out).unwrap();
        let code = out.split_whitespace().nth(1).unwrap().parse().unwrap();
        (code, out.split("\r\n\r\n").nth(1).unwrap_or("").to_string())
    }
    fn post(port: u16, a: &str, body: &str) -> (u16, String) {
        http(port, &format!("POST /push?a={a} HTTP/1.1\r\nHost: x\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()))
    }

    #[test]
    fn hmac_matches_the_shared_vector() {
        let key: Vec<u8> = (0..32).collect();
        assert_eq!(mac_hex(&key, "v|abc123"), "c014485e217b5e52e47739d96c38968362350723cffe5695d34aa737f274d2af");
    }

    #[test]
    fn secrets_roundtrip() {
        let s = new_secret();
        assert_eq!(s.len(), 43);
        assert_eq!(decode_secret(&s).unwrap().len(), 32);
        assert!(decode_secret("short").is_none());
    }

    #[test]
    fn authorised_push_is_delivered_and_wrong_or_replayed_is_not() {
        let secret: Vec<u8> = (0..32).collect();
        let got = Arc::new(Mutex::new(Vec::<String>::new()));
        let g2 = got.clone();
        let r = Receiver::start(0, vec![secret.clone()], move |v| g2.lock().unwrap().push(v)).unwrap();
        let vault = r#"{"v":1,"wk":"x","data":"y"}"#;

        let (c, nonce) = http(r.port, "GET /n HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        assert_eq!((c, nonce.len()), (200, 32));
        let good = format!("{nonce}.{}", mac_hex(&secret, &format!("p|{nonce}")));
        assert_eq!(post(r.port, &good, vault).0, 200);
        assert_eq!(got.lock().unwrap().as_slice(), &[vault.to_string()]);
        assert_eq!(post(r.port, &good, vault).0, 403, "nonce is single use");

        let (_, n2) = http(r.port, "GET /n HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        let bad = format!("{n2}.{}", mac_hex(&[9u8; 32], &format!("p|{n2}")));
        assert_eq!(post(r.port, &bad, vault).0, 403);

        let (_, n3) = http(r.port, "GET /n HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        let ok3 = format!("{n3}.{}", mac_hex(&secret, &format!("p|{n3}")));
        assert_eq!(post(r.port, &ok3, "garbage").0, 400, "must look like a vault file");
        assert_eq!(got.lock().unwrap().len(), 1);
        r.stop();
    }
}
