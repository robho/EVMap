package net.vonforst.evmap.api.availability

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import net.vonforst.evmap.model.ChargeLocation
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.time.Instant

interface NobilRealtimeProxyApi {
    @GET("{nobilId}")
    suspend fun getState(
        @Path("nobilId") nobilId: String,
    ): NobilLocationResponse

    companion object {
        fun create(client: OkHttpClient): NobilRealtimeProxyApi {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://nobil.example.com")
                .addConverterFactory(
                    MoshiConverterFactory.create(
                        Moshi.Builder().build()
                    )
                )
                .client(client)
                .build()
            return retrofit.create(NobilRealtimeProxyApi::class.java)
        }
    }
}

@JsonClass(generateAdapter = true)
data class NobilLocationResponse(
    val chargeports: List<NobilPort>
)

@JsonClass(generateAdapter = true)
data class NobilPort(
    val evseUid: String,
    val status: String,
    val timestamp: Long
)

class NobilAvailabilityDetector(client: OkHttpClient, baseUrl: String? = null) :
    BaseAvailabilityDetector(client) {
    val api = NobilRealtimeProxyApi.create(client)

    override suspend fun getAvailability(location: ChargeLocation): ChargeLocationStatus {
        // TODO: Parse and store internationalId in nobil data?
        val nobilId = when (location.address?.country) {
            "Denmark" -> "DAN"
            "Finland" -> "FIN"
            "Iceland" -> "ISL"
            "Norway" -> "NOR"
            "Sweden" -> "SWE"
            else -> throw AvailabilityDetectorException("no candidates found")
        } + "_" + "%05d".format(location.id)

        val response = api.getState(nobilId)

        // TODO: Code assumes cp.evseIds.size == cp.count
        return ChargeLocationStatus(
            location.chargepointsMerged.associateWith { cp ->
                cp.evseIds!!.map { evseId ->
                    when (response.chargeports.find { it.evseUid == evseId }?.status) {
                        "AVAILABLE" -> ChargepointStatus.AVAILABLE
                        "BLOCKED" -> ChargepointStatus.OCCUPIED
                        "CHARGING" -> ChargepointStatus.CHARGING
                        "INOPERATIVE" -> ChargepointStatus.FAULTED
                        "OUTOFORDER" -> ChargepointStatus.FAULTED
                        "PLANNED" -> ChargepointStatus.FAULTED
                        "REMOVED" -> ChargepointStatus.FAULTED
                        "RESERVED" -> ChargepointStatus.OCCUPIED
                        "UNKNOWN" -> ChargepointStatus.UNKNOWN
                        else -> ChargepointStatus.UNKNOWN
                    }
                }
            },
            "Nobil",
            location.chargepointsMerged.associateWith { cp ->
                cp.evseIds!!.map { it ?: "??" }
            },
            lastChange = location.chargepointsMerged.associateWith { cp ->
                cp.evseIds!!.map { evseId ->
                    response.chargeports.find {
                        it.evseUid == evseId
                    }?.let { Instant.ofEpochSecond(it.timestamp) }
                }
            }
        )
    }

    override fun isChargerSupported(charger: ChargeLocation): Boolean {
        return when (charger.dataSource) {
            // This nobil attribute isn't reliable:
            //   '21': {'attrtypeid': '21', 'attrname': 'Real-time information',
            //          'attrvalid': '2', 'trans': 'No', 'attrval': ''}
            //
            // .. but evseIds are required to match real-time data to chargepoints
            "nobil" -> charger.chargepoints.any { it.evseIds?.isNotEmpty() == true }
            else -> false
        }
    }
}
