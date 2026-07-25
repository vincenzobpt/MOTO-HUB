// CORE flavor: the original local connect path (Wi-Fi join + hudlib EasyConn discovery). This is
// exactly what HubViewModel.connectAndDiscover() used to do inline — behaviour unchanged for CORE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.MotorcycleProfile

/** Flavor factory resolved at compile time (a sibling exists in src/pro). */
fun createTBoxSessionEstablisher(context: Context): TBoxSessionEstablisher =
    LocalTBoxSessionEstablisher(context)

class LocalTBoxSessionEstablisher(private val context: Context) : TBoxSessionEstablisher {
    override val transport: TBoxTransport = RideDaemonTransport(context)
    override val networkConnector: TBoxNetworkConnector = TBoxNetworkConnector(context)
    private val capabilityStore = TBoxCapabilityStore(context)

    override suspend fun connectAndInstall(
        profile: MotorcycleProfile,
        onNetworkConnected: suspend () -> Unit,
        onNetworkError: (Throwable) -> Unit,
        onDiscoveryError: (Throwable) -> Unit
    ): Boolean {
        val connected = TBoxLinkResolver.connect(context, networkConnector, profile)
        val link = connected.getOrElse { onNetworkError(it); return false }
        onNetworkConnected()
        transport.configureProtocolProfile(
            TBoxModelProfile.resolve(
                profile.modelId,
                null,
                ProfileOverride.byKey(profile.profileOverrideKey)
            )
        )
        val discovered = transport.discover(link, profile.modelId)
        val host = discovered.getOrElse {
            transport.stop()
            link.disconnect()
            networkConnector.disconnect()
            TBoxSessionRegistry.clear()
            onDiscoveryError(it)
            return false
        }
        capabilityStore.recordDiscovery(profile, host)
        TBoxSessionRegistry.install(
            TBoxSessionHandle(transport, host, networkConnector, profile, link)
        )
        return true
    }
}
