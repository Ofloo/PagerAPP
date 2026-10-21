//! Small C ABI around rPGP (MIT/Apache-2.0) for PagerAPP iOS.
//!
//! Only the operations the chat needs are exposed: generate a v4 RSA key pair, encrypt bytes to a
//! public key, and decrypt an armored message with the matching secret key.

use std::ffi::{CStr, CString};
use std::io::BufReader;
use std::os::raw::c_char;
use std::ptr;
use std::slice;
use std::sync::Mutex;

use pgp::composed::{
    Deserializable, EncryptionCaps, KeyType, Message, MessageBuilder, SecretKeyParamsBuilder,
    SignedPublicKey, SignedSecretKey, SubkeyParamsBuilder,
};
use pgp::crypto::sym::SymmetricKeyAlgorithm;
use pgp::types::KeyDetails;
use rand::thread_rng;

const SECRET_SEPARATOR: &str = "\n-----PAGERAPP-SECRET-----\n";

static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);

#[repr(C)]
pub struct PgpBuffer {
    pub data: *mut u8,
    pub len: usize,
}

fn remember_error(message: impl Into<String>) -> *mut c_char {
    let text = message.into();
    if let Ok(mut slot) = LAST_ERROR.lock() {
        *slot = Some(text);
    }
    ptr::null_mut()
}

fn clear_error() {
    if let Ok(mut slot) = LAST_ERROR.lock() {
        *slot = None;
    }
}

unsafe fn c_string(value: *const c_char) -> Result<String, String> {
    if value.is_null() {
        return Err("null string".into());
    }
    CStr::from_ptr(value)
        .to_str()
        .map(|s| s.to_owned())
        .map_err(|e| e.to_string())
}

fn into_c_string(value: String) -> *mut c_char {
    match CString::new(value) {
        Ok(s) => s.into_raw(),
        Err(e) => remember_error(format!("string contains NUL: {e}")),
    }
}

fn keygen(user_id: &str) -> Result<(String, String), String> {
    let mut key_params = SecretKeyParamsBuilder::default();
    key_params
        .key_type(KeyType::Rsa(2048))
        .can_certify(true)
        .can_sign(true)
        .can_encrypt(EncryptionCaps::None)
        .primary_user_id(user_id.into());

    let encryption_subkey = SubkeyParamsBuilder::default()
        .key_type(KeyType::Rsa(2048))
        .can_sign(false)
        .can_encrypt(EncryptionCaps::All)
        .can_authenticate(false)
        .build()
        .map_err(|e| format!("subkey parameters: {e:?}"))?;

    key_params.subkeys(vec![encryption_subkey]);
    let params = key_params
        .build()
        .map_err(|e| format!("key parameters: {e:?}"))?;
    let secret = params
        .generate(thread_rng())
        .map_err(|e| format!("key generation: {e:?}"))?;
    let public = SignedPublicKey::from(secret.clone());

    let public_armor = public
        .to_armored_string(Default::default())
        .map_err(|e| format!("public armor: {e:?}"))?;
    let secret_armor = secret
        .to_armored_string(Default::default())
        .map_err(|e| format!("secret armor: {e:?}"))?;
    Ok((public_armor, secret_armor))
}

fn encrypt(data: &[u8], public_armor: &str) -> Result<String, String> {
    let (cert, _headers) = SignedPublicKey::from_string(public_armor)
        .map_err(|e| format!("public key: {e:?}"))?;
    let encryption_subkey = cert
        .public_subkeys
        .iter()
        .find(|key| key.algorithm().can_encrypt())
        .ok_or_else(|| "public key has no encryption subkey".to_string())?;

    let mut rng = thread_rng();
    let mut builder = MessageBuilder::from_bytes("", data.to_vec())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder
        .encrypt_to_key(&mut rng, encryption_subkey)
        .map_err(|e| format!("encrypt: {e:?}"))?;
    builder
        .to_armored_string(&mut rng, Default::default())
        .map_err(|e| format!("armor: {e:?}"))
}

