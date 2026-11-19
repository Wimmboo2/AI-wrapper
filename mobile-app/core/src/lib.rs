//! Shared chat logic for the Omni phone app.
//!
//! The UI crate renders this. Nothing in here touches a widget toolkit, so the
//! streaming loop, branches, and storage can be tested with `cargo test`.

#![forbid(unsafe_code)]

mod chat;
mod id;
mod markdown;
mod persist;
mod providers;
mod request;
mod sandbox;
mod settings;
mod store;
mod stream;
mod think;

pub use chat::{AgentStep, BranchUi, Chat, ChatListItem, Message, TurnMetrics};
pub use markdown::{parse_markdown, Block, Inline};
pub use persist::storage_dir;
pub use providers::{
    all_providers, available_models, provider, ModelChoice, ModelInfo, ProviderInfo,
};
pub use request::{worker_headers, Outbound, SendOpts};
pub use sandbox::{
    format_tool_result, parse_session, sandbox_request, tool_invocation, SandboxOp, SandboxSession,
    ToolInvocation,
};
pub use settings::{CustomModel, ExtraModel, Settings};
pub use store::{Prepared, Store, StreamEnd, ToolCall, ToolResult};
pub use stream::friendly_error;
