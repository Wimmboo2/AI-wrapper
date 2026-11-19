//! Settings and chat files. The settings file contains API keys on purpose,
//! the same way the web export does. Callers must not log the file body.

use serde::Deserialize;
use serde_json::{json, Value};

use crate::chat::Chat;
use crate::id::now_ms;
use crate::settings::Settings;

#[derive(Clone, Debug)]
pub enum ImportFile {
    Settings(Settings),
    Chats(Vec<Chat>),
}

pub fn export_settings(settings: &Settings) -> Result<String, String> {
    let value = json!({
        "version": 1,
        "kind": "omni-settings",
        "exportedAt": now_ms(),
        "settings": settings,
    });
    serde_json::to_string_pretty(&value).map_err(|_| "Could not export settings.".to_string())
}

pub fn export_chats(chats: &[Chat]) -> Result<String, String> {
    let value = json!({
        "version": 1,
        "kind": "omni-chats",
        "exportedAt": now_ms(),
        "chats": chats,
    });
    serde_json::to_string_pretty(&value).map_err(|_| "Could not export chats.".to_string())
}

pub fn parse_import(raw: &str) -> Result<ImportFile, String> {
    let value: Value =
        serde_json::from_str(raw).map_err(|_| "That file is not valid JSON.".to_string())?;
    let kind = value.get("kind").and_then(|v| v.as_str()).unwrap_or("");
    let version = value.get("version").and_then(|v| v.as_u64());
    if version != Some(1) {
        return Err("That file is not an Omni export.".into());
    }
    match kind {
        "omni-settings" => {
            let settings = value.get("settings").cloned().unwrap_or(Value::Null);
            let raw = serde_json::to_string(&settings)
                .map_err(|_| "That file is not an Omni export.".to_string())?;
            let settings = Settings::from_json(&raw)?;
            Ok(ImportFile::Settings(settings))
        }
        "omni-chats" => {
            #[derive(Deserialize)]
            struct Chats {
                chats: Vec<Chat>,
            }
            let parsed: Chats = serde_json::from_value(value)
                .map_err(|_| "That chat export could not be read.".to_string())?;
            Ok(ImportFile::Chats(parsed.chats))
        }
        _ => Err("That file is not an Omni export.".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_round_trip_keeps_keys_out_of_chat_files() {
        let mut settings = Settings::default();
        settings.set_api_key("openai", "sk-export-secret".into());
        settings.tavily_key = "tvly-export-secret".into();
        let raw = export_settings(&settings).unwrap();
        assert!(raw.contains("sk-export-secret"));
        let ImportFile::Settings(loaded) = parse_import(&raw).unwrap() else {
            panic!("settings file");
        };
        assert_eq!(loaded.api_key_for("openai"), "sk-export-secret");
        let debug = format!("{loaded:?}");
        assert!(!debug.contains("sk-export-secret"));
        assert!(!debug.contains("tvly-export-secret"));

        let chats = export_chats(&[]).unwrap();
        assert!(!chats.contains("sk-export-secret"));
        assert!(matches!(
            parse_import(&chats).unwrap(),
            ImportFile::Chats(_)
        ));
        assert!(parse_import(r#"{"version":1,"kind":"omni-chats","chats":[]}"#).is_ok());
        let err = parse_import(&chats);
        // A chat file is a valid import, just not settings. The caller branches.
        assert!(matches!(err.unwrap(), ImportFile::Chats(_)));
        assert!(parse_import(r#"{"version":2,"kind":"omni-settings","settings":{}}"#).is_err());
    }
}