fn decrypt(armor: &str, secret_armor: &str) -> Result<Vec<u8>, String> {
    let (secret, _headers) = SignedSecretKey::from_string(secret_armor)
        .map_err(|e| format!("secret key: {e:?}"))?;
    let (parsed, _armor_headers) = Message::from_armor(BufReader::new(armor.as_bytes()))
        .map_err(|e| format!("message: {e:?}"))?;
    let mut decrypted = parsed
        .decrypt(&"".into(), &secret)
        .map_err(|e| format!("decrypt: {e:?}"))?;
    if decrypted.is_compressed() {
        decrypted = decrypted
            .decompress()
            .map_err(|e| format!("decompress: {e:?}"))?;
    }
    decrypted
        .as_data_vec()
        .map_err(|e| format!("payload: {e:?}"))
}

/// Generate a key pair. Returns `public armor + separator + secret armor`.
#[no_mangle]
pub unsafe extern "C" fn pager_pgp_generate(user_id: *const c_char) -> *mut c_char {
    clear_error();
    let uid = match c_string(user_id) {
        Ok(v) => v,
        Err(e) => return remember_error(e),
    };
    match keygen(&uid) {
        Ok((public, secret)) => into_c_string(format!("{public}{SECRET_SEPARATOR}{secret}")),
        Err(e) => remember_error(e),
    }
}

/// Encrypt bytes to an armored public key and return an armored OpenPGP message.
#[no_mangle]
pub unsafe extern "C" fn pager_pgp_encrypt(
    data: *const u8,
    len: usize,
    public_armor: *const c_char,
) -> *mut c_char {
    clear_error();
    if data.is_null() && len > 0 {
        return remember_error("null plaintext");
    }
    let public = match c_string(public_armor) {
        Ok(v) => v,
        Err(e) => return remember_error(e),
    };
    let bytes = if len == 0 { &[] } else { slice::from_raw_parts(data, len) };
    match encrypt(bytes, &public) {
        Ok(armored) => into_c_string(armored),
        Err(e) => remember_error(e),
    }
}

/// Decrypt an armored message. The returned buffer must be freed with `pager_pgp_free_buffer`.
#[no_mangle]
pub unsafe extern "C" fn pager_pgp_decrypt(
    armor: *const c_char,
    secret_armor: *const c_char,
) -> PgpBuffer {
    clear_error();
    let message = match c_string(armor) {
        Ok(v) => v,
        Err(e) => {
            remember_error(e);
            return PgpBuffer { data: ptr::null_mut(), len: 0 };
        }
    };
    let secret = match c_string(secret_armor) {
        Ok(v) => v,
        Err(e) => {
            remember_error(e);
            return PgpBuffer { data: ptr::null_mut(), len: 0 };
        }
    };
    match decrypt(&message, &secret) {
        Ok(bytes) => {
            let boxed = bytes.into_boxed_slice();
            let len = boxed.len();
            let data = Box::into_raw(boxed) as *mut u8;
            PgpBuffer { data, len }
        }
        Err(e) => {
            remember_error(e);
            PgpBuffer { data: ptr::null_mut(), len: 0 }
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn pager_pgp_free_string(value: *mut c_char) {
    if !value.is_null() {
        drop(CString::from_raw(value));
    }
}

#[no_mangle]
pub unsafe extern "C" fn pager_pgp_free_buffer(buffer: PgpBuffer) {
    if !buffer.data.is_null() {
        let slice = ptr::slice_from_raw_parts_mut(buffer.data, buffer.len);
        drop(Box::from_raw(slice));
    }
}

/// Returns the last error, or a null pointer when there was none.
#[no_mangle]
pub extern "C" fn pager_pgp_last_error() -> *mut c_char {
    let text = LAST_ERROR
        .lock()
        .ok()
        .and_then(|slot| slot.clone())
        .unwrap_or_default();
    if text.is_empty() {
        ptr::null_mut()
    } else {
        into_c_string(text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_encrypt_decrypt_roundtrip() {
        let (public, secret) = keygen("PagerAPP <pagerapp>").expect("keygen");
        let plaintext = b"hello cross-platform PGP";
        let armored = encrypt(plaintext, &public).expect("encrypt");
        let decrypted = decrypt(&armored, &secret).expect("decrypt");
        assert_eq!(plaintext, decrypted.as_slice());
    }
}
