/*
 *     Pupil, Hitomi.la viewer for Android
 *     Copyright (C) 2019  tom5079
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.pawchive.media.reader

import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Volume keys are delivered to the activity, never to the composition, so a
 * screen that can turn a page leaves what to do here for the activity to call.
 *
 * Screens stack: the topmost one decides, and a screen drawn over another
 * uncovers it again when it leaves. Registering `null` says the keys belong to
 * the system on this screen, so a full screen page cannot silently turn the
 * list underneath it.
 */
class VolumePageTurnDispatcher {

    class Registration internal constructor(internal var onTurn: ((Int) -> Unit)?)

    private val registrations = mutableListOf<Registration>()

    /** Turns [delta] pages, answering whether a screen took the key. */
    fun turn(delta: Int): Boolean {
        val onTurn = registrations.lastOrNull()?.onTurn ?: return false

        onTurn(delta)

        return true
    }

    internal fun add(registration: Registration) = registrations.add(registration)

    internal fun remove(registration: Registration) = registrations.remove(registration)
}

val LocalVolumePageTurn = staticCompositionLocalOf { VolumePageTurnDispatcher() }

/**
 * Hands [onTurn] to the activity for as long as this screen is composed, or
 * takes the keys out of the app's hands entirely when it is `null`.
 */
@Composable
fun VolumePageTurnHandler(onTurn: ((Int) -> Unit)?) {
    val dispatcher = LocalVolumePageTurn.current
    val covered = !androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val registration = remember { VolumePageTurnDispatcher.Registration(onTurn) }

    SideEffect { registration.onTurn = if (covered) null else onTurn }

    DisposableEffect(dispatcher, registration) {
        dispatcher.add(registration)

        onDispose { dispatcher.remove(registration) }
    }
}
