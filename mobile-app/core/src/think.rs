//! Streaming splitter for inline reasoning tags.
//!
//! Ported from the web client's `feedThinkSplitter`. A tag can be cut in half
//! by a chunk boundary, so a trailing fragment that could still grow into a
//! marker is held back instead of being emitted.

use std::sync::LazyLock;

use regex::Regex;

const OPEN_TAGS: &[&str] = &[
    "<think>",
    "<thinking>",
    "<reason>",
    "<reasoning>",
    "<thought>",
    "<seed:think>",
    "[think]",
    "<|start_thinking|>",
    "◁think▷",
];
const CLOSE_TAGS: &[&str] = &[
    "</think>",
    "</thinking>",
    "</reason>",
    "</reasoning>",
    "</thought>",
    "</seed:think>",
    "[/think]",
    "<|end_thinking|>",
    "◁/think▷",
];
const DROP_TAGS: &[&str] = &[
    "<|start_response|>",
    "<|end_response|>",
    "<seed:cot_budget_reflect>",
    "</seed:cot_budget_reflect>",
];
const HARMONY_TAGS: &[&str] = &[
    "<|channel|>",
    "<|message|>",
    "<|start|>",
    "<|end|>",
    "<|return|>",
    "<|call|>",
    "<|constrain|>",
];
const OPENERS: &[char] = &['<', '[', '◁'];
const CLOSERS: &[char] = &['>', ']', '▷'];

static TAG_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"<\|[a-zA-Z_]+\|>|<\s*/?\s*[a-zA-Z][\w:.-]*\s*>|\[\s*/?\s*[A-Za-z_]+\s*\]|◁\s*/?\s*[a-zA-Z]+\s*▷")
        .expect("think-tag scanner")
});

#[derive(Clone, Debug)]
enum ChannelState {
    Idle,
    Channel,
    Message,
    Header,
}

