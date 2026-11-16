package dev.argus.automation.di

import android.content.Context
import dev.argus.automation.secrets.SecretDeletion
import dev.argus.brain.AndroidSecretVault
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.secrets.SecretVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 0.8: the phone's secrets vault and what deleting a secret does to the rules that use it. */
@Module
@InstallIn(SingletonComponent::class)
object SecretsModule {
    @Provides
    @Singleton
    fun secretVault(@ApplicationContext context: Context): SecretVault = AndroidSecretVault(context)

    @Provides
    @Singleton
    fun secretDeletion(vault: SecretVault, automations: AutomationStore, audit: AuditSink): SecretDeletion =
        SecretDeletion(vault, automations, audit)
}
