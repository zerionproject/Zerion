// Native-boundary hardening for the exact FFI entry points the Java wallet
// calls. Every entry point must fail closed on hostile input: return an error,
// never panic across the boundary, never abort, never corrupt state under
// concurrency or repeated create/free.

use std::panic;
use std::sync::Arc;
use std::thread;

use payjoin_ffi::ohttp::OhttpKeys;
use payjoin_ffi::uri::{Uri, Url};

fn catch<T>(f: impl FnOnce() -> T + panic::UnwindSafe) -> Result<T, ()> {
    let prev = panic::take_hook();
    panic::set_hook(Box::new(|_| {}));
    let r = panic::catch_unwind(f).map_err(|_| ());
    panic::set_hook(prev);
    r
}

#[test]
fn malformed_uri_returns_error_no_panic() {
    let cases = [
        "",
        "not a uri",
        "bitcoin:",
        "http://example.com",
        "bitcoin:not-an-address?pj=x",
        "bitcoin:BC1QINVALID?pj=notaurl",
        "\u{0}\u{0}\u{0}",
        "bitcoin:BC1Q?amount=abc&pj=",
    ];
    for c in cases {
        let r = catch(|| Uri::parse(c.to_string())).expect("no panic across boundary");
        assert!(r.is_err(), "expected error for {c:?}");
    }
}

#[test]
fn malformed_url_returns_error_no_panic() {
    for c in ["", "://", "ht!tp://x", " ", "\u{feff}bad"] {
        let r = catch(|| Url::parse(c.to_string())).expect("no panic across boundary");
        assert!(r.is_err(), "expected error for {c:?}");
    }
}

#[test]
fn malformed_ohttp_keys_returns_error_no_panic() {
    let cases: Vec<Vec<u8>> = vec![
        vec![],
        vec![0u8],
        vec![0xff; 3],
        vec![0x00; 64],
        (0..255u8).collect(),
    ];
    for bytes in cases {
        let r = catch(|| OhttpKeys::decode(bytes.clone())).expect("no panic across boundary");
        assert!(r.is_err(), "expected error decoding {} bytes", bytes.len());
    }
}

#[test]
fn oversized_input_fails_closed() {
    let big = "bitcoin:".to_string() + &"A".repeat(4 * 1024 * 1024);
    let r = catch(|| Uri::parse(big)).expect("no panic on oversized uri");
    assert!(r.is_err());

    let big_bytes = vec![0x41u8; 8 * 1024 * 1024];
    let r = catch(|| OhttpKeys::decode(big_bytes)).expect("no panic on oversized ohttp");
    assert!(r.is_err());
}

#[test]
fn unusual_unicode_and_control_chars_fail_closed() {
    for c in ["bitcoin:\u{202e}\u{200b}", "bitcoin:\t\r\n", "bitcoin:BC1Q\u{1f4a9}"] {
        let r = catch(|| Uri::parse(c.to_string())).expect("no panic on unicode");
        assert!(r.is_err());
    }
}

#[test]
fn repeated_create_free_is_stable() {
    for _ in 0..100_000 {
        let _ = Uri::parse("bitcoin:not-valid".to_string());
        let _ = OhttpKeys::decode(vec![0xde, 0xad, 0xbe, 0xef]);
    }
}

#[test]
fn concurrent_calls_are_consistent() {
    let flag = Arc::new(());
    let mut handles = Vec::new();
    for t in 0..16 {
        let _f = Arc::clone(&flag);
        handles.push(thread::spawn(move || {
            for i in 0..2_000u32 {
                let s = format!("bitcoin:garbage-{t}-{i}");
                assert!(Uri::parse(s).is_err());
                assert!(OhttpKeys::decode(vec![(i & 0xff) as u8; 5]).is_err());
            }
        }));
    }
    for h in handles {
        h.join().expect("worker thread did not panic");
    }
}
