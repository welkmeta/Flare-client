package flare.client.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.appcompat.widget.PopupMenu
import flare.client.app.R
import flare.client.app.data.model.DisplayItem
import flare.client.app.data.model.PingState
import flare.client.app.data.model.ProfileEntity
import flare.client.app.data.model.SubscriptionEntity
import flare.client.app.databinding.ItemProfileBinding
import flare.client.app.databinding.ItemSubscriptionBinding
import org.json.JSONObject
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import androidx.core.content.ContextCompat

class ProfileAdapter(
    private val onProfileClick: (ProfileEntity) -> Unit,
    private val onSubscriptionToggle: (SubscriptionEntity) -> Unit,
    private val onSubscriptionDelete: (Long) -> Unit,
    private val onSubscriptionSpeedTest: (Long) -> Unit,
    private val onSubscriptionOptions: (Long) -> Unit,
    private val onEditProfileJson: (ProfileEntity) -> Unit,
    private val onEditSubscriptionJson: (SubscriptionEntity) -> Unit,
    private val onSubscriptionUpdate: (SubscriptionEntity) -> Unit,
    private val onProfileLongClick: (ProfileEntity) -> Unit,
    private val onProfileDelete: (ProfileEntity) -> Unit,
) : ListAdapter<DisplayItem, RecyclerView.ViewHolder>(DIFF) {

    var accentColor: Int? = null

    companion object {
        const val TYPE_SUBSCRIPTION = 0
        const val TYPE_PROFILE = 1

        private val DIFF = object : DiffUtil.ItemCallback<DisplayItem>() {
            override fun areItemsTheSame(old: DisplayItem, new: DisplayItem): Boolean {
                return when {
                    old is DisplayItem.SubscriptionItem && new is DisplayItem.SubscriptionItem ->
                        old.entity.id == new.entity.id
                    old is DisplayItem.ProfileItem && new is DisplayItem.ProfileItem ->
                        old.entity.id == new.entity.id
                    else -> false
                }
            }
            override fun areContentsTheSame(old: DisplayItem, new: DisplayItem) = old == new

            override fun getChangePayload(oldItem: DisplayItem, newItem: DisplayItem): Any? {
                if (oldItem is DisplayItem.ProfileItem && newItem is DisplayItem.ProfileItem) {
                    val oldBase = oldItem.copy(pingState = PingState.None, isSelected = false, cornerType = DisplayItem.CornerType.NONE)
                    val newBase = newItem.copy(pingState = PingState.None, isSelected = false, cornerType = DisplayItem.CornerType.NONE)
                    if (oldBase == newBase) {
                        val payloads = mutableSetOf<String>()
                        if (oldItem.pingState != newItem.pingState) payloads.add("PING")
                        if (oldItem.isSelected != newItem.isSelected) payloads.add("SELECTION")
                        if (oldItem.cornerType != newItem.cornerType) payloads.add("CORNERS")
                        return if (payloads.isNotEmpty()) payloads else null
                    }
                } else if (oldItem is DisplayItem.SubscriptionItem && newItem is DisplayItem.SubscriptionItem) {
                    val oldBase = oldItem.copy(isExpanded = false, isRefreshing = false, cornerType = DisplayItem.CornerType.NONE, entity = oldItem.entity.copy(upload = 0, download = 0, total = 0))
                    val newBase = newItem.copy(isExpanded = false, isRefreshing = false, cornerType = DisplayItem.CornerType.NONE, entity = newItem.entity.copy(upload = 0, download = 0, total = 0))
                    if (oldBase == newBase) {
                        val payloads = mutableSetOf<String>()
                        if (oldItem.isExpanded != newItem.isExpanded) payloads.add("EXPAND")
                        if (oldItem.isRefreshing != newItem.isRefreshing) payloads.add("REFRESH")
                        if (oldItem.entity.upload != newItem.entity.upload || oldItem.entity.download != newItem.entity.download || oldItem.entity.total != newItem.entity.total) payloads.add("TRAFFIC")
                        if (oldItem.cornerType != newItem.cornerType) payloads.add("CORNERS")
                        return if (payloads.isNotEmpty()) payloads else null
                    }
                }
                return "FULL_BIND"
            }
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is DisplayItem.SubscriptionItem -> TYPE_SUBSCRIPTION
        is DisplayItem.ProfileItem -> TYPE_PROFILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SUBSCRIPTION -> SubscriptionVH(
                ItemSubscriptionBinding.inflate(inflater, parent, false)
            )
            else -> ProfileVH(
                ItemProfileBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DisplayItem.SubscriptionItem ->
                (holder as SubscriptionVH).bind(
                    item,
                    onSubscriptionToggle,
                    onSubscriptionDelete,
                    onSubscriptionSpeedTest,
                    onSubscriptionOptions
                )
            is DisplayItem.ProfileItem ->
                (holder as ProfileVH).bind(item, onProfileClick, onProfileLongClick, onEditProfileJson)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.contains("FULL_BIND")) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = getItem(position)
            val combinedPayloads = mutableSetOf<String>()
            for (p in payloads) {
                if (p is Set<*>) {
                    combinedPayloads.addAll(p as Set<String>)
                }
            }

            when (item) {
                is DisplayItem.SubscriptionItem -> {
                    val vh = holder as SubscriptionVH
                    if (combinedPayloads.contains("EXPAND")) vh.updateExpand(item)
                    if (combinedPayloads.contains("REFRESH")) vh.updateRefresh(item)
                    if (combinedPayloads.contains("TRAFFIC")) vh.updateTraffic(item)
                    if (combinedPayloads.contains("CORNERS")) vh.updateCorners(item)
                }
                is DisplayItem.ProfileItem -> {
                    val vh = holder as ProfileVH
                    if (combinedPayloads.contains("PING")) vh.updatePing(item)
                    if (combinedPayloads.contains("SELECTION")) vh.updateSelection(item)
                    if (combinedPayloads.contains("CORNERS")) vh.updateCorners(item)
                }
            }
        }
    }

    inner class SubscriptionVH(
        private val binding: ItemSubscriptionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: DisplayItem.SubscriptionItem,
            toggle: (SubscriptionEntity) -> Unit,
            delete: (Long) -> Unit,
            speedTest: (Long) -> Unit,
            options: (Long) -> Unit
        ) {
            val layoutParams = binding.root.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = if (bindingAdapterPosition == 0) 0 else (12 * binding.root.resources.displayMetrics.density).toInt()
            binding.root.layoutParams = layoutParams

            binding.tvSubName.text = item.entity.name
            updateTraffic(item)
            updateExpand(item)
            updateRefresh(item)
            updateCorners(item)

            binding.root.setOnClickListener { toggle(item.entity) }
            binding.ivSync.setOnClickListener { onSubscriptionUpdate(item.entity) }
            binding.ivSpeedTest.setOnClickListener { speedTest(item.entity.id) }

            val isVirtual = item.entity.id == -1L
            binding.ivSync.visibility = if (isVirtual) View.GONE else View.VISIBLE
            binding.ivMoreOptions.visibility = View.VISIBLE
            
            binding.ivMoreOptions.setOnClickListener { view ->
                val items = if (isVirtual) {
                    val deleteLabel = view.context.getString(R.string.menu_delete_subscription)
                    listOf(
                        flare.client.app.util.GlassUtils.MenuItem(1, deleteLabel) {
                            onSubscriptionDelete(item.entity.id)
                        }
                    )
                } else {
                    val editLabel = view.context.getString(R.string.menu_edit_subscription)
                    val deleteLabel = view.context.getString(R.string.menu_delete_subscription)
                    listOf(
                        flare.client.app.util.GlassUtils.MenuItem(1, editLabel) {
                            onEditSubscriptionJson(item.entity)
                        },
                        flare.client.app.util.GlassUtils.MenuItem(2, deleteLabel) {
                            onSubscriptionDelete(item.entity.id)
                        }
                    )
                }
                flare.client.app.util.GlassUtils.showGlassMenu(view, items)
            }
        }

        fun updateTraffic(item: DisplayItem.SubscriptionItem) {
            val used = item.entity.upload + item.entity.download
            val isInfinite = item.entity.id == -1L || item.entity.total == Long.MAX_VALUE

            accentColor?.let { color ->
                binding.pbTraffic.progressTintList = android.content.res.ColorStateList.valueOf(color)
            }

            if (isInfinite || item.entity.total > 0 || used > 0) {
                binding.llTrafficContainer.visibility = View.VISIBLE
                if (isInfinite) {
                    binding.pbTraffic.progress = 0
                    binding.tvTrafficInfo.text = "∞ / ∞"
                } else {
                    binding.pbTraffic.progress = if (item.entity.total > 0) ((used.toDouble() / item.entity.total) * 10000).toInt() else 0
                    val formatBytes = { bytes: Long ->
                        val mb = bytes.toDouble() / (1024 * 1024)
                        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
                        if (gb >= 1.0) {
                            java.lang.String.format(java.util.Locale.US, "%.2f GB", gb)
                        } else if (mb >= 1.0) {
                            java.lang.String.format(java.util.Locale.US, "%.2f MB", mb)
                        } else {
                            "$bytes B"
                        }
                    }
                    val totalStr = if (item.entity.total > 0) formatBytes(item.entity.total) else "∞"
                    binding.tvTrafficInfo.text = "${formatBytes(used)} / $totalStr"
                }
            } else {
                binding.llTrafficContainer.visibility = View.GONE
            }

            if (item.entity.expire > 0) {
                binding.tvExpireDate.visibility = View.VISIBLE
                val expireMillis = if (item.entity.expire > 1000000000000L) item.entity.expire else item.entity.expire * 1000L
                val date = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(expireMillis))
                binding.tvExpireDate.text = binding.root.context.getString(R.string.label_expires, date)
            } else {
                binding.tvExpireDate.visibility = View.GONE
            }

            if (item.entity.description.isNotBlank()) {
                binding.tvDescription.visibility = View.VISIBLE
                binding.tvDescription.text = item.entity.description
            } else {
                binding.tvDescription.visibility = View.GONE
            }
        }

        fun updateExpand(item: DisplayItem.SubscriptionItem) {
            val rotation = if (item.isExpanded) 90f else 0f
            binding.ivArrow.animate().rotation(rotation).setDuration(200).start()
        }

        fun updateRefresh(item: DisplayItem.SubscriptionItem) {
            if (item.isRefreshing) {
                val rotate = android.view.animation.RotateAnimation(
                    360f, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                )
                rotate.duration = 1000
                rotate.repeatCount = android.view.animation.Animation.INFINITE
                rotate.interpolator = android.view.animation.LinearInterpolator()
                binding.ivSync.startAnimation(rotate)
                binding.ivSync.isEnabled = false
            } else {
                binding.ivSync.clearAnimation()
                binding.ivSync.isEnabled = true
            }
        }

        fun updateCorners(item: DisplayItem.SubscriptionItem) {
            applyCorners(binding.root, item.cornerType)
            binding.viewSeparator.visibility = if (item.cornerType == DisplayItem.CornerType.BOTTOM || item.cornerType == DisplayItem.CornerType.ALL) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    inner class ProfileVH(
        private val binding: ItemProfileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: DisplayItem.ProfileItem,
            click: (ProfileEntity) -> Unit,
            longClick: (ProfileEntity) -> Unit,
            editJson: (ProfileEntity) -> Unit
        ) {
            binding.tvProfileName.text = item.entity.name
            val displayInfo = getProtocolDisplay(item.entity)
            binding.tvServerDescription.text = displayInfo
            binding.tvServerDescription.visibility = View.VISIBLE

            updateSelection(item)
            updatePing(item)
            updateCorners(item)

            binding.root.setOnClickListener { click(item.entity) }
            binding.root.setOnLongClickListener {
                longClick(item.entity)
                true
            }
            binding.ivEditJson.setOnClickListener { editJson(item.entity) }
        }

        fun updateSelection(item: DisplayItem.ProfileItem) {
            val selectionAlpha = if (item.isSelected) 1f else 0f
            binding.viewSelectedBg.alpha = selectionAlpha
            binding.ivSelectedCheck.alpha = selectionAlpha

            accentColor?.let { color ->
                binding.ivSelectedCheck.imageTintList = android.content.res.ColorStateList.valueOf(color)
            }

            if (item.isSelected) {
                binding.tvProfileName.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_profile_selected_primary))
                binding.tvServerDescription.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_profile_selected_secondary))
                binding.ivEditJson.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.text_profile_selected_secondary))
                binding.viewArrowDivider.background = android.graphics.drawable.ColorDrawable(ContextCompat.getColor(binding.root.context, R.color.divider_profile_selected))
                binding.viewArrowDivider.alpha = 1.0f
            } else {
                binding.tvProfileName.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_primary))
                binding.tvServerDescription.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_secondary))
                binding.ivEditJson.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.text_secondary))
                binding.viewArrowDivider.background = android.graphics.drawable.ColorDrawable(ContextCompat.getColor(binding.root.context, R.color.bg_surface))
                binding.viewArrowDivider.alpha = 0.3f
            }
            binding.layoutContent.alpha = if (item.isSelected) 1.0f else 0.7f
        }

        fun updatePing(item: DisplayItem.ProfileItem) {
            val pingStyle = flare.client.app.data.SettingsManager(binding.root.context).pingStyle
            when (val state = item.pingState) {
                is PingState.None -> {
                    binding.layoutPingContainer.visibility = View.GONE
                }
                is PingState.Loading -> {
                    binding.layoutPingContainer.visibility = View.VISIBLE
                    binding.pbPingLoading.visibility = View.VISIBLE
                    binding.ivPingIcon.visibility = View.GONE
                    binding.tvPingText.visibility = View.GONE
                }
                is PingState.Result -> {
                    binding.layoutPingContainer.visibility = View.VISIBLE
                    binding.pbPingLoading.visibility = View.GONE
                    val showIcon = pingStyle == "icon" || pingStyle == "both"
                    val showText = pingStyle == "time" || pingStyle == "both"
                    if (state.isError || state.latency > 5000) {
                        if (showIcon) {
                            binding.ivPingIcon.visibility = View.VISIBLE
                            binding.ivPingIcon.setImageResource(R.drawable.ic_error)
                        } else binding.ivPingIcon.visibility = View.GONE
                        if (showText) {
                            binding.tvPingText.visibility = View.VISIBLE
                            binding.tvPingText.text = if (state.isError) (state.errorMessage ?: "Error") else "${state.latency} ms"
                            binding.tvPingText.setTextColor(android.graphics.Color.RED)
                        } else binding.tvPingText.visibility = View.GONE
                    } else {
                        val iconRes = when {
                            state.latency <= 300 -> R.drawable.ic_success
                            state.latency <= 800 -> R.drawable.ic_warning
                            else -> R.drawable.ic_error
                        }
                        val textColorRes = when {
                            state.latency <= 300 -> android.graphics.Color.parseColor("#4CAF50")
                            state.latency <= 800 -> android.graphics.Color.parseColor("#FFC107")
                            else -> android.graphics.Color.RED
                        }
                        if (showIcon) {
                            binding.ivPingIcon.visibility = View.VISIBLE
                            binding.ivPingIcon.setImageResource(iconRes)
                        } else binding.ivPingIcon.visibility = View.GONE
                        if (showText) {
                            binding.tvPingText.visibility = View.VISIBLE
                            binding.tvPingText.text = "${state.latency} ms"
                            binding.tvPingText.setTextColor(textColorRes)
                        } else binding.tvPingText.visibility = View.GONE
                    }
                }
            }
        }

        fun updateCorners(item: DisplayItem.ProfileItem) {
            val layoutParams = binding.root.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = if (item.cornerType == DisplayItem.CornerType.ALL && bindingAdapterPosition != 0) {
                (12 * binding.root.resources.displayMetrics.density).toInt()
            } else {
                0
            }
            binding.root.layoutParams = layoutParams

            applyCorners(binding.layoutContent, item.cornerType)
            applyCorners(binding.viewSelectedBg, item.cornerType)
            binding.viewSeparator.visibility = if (item.cornerType == DisplayItem.CornerType.BOTTOM || item.cornerType == DisplayItem.CornerType.ALL) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        private fun getProtocolDisplay(entity: ProfileEntity): String {
            val rawProtocol = if (entity.uri.startsWith("internal://json")) {
                try {
                    val json = JSONObject(entity.configJson)
                    val outbounds = json.optJSONArray("outbounds")
                    val proxy = outbounds?.optJSONObject(0)
                    proxy?.optString("type")?.uppercase() ?: "JSON"
                } catch (e: Exception) {
                    "JSON"
                }
            } else {
                entity.uri.substringBefore("://").uppercase()
            }

            val protocol = if (rawProtocol == "SS") "SHADOWSOCKS" else rawProtocol

            val base = if (entity.uri.startsWith("internal://json")) {
                "$protocol | JSON"
            } else {
                protocol
            }

            val description = entity.serverDescription
            return if (description.isNullOrBlank()) base else "$base | $description"
        }
    }

    private fun applyCorners(view: View, cornerType: DisplayItem.CornerType) {
        val radius = 12f * view.context.resources.displayMetrics.density
        val background = view.background as? GradientDrawable ?: return
        val radii = when (cornerType) {
            DisplayItem.CornerType.ALL -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            DisplayItem.CornerType.TOP -> floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            DisplayItem.CornerType.BOTTOM -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            DisplayItem.CornerType.NONE -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        background.mutate()
        background.cornerRadii = radii
    }
}