#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::{Emitter, State};

#[derive(Default)]
struct Lan(Mutex<Option<k2_lan::Receiver>>);

/// The extension shipped inside this exe (see pack-ext.mjs) and where it gets unpacked.
#[derive(serde::Deserialize)]
struct Bundle {
    version: String,
    key: String,
    files: std::collections::HashMap<String, String>,
}
const BUNDLE: &str = include_str!("../ext.bundle.json");

struct Ext {
    dir: PathBuf,
    version: String,
    id: String,
    vault: Arc<Mutex<Option<String>>>,
    server: Mutex<Option<k2_lan::local::LocalServer>>,
}

fn ext_dir() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
    base.join("K2Pass").join("extension")
}

/// Unpacks the bundled extension into [dir] when it isn't there yet or is another version (the folder is ours, so it is replaced whole).
fn install_extension(b: &Bundle, dir: &PathBuf) -> Result<(), String> {
    use base64::Engine;
    let marker = dir.join(".k2ver");
    if std::fs::read_to_string(&marker).map(|v| v.trim() == b.version).unwrap_or(false) {
        return Ok(());
    }
    let _ = std::fs::remove_dir_all(dir);
    for (rel, b64) in &b.files {
        if rel.contains("..") {
            continue;
        }
        let path = dir.join(rel);
        std::fs::create_dir_all(path.parent().unwrap()).map_err(|e| e.to_string())?;
        std::fs::write(&path, base64::engine::general_purpose::STANDARD.decode(b64).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    }
    std::fs::write(marker, &b.version).map_err(|e| e.to_string())
}

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

#[derive(serde::Serialize)]
struct ExtInfo {
    path: String,
    version: String,
    id: String,
    serving: bool,
}

/// The webview hands over the (encrypted) vault file whenever it changes; the extension reads it from the loopback server.
#[tauri::command]
fn vault_publish(ext: State<Ext>, text: Option<String>) {
    *ext.vault.lock().unwrap() = text;
}

#[tauri::command]
fn ext_info(ext: State<Ext>) -> ExtInfo {
    ExtInfo { path: ext.dir.display().to_string(), version: ext.version.clone(), id: ext.id.clone(), serving: ext.server.lock().unwrap().is_some() }
}

#[tauri::command]
fn ext_open_folder(ext: State<Ext>) {
    let _ = std::process::Command::new("explorer").arg(&ext.dir).spawn();
}

#[tauri::command]
fn new_secret() -> String {
    k2_lan::new_secret()
}

fn main() {
    let bundle: Bundle = serde_json::from_str(BUNDLE).expect("ext.bundle.json");
    let dir = ext_dir();
    let _ = install_extension(&bundle, &dir);
    let id = k2_lan::local::extension_id(&bundle.key).unwrap_or_default();
    let vault = Arc::new(Mutex::new(None));
    let server = k2_lan::local::LocalServer::start(k2_lan::local::LOCAL_PORT, vec![format!("chrome-extension://{id}")], vault.clone(), bundle.version.clone()).ok();
    tauri::Builder::default()
        .manage(Lan::default())
        .manage(Ext { dir, version: bundle.version, id, vault, server: Mutex::new(server) })
        .invoke_handler(tauri::generate_handler![lan_start, lan_stop, new_secret, phone_find, phone_pull, vault_publish, ext_info, ext_open_folder])
        .run(tauri::generate_context!())
        .expect("error while running K2Pass");
}
