package dev.picori.tmc;

/**
 * Read-only bridge to the running game's state, plus on-demand art
 * generation from the player's own loaded ROM — the zelda3-android
 * GameState.java pattern. The natives live in libmain.so
 * (port/port_second_screen_jni.cpp), which SDLActivity loads before any of
 * this is reachable, so no loadLibrary is needed here.
 *
 * Snapshot int[] layout (must match SNAPSHOT_INTS packing in
 * port_second_screen_jni.cpp):
 *   0 inGame, 1 area, 2 room, 3 playerX, 4 playerY,
 *   5 equippedA, 6 equippedB, 7 equippedSlotA, 8 equippedSlotB,
 *   9 health, 10 maxHealth, 11 rupees,
 *   12..27 menuItems[16], 28..31 bottleContents[4],
 *   32/33 visitedMask lo/hi, 34.. rooms x,y,w,h x64.
 */
public final class GameStateNative {
    private GameStateNative() {}

    // Offsets into the snapshot array.
    public static final int IN_GAME = 0;
    public static final int AREA = 1;
    public static final int ROOM = 2;
    public static final int PLAYER_X = 3;
    public static final int PLAYER_Y = 4;
    public static final int EQUIPPED_A = 5;
    public static final int EQUIPPED_B = 6;
    public static final int EQUIPPED_SLOT_A = 7;
    public static final int EQUIPPED_SLOT_B = 8;
    public static final int HEALTH = 9;
    public static final int MAX_HEALTH = 10;
    public static final int RUPEES = 11;
    public static final int MENU_ITEMS = 12;   // 16 ints
    public static final int BOTTLES = 28;      // 4 ints
    public static final int VISITED_LO = 32;
    public static final int VISITED_HI = 33;
    public static final int ROOMS = 34;        // x,y,w,h per room, 64 rooms

    public static final int ITEM_SLOTS = 16;
    public static final int MAX_ROOMS = 64;

    // Gear-tab extras, right after the room block (34 + MAX_ROOMS*4).
    public static final int KINSTONES_FUSED = ROOMS + MAX_ROOMS * 4;
    public static final int FIGURINE_COUNT = KINSTONES_FUSED + 1;
    public static final int ELEMENTS = KINSTONES_FUSED + 2; // bit0 Earth, 1 Fire, 2 Water, 3 Wind

    /** Icon sheet geometry: cell index == item id. */
    public static final int ICON_SHEET_COLS = 16;
    public static final int ICON_SHEET_W = 256;
    public static final int ICON_SHEET_H = 128;
    public static final int ICON_CELL = 16;

    /** Heart sheet geometry: cell index == quarters filled (0=empty..4=full). */
    public static final int HEART_FRAMES = 5;
    public static final int HEART_SHEET_W = HEART_FRAMES * 16;
    public static final int HEART_SHEET_H = 16;

    /** Local-area map geometry: the room the player is standing in, real
     * tile detail, centered on the player (see port_second_screen_state.h's
     * SECOND_SCREEN_LOCAL_MAP_TILES_W/H). */
    public static final int LOCAL_MAP_TILES_W = 20;
    public static final int LOCAL_MAP_TILES_H = 15;
    public static final int LOCAL_MAP_W = LOCAL_MAP_TILES_W * 16;
    public static final int LOCAL_MAP_H = LOCAL_MAP_TILES_H * 16;

    /** Whole-room map geometry: the "zoomed out" view (see
     * SECOND_SCREEN_ROOM_MAP_TILES_W/H in port_second_screen_render.h). */
    public static final int ROOM_MAP_TILES_W = 64;
    public static final int ROOM_MAP_TILES_H = 64;
    public static final int ROOM_MAP_W = ROOM_MAP_TILES_W * 16;
    public static final int ROOM_MAP_H = ROOM_MAP_TILES_H * 16;

    /** Player's room-local pixel position (playerX/Y minus the room's own
     * origin) — where to draw the marker on the whole-room map. */
    public static final int PLAYER_ROOM_X = ELEMENTS + 1;
    public static final int PLAYER_ROOM_Y = ELEMENTS + 2;

    /** Size (in ints) the snapshot array must have. */
    public static native int snapshotSize();

    /** Fills {@code out} with the latest game snapshot (see layout above). */
    public static native void getSnapshot(int[] out);

    /**
     * Fills {@code out} (ICON_SHEET_W * ICON_SHEET_H ARGB ints) with the
     * item-icon sheet rendered from the loaded ROM's own graphics. Returns
     * false until the ROM's sprite tables are ready — retry later.
     */
    public static native boolean renderIconSheet(int[] out);

    /**
     * Fills {@code out} (HEART_SHEET_W * HEART_SHEET_H ARGB ints) with the
     * real heart-glyph strip (5 cells, empty..full) rendered from the loaded
     * ROM. Returns false until the ROM's sprite tables are ready — retry.
     */
    public static native boolean renderHeartSheet(int[] out);

    /**
     * Fills {@code out} (LOCAL_MAP_W * LOCAL_MAP_H ARGB ints) with the room
     * the player is currently standing in, rendered from the same live tile
     * data the primary screen is already using this frame — real detail,
     * not a schematic automap. Centered on the player. Returns false
     * outside gameplay or before the area's tileset resolves — retry.
     */
    public static native boolean renderLocalMap(int[] out);

    /**
     * Fills {@code out} (ROOM_MAP_W * ROOM_MAP_H ARGB ints) with the whole
     * current room in real tile detail — the "zoomed out" view. Only meant
     * to be called when the zoomed-out view is opened or the room changes,
     * not every tick. Returns false outside gameplay.
     */
    public static native boolean renderRoomMap(int[] out);

    /** Request equipping an item; applied on the game thread. slot: 0=A, 1=B. */
    public static native void requestEquip(int itemId, int slot);
}
