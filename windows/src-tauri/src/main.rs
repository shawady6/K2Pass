#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use std::sync::Mutex;
use tauri::{Emitter, State};

#[derive(Default)]
struct Lan(Mutex<Option<k2_lan::Receiver>>);

#[derive(serde::Serialize)]
struct Listening {
    ips: Vec<String>,
    port: u16,
}

fn private_ips() -> Vec<String> {
    let mut v: Vec<String> = local_ip_address::list_afinet_netifas()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(_, ip)| match ip {
            std::net::IpAddr::V4(a) if a.is_private() => Some(a.to_string()),
            _ => None,
        })
        .collect();
    v.sort();
    v.dedup();
    // The adapter that carries the default route (the real Wi-Fi/Ethernet) goes first, not a VPN/WSL/VirtualBox one.
    if let Ok(std::net::IpAddr::V4(main)) = local_ip_address::local_ip() {
        let m = main.to_string();
        if let Some(i) = v.iter().position(|x| *x == m) {
            let x = v.remove(i);
            v.insert(0, x);
        }
    }
    v
}

/// Starts (or restarts) the receiver for the given pairing secrets (base64url, 43 chars each).
#[tauri::command]
fn lan_start(app: tauri::AppHandle, lan: State<Lan>, secrets: Vec<String>) -> Result<Listening, String> {
    let keys: Vec<Vec<u8>> = secrets.iter().filter_map(|s| k2_lan::decode_secret(s)).collect();
    if keys.is_empty() {
        return Err("no valid secret".into());
    }
    let mut slot = lan.0.lock().unwrap();
    if let Some(old) = slot.take() {
        old.stop();
    }
    let make = |port: u16| {
        let handle = app.clone();
        k2_lan::Receiver::start(port, keys.clone(), move |vault| {
            let _ = handle.emit("vault-pushed", vault);
        })
    };
    let rx = make(k2_lan::PORT).or_else(|_| make(0))?;
    let port = rx.port;
    *slot = Some(rx);
    Ok(Listening { ips: private_ips(), port })
}

#[tauri::command]
fn lan_stop(lan: State<Lan>) {
    if let Some(r) = lan.0.lock().unwrap().take() {
        r.stop();
    }
}

#[derive(serde::Serialize)]
struct PhoneHit {
    ip: String,
    vault: String,
}

fn keys(secrets: &[String]) -> Vec<Vec<u8>> {
    secrets.iter().filter_map(|s| k2_lan::decode_secret(s)).collect()
}

/// PC → phone: scan this PC's own /24 networks for the phone's server (port 8765) and pull the encrypted vault.
/// Outgoing connections are not blocked by the Windows firewall, so this works even when the phone can't reach us.
#[tauri::command(async)]
fn phone_find(secrets: Vec<String>) -> Option<PhoneHit> {
    let prefixes: Vec<String> = private_ips().iter().take(3).map(|ip| ip.rsplitn(2, '.').nth(1).unwrap_or("").to_string()).collect();
    k2_lan::client::find_phone(&prefixes, k2_lan::client::PHONE_PORT, &keys(&secrets)).map(|(ip, vault)| PhoneHit { ip, vault })
}

/// Same, for an address typed by hand ("192.168.1.20" or "192.168.1.20:8765").
#[tauri::command(async)]
fn phone_pull(addr: String, secrets: Vec<String>) -> Result<String, String> {
    let (host, port) = match addr.trim().split_once(':') {
        Some((h, p)) => (h.to_string(), p.parse().unwrap_or(k2_lan::client::PHONE_PORT)),
        None => (addr.trim().to_string(), k2_lan::client::PHONE_PORT),
    };
    for k in keys(&secrets) {
        match k2_lan::client::pull(&host, port, &k) {
            Ok(v) => return Ok(v),
            Err(k2_lan::client::PullError::Denied) => continue,
            Err(k2_lan::client::PullError::NoServer) => return Err("مفيش سيرفر K2Pass على العنوان ده. افتح شاشة المزامنة على الموبايل وامسح الكود.".into()),
            Err(_) => return Err("رد غير متوقع من الموبايل.".into()),
        }
    }
    Err("الموبايل رفض الكود. امسح كود جديد.".into())
}

#[tauri::command]
fn new_secret() -> String {
    k2_lan::new_secret()
}

fn main() {
    tauri::Builder::default()
        .manage(Lan::default())
        .invoke_handler(tauri::generate_handler![lan_start, lan_stop, new_secret, phone_find, phone_pull])
        .run(tauri::generate_context!())
        .expect("error while running K2Pass");
}
