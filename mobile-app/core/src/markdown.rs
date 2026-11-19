//! Markdown to a small block tree. The UI renders the tree as widgets, so model
//! HTML never becomes markup in the webview.

use pulldown_cmark::{CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag, TagEnd};

#[derive(Clone, Debug, PartialEq)]
pub enum Block {
    Paragraph(Vec<Inline>),
    Heading(u8, Vec<Inline>),
    Code {
        lang: String,
        code: String,
    },
    List {
        ordered: bool,
        items: Vec<Vec<Block>>,
    },
    Quote(Vec<Block>),
    Table {
        header: Vec<String>,
        rows: Vec<Vec<String>>,
    },
    Rule,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Inline {
    Text(String),
    Em(Vec<Inline>),
    Strong(Vec<Inline>),
    Strike(Vec<Inline>),
    Code(String),
    Link { text: Vec<Inline>, href: String },
    Image { alt: String, src: String },
    Break,
}

pub fn parse_markdown(src: &str) -> Vec<Block> {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_STRIKETHROUGH);
    options.insert(Options::ENABLE_TABLES);
    let parser = Parser::new_ext(src, options);
    let mut root: Vec<Block> = Vec::new();
    let mut stack: Vec<Frame> = Vec::new();
    let mut skip = 0u32;

