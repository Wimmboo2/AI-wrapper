//! User attachments. Images travel as data URLs; other files travel as text,
//! matching `buildUserMessagePayload` in the web client.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

const TEXT_LIMIT: usize = 8000;
const IMAGE_LIMIT: usize = 12_000_000;
const FILE_LIMIT: usize = 8;

#[derive(Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Attachment {
    pub name: String,
    pub mime: String,
    /// Data URL for an image, or the file's text.
    pub data: String,
}

impl std::fmt::Debug for Attachment {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Attachment")
            .field("name", &self.name)
            .field("mime", &self.mime)
            .field("bytes", &self.data.len())
            .finish()
    }
}

impl Attachment {
    pub fn is_image(&self) -> bool {
        self.mime.starts_with("image/")
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct UserPayload {
    pub display: String,
    pub api: Option<Value>,
    pub attachments: Vec<Attachment>,
}

pub fn prepare_files(files: &[Attachment]) -> Result<Vec<Attachment>, String> {
    if files.len() > FILE_LIMIT {
        return Err(format!("Attach up to {FILE_LIMIT} files."));
    }
    files.iter().cloned().map(sanitize).collect()
}

fn sanitize(mut file: Attachment) -> Result<Attachment, String> {
    file.name = file
        .name
        .replace(['\n', '\r'], " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if file.name.is_empty() {
        file.name = if file.is_image() {
            "image".into()
        } else {
            "file".into()
        };
    }
    if file.name.chars().count() > 120 {
        file.name = file.name.chars().take(120).collect();
    }
    if file.mime.trim().is_empty() {
        file.mime = if file.data.starts_with("data:image/") {
            "image/png".into()
        } else {
            "text/plain".into()
        };
    }
    if file.is_image() {
        if !file.data.starts_with("data:image/") {
            return Err("That image could not be read.".into());
        }
        if file.data.len() > IMAGE_LIMIT {
            return Err("That image is too large.".into());
        }
    } else if file.data.chars().count() > TEXT_LIMIT && !file.data.contains("[truncated]") {
        file.data = file.data.chars().take(TEXT_LIMIT).collect();
        file.data.push_str("\n... [truncated]");
    }
    Ok(file)
}

/// `prefix` is prepended to the wire text only. The bubble keeps what the user typed.
pub fn user_payload(text: &str, files: &[Attachment], prefix: &str) -> UserPayload {
    let wire_text = if prefix.is_empty() {
        text.to_string()
    } else {
        format!("{prefix}{text}")
    };
    if files.is_empty() {
        let api = if prefix.is_empty() {
            None
        } else {
            Some(Value::String(wire_text))
        };
        return UserPayload {
            display: text.to_string(),
            api,
            attachments: Vec::new(),
        };
    }
    let names: Vec<&str> = files.iter().map(|file| file.name.as_str()).collect();
    let marker = format!("[Attached: {}]", names.join(", "));
    let display = if text.is_empty() {
        marker
    } else {
        format!("{text}\n\n{marker}")
    };
    let mut parts = Vec::new();
    if !wire_text.is_empty() {
        parts.push(json!({ "type": "text", "text": wire_text }));
    }
    for file in files {
        if file.is_image() {
            parts.push(json!({
                "type": "image_url",
                "image_url": { "url": file.data, "detail": "auto" }
            }));
        } else {
            parts.push(json!({
                "type": "text",
                "text": format!("[File: {}]\n{}", file.name, file.data)
            }));
        }
    }
    let api = if parts.len() == 1 && parts[0].get("type").and_then(|v| v.as_str()) == Some("text") {
        parts[0].get("text").cloned()
    } else {
        Some(Value::Array(parts))
    };
    UserPayload {
        display,
        api,
        attachments: files.to_vec(),
    }
}

pub fn strip_attachment_marker(text: &str) -> String {
    let trimmed = text.trim();
    let Some(start) = trimmed.rfind("[Attached:") else {
        return trimmed.to_string();
    };
    let marker = &trimmed[start..];
    if !marker.ends_with(']') || marker.contains('\n') {
        return trimmed.to_string();
    }
    trimmed[..start].trim().to_string()
}

pub fn title_source(display: &str, files: &[Attachment]) -> String {
    let plain = strip_attachment_marker(display);
    if !plain.is_empty() {
        return plain;
    }
    if files.len() == 1 {
        return files[0].name.clone();
    }
    if files.len() > 1 {
        return format!("{} attachments", files.len());
    }
    String::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn images_and_text_files_match_the_web_payload() {
        let files = vec![
            Attachment {
                name: "cat.png".into(),
                mime: "image/png".into(),
                data: "data:image/png;base64,aaaa".into(),
            },
            Attachment {
                name: "notes.md".into(),
                mime: "text/markdown".into(),
                data: "hello".into(),
            },
        ];
        let payload = user_payload(
            "Look",
            &files,
            "Explain this topic simply and clearly, step by step: ",
        );
        assert_eq!(payload.display, "Look\n\n[Attached: cat.png, notes.md]");
        let parts = payload.api.unwrap();
        let parts = parts.as_array().unwrap();
        assert_eq!(
            parts[0]["text"],
            "Explain this topic simply and clearly, step by step: Look"
        );
        assert_eq!(parts[1]["type"], "image_url");
        assert_eq!(parts[1]["image_url"]["detail"], "auto");
        assert!(parts[2]["text"]
            .as_str()
            .unwrap()
            .starts_with("[File: notes.md]\nhello"));
    }

    #[test]
    fn marker_strips_for_editing_and_long_text_is_cut() {
        assert_eq!(
            strip_attachment_marker("Hello\n\n[Attached: a.png]"),
            "Hello"
        );
        let long = "x".repeat(9000);
        let file = sanitize(Attachment {
            name: "a.txt".into(),
            mime: "text/plain".into(),
            data: long,
        })
        .unwrap();
        assert!(file.data.contains("[truncated]"));
        assert!(file.data.chars().count() < 9000);
    }
}
