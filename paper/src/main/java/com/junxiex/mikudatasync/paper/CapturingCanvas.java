package com.junxiex.mikudatasync.paper;

import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapFont;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Image;

/** Captures pixels/cursors rendered by existing map renderers. */
final class CapturingCanvas implements MapCanvas {

    private final MapView view;
    private final byte[] pixels = new byte[128 * 128];
    private MapCursorCollection cursors = new MapCursorCollection();
    private byte basePixel;

    CapturingCanvas(MapView view) {
        this.view = view;
    }

    byte[] pixels() {
        return pixels;
    }

    MapCursorCollection cursors() {
        return cursors;
    }

    @Override
    public @NotNull MapView getMapView() {
        return view;
    }

    @Override
    public @NotNull MapCursorCollection getCursors() {
        return cursors;
    }

    @Override
    public void setCursors(@NotNull MapCursorCollection cursors) {
        this.cursors = cursors;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setPixel(int x, int y, byte color) {
        if (x >= 0 && x < 128 && y >= 0 && y < 128) {
            pixels[y * 128 + x] = color;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public byte getPixel(int x, int y) {
        return x >= 0 && x < 128 && y >= 0 && y < 128 ? pixels[y * 128 + x] : 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    public byte getBasePixel(int x, int y) {
        return basePixel;
    }

    @Override
    public void setPixelColor(int x, int y, @Nullable Color color) {
        if (x >= 0 && x < 128 && y >= 0 && y < 128) {
            pixels[y * 128 + x] = color == null ? 0 : MapPalette.matchColor(color);
        }
    }

    @Override
    public @Nullable Color getPixelColor(int x, int y) {
        return MapPalette.getColor(getPixel(x, y));
    }

    @Override
    public @NotNull Color getBasePixelColor(int x, int y) {
        return MapPalette.getColor(basePixel);
    }

    @Override
    public void drawImage(int x, int y, @NotNull Image image) {
        // Not needed for vanilla map renderers.
    }

    @Override
    public void drawText(int x, int y, @NotNull MapFont font, @NotNull String text) {
        // Not needed for vanilla map renderers.
    }
}
