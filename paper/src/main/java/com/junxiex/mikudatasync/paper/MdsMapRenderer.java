package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.ItemMapData;
import com.junxiex.mikudatasync.common.MapDataList;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** Draws persisted map colours and cursor markers every render, independent of world saved data. */
public final class MdsMapRenderer extends MapRenderer {

    private final byte[] colors;
    private final java.util.List<ItemMapData.CursorData> cursors;

    public MdsMapRenderer(byte[] colors, java.util.List<ItemMapData.CursorData> cursors) {
        super(false);
        this.colors = colors;
        this.cursors = cursors;
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
        MapCursorCollection collection = canvas.getCursors();
        while (collection.size() > 0) {
            collection.removeCursor(collection.getCursor(0));
        }
        if (cursors == null) {
            return;
        }
        for (ItemMapData.CursorData cursor : cursors) {
            try {
                MapCursor.Type type = MapCursor.Type.valueOf(cursor.type());
                collection.addCursor(new MapCursor(cursor.x(), cursor.y(), cursor.direction(),
                        type, true, cursor.caption()));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
