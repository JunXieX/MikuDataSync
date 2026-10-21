package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.MapDataList;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** Draws persisted map colours every render, independent of world map saved data. */
public final class MdsMapRenderer extends MapRenderer {

    private final byte[] colors;

    public MdsMapRenderer(byte[] colors) {
        super(false);
        this.colors = colors;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (colors == null || colors.length != MapDataList.COLORS_LENGTH) {
            return;
        }
        for (int y = 0; y < MapDataList.MAP_HEIGHT; y++) {
            for (int x = 0; x < MapDataList.MAP_WIDTH; x++) {
                canvas.setPixel(x, y, colors[y * MapDataList.MAP_WIDTH + x]);
            }
        }
    }
}
