package dev.argus.automation.di

import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.DispatchFrame
import dev.argus.automation.PersistentStateReader
import dev.argus.automation.foreground.ReceiverWorkLauncher
import dev.argus.automation.manual.ManualTokenStore
import dev.argus.automation.modes.EngineUserModeEventDispatcher
import dev.argus.automation.modes.ModeEntryPoints
import dev.argus.automation.modes.ModeWorkLauncher
import dev.argus.automation.modes.UserModeEditor
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomBackupState
import dev.argus.data.RoomStoredValueStore
import dev.argus.data.RoomUserModeStore
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.StoredModeActions
import dev.argus.engine.runtime.UserModeEventDispatcher
import dev.argus.engine.runtime.UserModeSwitcher
import javax.inject.Singleton

/** 0.8: valori conservati fra le esecuzioni e modalità dell'utente. */
@Module
@InstallIn(SingletonComponent::class)
object StoredModesModule {
    @Provides
    @Singleton
    fun storedValueStore(database: ArgusDatabase): RoomStoredValueStore = RoomStoredValueStore(database)

    @Provides
    @Singleton
    fun userModeStore(database: ArgusDatabase): RoomUserModeStore = RoomUserModeStore(database)

    @Provides
    @Singleton
    fun persistentStateReader(values: RoomStoredValueStore, modes: RoomUserModeStore): PersistentStateReader =
        PersistentStateReader(values, modes)

    @Provides
    @Singleton
    fun backupState(values: RoomStoredValueStore, modes: RoomUserModeStore): RoomBackupState =
        RoomBackupState(values, modes)

    /**
     * Il motore è pigro: Engine → esecutore → StoredModeActions → UserModeSwitcher → questo dispatcher → Engine.
     * Il cambio di modalità parte sul lanciatore condiviso (lease del servizio in primo piano), fuori dal chiamante.
     */
    @Provides
    @Singleton
    fun userModeEventDispatcher(
        engine: Lazy<Engine>,
        state: DeviceStateSnapshotProvider,
        frame: DispatchFrame,
        launcher: ReceiverWorkLauncher,
    ): UserModeEventDispatcher = EngineUserModeEventDispatcher(
        engine = { engine.get() },
        state = state,
        launcher = ModeWorkLauncher { block -> launcher.launch("user_mode", block) },
        frame = frame,
    )

    @Provides
    @Singleton
    fun userModeSwitcher(modes: RoomUserModeStore, dispatcher: UserModeEventDispatcher): UserModeSwitcher =
        UserModeSwitcher(modes, dispatcher)

    @Provides
    @Singleton
    fun storedModeActions(values: RoomStoredValueStore, switcher: UserModeSwitcher): StoredModeActions =
        StoredModeActions(values, switcher)

    @Provides
    @Singleton
    fun userModeEditor(modes: RoomUserModeStore, automations: AutomationStore, audit: AuditSink): UserModeEditor =
        UserModeEditor(modes, automations, audit)

    @Provides
    @Singleton
    fun modeEntryPoints(switcher: UserModeSwitcher, tokens: ManualTokenStore): ModeEntryPoints =
        ModeEntryPoints(switcher, tokens)
}
