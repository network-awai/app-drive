use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use p256::ecdsa::Signature;
use security_framework::{
    item::{ItemClass, ItemSearchOptions, KeyClass, Limit, Location, Reference, SearchResult},
    key::{Algorithm, GenerateKeyOptions, KeyType, SecKey},
    os::macos::keychain::SecKeychain,
};
use serde_json::{json, Value};
use std::time::{SystemTime, UNIX_EPOCH};

type Result<T> = std::result::Result<T, String>;
const PROOF: &str = "awai.wnfs.account-proof.v1";
const PURPOSE: &str = "awai.drive.device-authorize.v1";
const MAX_LIFETIME_MS: u64 = 86_400_000;
const ITEM_NOT_FOUND: i32 = -25300;

pub(crate) fn device_key(scope: &str, create: bool) -> Result<Option<SecKey>> {
    let label = format!("{scope}.device-signing.v1");
    let chain = SecKeychain::default().map_err(|_| "keychain-unavailable")?;
    let result = ItemSearchOptions::new()
        .keychains(std::slice::from_ref(&chain))
        .class(ItemClass::key())
        .key_class(KeyClass::private())
        .label(&label)
        .limit(Limit::All)
        .load_refs(true)
        .search();
    match result {
        Ok(mut keys) if keys.len() == 1 => match keys.pop() {
            Some(SearchResult::Ref(Reference::Key(key))) => Ok(Some(key)),
            _ => Err("keychain-unavailable".into()),
        },
        Err(error) if error.code() == ITEM_NOT_FOUND => {
            if !create {
                return Ok(None);
            }
            let mut opts = GenerateKeyOptions::default();
            opts.set_key_type(KeyType::ec_sec_prime_random())
                .set_size_in_bits(256)
                .set_label(label)
                .set_synchronizable(false)
                .set_location(Location::FileKeychain(chain));
            SecKey::new(&opts)
                .map(Some)
                .map_err(|_| "keychain-unavailable".into())
        }
        _ => Err("keychain-unavailable".into()),
    }
}

fn public_bytes(key: &SecKey) -> Result<Vec<u8>> {
    let bytes = key
        .public_key()
        .and_then(|key| key.external_representation())
        .ok_or("account-device-key-missing")?
        .to_vec();
    if bytes.len() != 65 || bytes[0] != 4 {
        return Err("invalid-account-link".into());
    }
    Ok(bytes)
}

pub(crate) fn public_value(key: &SecKey) -> Result<Value> {
    Ok(json!({"publicKey":URL_SAFE_NO_PAD.encode(public_bytes(key)?)}))
}

fn did(raw: &[u8]) -> String {
    let mut bytes = vec![0x80, 0x24, 2 + (raw[64] & 1)];
    bytes.extend_from_slice(&raw[1..33]);
    format!("did:key:z{}", bs58::encode(bytes).into_string())
}

pub(crate) fn validate_link(key: &SecKey, link: &Value) -> Result<()> {
    let account = link["accountDid"]
        .as_str()
        .and_then(|account| account.strip_prefix("did:key:z"))
        .filter(|value| {
            (20..=110).contains(&value.len()) && bs58::decode(value).into_vec().is_ok()
        });
    if account.is_none()
        || link["request"]["purpose"].as_str() != Some(PURPOSE)
        || link["request"]["device"].as_str() != Some(did(&public_bytes(key)?).as_str())
        || link["response"]["method"].as_str() != Some("passkey")
        || serde_json::to_vec(link)
            .map_err(|_| "invalid-account-link")?
            .len()
            > 8192
    {
        return Err("invalid-account-link".into());
    }
    Ok(())
}

