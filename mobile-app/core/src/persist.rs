//! On-device JSON files. Keys stay in the app sandbox, mode 0600 where the OS allows it.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::chat::Chat;
use crate::settings::Settings;
use crate::store::Store;

pub fn storage_dir() -> PathBuf {
    if let Ok(dir) = std::env::var("OMNI_DATA_DIR") {
        let trimmed = dir.trim();
        if !trimmed.is_empty() {
            return PathBuf::from(trimmed);
        }
    }
    if let Some(dir) = dirs_fallback() {
        return dir;
    }
    std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join("omni-data")
}

fn dirs_fallback() -> Option<PathBuf> {
    // Android and iOS give the process a private files directory as HOME or
    // as the working directory. Prefer those over a shared path.
    if cfg!(target_os = "android") || cfg!(target_os = "ios") {
        if let Ok(home) = std::env::var("HOME") {
            if !home.trim().is_empty() {
                return Some(PathBuf::from(home).join("omni-chat"));
            }
        }
        return Some(PathBuf::from("omni-data"));
    }
    None
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ChatFile {
    chats: Vec<Chat>,
    #[serde(default)]
    active_id: Option<String>,
}

pub fn load_into(dir: &Path) -> Store {
    let mut store = Store::fresh();
    let settings_path = dir.join("settings.json");
    if let Ok(raw) = fs::read_to_string(&settings_path) {
        if let Ok(settings) = Settings::from_json(&raw) {
            store.settings = settings;
        }
    }
    let chats_path = dir.join("chats.json");
    if let Ok(raw) = fs::read_to_string(&chats_path) {
        if let Ok(mut value) = serde_json::from_str::<serde_json::Value>(&raw) {
            strip_nulls(&mut value);
            if let Ok(file) = serde_json::from_value::<ChatFile>(value) {
                store.replace_chats(
                    file.chats
                        .into_iter()
                        .filter(|chat| !chat.incognito)
                        .collect(),
                    file.active_id,
                );
            }
        }
    }
    store
}

pub fn save_store(store: &Store, dir: &Path) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(|_| "Could not create the app data folder.".to_string())?;
    let chats = ChatFile {
        chats: store.saved_chats(),
        active_id: store.saved_active_id(),
    };
    atomic_write(
        &dir.join("chats.json"),
        &serde_json::to_vec_pretty(&chats).map_err(|_| "Could not write chats.".to_string())?,
    )?;
    atomic_write(
        &dir.join("settings.json"),
        &serde_json::to_vec_pretty(&store.settings)
            .map_err(|_| "Could not write settings.".to_string())?,
    )?;
    Ok(())
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("json.tmp");
    {
        let mut file =
            fs::File::create(&tmp).map_err(|_| "Could not write app data.".to_string())?;
        file.write_all(bytes)
            .map_err(|_| "Could not write app data.".to_string())?;
        file.sync_all().ok();
    }
    fs::rename(&tmp, path).map_err(|_| "Could not write app data.".to_string())?;
    set_private(path);
    Ok(())
}

fn set_private(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
    }
}

fn strip_nulls(value: &mut serde_json::Value) {
    match value {
        serde_json::Value::Object(map) => {
            map.retain(|_, child| !child.is_null());
            for child in map.values_mut() {
                strip_nulls(child);
            }
        }
        serde_json::Value::Array(items) => {
            for child in items {
                strip_nulls(child);
            }
        }
        _ => {}
    }
}
