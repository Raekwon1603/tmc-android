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

    /** Icon sheet geometry: cell index == item id. */
    public static final int ICON_SHEET_COLS = 16;
    public static final int ICON_SHEET_W = 256;
    public static final int ICON_SHEET_H = 128;
    public static final int ICON_CELL = 16;

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

    /** Request equipping an item; applied on the game thread. slot: 0=A, 1=B. */
    public static native void requestEquip(int itemId, int slot);
}
