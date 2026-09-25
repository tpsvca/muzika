package lt.a777.muzika.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Tells the system there is a queue.
 *
 * The real queue lives in [MuzikaPlayer], not in ExoPlayer: each track is
 * resolved to a stream URL only when it is about to play, so ExoPlayer holds
 * exactly one item at a time. Left alone it therefore reports "no next track"
 * and the notification and lock screen lose their skip buttons. This wrapper
 * advertises the commands and routes them back to the real queue.
 */
class QueuePlayer(inner: Player) : ForwardingPlayer(inner) {

    private val skipCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*skipCommands).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in skipCommands || super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = MuzikaPlayer.hasNext
    override fun hasPreviousMediaItem(): Boolean = MuzikaPlayer.hasPrevious

    override fun seekToNext() = MuzikaPlayer.next()
    override fun seekToNextMediaItem() = MuzikaPlayer.next()
    override fun seekToPrevious() = MuzikaPlayer.previous()
    override fun seekToPreviousMediaItem() = MuzikaPlayer.previous()
}
