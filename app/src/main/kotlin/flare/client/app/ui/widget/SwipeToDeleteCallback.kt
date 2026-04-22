package flare.client.app.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import flare.client.app.R
import flare.client.app.ui.adapter.ProfileAdapter
import kotlin.math.abs
import kotlin.math.min

class SwipeToDeleteCallback(
    private val adapter: ProfileAdapter,
    private val onSwiped: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    private val paint = Paint()
    private val iconColor = Color.WHITE
    private val backgroundColor = Color.parseColor("#E53935") 

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        return 0.5f
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        onSwiped(viewHolder.bindingAdapterPosition)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (viewHolder.itemViewType != ProfileAdapter.TYPE_PROFILE) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }

        val itemView = viewHolder.itemView
        val itemHeight = itemView.bottom - itemView.top
        val isCanceled = dX == 0f && !isCurrentlyActive

        if (!isCanceled && actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            
            paint.color = backgroundColor
            val cornerRadius = 12f * recyclerView.context.resources.displayMetrics.density
            
            
            val background = RectF(
                itemView.right.toFloat() + dX - cornerRadius, 
                itemView.top.toFloat(),
                itemView.right.toFloat(),
                itemView.bottom.toFloat()
            )
            c.drawRoundRect(background, cornerRadius, cornerRadius, paint)

            
            val deleteIcon = ContextCompat.getDrawable(recyclerView.context, R.drawable.ic_delete)
            if (deleteIcon != null) {
                val iconSize = (24 * recyclerView.context.resources.displayMetrics.density).toInt()
                val iconMargin = (itemHeight - iconSize) / 2
                val iconTop = itemView.top + iconMargin
                val iconBottom = iconTop + iconSize
                
                
                val swipeThreshold = itemView.width * 0.5f
                val progress = min(1f, abs(dX) / swipeThreshold)
                
                
                val alpha = (progress * 255).toInt().coerceIn(0, 255)
                val scale = 0.5f + (progress * 0.5f)
                
                val iconRight = itemView.right - iconMargin
                val iconLeft = iconRight - iconSize
                
                val centerX = (iconLeft + iconRight) / 2f
                val centerY = (iconTop + iconBottom) / 2f
                
                c.save()
                c.scale(scale, scale, centerX, centerY)
                deleteIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                deleteIcon.setTint(iconColor)
                deleteIcon.alpha = alpha
                deleteIcon.draw(c)
                c.restore()
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
