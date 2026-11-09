//! PC → phone direction: the phone (Android app) serves its encrypted vault on port 8765 (see LanSyncServer.kt):
//!   GET /n -> nonce ; GET /v?a=<nonce>.<hex HMAC-SHA256(secret,"v|"+nonce)> -> vault file.
//! Outgoing connections are not blocked by the Windows firewall, which is why this direction is the reliable one.
use crate::mac_hex;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::mpsc;
use std::time::Duration;

pub const PHONE_PORT: u16 = 8765;

#[derive(Debug, PartialEq)]
pub enum PullError {
    NoServer,
    Denied,
    Bad,
}

fn get(addr: &SocketAddr, path: &str, timeout: Duration) -> Option<(u16, String)> {
    let mut s = TcpStream::connect_timeout(addr, timeout).ok()?;
    s.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
    s.write_all(format!("GET {path} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n", addr.ip()).as_bytes()).ok()?;
    let mut raw = Vec::new();
    s.take(30 * 1024 * 1024).read_to_end(&mut raw).ok()?;
    let text = String::from_utf8_lossy(&raw).to_string();
    let code = text.split_whitespace().nth(1)?.parse().ok()?;
    Some((code, text.split_once("\r\n\r\n").map(|x| x.1).unwrap_or("").to_string()))
}

pub fn pull(host: &str, port: u16, secret: &[u8]) -> Result<String, PullError> {
    let addr = (host, port).to_socket_addrs().ok().and_then(|mut a| a.next()).ok_or(PullError::NoServer)?;
    let (c, nonce) = get(&addr, "/n", Duration::from_millis(1500)).ok_or(PullError::NoServer)?;
    let nonce = nonce.trim().to_string();
    if c != 200 || nonce.len() != 32 {
        return Err(PullError::NoServer);
    }
    let (c, body) = get(&addr, &format!("/v?a={nonce}.{}", mac_hex(secret, &format!("v|{nonce}"))), Duration::from_millis(1500)).ok_or(PullError::NoServer)?;
    match c {
        200 if body.contains("\"wk\"") && body.contains("\"data\"") => Ok(body),
        403 => Err(PullError::Denied),
        _ => Err(PullError::Bad),
    }
}

/// Scans every host of the given /24 prefixes ("192.168.1") for an open `port` (fast TCP connect), then pulls from each responder
/// with each secret. Returns (ip, vault) of the first that accepts.
pub fn find_phone(prefixes: &[String], port: u16, secrets: &[Vec<u8>]) -> Option<(String, String)> {
    let (tx, rx) = mpsc::channel();
    let mut n = 0;
    for p in prefixes {
        for h in 1..=254u8 {
            let ip = format!("{p}.{h}");
            let tx = tx.clone();
            n += 1;
            std::thread::spawn(move || {
                let ok = format!("{ip}:{port}").parse::<SocketAddr>().ok().map(|a| TcpStream::connect_timeout(&a, Duration::from_millis(400)).is_ok()).unwrap_or(false);
                let _ = tx.send(if ok { Some(ip) } else { None });
            });
        }
    }
    drop(tx);
    let mut hits = Vec::new();
    for _ in 0..n {
        if let Ok(Some(ip)) = rx.recv() {
            hits.push(ip);
        }
    }
    for ip in hits {
        for s in secrets {
            if let Ok(v) = pull(&ip, port, s) {
                return Some((ip, v));
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    /// Minimal stand-in for the Android server (same protocol as LanSyncServer.kt).
    fn fake_phone(secret: Vec<u8>) -> u16 {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        std::thread::spawn(move || {
            for s in l.incoming() {
                let mut s = s.unwrap();
                let mut buf = [0u8; 2048];
                let n = s.read(&mut buf).unwrap_or(0);
                let req = String::from_utf8_lossy(&buf[..n]).to_string();
                let line = req.lines().next().unwrap_or("").to_string();
                let nonce = "00112233445566778899aabbccddeeff";
                let (code, body) = if line.starts_with("GET /n") {
                    (200, nonce.to_string())
                } else if line.contains(&format!("a={nonce}.{}", mac_hex(&secret, &format!("v|{nonce}")))) {
                    (200, r#"{"v":1,"wk":"a","data":"b"}"#.to_string())
                } else {
                    (403, "denied".to_string())
                };
                let _ = s.write_all(format!("HTTP/1.1 {code} X\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes());
            }
        });
        port
    }

    #[test]
    fn pulls_with_the_right_secret_only() {
        let secret: Vec<u8> = (0..32).collect();
        let port = fake_phone(secret.clone());
        assert_eq!(pull("127.0.0.1", port, &secret).unwrap(), r#"{"v":1,"wk":"a","data":"b"}"#);
        assert_eq!(pull("127.0.0.1", port, &[9u8; 32]), Err(PullError::Denied));
        assert_eq!(pull("127.0.0.1", 1, &secret), Err(PullError::NoServer));
    }

    #[test]
    fn scan_finds_the_phone_among_254_hosts() {
        let secret: Vec<u8> = (0..32).collect();
        let port = fake_phone(secret.clone());
        // 127.0.0.1 is host .1 of 127.0.0.x
        let hit = find_phone(&["127.0.0".to_string()], port, &[vec![5u8; 32], secret]);
        assert_eq!(hit.map(|h| h.0), Some("127.0.0.1".to_string()));
        assert!(find_phone(&["127.0.0".to_string()], port, &[vec![5u8; 32]]).is_none());
    }
}
