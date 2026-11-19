# Omni mobile device checklist

Run this on a real Android or iOS phone (`dx serve --android` or `dx serve --ios` from `mobile-app/app`). Skip a step when you do not have that key. There is no voice input, voice output, or voice setting. If any of those show up, stop and file it.

Start from a fresh install, or clear the app's data first.

## Fresh install and first launch

- [ ] The app opens on Chats. The empty list says to start one with New.
- [ ] Chat shows "Send a message to start. Pick a model and add your API key in Settings."
- [ ] Settings opens. API endpoint is `https://omni-api.wimmboo.workers.dev`. The API key field is empty. Keys stay hidden until Show keys.

## Endpoint, key, streaming, and Stop

- [ ] In Settings, keep the hosted endpoint or set your own Worker. Paste the provider API key. If the Worker is locked, also fill Proxy key and App origin.
- [ ] Pick the provider and model in Settings, or with the model chip on the composer.
- [ ] On Chat, type a short message and tap Send. The reply arrives in pieces. A status line shows before the first text.
- [ ] While it is still writing, tap Stop. Generation stops, Send comes back, and the partial reply stays in the thread.

## New chat, switch, pin, rename, delete

- [ ] Chats → New opens an empty thread.
- [ ] Send once so the chat has a title. Switch to another chat, then back. The thread is the one you left.
- [ ] Pin. The row shows Pinned and sorts above unpinned chats. Unpin removes the tag.
- [ ] On the thread, tap Rename, change the title, and Save. The new title shows on the thread and in the list.
- [ ] Chats → Delete, then Delete again to confirm. Keep cancels. The chat is gone.

## Attach an image and a text file

Use a model that accepts images.

- [ ] Tap Attach. Pick one image and one text file (`.txt`, `.md`, `.py`, or similar). Both appear as pills. The image has a thumbnail.
- [ ] Send. The image and the file name show on your bubble, and the reply can refer to the picture.
- [ ] On a model without vision, an image is refused with "This model doesn't support images." and is not added.

## Slash menu

- [ ] Type `/`. The menu lists `/code`, `/search`, `/web`, `/explain`, `/summarize`, `/refactor`, and `/translate`.
- [ ] `/code` turns the Code chip on. `/web` turns Search on. `/explain`, `/summarize`, `/refactor`, and `/translate` leave a prefix chip. Send a follow-up and confirm the reply follows that command. The prefix is not written into your bubble.
- [ ] Type `/search` plus a question and pick the command. It sends immediately, with Search on.

## Regenerate and branch switching

- [ ] On a finished reply, tap Regenerate. A new reply replaces the visible one.
- [ ] Prev, Next, and a `1/2` counter appear. Both replies are still there. Switching does not delete the other one.

## Edit an earlier message

- [ ] On your message, tap Edit. The banner says "Editing an earlier message" and the composer button says Save.
- [ ] Change the text and tap Save. A new reply is generated.
- [ ] Prev and Next on that user message switch the whole later thread. The old wording is still on the other branch.
- [ ] Regenerate on the new branch still cycles reply branches.

## Search

- [ ] Tavily: Settings → Web search, mode `on` (or the Search chip), and a Tavily API key. Send a question that needs the web. The reply has a sources block.
- [ ] Native: use Anthropic, Google, or OpenRouter with no Tavily key. The reply still cites sources, and the Tavily key is not required.
- [ ] On any other provider with no Tavily key, the chat still answers. It does not fail.

## Code and agent mode

Skip when you have no E2B or Novita key.

- [ ] Settings → Code sandbox: choose `e2b` or `novita` and paste that Sandbox API key.
- [ ] Turn on Agent. The thread header says Agent.
- [ ] Ask for a short Python or shell command. A tool step shows the command and its output. The sandbox key is not shown in the thread.

## Settings export and import

- [ ] Settings → Export settings. The toast says "Settings exported. The file includes your API keys." The file is named `omni-settings-YYYY-MM-DD.json`.
- [ ] Open it only in a private place. It contains the API key. Do not paste it into chat, a log, or a commit.
- [ ] Change the key in the app, then Import that file. Confirm "Replace the settings on this device with this file?" and tap Replace. The toast says "Settings imported." The previous key is back.

## Chat export and import

- [ ] Export chats. The toast says "Chats exported." The file is named `omni-chats-YYYY-MM-DD.json`.
- [ ] The file has the chats and does not contain the API key.
- [ ] Delete a chat, or use a second install. Import the file, confirm "Replace the chats on this device with this file?", and tap Replace. The toast says "Chats imported." The chats are back. Settings and keys are unchanged.

## Metrics sheet

- [ ] After a reply, a one-line summary sits above the composer (t/s, tokens, TTFT, duration — whatever the provider reported). It does not cover the thread.
- [ ] Tap it. The sheet shows numbers such as Gen t/s, Prefill t/s, TTFT, Total, Input, Output, and Context usage with a bar.
- [ ] A stop note appears only when the turn was cut off: "truncated — hit the output cap", "stopped by a content filter", "hit a stop sequence", or "the provider ended the turn on an error".
- [ ] Tap the summary again. The sheet closes and you can keep typing.

## Incognito stays off disk

- [ ] Chats → Incognito. The banner says "Incognito — this chat stays on this screen only."
- [ ] Send a message. Force-quit and reopen. That chat is gone. Incognito is off. Other chats are still there.

## Restart keeps chats and settings

- [ ] Force-quit and reopen. Chats, titles, pins, the endpoint, and the API key are still there.
- [ ] Send one more message on an existing chat. The saved key still works.
