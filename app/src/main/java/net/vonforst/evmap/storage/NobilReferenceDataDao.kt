package net.vonforst.evmap.storage

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.vonforst.evmap.api.nobil.*
import net.vonforst.evmap.viewmodel.Status
import java.time.Duration
import java.time.Instant

@Entity
data class NobilNetwork(@PrimaryKey val name: String)

@Dao
abstract class NobilReferenceDataDao {
    // NETWORKS
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(vararg networks: NobilNetwork)

    @Query("DELETE FROM nobilnetwork")
    abstract fun deleteAllNetworks()

    @Transaction
    open suspend fun updateNetworks(networks: List<NobilNetwork>) {
        deleteAllNetworks()
        for (network in networks) {
            insert(network)
        }
    }

    @Query("SELECT * FROM nobilnetwork")
    abstract fun getAllNetworks(): LiveData<List<NobilNetwork>>
}


class NobilReferenceDataRepository(private val dao: NobilReferenceDataDao) {
    fun getReferenceData(): LiveData<NobilReferenceData> {
        val networks = dao.getAllNetworks()
        return MediatorLiveData<NobilReferenceData>().apply {
            value = null
            addSource(networks) { _ ->
                val n = networks.value ?: return@addSource
                value = NobilReferenceData(n.map { it.name })
            }
        }
    }

    suspend fun updateReferenceData(refData: NobilReferenceData) {
        dao.updateNetworks(refData.networks.map { NobilNetwork(it) })
    }
}
