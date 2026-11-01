package com.machankura.dedun.compose.ui.composable.widgets.content

import com.machankura.dedun.compose.database.model.NostrEvent

data class NoteActions(
    val onReply: (NostrEvent) -> Unit = {},
    val onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    val onRepost: (NostrEvent) -> Unit = {},
    val onQuote: (NostrEvent) -> Unit = {},
    val onZap: (NostrEvent) -> Unit = {},
    val onProfileClick: (String) -> Unit = {},
    val onNoteClick: (String) -> Unit = {},
    val onAddToList: (String) -> Unit = {},
    val onFollowAuthor: (String) -> Unit = {},
    val onBlockAuthor: (String) -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onDelete: (String, Int) -> Unit = { _, _ -> },
    val isFollowing: (String) -> Boolean = { false },
    val userPubkey: String? = null,
//    val nip05Repo: Nip05Repository? = null,
    val onHashtagClick: ((String) -> Unit)? = null,
    val onRelayClick: ((String) -> Unit)? = null,
    val onArticleClick: ((Int, String, String) -> Unit)? = null,
    val onPayInvoice: (suspend (String) -> Boolean)? = null,
    val onGroupRoom: ((String, String) -> Unit)? = null,
    val onLiveStreamClick: ((hostPubkey: String, dTag: String, relayHint: String?) -> Unit)? = null,
//    val groupMetadataProvider: ((String, String) -> Nip29.GroupMetadata?)? = null,
//    val fetchGroupPreview: (suspend (String, String) -> GroupPreview?)? = null,
    val onAddEmojiSet: ((pubkey: String, dTag: String) -> Unit)? = null,
    val onRemoveEmojiSet: ((pubkey: String, dTag: String) -> Unit)? = null,
    val isEmojiSetAdded: ((pubkey: String, dTag: String) -> Boolean)? = null,
    val onPollVote: (String, List<String>) -> Unit = { _, _ -> },
)
