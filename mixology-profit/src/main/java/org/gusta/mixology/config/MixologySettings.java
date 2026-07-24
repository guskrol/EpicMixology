package org.gusta.mixology.config;

import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;

public class MixologySettings {
    public static final int REQUIRED_HERBLORE_LEVEL = 60;
    public static final int MIN_STARTER_HERBS_PER_TYPE = 800;
    public static final int MAX_STARTER_HERBS_PER_TYPE = 1000;
    public static final int MIN_RESTOCK_PASTE_PER_TYPE = 6_000;
    public static final int MAX_RESTOCK_PASTE_PER_TYPE = 7_200;
    public static final int MIN_EMPTY_SLOTS_FOR_ORDERS = 3;
    public static final int MAX_HOPPER_PASTE_PER_TYPE = 3_000;

    private static final Area SOCIETY_SURFACE_AREA = new Area(1370, 2904, 1410, 2940, 0);
    private static final Area ALCHEMICAL_SOCIETY_AREA = new Area(1368, 9288, 1425, 9355, 0);
    private static final Area MIXING_ROOM_AREA = new Area(1384, 9306, 1412, 9334, 0);
    private static final Tile SOCIETY_CENTER_TILE = new Tile(1389, 2918, 0);
    private static final Tile MIXING_ROOM_CENTER_TILE = new Tile(1394, 9323, 0);
    private static final Tile LEVER_CENTER_TILE = new Tile(1394, 9324, 0);
    private static final Tile[] LEVER_RETURN_TILES = {
            new Tile(1394, 9324, 0),
            new Tile(1394, 9325, 0)
    };

    public Area societySurfaceArea() {
        return SOCIETY_SURFACE_AREA;
    }

    public Area alchemicalSocietyArea() {
        return ALCHEMICAL_SOCIETY_AREA;
    }

    public Area mixingRoomArea() {
        return MIXING_ROOM_AREA;
    }

    public Tile societyCenterTile() {
        return SOCIETY_CENTER_TILE;
    }

    public Tile mixingRoomCenterTile() {
        return MIXING_ROOM_CENTER_TILE;
    }

    public Tile leverCenterTile() {
        return LEVER_CENTER_TILE;
    }

    public Tile[] leverReturnTiles() {
        return LEVER_RETURN_TILES.clone();
    }

    public boolean isSocietySurfaceTile(Tile tile) {
        return tileInBounds(tile, 1370, 2904, 1410, 2940, 0);
    }

    public boolean isAlchemicalSocietyTile(Tile tile) {
        return tileInBounds(tile, 1368, 9288, 1425, 9355, 0);
    }

    public boolean isMixingRoomTile(Tile tile) {
        return tileInBounds(tile, 1384, 9306, 1412, 9334, 0);
    }

    private boolean tileInBounds(Tile tile, int minX, int minY, int maxX, int maxY, int plane) {
        return tile != null
                && tile.getPlane() == plane
                && tile.getX() >= minX
                && tile.getX() <= maxX
                && tile.getY() >= minY
                && tile.getY() <= maxY;
    }
}