#[derive(Clone, Debug)]
pub struct ThinkSplitter {
    buf: String,
    in_think: bool,
    saw_open: bool,
    reclaimed: bool,
    presumed: bool,
    ever_closed: bool,
    harmony: bool,
    h_state: ChannelState,
    h_channel: String,
    h_spec: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Fed {
    pub text: String,
    pub think: String,
    pub closed: bool,
    pub reclaim: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Flushed {
    pub text: String,
    pub think: String,
    pub unpresume: bool,
}

struct Hit {
    idx: usize,
    len: usize,
    norm: String,
}

pub fn make_splitter(presume_open: bool) -> ThinkSplitter {
    ThinkSplitter {
        buf: String::new(),
        in_think: presume_open,
        saw_open: false,
        reclaimed: false,
        presumed: presume_open,
        ever_closed: false,
        harmony: false,
        h_state: ChannelState::Idle,
        h_channel: String::new(),
        h_spec: String::new(),
    }
}

impl ThinkSplitter {
    pub fn feed(&mut self, chunk: &str) -> Fed {
        self.buf.push_str(chunk);
        let mut text = String::new();
        let mut think = String::new();
        let mut closed = false;
        let mut reclaim = false;

        while !self.buf.is_empty() {
            if self.harmony {
                let hm = find_tag(&self.buf, HARMONY_TAGS);
                let upto = hm
                    .as_ref()
                    .map(|hit| hit.idx)
                    .unwrap_or_else(|| self.buf.len() - dangling_len(&self.buf, HARMONY_TAGS));
                let seg = self.buf[..upto].to_string();
                if !seg.is_empty() {
                    match self.h_state {
                        ChannelState::Message => {
                            if self.h_channel == "final" {
                                text.push_str(&seg);
                            } else {
                                think.push_str(&seg);
                            }
                        }
                        ChannelState::Channel => self.h_spec.push_str(&seg),
                        _ => {}
                    }
                }
                self.buf = self.buf[upto..].to_string();
                let Some(hm) = find_tag(&self.buf, HARMONY_TAGS) else {
                    break;
                };
                // The match is at the start only when upto consumed everything before it.
                if hm.idx != 0 {
                    break;
                }
                self.buf = self.buf[hm.len..].to_string();
                match hm.norm.as_str() {
                    "<|channel|>" => {
                        self.h_state = ChannelState::Channel;
                        self.h_spec.clear();
                    }
                    "<|message|>" => {
                        self.h_channel = self
                            .h_spec
                            .trim_start()
                            .split(|c: char| c.is_whitespace() || c == '<')
                            .next()
                            .unwrap_or("")
                            .to_lowercase();
                        self.h_state = ChannelState::Message;
                    }
                    "<|start|>" => self.h_state = ChannelState::Header,
                    "<|end|>" | "<|return|>" | "<|call|>" => {
                        if matches!(self.h_state, ChannelState::Message)
                            && self.h_channel != "final"
                        {
                            self.ever_closed = true;
                            closed = true;
                        }
                        self.h_state = ChannelState::Idle;
                        self.h_channel.clear();
                    }
                    _ => {}
                }
                continue;
            }

            let harmony_hit = find_tag(&self.buf, HARMONY_TAGS);
            let drop_hit = find_tag(&self.buf, DROP_TAGS);

            if !self.in_think {
                let open = find_tag(&self.buf, OPEN_TAGS);
                let orphan = if self.saw_open || self.reclaimed {
                    None
                } else {
                    find_tag(&self.buf, CLOSE_TAGS)
                };
                let first = earliest(&[
                    ("harmony", harmony_hit),
                    ("drop", drop_hit),
                    ("open", open),
                    ("orphan", orphan),
                ]);
                if let Some((kind, hit)) = first {
                    text.push_str(&self.buf[..hit.idx]);
                    if kind == "harmony" {
                        self.buf = self.buf[hit.idx..].to_string();
                        self.harmony = true;
                        continue;
                    }
                    self.buf = self.buf[hit.idx + hit.len..].to_string();
                    if kind == "drop" {
                        continue;
                    }
                    if kind == "open" {
                        self.in_think = true;
                        self.saw_open = true;
                        continue;
                    }
                    think.push_str(&text);
                    text.clear();
                    self.reclaimed = true;
                    self.ever_closed = true;
                    reclaim = true;
                    closed = true;
                    continue;
                }
                let mut candidates: Vec<&str> = OPEN_TAGS.to_vec();
                if !(self.saw_open || self.reclaimed) {
                    candidates.extend(CLOSE_TAGS);
                }
                candidates.extend(DROP_TAGS);
                candidates.extend(HARMONY_TAGS);
                let hold = dangling_len(&self.buf, &candidates);
                let emit = self.buf.len() - hold;
                text.push_str(&self.buf[..emit]);
                self.buf = self.buf[emit..].to_string();
                break;
            }

            let close = find_tag(&self.buf, CLOSE_TAGS);
            let first = earliest(&[
                ("harmony", harmony_hit),
                ("drop", drop_hit),
                ("close", close),
            ]);
            if let Some((kind, hit)) = first {
                think.push_str(&self.buf[..hit.idx]);
                if kind == "harmony" {
                    self.buf = self.buf[hit.idx..].to_string();
                    self.harmony = true;
                    self.in_think = false;
                    continue;
                }
                self.buf = self.buf[hit.idx + hit.len..].to_string();
                if kind == "drop" {
                    continue;
                }
                self.in_think = false;
                self.ever_closed = true;
                closed = true;
                continue;
            }
            let mut candidates: Vec<&str> = CLOSE_TAGS.to_vec();
            candidates.extend(DROP_TAGS);
            candidates.extend(HARMONY_TAGS);
            let hold = dangling_len(&self.buf, &candidates);
            let emit = self.buf.len() - hold;
            think.push_str(&self.buf[..emit]);
            self.buf = self.buf[emit..].to_string();
            break;
        }

        Fed {
            text,
            think,
            closed,
            reclaim,
        }
    }

    pub fn flush(&mut self, interrupted: bool) -> Flushed {
        let in_thought = self.in_think
            || (self.presumed && !self.ever_closed)
            || (self.harmony
                && matches!(self.h_state, ChannelState::Message)
                && !self.h_channel.is_empty()
                && self.h_channel != "final");
        let rest = std::mem::take(&mut self.buf);
        if interrupted && in_thought {
            return Flushed {
                text: String::new(),
                think: rest,
                unpresume: false,
            };
        }
        if self.harmony {
            if matches!(self.h_state, ChannelState::Message) {
                return if self.h_channel == "final" {
                    Flushed {
                        text: rest,
                        think: String::new(),
                        unpresume: false,
                    }
                } else {
                    Flushed {
                        text: String::new(),
                        think: rest,
                        unpresume: false,
                    }
                };
            }
            return Flushed {
                text: String::new(),
                think: String::new(),
                unpresume: false,
            };
        }
        if self.presumed && !self.ever_closed {
            return Flushed {
                text: rest,
                think: String::new(),
                unpresume: true,
            };
        }
        if rest.is_empty() {
            return Flushed {
                text: String::new(),
                think: String::new(),
                unpresume: false,
            };
        }
        if self.in_think {
            Flushed {
                text: String::new(),
                think: rest,
                unpresume: false,
            }
        } else {
            Flushed {
                text: rest,
                think: String::new(),
                unpresume: false,
            }
        }
    }
}

fn find_tag(hay: &str, tags: &[&str]) -> Option<Hit> {
    for mat in TAG_RE.find_iter(hay) {
        let norm = mat
            .as_str()
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>()
            .to_lowercase();
        if tags.contains(&norm.as_str()) {
            return Some(Hit {
                idx: mat.start(),
                len: mat.end() - mat.start(),
                norm,
            });
        }
    }
    None
}

fn earliest(hits: &[(&str, Option<Hit>)]) -> Option<(String, Hit)> {
    let mut best: Option<(String, Hit)> = None;
    for (kind, hit) in hits {
        if let Some(hit) = hit {
            let replace = match &best {
                None => true,
                Some((_, current)) => hit.idx < current.idx,
            };
            if replace {
                best = Some((
                    (*kind).to_string(),
                    Hit {
                        idx: hit.idx,
                        len: hit.len,
                        norm: hit.norm.clone(),
                    },
                ));
            }
        }
    }
    best
}

fn dangling_len(source: &str, tags: &[&str]) -> usize {
    let mut start = None;
    for (idx, ch) in source.char_indices().rev() {
        if OPENERS.contains(&ch) {
            start = Some(idx);
            break;
        }
    }
    let Some(start) = start else {
        return 0;
    };
    let tail = &source[start..];
    if tail.chars().any(|ch| CLOSERS.contains(&ch)) {
        return 0;
    }
    let norm = tail
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>()
        .to_lowercase();
    if tags.iter().any(|tag| tag.starts_with(&norm)) {
        source.len() - start
    } else {
        0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(chunks: &[&str]) -> (String, String) {
        let mut splitter = make_splitter(false);
        let mut text = String::new();
        let mut think = String::new();
        for chunk in chunks {
            let fed = splitter.feed(chunk);
            text.push_str(&fed.text);
            think.push_str(&fed.think);
        }
        let flushed = splitter.flush(false);
        text.push_str(&flushed.text);
        think.push_str(&flushed.think);
        (text, think)
    }

    #[test]
    fn splits_a_think_tag() {
        let (text, think) = run(&["Hello <think>secret</think> world"]);
        assert_eq!(text, "Hello  world");
        assert_eq!(think, "secret");
    }

    #[test]
    fn holds_a_tag_split_across_chunks() {
        let (text, think) = run(&["Hello <thi", "nk>secret</thi", "nk>!"]);
        assert_eq!(text, "Hello !");
        assert_eq!(think, "secret");
    }

    #[test]
    fn orphan_close_reclaims_the_preamble() {
        let mut splitter = make_splitter(false);
        let fed = splitter.feed("reasoning</think>answer");
        assert!(fed.reclaim);
        assert_eq!(fed.think, "reasoning");
        assert_eq!(fed.text, "answer");
    }

    #[test]
    fn harmony_keeps_analysis_out_of_the_answer() {
        let (text, think) =
            run(&["<|channel|>analysis<|message|>secret<|end|><|channel|>final<|message|>answer"]);
        assert_eq!(think, "secret");
        assert_eq!(text, "answer");
    }

    #[test]
    fn presumed_open_without_a_close_asks_the_caller_to_restore_the_answer() {
        let mut splitter = make_splitter(true);
        let fed = splitter.feed("plain answer");
        assert_eq!(fed.think, "plain answer");
        assert!(fed.text.is_empty());
        let flushed = splitter.flush(false);
        assert!(flushed.unpresume);
    }
}