pub(crate) fn binding(key: &SecKey, link: &Value, request: &Value) -> Result<Value> {
    validate_link(key, link)?;
    let value = request["value"]
        .as_str()
        .and_then(|value| value.strip_prefix("kv1:"))
        .filter(|value| {
            value.len() == 43
                && value
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
        .ok_or("invalid-account-action")?;
    let raw = public_bytes(key)?;
    let device = did(&raw);
    let issued = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "invalid-account-proof")?
        .as_millis() as u64;
    let expires = request["expires"]
        .as_u64()
        .filter(|expiry| *expiry > issued)
        .map(|expiry| expiry.min(issued + MAX_LIFETIME_MS))
        .ok_or("session-expired")?;
    let account = link["accountDid"].as_str().ok_or("invalid-account-link")?;
    let value = format!("kv1:{value}");
    let app = "network.awai.drive";
    let action = "bind-wnfs-key";
    // Only this structured claim can reach the device signer.
    let payload = serde_json::to_vec(&json!([
        PROOF, app, account, device, issued, expires, action, value, null
    ]))
    .map_err(|_| "invalid-account-proof")?;
    let der = key
        .create_signature(Algorithm::ECDSASignatureMessageX962SHA256, &payload)
        .map_err(|_| "keychain-unavailable")?;
    let signature = Signature::from_der(&der).map_err(|_| "invalid-account-signature")?;
    Ok(json!({"proof":{"schema":PROOF,
        "claim":{"app":app,"account":account,"device":device,"issued":issued,"expires":expires,
            "action":{"purpose":action,"value":value}},
        "key":URL_SAFE_NO_PAD.encode(raw),"signature":URL_SAFE_NO_PAD.encode(signature.to_bytes()),
        "link":{"request":link["request"],"response":link["response"]}}}))
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::{signature::Verifier, VerifyingKey};

    #[test]
    #[ignore = "requires an unlocked macOS login Keychain"]
    fn device_signing_key_stays_out_of_icloud_keychain() {
        let scope = format!(
            "network.awai.drive.keychain-test.{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        let label = format!("{scope}.device-signing.v1");
        struct Remove(String);
        impl Drop for Remove {
            fn drop(&mut self) {
                // Both halves of the generated pair carry the label.
                if let Ok(found) = ItemSearchOptions::new()
                    .class(ItemClass::key())
                    .label(&self.0)
                    .limit(Limit::All)
                    .load_refs(true)
                    .search()
                {
                    for item in found {
                        if let SearchResult::Ref(Reference::Key(key)) = item {
                            let _ = key.delete();
                        }
                    }
                }
            }
        }
        let _remove = Remove(label.clone());
        assert!(device_key(&scope, true).unwrap().is_some());
        let chain = SecKeychain::default().unwrap();
        crate::desktop_vault::tests::assert_device_only(
            ItemSearchOptions::new()
                .keychains(std::slice::from_ref(&chain))
                .class(ItemClass::key())
                .key_class(KeyClass::private())
                .label(&label),
        );
        assert!(device_key(&scope, false).unwrap().is_some());
    }

    #[test]
    fn signs_the_canonical_wnfs_claim_with_the_linked_device_key() {
        let mut options = GenerateKeyOptions::default();
        options
            .set_key_type(KeyType::ec_sec_prime_random())
            .set_size_in_bits(256);
        let key = SecKey::new(&options).unwrap();
        let raw = public_bytes(&key).unwrap();
        let device = did(&raw);
        let mut link = json!({
            "accountDid":device, "request":{"device":device,"purpose":PURPOSE},
            "response":{"method":"passkey"}
        });
        let binding_value = format!("kv1:{}", "a".repeat(43));
        let request = json!({"value":binding_value,"expires":u64::MAX});
        let binding = binding(&key, &link, &request).unwrap();
        let proof = &binding["proof"];
        let claim = &proof["claim"];
        let payload = serde_json::to_vec(&json!([
            PROOF,
            "network.awai.drive",
            device,
            device,
            claim["issued"],
            claim["expires"],
            "bind-wnfs-key",
            binding_value,
            null
        ]))
        .unwrap();
        let signature = Signature::from_slice(
            &URL_SAFE_NO_PAD
                .decode(proof["signature"].as_str().unwrap())
                .unwrap(),
        )
        .unwrap();
        VerifyingKey::from_sec1_bytes(&raw)
            .unwrap()
            .verify(&payload, &signature)
            .unwrap();
        link["request"]["device"] = json!("did:key:zDifferent");
        assert_eq!(
            validate_link(&key, &link).unwrap_err(),
            "invalid-account-link"
        );
    }
}
