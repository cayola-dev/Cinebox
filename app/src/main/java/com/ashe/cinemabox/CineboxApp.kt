package com.ashe.cinemabox

import android.app.Application
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.LibraryRepository
import com.ashe.cinemabox.library.LibrarySettings
import com.ashe.cinemabox.music.MusicIdentifier
import com.ashe.cinemabox.telegram.TdClient

class CineboxApp : Application() {
    val telegram: TdClient by lazy { TdClient(this) }
    val library: LibraryRepository by lazy { LibraryRepository(telegram) }
    val files: FileRepository by lazy { FileRepository(telegram) }
    val librarySettings: LibrarySettings by lazy { LibrarySettings(this) }
    val musicIdentifier: MusicIdentifier by lazy { MusicIdentifier(this, files, librarySettings) }
}
