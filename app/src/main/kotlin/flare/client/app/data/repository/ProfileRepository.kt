package flare.client.app.data.repository

import flare.client.app.data.dao.ProfileDao
import flare.client.app.data.dao.SubscriptionDao
import flare.client.app.data.model.ProfileEntity
import flare.client.app.data.model.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

class ProfileRepository(
    private val profileDao: ProfileDao,
    private val subscriptionDao: SubscriptionDao
) {

    fun getAllProfiles(): Flow<List<ProfileEntity>> = profileDao.getAllProfiles()
    fun getStandaloneProfiles(): Flow<List<ProfileEntity>> = profileDao.getStandaloneProfiles()
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>> = subscriptionDao.getAllSubscriptions()
    fun getProfilesBySubscription(subId: Long): Flow<List<ProfileEntity>> =
        profileDao.getProfilesBySubscription(subId)

    suspend fun insertProfile(profile: ProfileEntity): Long = profileDao.insert(profile)

    suspend fun insertSubscriptionWithProfiles(
        subscription: SubscriptionEntity,
        profiles: List<ProfileEntity>
    ) {
        val subId = subscriptionDao.insert(subscription)
        val withSubId = profiles.map { it.copy(subscriptionId = subId) }
        profileDao.insertAll(withSubId)
    }

    suspend fun deleteProfile(profile: ProfileEntity) = profileDao.delete(profile)
    suspend fun deleteSubscription(subscription: SubscriptionEntity) =
        subscriptionDao.delete(subscription)

    suspend fun deleteSubscriptionById(id: Long) =
        subscriptionDao.deleteById(id)

    suspend fun selectProfile(id: Long) {
        profileDao.clearSelection()
        profileDao.selectProfile(id)
    }

    suspend fun getSelectedProfile(): ProfileEntity? = profileDao.getSelectedProfile()
 
    suspend fun updateProfileConfig(id: Long, configJson: String) =
        profileDao.updateConfigJson(id, configJson)
 
    suspend fun updateSubscription(id: Long, name: String, url: String) =
        subscriptionDao.updateSubscription(id, name, url)

    suspend fun deleteProfilesBySubscription(subId: Long) =
        profileDao.deleteBySubscriptionId(subId)
}
