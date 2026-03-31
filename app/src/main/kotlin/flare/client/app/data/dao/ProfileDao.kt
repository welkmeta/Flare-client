package flare.client.app.data.dao

import androidx.room.*
import flare.client.app.data.model.ProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY id ASC")
    fun getAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE subscriptionId IS NULL ORDER BY id ASC")
    fun getStandaloneProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE subscriptionId = :subId ORDER BY id ASC")
    fun getProfilesBySubscription(subId: Long): Flow<List<ProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity): Long

    @Query("UPDATE profiles SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE profiles SET isSelected = 1 WHERE id = :id")
    suspend fun selectProfile(id: Long)

    @Query("SELECT * FROM profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedProfile(): ProfileEntity?

    @Query("UPDATE profiles SET configJson = :configJson WHERE id = :id")
    suspend fun updateConfigJson(id: Long, configJson: String)

    @Query("UPDATE profiles SET name = :name, configJson = :configJson WHERE id = :id")
    suspend fun updateProfile(id: Long, name: String, configJson: String)
 
    @Delete
    suspend fun delete(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE subscriptionId = :subId")
    suspend fun deleteBySubscriptionId(subId: Long)

    @Query("DELETE FROM profiles WHERE subscriptionId IS NULL")
    suspend fun deleteStandaloneProfiles()
}
