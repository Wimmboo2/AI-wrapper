//! Shared chat logic for the Omni phone app.
//!
//! The UI crate renders this. Nothing in here touches a widget toolkit, so the
//! streaming loop, branches, and storage can be tested with `cargo test`.

#![forbid(unsafe_code)]

mod chat;
mod exchange;
mod files;
mod id;
mod markdown;
mod metrics;
mod persist;
mod providers;
mod request;
mod sandbox;
mod settings;
mod slash;
mod store;
mod stream;
mod think;

pub use chat::{AgentStep, BranchUi, Chat, ChatListItem, Message, TurnMetrics};
pub use exchange::{export_chats, export_settings, parse_import, ImportFile};
pub use files::{prepare_files, strip_attachment_marker, user_payload, Attachment};
pub use markdown::{parse_markdown, Block, Inline};
pub use metrics::{metric_sheet, MetricRow, MetricSheet, PhaseSeg};
pub use persist::storage_dir;
pub use providers::{
    all_providers, allows_vision, available_models, context_window, provider, ModelChoice,
    ModelInfo, ProviderInfo,
};
pub use request::{worker_headers, Outbound, SendOpts};
pub use sandbox::{
    format_tool_result, parse_session, sandbox_request, tool_invocation, SandboxOp, SandboxSession,
    ToolInvocation,
};
pub use settings::{CustomModel, ExtraModel, Settings};
pub use slash::{filter_slash, pick_slash, slash_commands, SlashCommand, SlashPick};
pub use store::{EditUi, Prepared, Store, StreamEnd, ToolCall, ToolResult};
pub use stream::friendly_error;
