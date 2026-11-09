// Dev helper for the e2e test: serve a vault file to the extension. Usage: serve <vault-file> <origin> <ext-version>
use std::sync::{Arc, Mutex};
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let v = std::fs::read_to_string(&a[1]).ok();
    let slot = Arc::new(Mutex::new(v));
    let s = k2_lan::local::LocalServer::start(k2_lan::local::LOCAL_PORT, vec![a[2].clone()], slot, a[3].clone()).expect("bind");
    println!("listening on {}", s.port);
    loop { std::thread::sleep(std::time::Duration::from_secs(3600)); }
}
