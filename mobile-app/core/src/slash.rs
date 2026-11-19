//! Slash commands from the web client's `DEFAULT_TEMPLATES`.

use crate::request::slash_query;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlashCommand {
    pub key: &'static str,
    pub label: &'static str,
    pub hint: &'static str,
    pub color: &'static str,
    pub prefix: &'static str,
    pub code_mode: bool,
    pub web_search: bool,
}

const COMMANDS: &[SlashCommand] = &[
    SlashCommand {
        key: "code",
        label: "Code mode",
        hint: "Activate mode",
        color: "purple",
        prefix: "",
        code_mode: true,
        web_search: false,
    },
    SlashCommand {
        key: "search",
        label: "Search the web",
        hint: "Runs the rest of the line",
        color: "blue",
        prefix: "",
        code_mode: false,
        web_search: true,
    },
    SlashCommand {
        key: "web",
        label: "Web search",
        hint: "Activate mode",
        color: "blue",
        prefix: "",
        code_mode: false,
        web_search: true,
    },
    SlashCommand {
        key: "explain",
        label: "Simple Explainer",
        hint: "Activate mode",
        color: "amber",
        prefix: "Explain this topic simply and clearly, step by step: ",
        code_mode: false,
        web_search: false,
    },
    SlashCommand {
        key: "summarize",
        label: "Summarize Text",
        hint: "Activate mode",
        color: "emerald",
        prefix: "Summarize the key takeaways and main points of the following text: ",
        code_mode: false,
        web_search: false,
    },
    SlashCommand {
        key: "refactor",
        label: "Code Refactor",
        hint: "Activate mode",
        color: "purple",
        prefix: "Refactor and optimize the following code: ",
        code_mode: true,
        web_search: false,
    },
    SlashCommand {
        key: "translate",
        label: "Language Translation",
        hint: "Activate mode",
        color: "pink",
        prefix: "Translate the following text into natural English: ",
        code_mode: false,
        web_search: false,
    },
];

pub fn slash_commands() -> &'static [SlashCommand] {
    COMMANDS
}

pub fn filter_slash(draft: &str) -> Vec<&'static SlashCommand> {
    let Some(query) = draft.trim_start().strip_prefix('/') else {
        return Vec::new();
    };
    if query.contains('\n') {
        return Vec::new();
    }
    let query = query.to_lowercase();
    COMMANDS
        .iter()
        .filter(|command| {
            command.key.contains(query.as_str())
                || command.label.to_lowercase().contains(query.as_str())
        })
        .collect()
}

#[derive(Clone, Debug, PartialEq)]
pub enum SlashPick {
    /// `/search` already has a query, so the message sends now.
    Send { text: String },
    Arm {
        label: String,
        prefix: String,
        code_mode: bool,
        web_search: bool,
    },
}

pub fn pick_slash(command: &SlashCommand, draft: &str) -> SlashPick {
    if command.key == "search" {
        if let Some(query) = slash_query(draft, "/search") {
            if !query.is_empty() {
                return SlashPick::Send { text: query };
            }
        }
    }
    SlashPick::Arm {
        label: command.label.to_string(),
        prefix: command.prefix.to_string(),
        code_mode: command.code_mode,
        web_search: command.web_search,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn menu_lists_the_web_commands_and_search_sends() {
        let matches = filter_slash("/ref");
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].key, "refactor");
        match pick_slash(matches[0], "/refactor") {
            SlashPick::Arm {
                prefix, code_mode, ..
            } => {
                assert!(prefix.starts_with("Refactor"));
                assert!(code_mode);
            }
            SlashPick::Send { .. } => panic!("refactor should arm a prefix"),
        }
        let search = slash_commands().iter().find(|c| c.key == "search").unwrap();
        match pick_slash(search, "/search latest rust") {
            SlashPick::Send { text } => assert_eq!(text, "latest rust"),
            SlashPick::Arm { .. } => panic!("a search query should send"),
        }
        match pick_slash(search, "/search") {
            SlashPick::Arm {
                web_search, prefix, ..
            } => {
                assert!(web_search);
                assert!(prefix.is_empty());
            }
            SlashPick::Send { .. } => panic!("bare /search arms the chip"),
        }
    }
}
