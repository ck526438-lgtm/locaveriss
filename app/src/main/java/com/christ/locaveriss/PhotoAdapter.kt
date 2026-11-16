package com.christ.locaveriss

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Bande horizontale de photos (adresses web ou images locales content://). */
class PhotoAdapter(
    private val items: List<String>,
    private val widthDp: Int,
    private val heightDp: Int,
    private val onClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    class VH(val img: ImageView) : RecyclerView.ViewHolder(img)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val img = ImageView(parent.context)
        val lp = ViewGroup.MarginLayoutParams((widthDp * d).toInt(), (heightDp * d).toInt())
        lp.rightMargin = (6 * d).toInt()
        img.layoutParams = lp
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.contentDescription = "Photo du logement"
        return VH(img)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        h.img.load(items[position]) {
            placeholder(R.drawable.ph)
            error(R.drawable.ph)
        }
        val click = onClick
        if (click != null) {
            h.img.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) click(pos)
            }
        }
    }
}
