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
) : ListAdapter<DisplayItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_SUBSCRIPTION = 0
        private const val TYPE_PROFILE = 1

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
                (holder as ProfileVH).bind(item, onProfileClick, onEditProfileJson)
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
            val rotation = if (item.isExpanded) 90f else 0f
            binding.ivArrow.animate().rotation(rotation).setDuration(200).start()
            
            applyCorners(binding.root, item.cornerType)
            binding.viewSeparator.visibility = if (item.cornerType == DisplayItem.CornerType.BOTTOM || item.cornerType == DisplayItem.CornerType.ALL) {
                View.GONE
            } else {
                View.VISIBLE
            }

            binding.root.setOnClickListener { toggle(item.entity) }
            binding.ivDelete.setOnClickListener { delete(item.entity.id) }
            binding.ivSpeedTest.setOnClickListener { speedTest(item.entity.id) }

            val isVirtual = item.entity.id == -1L
            binding.ivMoreOptions.visibility = if (isVirtual) View.GONE else View.VISIBLE
            
            if (!isVirtual) {
                binding.ivMoreOptions.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view, Gravity.END)
                val textColor = ContextCompat.getColor(view.context, R.color.menu_text_color)

                val updateLabel = view.context.getString(R.string.menu_update_subscription)
                val updateSpannable = SpannableString(updateLabel)
                updateSpannable.setSpan(ForegroundColorSpan(textColor), 0, updateSpannable.length, 0)
                popup.menu.add(0, 1, 1, updateSpannable).setOnMenuItemClickListener {
                    onSubscriptionUpdate(item.entity)
                    true
                }

                val editLabel = view.context.getString(R.string.menu_edit_subscription)
                val editSpannable = SpannableString(editLabel)
                editSpannable.setSpan(ForegroundColorSpan(textColor), 0, editSpannable.length, 0)
                popup.menu.add(1, 2, 2, editSpannable).setOnMenuItemClickListener {
                    onEditSubscriptionJson(item.entity)
                    true
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    popup.menu.setGroupDividerEnabled(true)
                }
                
                popup.show()
                }
            }
        }
    }

    inner class ProfileVH(
        private val binding: ItemProfileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: DisplayItem.ProfileItem, 
            click: (ProfileEntity) -> Unit,
            editJson: (ProfileEntity) -> Unit
        ) {
            val layoutParams = binding.root.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = if (item.cornerType == DisplayItem.CornerType.ALL && bindingAdapterPosition != 0) {
                (12 * binding.root.resources.displayMetrics.density).toInt()
            } else {
                0
            }
            binding.root.layoutParams = layoutParams

            binding.tvProfileName.text = item.entity.name
            
            val displayInfo = getProtocolDisplay(item.entity)
            binding.tvServerDescription.text = displayInfo
            binding.tvServerDescription.visibility = View.VISIBLE

            val selectionAlpha = if (item.isSelected) 1f else 0f
            binding.viewSelectedBg.alpha = selectionAlpha
            binding.ivSelectedCheck.alpha = selectionAlpha
            
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
                    
                    val showIcon = pingStyle == "Значок" || pingStyle == "Время и значок"
                    val showText = pingStyle == "Время" || pingStyle == "Время и значок"
                    
                    if (state.isError || state.latency > 5000) {
                        if (showIcon) {
                            binding.ivPingIcon.visibility = View.VISIBLE
                            binding.ivPingIcon.setImageResource(R.drawable.ic_error)
                        } else binding.ivPingIcon.visibility = View.GONE
                        
                        if (showText) {
                            binding.tvPingText.visibility = View.VISIBLE
                            binding.tvPingText.text = if (state.isError) "Error" else "${state.latency} ms"
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
            
            applyCorners(binding.layoutContent, item.cornerType)
            applyCorners(binding.viewSelectedBg, item.cornerType)
            
            binding.viewSeparator.visibility = if (item.cornerType == DisplayItem.CornerType.BOTTOM || item.cornerType == DisplayItem.CornerType.ALL) {
                View.GONE
            } else {
                View.VISIBLE
            }
            
            binding.layoutContent.alpha = if (item.isSelected) 1.0f else 0.7f
            
            binding.root.setOnClickListener { click(item.entity) }
            binding.ivEditJson.setOnClickListener { editJson(item.entity) }
        }

        private fun getProtocolDisplay(entity: ProfileEntity): String {
            val protocol = if (entity.uri.startsWith("internal://json")) {
                try {
                    val json = JSONObject(entity.configJson)
                    val outbounds = json.optJSONArray("outbounds")
                    val proxy = outbounds?.optJSONObject(0)
                    proxy?.optString("type")?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "JSON"
                } catch (e: Exception) {
                    "JSON"
                }
            } else {
                entity.uri.substringBefore("://").uppercase()
            }

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
