//! Sandbox calls. The phone asks the existing Worker routes; it never talks to
//! E2B or Novita directly, and it never stores a sandbox id.

use serde_json::{json, Value};

use crate::request::{worker_headers, Outbound};
use crate::settings::Settings;

pub const AGENT_PROMPT: &str = "You have access to a sandboxed Linux VM with Python 3 and bash. Prefer running code over reasoning about what code would produce — actually execute it and report what really happened. Each run_python call starts a fresh process, so write self-contained snippets and use files to carry state between them. Print the results you want to keep. When you have the answer, state it plainly rather than only showing the code.";

pub fn agent_tools() -> Value {
    json!([
        {
            "name": "run_python",
            "description": "Execute Python 3 code in a sandboxed Linux VM and return its stdout and stderr. Use this for calculations, data processing, and anything you would otherwise have to reason about step by step. Packages can be installed with run_bash and pip. State does NOT persist between calls, so each snippet must be self-contained.",
            "parameters": {
                "type": "object",
                "properties": { "code": { "type": "string", "description": "The Python source to run. Print anything you want to see." } },
                "required": ["code"]
            }
        },
        {
            "name": "run_bash",
            "description": "Execute a bash script in the same sandboxed Linux VM and return its stdout and stderr. Use for installing packages, inspecting the filesystem, and running command line tools.",
            "parameters": {
                "type": "object",
                "properties": { "script": { "type": "string", "description": "The bash script to run." } },
                "required": ["script"]
            }
        },
        {
            "name": "write_file",
            "description": "Write a text file inside the sandbox. Files persist for the lifetime of the sandbox, so this is how you carry state between run_python calls.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Absolute path, e.g. /home/user/data.csv" },
                    "content": { "type": "string", "description": "The full file contents." }
                },
                "required": ["path", "content"]
            }
        },
        {
            "name": "read_file",
            "description": "Read a text file back from the sandbox.",
            "parameters": {
                "type": "object",
                "properties": { "path": { "type": "string", "description": "Absolute path to read." } },
                "required": ["path"]
            }
        }
    ])
}

#[derive(Clone, Debug)]
pub struct SandboxSession {
    pub sandbox_id: String,
    pub domain: String,
    pub token: String,
    pub provider: String,
}