    for event in parser {
        if skip > 0 {
            match event {
                Event::Start(_) => skip += 1,
                Event::End(_) => skip -= 1,
                _ => {}
            }
            continue;
        }
        match event {
            Event::Start(tag) => match tag {
                Tag::Paragraph => stack.push(Frame::Inlines(Vec::new())),
                Tag::Heading { level, .. } => {
                    stack.push(Frame::Heading(heading(level), Vec::new()))
                }
                Tag::BlockQuote => stack.push(Frame::Blocks(Vec::new())),
                Tag::CodeBlock(kind) => {
                    let lang = match kind {
                        CodeBlockKind::Fenced(lang) => lang.to_string(),
                        CodeBlockKind::Indented => String::new(),
                    };
                    stack.push(Frame::Code {
                        lang,
                        code: String::new(),
                    });
                }
                Tag::List(start) => stack.push(Frame::List {
                    ordered: start.is_some(),
                    items: Vec::new(),
                    current: None,
                }),
                Tag::Item => {
                    if let Some(Frame::List { current, .. }) = stack.last_mut() {
                        *current = Some(Vec::new());
                    }
                }
                Tag::Emphasis => stack.push(Frame::Em(Vec::new())),
                Tag::Strong => stack.push(Frame::Strong(Vec::new())),
                Tag::Strikethrough => stack.push(Frame::Strike(Vec::new())),
                Tag::Link { dest_url, .. } => {
                    stack.push(Frame::Link {
                        href: safe_href(dest_url.as_ref()),
                        text: Vec::new(),
                    });
                }
                Tag::Image { dest_url, .. } => {
                    stack.push(Frame::Image {
                        src: safe_href(dest_url.as_ref()),
                        alt: String::new(),
                    });
                }
                Tag::Table(_) => stack.push(Frame::Table {
                    header: Vec::new(),
                    rows: Vec::new(),
                    in_head: false,
                    cell: String::new(),
                }),
                Tag::TableHead => {
                    if let Some(Frame::Table { in_head, .. }) = stack.last_mut() {
                        *in_head = true;
                    }
                }
                Tag::TableRow => {
                    if let Some(Frame::Table { rows, in_head, .. }) = stack.last_mut() {
                        if !*in_head {
                            rows.push(Vec::new());
                        }
                    }
                }
                Tag::TableCell => {
                    if let Some(Frame::Table { cell, .. }) = stack.last_mut() {
                        cell.clear();
                    }
                }
                _ => skip = 1,
            },
            Event::End(tag) => match tag {
                TagEnd::Paragraph => finish_inlines(&mut stack, &mut root, Finish::Paragraph),
                TagEnd::Heading(_) => finish_inlines(&mut stack, &mut root, Finish::Heading),
                TagEnd::BlockQuote => {
                    if let Some(Frame::Blocks(blocks)) = stack.pop() {
                        push_block(&mut stack, &mut root, Block::Quote(blocks));
                    }
                }
                TagEnd::CodeBlock => {
                    if let Some(Frame::Code { lang, code }) = stack.pop() {
                        push_block(
                            &mut stack,
                            &mut root,
                            Block::Code {
                                lang,
                                code: code.trim_end_matches('\n').to_string(),
                            },
                        );
                    }
                }
                TagEnd::List(_) => {
                    if let Some(Frame::List {
                        ordered,
                        mut items,
                        current,
                    }) = stack.pop()
                    {
                        if let Some(item) = current {
                            items.push(item);
                        }
                        push_block(&mut stack, &mut root, Block::List { ordered, items });
                    }
                }
                TagEnd::Item => {
                    if let Some(Frame::List { items, current, .. }) = stack.last_mut() {
                        if let Some(item) = current.take() {
                            items.push(item);
                        }
                    }
                }
                TagEnd::Emphasis => finish_wrap(&mut stack, &mut root, Wrap::Em),
                TagEnd::Strong => finish_wrap(&mut stack, &mut root, Wrap::Strong),
                TagEnd::Strikethrough => finish_wrap(&mut stack, &mut root, Wrap::Strike),
                TagEnd::Link => {
                    if let Some(Frame::Link { href, text }) = stack.pop() {
                        push_inline(&mut stack, &mut root, Inline::Link { text, href });
                    }
                }
                TagEnd::Image => {
                    if let Some(Frame::Image { src, alt }) = stack.pop() {
                        push_inline(&mut stack, &mut root, Inline::Image { alt, src });
                    }
                }
                TagEnd::TableCell => {
                    if let Some(Frame::Table {
                        header,
                        rows,
                        in_head,
                        cell,
                    }) = stack.last_mut()
                    {
                        let text = std::mem::take(cell);
                        if *in_head {
                            header.push(text);
                        } else if let Some(row) = rows.last_mut() {
                            row.push(text);
                        }
                    }
                }
                TagEnd::TableRow | TagEnd::TableHead => {
                    if let Some(Frame::Table { in_head, .. }) = stack.last_mut() {
                        *in_head = false;
                    }
                }
                TagEnd::Table => {
                    if let Some(Frame::Table {
                        header, mut rows, ..
                    }) = stack.pop()
                    {
                        rows.retain(|row| !row.is_empty());
                        push_block(&mut stack, &mut root, Block::Table { header, rows });
                    }
                }
                _ => {}
            },
            Event::Text(text) => {
                if let Some(frame) = stack.last_mut() {
                    match frame {
                        Frame::Code { code, .. } => code.push_str(&text),
                        Frame::Image { alt, .. } => alt.push_str(&text),
                        Frame::Table { cell, .. } => cell.push_str(&text),
                        _ => {}
                    }
                }
                if !matches!(
                    stack.last(),
                    Some(Frame::Code { .. } | Frame::Image { .. } | Frame::Table { .. })
                ) {
                    push_inline(&mut stack, &mut root, Inline::Text(text.to_string()));
                }
            }
            Event::Code(text) => push_inline(&mut stack, &mut root, Inline::Code(text.to_string())),
            Event::SoftBreak | Event::HardBreak => {
                push_inline(&mut stack, &mut root, Inline::Break)
            }
            Event::Rule => push_block(&mut stack, &mut root, Block::Rule),
            Event::Html(_)
            | Event::InlineHtml(_)
            | Event::FootnoteReference(_)
            | Event::TaskListMarker(_) => {}
        }
    }
    root
}

enum Frame {
    Inlines(Vec<Inline>),
    Heading(u8, Vec<Inline>),
    Blocks(Vec<Block>),
    Code {
        lang: String,
        code: String,
    },
    List {
        ordered: bool,
        items: Vec<Vec<Block>>,
        current: Option<Vec<Block>>,
    },
    Em(Vec<Inline>),
    Strong(Vec<Inline>),
    Strike(Vec<Inline>),
    Link {
        href: String,
        text: Vec<Inline>,
    },
    Image {
        src: String,
        alt: String,
    },
    Table {
        header: Vec<String>,
        rows: Vec<Vec<String>>,
        in_head: bool,
        cell: String,
    },
}

enum Finish {
    Paragraph,
    Heading,
}
enum Wrap {
    Em,
    Strong,
    Strike,
}

fn heading(level: HeadingLevel) -> u8 {
    match level {
        HeadingLevel::H1 => 1,
        HeadingLevel::H2 => 2,
        HeadingLevel::H3 => 3,
        HeadingLevel::H4 => 4,
        HeadingLevel::H5 => 5,
        HeadingLevel::H6 => 6,
    }
}

