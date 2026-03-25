package flare.client.app.data.model

/**
 * UI display models (not database entities) used by the adapter.
 */
sealed class DisplayItem {
    enum class CornerType {
        ALL, TOP, BOTTOM, NONE
    }

    data class SubscriptionItem(
        val entity: SubscriptionEntity,
        val profiles: List<ProfileEntity>,
        val isExpanded: Boolean,
        val cornerType: CornerType = CornerType.ALL
    ) : DisplayItem()

    data class ProfileItem(
        val entity: ProfileEntity,
        val isSelected: Boolean,
        val pingState: PingState = PingState.None,
        val cornerType: CornerType = CornerType.NONE
    ) : DisplayItem()
}

sealed class PingState {
    object None : PingState()
    object Loading : PingState()
    data class Result(val latency: Long, val isError: Boolean = false) : PingState()
}
