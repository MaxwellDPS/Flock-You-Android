package com.flockyou.di

import android.content.Context
import com.flockyou.data.NetworkSettingsRepository
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.data.OuiSettingsRepository
import com.flockyou.data.SecuritySettingsRepository
import com.flockyou.service.ScanningServiceConnection
import com.flockyou.data.oui.OuiDownloader
import com.flockyou.data.oui.OuiLookupService
import com.flockyou.data.repository.DetectionDao
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.FlockYouDatabase
import com.flockyou.data.repository.OuiDao
import com.flockyou.data.repository.OuiRepository
import com.flockyou.network.OrbotHelper
import com.flockyou.network.TorAwareHttpClient
import com.flockyou.privilege.PrivilegeMode
import com.flockyou.privilege.PrivilegeModeDetector
import com.flockyou.scanner.IBluetoothScanner
import com.flockyou.scanner.ICellularScanner
import com.flockyou.scanner.IWifiScanner
import com.flockyou.scanner.ScannerBundle
import com.flockyou.scanner.ScannerCapabilities
import com.flockyou.scanner.ScannerFactory
import com.flockyou.scanner.ScannerModeHelper
import com.flockyou.security.AppLockManager
import com.flockyou.security.DuressAuthenticator
import com.flockyou.security.FailedAuthWatcher
import com.flockyou.security.NukeManager
import com.flockyou.security.SecureKeyManager
import com.flockyou.service.nuke.GeofenceWatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ================================================================
    // Privilege Mode & Scanner Factory
    // ================================================================

    @Provides
    @Singleton
    fun providePrivilegeMode(@ApplicationContext context: Context): PrivilegeMode {
        return PrivilegeModeDetector.detect(context)
    }

    @Provides
    @Singleton
    fun provideScannerFactory(@ApplicationContext context: Context): ScannerFactory {
        return ScannerFactory.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideScannerCapabilities(scannerFactory: ScannerFactory): ScannerCapabilities {
        return scannerFactory.getCapabilities()
    }

    @Provides
    @Singleton
    fun provideWifiScanner(scannerFactory: ScannerFactory): IWifiScanner {
        return scannerFactory.createWifiScanner()
    }

    @Provides
    @Singleton
    fun provideBluetoothScanner(scannerFactory: ScannerFactory): IBluetoothScanner {
        return scannerFactory.createBluetoothScanner()
    }

    @Provides
    @Singleton
    fun provideCellularScanner(scannerFactory: ScannerFactory): ICellularScanner {
        return scannerFactory.createCellularScanner()
    }

    @Provides
    @Singleton
    fun provideScannerBundle(
        wifiScanner: IWifiScanner,
        bluetoothScanner: IBluetoothScanner,
        cellularScanner: ICellularScanner,
        scannerFactory: ScannerFactory
    ): ScannerBundle {
        return ScannerBundle(wifiScanner, bluetoothScanner, cellularScanner, scannerFactory)
    }

    @Provides
    @Singleton
    fun provideScannerModeHelper(@ApplicationContext context: Context): ScannerModeHelper {
        return ScannerModeHelper(context)
    }

    // ================================================================
    // Database & Repositories
    // ================================================================

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FlockYouDatabase {
        return FlockYouDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideDetectionDao(database: FlockYouDatabase): DetectionDao {
        return database.detectionDao()
    }

    @Provides
    @Singleton
    fun provideDetectionRepository(detectionDao: DetectionDao): DetectionRepository {
        return DetectionRepository(detectionDao)
    }

    @Provides
    @Singleton
    fun provideOuiDao(database: FlockYouDatabase): OuiDao {
        return database.ouiDao()
    }

    @Provides
    @Singleton
    fun provideOuiRepository(ouiDao: OuiDao): OuiRepository {
        return OuiRepository(ouiDao)
    }

    @Provides
    @Singleton
    fun provideOuiSettingsRepository(@ApplicationContext context: Context): OuiSettingsRepository {
        return OuiSettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideOuiLookupService(ouiRepository: OuiRepository): OuiLookupService {
        return OuiLookupService(ouiRepository)
    }

    // ================================================================
    // Security
    // ================================================================

    @Provides
    @Singleton
    fun provideSecureKeyManager(@ApplicationContext context: Context): SecureKeyManager {
        return SecureKeyManager(context)
    }

    @Provides
    @Singleton
    fun provideSecuritySettingsRepository(@ApplicationContext context: Context): SecuritySettingsRepository {
        return SecuritySettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideAppLockManager(
        @ApplicationContext context: Context,
        securitySettingsRepository: SecuritySettingsRepository,
        secureKeyManager: SecureKeyManager,
        duressAuthenticator: DuressAuthenticator,
        failedAuthWatcher: FailedAuthWatcher
    ): AppLockManager {
        return AppLockManager(context, securitySettingsRepository, secureKeyManager, duressAuthenticator, failedAuthWatcher)
    }

    // Network Settings
    @Provides
    @Singleton
    fun provideNetworkSettingsRepository(@ApplicationContext context: Context): NetworkSettingsRepository {
        return NetworkSettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideOrbotHelper(@ApplicationContext context: Context): OrbotHelper {
        return OrbotHelper(context)
    }

    @Provides
    @Singleton
    fun provideTorAwareHttpClient(
        networkSettingsRepository: NetworkSettingsRepository,
        orbotHelper: OrbotHelper
    ): TorAwareHttpClient {
        return TorAwareHttpClient(networkSettingsRepository, orbotHelper)
    }

    @Provides
    @Singleton
    fun provideOuiDownloader(
        @ApplicationContext context: Context,
        torAwareHttpClient: TorAwareHttpClient
    ): OuiDownloader {
        return OuiDownloader(context, torAwareHttpClient)
    }

    // ================================================================
    // Nuke/Emergency Wipe System
    // ================================================================

    @Provides
    @Singleton
    fun provideNukeSettingsRepository(@ApplicationContext context: Context): NukeSettingsRepository {
        return NukeSettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideNukeManager(
        @ApplicationContext context: Context,
        nukeSettingsRepository: NukeSettingsRepository
    ): NukeManager {
        return NukeManager(context, nukeSettingsRepository)
    }

    @Provides
    @Singleton
    fun provideFailedAuthWatcher(
        @ApplicationContext context: Context,
        nukeSettingsRepository: NukeSettingsRepository,
        nukeManager: NukeManager
    ): FailedAuthWatcher {
        return FailedAuthWatcher(context, nukeSettingsRepository, nukeManager)
    }

    @Provides
    @Singleton
    fun provideDuressAuthenticator(
        @ApplicationContext context: Context,
        nukeSettingsRepository: NukeSettingsRepository,
        nukeManager: NukeManager
    ): DuressAuthenticator {
        return DuressAuthenticator(context, nukeSettingsRepository, nukeManager)
    }

    @Provides
    @Singleton
    fun provideGeofenceWatcher(
        @ApplicationContext context: Context,
        nukeSettingsRepository: NukeSettingsRepository,
        nukeManager: NukeManager
    ): GeofenceWatcher {
        return GeofenceWatcher(context, nukeSettingsRepository, nukeManager)
    }

    // ================================================================
    // IPC Service Connection
    // ================================================================

    @Provides
    @Singleton
    fun provideScanningServiceConnection(
        @ApplicationContext context: Context
    ): ScanningServiceConnection {
        return ScanningServiceConnection(context).also {
            // Auto-bind when the connection is created
            it.bind()
        }
    }
}