#[derive(Clone, Debug)]
pub enum SandboxOp {
    Create {
        timeout_sec: u32,
    },
    Keepalive(SandboxSession),
    Kill(SandboxSession),
    Exec {
        session: SandboxSession,
        code: String,
        language: String,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct ToolInvocation {
    pub language: String,
    pub code: String,
}

pub fn sandbox_request(settings: &Settings, op: &SandboxOp) -> Result<Outbound, String> {
    let endpoint = settings.api_endpoint.trim().trim_end_matches('/');
    if endpoint.is_empty() {
        return Err("Set your API endpoint in Settings first.".into());
    }
    let key = settings.sandbox_key();
    if key.is_empty() {
        return Err("Add a sandbox API key in Settings to run code.".into());
    }
    let provider = if settings.sandbox_provider == "novita" {
        "novita"
    } else {
        "e2b"
    };
    let (path, mut body) = match op {
        SandboxOp::Create { timeout_sec } => (
            "/sandbox/create",
            json!({
                "sandboxProvider": provider,
                "sandboxKey": key,
                "timeoutSec": timeout_sec,
            }),
        ),
        SandboxOp::Keepalive(session) => (
            "/sandbox/keepalive",
            json!({
                "sandboxProvider": session.provider,
                "sandboxKey": key,
                "sandboxId": session.sandbox_id,
                "timeoutSec": settings.agent_sandbox_timeout,
            }),
        ),
        SandboxOp::Kill(session) => (
            "/sandbox/kill",
            json!({
                "sandboxProvider": session.provider,
                "sandboxKey": key,
                "sandboxId": session.sandbox_id,
                "domain": session.domain,
            }),
        ),
        SandboxOp::Exec {
            session,
            code,
            language,
        } => (
            "/sandbox/exec",
            json!({
                "sandboxProvider": session.provider,
                "sandboxKey": key,
                "sandboxId": session.sandbox_id,
                "domain": session.domain,
                "token": session.token,
                "code": code,
                "language": language,
            }),
        ),
    };
    if provider == "e2b" {
        if let Some(object) = body.as_object_mut() {
            object.insert("e2bKey".into(), Value::String(key));
        }
    }
    if !settings.proxy_key.trim().is_empty() {
        if let Some(object) = body.as_object_mut() {
            object.insert(
                "proxyKey".into(),
                Value::String(settings.proxy_key.trim().to_string()),
            );
        }
    }
    Ok(Outbound {
        url: format!("{endpoint}{path}"),
        headers: worker_headers(settings),
        body,
    })
}

pub fn tool_invocation(name: &str, args: &Value) -> Result<ToolInvocation, String> {
    match name {
        "run_python" => Ok(ToolInvocation {
            language: "python".into(),
            code: args
                .get("code")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
        }),
        "run_bash" => Ok(ToolInvocation {
            language: "bash".into(),
            code: args
                .get("script")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
        }),
        "write_file" => {
            let path =
                serde_json::to_string(args.get("path").and_then(|v| v.as_str()).unwrap_or(""))
                    .unwrap_or_else(|_| "\"\"".into());
            let body =
                serde_json::to_string(args.get("content").and_then(|v| v.as_str()).unwrap_or(""))
                    .unwrap_or_else(|_| "\"\"".into());
            Ok(ToolInvocation {
                language: "python".into(),
                code: format!(
                    "import os,pathlib\np={path}\nos.makedirs(os.path.dirname(p) or \".\",exist_ok=True)\npathlib.Path(p).write_text({body})\nprint(\"wrote\",p,len({body}),\"bytes\")"
                ),
            })
        }
        "read_file" => {
            let path =
                serde_json::to_string(args.get("path").and_then(|v| v.as_str()).unwrap_or(""))
                    .unwrap_or_else(|_| "\"\"".into());
            Ok(ToolInvocation {
                language: "python".into(),
                code: format!("import pathlib\nprint(pathlib.Path({path}).read_text())"),
            })
        }
        other => Err(format!("Error: unknown tool \"{other}\"")),
    }
}

pub fn format_tool_result(data: &Value) -> String {
    if let Some(message) = data.get("error").and_then(|err| {
        err.get("message")
            .and_then(|v| v.as_str())
            .or_else(|| err.as_str())
    }) {
        return format!("Error: {message}");
    }
    if data.get("error").is_some() {
        return "Error: sandbox call failed".into();
    }
    let mut out = String::new();
    if let Some(stdout) = data.get("stdout").and_then(|v| v.as_str()) {
        if !stdout.is_empty() {
            out.push_str(stdout);
        }
    }
    if let Some(stderr) = data.get("stderr").and_then(|v| v.as_str()) {
        if !stderr.is_empty() {
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str("[stderr]\n");
            out.push_str(stderr);
        }
    }
    if let Some(code) = data.get("exit_code").and_then(|v| v.as_i64()) {
        if code != 0 {
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str(&format!("[exit code {code}]"));
        }
    }
    if data
        .get("timed_out")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        if !out.is_empty() {
            out.push('\n');
        }
        out.push_str("[timed out]");
    }
    if data
        .get("truncated")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        out.push_str("\n[output truncated]");
    }
    if out.is_empty() {
        "(no output)".into()
    } else {
        out
    }
}

pub fn parse_session(data: &Value, fallback_provider: &str) -> Result<SandboxSession, String> {
    if let Some(message) = data.pointer("/error/message").and_then(|v| v.as_str()) {
        return Err(message.to_string());
    }
    let sandbox_id = data
        .get("sandboxId")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if sandbox_id.len() < 8 {
        return Err("Could not start sandbox".into());
    }
    Ok(SandboxSession {
        sandbox_id,
        domain: data
            .get("domain")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        token: data
            .get("token")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        provider: data
            .get("provider")
            .and_then(|v| v.as_str())
            .unwrap_or(fallback_provider)
            .to_string(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_file_does_not_interpolate_the_path_into_the_shell() {
        let args = json!({"path": "/tmp/a'; rm -rf /", "content": "hi"});
        let invocation = tool_invocation("write_file", &args).unwrap();
        assert!(invocation.code.contains("p=\"/tmp/a'; rm -rf /\""));
        assert!(invocation.code.contains("write_text("));
        assert_eq!(invocation.language, "python");
    }

    #[test]
    fn formats_stdout_and_errors() {
        assert_eq!(
            format_tool_result(&json!({"stdout": "4\n", "exit_code": 0})),
            "4\n"
        );
        assert!(format_tool_result(&json!({"error": {"message": "nope"}})).contains("nope"));
    }
}
