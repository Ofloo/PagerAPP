package io.ofloo.pagerapp.shared

/**
 * What a contact is, which decides how the app treats it.
 *
 * - [PERSON] — a two-way chat. Reading a message *is* the acknowledgement, so there is no manual
 *   ACK: the app acks inbound channel messages as it fetches them, and the chat marks them read.
 * - [SYSTEM] — an alarm/notification source. Messages stay in the notification list and offer a
 *   manual **ACK** (with an optional note) whenever the page carries an `ack_uuid`.
 */
enum class ContactKind { PERSON, SYSTEM }

/**
 * A person you talk to over a dedicated PagerMCP mailbox.
 *
 * The pair shares one channel mailbox, identified by [uuid] (which is also the bearer credential
 * for it). Your own private mailbox is never involved, so a contact can only ever read this
 * channel — not the pages your systems send to you. Throwing a spammed channel away is just
 * dropping the contact and inviting again.
 *
 * Identity is per channel, never the private mailbox:
 * - [mySenderId] is the random id this device stamps as `from` on its own messages here.
 * - [senderId] is the peer's id, learned from the QR code or from their first message.
 */
data class Contact(
    val name: String,
    val serverUrl: String,
    val uuid: String,
    val senderId: String = "",
    val mySenderId: String = "",
    /** The peer's armored OpenPGP public key, learned automatically from their first message. */
    val publicKey: String = "",
    /** Chat partner or alarm source; see [ContactKind]. */
    val kind: ContactKind = ContactKind.PERSON,
    /** Which of the configured chat levels this chat uses (see [PagerConfig.chatLevels]). */
    val level: String = LEVEL_NORMAL,
    /**
     * The peer's receiving mailbox: everything we send goes here. Empty on legacy contacts, which
     * shared one mailbox both ways — [outbox] then falls back to [uuid].
     */
    val sendTo: String = "",
    /**
     * Our own receiving mailbox for this chat: the peer writes here and only we read it. Empty on
     * legacy contacts; [inbox] then falls back to [uuid].
     */
    val receiveOn: String = "",
) {
    /** Where to `POST /notify` for this chat. */
    val outbox: String get() = sendTo.ifBlank { uuid }

    /** Which mailbox to poll and ack; one reader per mailbox, so nobody acks our messages away. */
    val inbox: String get() = receiveOn.ifBlank { uuid }

    /** Stable local key; also separates the same channel on different servers. */
    val id: String get() = "$serverUrl|$uuid"
    /** True until we know who is on the other end (we invited, they have not replied yet). */
    val awaitingReply: Boolean get() = senderId.isBlank()
}
