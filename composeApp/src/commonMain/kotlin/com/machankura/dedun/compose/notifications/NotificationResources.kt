package com.machankura.dedun.compose.notifications

import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.action_mark_read
import dedun.composeapp.generated.resources.action_reply
import dedun.composeapp.generated.resources.action_reply_hint
import dedun.composeapp.generated.resources.notification_channel_group_activity
import dedun.composeapp.generated.resources.notification_channel_group_activity_description
import dedun.composeapp.generated.resources.notification_channel_messages
import dedun.composeapp.generated.resources.notification_channel_messages_description
import dedun.composeapp.generated.resources.notification_channel_needs_you
import dedun.composeapp.generated.resources.notification_channel_needs_you_description
import dedun.composeapp.generated.resources.notification_more
import dedun.composeapp.generated.resources.notification_new_message
import dedun.composeapp.generated.resources.notification_note_to_self
import dedun.composeapp.generated.resources.notification_someone
import dedun.composeapp.generated.resources.notification_you
import org.jetbrains.compose.resources.getString

/** The catalogue's words for notifications, read outside any composition. */
object NotificationResources {

    suspend fun strings(): NotificationStrings {
        val more = getString(Res.string.notification_more)
        return NotificationStrings(
            newMessage = getString(Res.string.notification_new_message),
            noteToSelf = getString(Res.string.notification_note_to_self),
            unknownSender = getString(Res.string.notification_someone),
            moreMessages = { count -> more.replace("%1\$d", count.toString()) },
        )
    }

    suspend fun channelNames(): NotificationChannelNames = NotificationChannelNames(
        messages = getString(Res.string.notification_channel_messages),
        messagesDescription = getString(Res.string.notification_channel_messages_description),
        needsYou = getString(Res.string.notification_channel_needs_you),
        needsYouDescription = getString(Res.string.notification_channel_needs_you_description),
        groupActivity = getString(Res.string.notification_channel_group_activity),
        groupActivityDescription = getString(Res.string.notification_channel_group_activity_description),
        you = getString(Res.string.notification_you),
        newMessage = getString(Res.string.notification_new_message),
        markRead = getString(Res.string.action_mark_read),
        reply = getString(Res.string.action_reply),
        replyHint = getString(Res.string.action_reply_hint),
    )
}
