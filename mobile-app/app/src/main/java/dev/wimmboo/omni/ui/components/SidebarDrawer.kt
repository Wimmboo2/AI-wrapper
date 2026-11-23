package dev.wimmboo.omni.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.OmniTheme

@Composable
fun SidebarDrawerContent(
    chats: List<Chat>,
    activeChatId: String?,
    incognito: Boolean,
    incognitoChat: Chat?,
    streamingChatIds: Set<String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCollapse: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportChats: () -> Unit,
    onImportChats: () -> Unit,
    onSelectChat: (String) -> Unit,
    onPinChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit,
) {
    val colors = OmniTheme.colors
    var renameTarget by remember { mutableStateOf<Chat?>(null) }
    var deleteTarget by remember { mutableStateOf<Chat?>(null) }

    Column(
        modifier = Modifier
            .width(284.dp)
            .fillMaxHeight()
            .background(colors.bgSidebar)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // .sidebar-top
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Omni",
                color = colors.text,
                fontFamily = InterFontFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            OmniIconButton(
                icon = OmniIcons.Sidebar,
                contentDescription = "Close sidebar",
                tint = colors.textMuted,
                onClick = onCollapse,
            )
        }

        // .nav
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SidebarNavItem(
                icon = OmniIcons.Plus,
                label = "New chat",
                onClick = {
                    onNewChat()
                    onCollapse()
                },
            )
            SidebarNavItem(
                icon = OmniIcons.Sliders,
                label = "Settings",
                onClick = {
                    onCollapse()
                    onOpenSettings()
                },
            )
        }

        Spacer(Modifier.height(6.dp))

        // .sidebar-search
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bgHover)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search chats and messages...",
                            color = colors.textFaint,
                            fontFamily = InterFontFamily,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = colors.text,
                            fontFamily = InterFontFamily,
                            fontSize = 12.5.sp,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = OmniIcons.Close,
                        contentDescription = "Clear search",
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSearchQueryChange("") }
                            .padding(2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // .section-head
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (incognito) "This session" else "Recents",
                color = colors.textFaint,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                OmniIconButton(
                    icon = OmniIcons.Download,
                    contentDescription = "Export chats",
                    tint = colors.textMuted,
                    onClick = onExportChats,
                )
                OmniIconButton(
                    icon = OmniIcons.Upload,
                    contentDescription = "Import chats",
                    tint = colors.textMuted,
                    onClick = onImportChats,
                )
            }
        }

        // .chat-list
        if (incognito) {
            if (incognitoChat != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.incognitoSoft)
                        .clickable { onCollapse() }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = OmniIcons.Incognito,
                        contentDescription = null,
                        tint = colors.incognito,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = incognitoChat.title,
                        color = colors.incognito,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            val q = searchQuery.trim().lowercase()
            val filtered = remember(chats, q) {
                if (q.isEmpty()) chats
                else chats.filter { c ->
                    c.title.lowercase().contains(q) ||
                        c.messages.any { it.content.lowercase().contains(q) }
                }
            }

            if (filtered.isEmpty()) {
                Text(
                    text = if (q.isNotEmpty()) "No chats match \u201c$q\u201d" else "No recent chats",
                    color = colors.textFaint,
                    fontFamily = InterFontFamily,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = filtered,
                        key = { it.id },
                        contentType = { "chat_row" },
                    ) { chat ->
                        val isActive = chat.id == activeChatId
                        val isStreaming = chat.id in streamingChatIds
                        val snippet = remember(chat, q) {
                            if (q.isEmpty() || chat.title.lowercase().contains(q)) null
                            else findSearchSnippet(chat, q)
                        }
                        SidebarChatRow(
                            chat = chat,
                            isActive = isActive,
                            isStreaming = isStreaming,
                            snippet = snippet,
                            onSelect = {
                                onSelectChat(chat.id)
                                onCollapse()
                            },
                            onPin = { onPinChat(chat.id) },
                            onRename = { renameTarget = chat },
                            onDelete = { deleteTarget = chat },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    // Rename Dialog
    renameTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = colors.bgModal,
            titleContentColor = colors.text,
            textContentColor = colors.textSecondary,
            title = {
                Text(
                    text = "Rename chat",
                    fontFamily = InterFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.bgInputField)
                        .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 100) draft = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = colors.text,
                            fontFamily = InterFontFamily,
                            fontSize = 13.5.sp,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameChat(target.id, draft)
                        renameTarget = null
                    },
                ) {
                    Text("Save", color = colors.accent, fontFamily = InterFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = colors.textSecondary, fontFamily = InterFontFamily)
                }
            },
        )
    }

    // Delete Confirmation Dialog
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.bgModal,
            titleContentColor = colors.text,
            textContentColor = colors.textSecondary,
            title = {
                Text(
                    text = "Delete chat?",
                    fontFamily = InterFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Text(
                    text = "\u201c${target.title}\u201d will be permanently removed from this device.",
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteChat(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete", color = colors.danger, fontFamily = InterFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = colors.textSecondary, fontFamily = InterFontFamily)
                }
            },
        )
    }
}

@Composable
private fun SidebarNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary.copy(alpha = 0.9f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = colors.textSecondary,
            fontFamily = InterFontFamily,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SidebarChatRow(
    chat: Chat,
    isActive: Boolean,
    isStreaming: Boolean,
    snippet: String?,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) colors.bgActive else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.title,
                color = if (isActive) colors.text else colors.textMuted,
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snippet != null) {
                Text(
                    text = snippet,
                    color = colors.textFaint.copy(alpha = 0.85f),
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isStreaming) {
            val inf = rememberInfiniteTransition(label = "streamDot")
            val dotAlpha by inf.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "dotAlpha",
            )
            Box(
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(7.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }

        SmallRowAction(
            icon = OmniIcons.Pin,
            contentDescription = if (chat.pinnedAt != null) "Unpin" else "Pin",
            tint = if (chat.pinnedAt != null) colors.accent else colors.textFaint,
            onClick = onPin,
        )
        SmallRowAction(
            icon = OmniIcons.Edit,
            contentDescription = "Rename",
            tint = colors.textFaint,
            onClick = onRename,
        )
        SmallRowAction(
            icon = OmniIcons.Trash,
            contentDescription = "Delete",
            tint = colors.textFaint,
            onClick = onDelete,
        )
    }
}

@Composable
private fun SmallRowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
fun OmniIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

private val SNIPPET_WHITESPACE_RE = Regex("\\s+")

private fun findSearchSnippet(chat: Chat, q: String): String? {
    for (m in chat.messages) {
        val idx = m.content.lowercase().indexOf(q)
        if (idx != -1) {
            val start = (idx - 20).coerceAtLeast(0)
            val end = (idx + q.length + 30).coerceAtMost(m.content.length)
            val body = m.content.substring(start, end).replace(SNIPPET_WHITESPACE_RE, " ")
            return (if (start > 0) "..." else "") + body + (if (end < m.content.length) "..." else "")
        }
    }
    return null
}
