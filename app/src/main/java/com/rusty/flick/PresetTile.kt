package com.rusty.flick

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

/**
 * Swipe-over tile: one round button per preset (emoji) plus an off button. Tapping arms that
 * preset without opening the app; beaming works in the background, so that's all it takes.
 * Room for 7 presets: the first 7 in the editor's order.
 */
class PresetTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val ctx = this@PresetTileService
            Store.load(ctx)
            val clicked = requestParams.currentState.lastClickableId
            if (clicked.startsWith("p:") || clicked == "off") {
                Store.select(ctx, clicked.removePrefix("p:").takeIf { clicked != "off" } ?: "")
                getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
            val state = Store.state.value
            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(ctx, state)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion("1").build() }

    private fun layout(ctx: Context, state: State): LayoutElementBuilders.LayoutElement {
        val active = state.active
        val buttons = state.presets.take(MAX).map { p ->
            button("p:${p.id}", p.icon, if (p.id == state.activeId) ACCENT else IDLE, 20f)
        } + button("off", "off", if (active == null) OFF_ON else IDLE, 12f)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(header(ctx, active))
            .addContent(gap(h = 8f))
        if (state.presets.isEmpty()) {
            col.addContent(text("No presets yet. Open Flick to sync.", 12f, MUTED, 2))
        } else {
            buttons.chunked(3).forEachIndexed { i, rowItems ->
                if (i > 0) col.addContent(gap(h = 6f))
                val row = LayoutElementBuilders.Row.Builder()
                rowItems.forEachIndexed { j, b -> if (j > 0) row.addContent(gap(w = 6f)); row.addContent(b) }
                col.addContent(row.build())
            }
        }
        return col.build()
    }

    /** "⚡ Name" (or "Not beaming"); tapping it opens the app. */
    private fun header(ctx: Context, active: Preset?): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(
                    ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
                        ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(ctx.packageName)
                                .setClassName(MainActivity::class.java.name)
                                .build()
                        ).build()
                    ).build()
                ).build()
            )
            .addContent(
                if (active == null) text("Not beaming", 13f, MUTED, 1)
                else text("⚡ " + active.name, 13f, ACCENT, 1)
            )
            .build()

    private fun button(id: String, label: String, color: Int, size: Float) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(BTN)).setHeight(dp(BTN))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BTN / 2)).build())
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder().setId(id)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build()).build()
                    ).build()
            )
            .addContent(text(label, size, if (color == ACCENT || color == OFF_ON) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 1))
            .build()

    private fun text(s: String, size: Float, color: Int, maxLines: Int) =
        LayoutElementBuilders.Text.Builder()
            .setText(s).setMaxLines(maxLines)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
            .build()

    private fun gap(h: Float = 0f, w: Float = 0f) =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).setWidth(dp(w)).build()

    companion object {
        private const val MAX = 7
        private const val BTN = 46f
        private const val ACCENT = 0xFF9C7BFF.toInt()
        private const val IDLE = 0xFF2E2B3A.toInt()
        private const val OFF_ON = 0xFFAAAAAA.toInt()
        private const val MUTED = 0xFFAAAAAA.toInt()

        /** Re-render after the app or a sync changes what's armed. */
        fun refresh(ctx: Context) {
            runCatching { getUpdater(ctx).requestUpdate(PresetTileService::class.java) }
        }
    }
}