fn safe_href(raw: &str) -> String {
    let trimmed = raw.trim();
    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("https://") || lower.starts_with("http://") || lower.starts_with("mailto:")
    {
        trimmed.to_string()
    } else {
        String::new()
    }
}

fn push_inline(stack: &mut Vec<Frame>, root: &mut Vec<Block>, inline: Inline) {
    match stack.last_mut() {
        Some(
            Frame::Inlines(items)
            | Frame::Em(items)
            | Frame::Strong(items)
            | Frame::Strike(items)
            | Frame::Link { text: items, .. },
        ) => {
            items.push(inline);
        }
        Some(Frame::Heading(_, items)) => items.push(inline),
        Some(Frame::List {
            current: Some(items),
            ..
        }) => {
            if let Some(Block::Paragraph(existing)) = items.last_mut() {
                existing.push(inline);
            } else {
                items.push(Block::Paragraph(vec![inline]));
            }
        }
        _ => {
            // A loose inline outside a paragraph still needs a home.
            if let Inline::Text(text) = &inline {
                if text.trim().is_empty() {
                    return;
                }
            }
            root.push(Block::Paragraph(vec![inline]));
        }
    }
}

fn push_block(stack: &mut Vec<Frame>, root: &mut Vec<Block>, block: Block) {
    match stack.last_mut() {
        Some(Frame::Blocks(items)) => items.push(block),
        Some(Frame::List {
            current: Some(items),
            ..
        }) => items.push(block),
        _ => root.push(block),
    }
}

fn finish_inlines(stack: &mut Vec<Frame>, root: &mut Vec<Block>, kind: Finish) {
    let frame = stack.pop();
    let block = match (kind, frame) {
        (Finish::Paragraph, Some(Frame::Inlines(items))) => Block::Paragraph(items),
        (Finish::Heading, Some(Frame::Heading(level, items))) => Block::Heading(level, items),
        _ => return,
    };
    push_block(stack, root, block);
}

fn finish_wrap(stack: &mut Vec<Frame>, root: &mut Vec<Block>, kind: Wrap) {
    let frame = stack.pop();
    let inline = match (kind, frame) {
        (Wrap::Em, Some(Frame::Em(items))) => Inline::Em(items),
        (Wrap::Strong, Some(Frame::Strong(items))) => Inline::Strong(items),
        (Wrap::Strike, Some(Frame::Strike(items))) => Inline::Strike(items),
        _ => return,
    };
    push_inline(stack, root, inline);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_emphasis_code_and_links() {
        let blocks =
            parse_markdown("Hello **world** and `code`.\n\nSee [docs](https://example.com).");
        assert!(matches!(blocks[0], Block::Paragraph(_)));
        match &blocks[0] {
            Block::Paragraph(inlines) => {
                assert!(inlines.iter().any(|item| matches!(item, Inline::Strong(_))));
                assert!(inlines
                    .iter()
                    .any(|item| matches!(item, Inline::Code(text) if text == "code")));
            }
            _ => panic!("expected paragraph"),
        }
        match &blocks[1] {
            Block::Paragraph(inlines) => {
                assert!(inlines.iter().any(|item| matches!(item, Inline::Link { href, .. } if href == "https://example.com")));
            }
            _ => panic!("expected link paragraph"),
        }
    }

    #[test]
    fn keeps_fenced_code_and_drops_script_urls() {
        let blocks = parse_markdown("```rust\nfn main() {}\n```\n\n[x](javascript:alert(1))");
        match &blocks[0] {
            Block::Code { lang, code } => {
                assert_eq!(lang, "rust");
                assert!(code.contains("fn main"));
            }
            other => panic!("expected code, got {other:?}"),
        }
        match &blocks[1] {
            Block::Paragraph(inlines) => {
                assert!(inlines
                    .iter()
                    .any(|item| matches!(item, Inline::Link { href, .. } if href.is_empty())));
            }
            other => panic!("expected paragraph, got {other:?}"),
        }
    }

    #[test]
    fn lists_and_headings() {
        let blocks = parse_markdown("# Title\n\n- one\n- two");
        assert!(matches!(blocks[0], Block::Heading(1, _)));
        match &blocks[1] {
            Block::List { ordered, items } => {
                assert!(!ordered);
                assert_eq!(items.len(), 2);
            }
            other => panic!("expected list, got {other:?}"),
        }
    }
}
