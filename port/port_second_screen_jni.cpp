/* JNI bridge for the second-screen panel. Android-only — this translation
 * unit is not added to the build on other platforms (see xmake.lua).
 *
 * Architecture follows zelda3-android's second_screen_jni.c: the native side
 * exposes read-only game-state accessors plus on-demand sheet renderers, and
 * ALL drawing lives in Java (SecondScreenView, an android.graphics.Canvas
 * custom View) — there is no native render thread or ANativeWindow here.
 * Implicit JNI naming (Java_<package>_<Class>_<method>) is used throughout,
 * matching the rest of this codebase's JNI surface; SDL's static-linked glue
 * owns JNI_OnLoad, and implicit-named natives resolve without registration.
 */

#include <jni.h>
#include <string.h>

#include "port_second_screen_render.h"
#include "port_second_screen_state.h"

/* Snapshot packed into one int[] so the Java tick costs a single JNI call.
 * Layout (indices):
 *   0  inGame            1  area              2  room
 *   3  playerX           4  playerY
 *   5  equippedA         6  equippedB
 *   7  equippedSlotA     8  equippedSlotB
 *   9  health            10 maxHealth         11 rupees
 *   12..27  menuItems[16]
 *   28..31  bottleContents[4]
 *   32 visitedMask low 32 bits   33 visitedMask high 32 bits
 *   34..(34+64*4-1)  rooms: x,y,w,h per room id (64 rooms)
 *   34+64*4  kinstonesFused   +1  figurineCount   +2  elements bitmask
 *   34+64*4+3  playerRoomX    +1  playerRoomY
 * Total: 34 + 256 + 3 + 2 = 295 ints. Java mirrors this in GameStateNative. */
#define SNAPSHOT_INTS (34 + SECOND_SCREEN_MAX_ROOMS * 4 + 3 + 2)

extern "C" JNIEXPORT jint JNICALL Java_dev_picori_tmc_GameStateNative_snapshotSize(JNIEnv*, jclass) {
    return SNAPSHOT_INTS;
}

extern "C" JNIEXPORT void JNICALL Java_dev_picori_tmc_GameStateNative_getSnapshot(JNIEnv* env, jclass,
                                                                                   jintArray out) {
    SecondScreenSnapshot snap;
    Port_SecondScreenState_Read(&snap);

    jint buf[SNAPSHOT_INTS];
    buf[0] = snap.inGame;
    buf[1] = snap.area;
    buf[2] = snap.room;
    buf[3] = snap.playerX;
    buf[4] = snap.playerY;
    buf[5] = snap.equippedA;
    buf[6] = snap.equippedB;
    buf[7] = snap.equippedSlotA;
    buf[8] = snap.equippedSlotB;
    buf[9] = snap.health;
    buf[10] = snap.maxHealth;
    buf[11] = snap.rupees;
    for (int i = 0; i < SECOND_SCREEN_ITEM_SLOTS; i++) {
        buf[12 + i] = snap.menuItems[i];
    }
    for (int i = 0; i < 4; i++) {
        buf[28 + i] = snap.bottleContents[i];
    }
    buf[32] = (jint)(snap.visitedMask & 0xFFFFFFFFu);
    buf[33] = (jint)(snap.visitedMask >> 32);
    for (int i = 0; i < SECOND_SCREEN_MAX_ROOMS; i++) {
        buf[34 + i * 4 + 0] = snap.rooms[i].x;
        buf[34 + i * 4 + 1] = snap.rooms[i].y;
        buf[34 + i * 4 + 2] = snap.rooms[i].w;
        buf[34 + i * 4 + 3] = snap.rooms[i].h;
    }
    int gearBase = 34 + SECOND_SCREEN_MAX_ROOMS * 4;
    buf[gearBase + 0] = snap.kinstonesFused;
    buf[gearBase + 1] = snap.figurineCount;
    buf[gearBase + 2] = snap.elements;
    buf[gearBase + 3] = snap.playerRoomX;
    buf[gearBase + 4] = snap.playerRoomY;

    jsize n = env->GetArrayLength(out);
    if (n > SNAPSHOT_INTS) {
        n = SNAPSHOT_INTS;
    }
    env->SetIntArrayRegion(out, 0, n, buf);
}

/* Item-icon sheet (cell == item id, 16x16 cells, 16 cols x 8 rows; see
 * Port_SecondScreenRender_RenderIconSheetArgb). Returns false until the
 * ROM's sprite tables are loaded — Java retries on its tick until true,
 * then builds its Bitmap once. */
extern "C" JNIEXPORT jboolean JNICALL Java_dev_picori_tmc_GameStateNative_renderIconSheet(JNIEnv* env, jclass,
                                                                                           jintArray out) {
    const int n = SECOND_SCREEN_ICON_SHEET_W * SECOND_SCREEN_ICON_SHEET_H;
    if (env->GetArrayLength(out) < n) {
        return JNI_FALSE;
    }
    static uint32_t px[SECOND_SCREEN_ICON_SHEET_W * SECOND_SCREEN_ICON_SHEET_H];
    if (!Port_SecondScreenRender_RenderIconSheetArgb(px)) {
        return JNI_FALSE;
    }
    env->SetIntArrayRegion(out, 0, n, (const jint*)px);
    return JNI_TRUE;
}

/* Local-area map — the room the player is standing in, rendered from the
 * live tile data the primary screen is already using (see
 * Port_SecondScreenRender_RenderLocalMapArgb). Returns false outside
 * gameplay or before the area's tileset resolves.
 *
 * Writes straight into the Java int[]'s backing memory via
 * GetPrimitiveArrayCritical instead of rendering to a native scratch buffer
 * and SetIntArrayRegion-copying it over — this call happens often enough
 * (the map redraws as the player walks) that the extra 384x288-int copy
 * was a real, measurable chunk of the reported lag. Critical sections must
 * be short and make no other JNI calls, which the render function already
 * satisfies (pure memory writes, no callbacks). */
extern "C" JNIEXPORT jboolean JNICALL Java_dev_picori_tmc_GameStateNative_renderLocalMap(JNIEnv* env, jclass,
                                                                                          jintArray out) {
    const int n = SECOND_SCREEN_LOCAL_MAP_W * SECOND_SCREEN_LOCAL_MAP_H;
    if (env->GetArrayLength(out) < n) {
        return JNI_FALSE;
    }
    void* px = env->GetPrimitiveArrayCritical(out, nullptr);
    if (px == nullptr) {
        return JNI_FALSE;
    }
    jboolean ok = Port_SecondScreenRender_RenderLocalMapArgb((uint32_t*)px) ? JNI_TRUE : JNI_FALSE;
    env->ReleasePrimitiveArrayCritical(out, px, 0);
    return ok;
}

/* Whole current room, real tile detail — the zoomed-out map view (see
 * Port_SecondScreenRender_RenderRoomMapArgb). Only called on demand (map
 * opened/room changed while zoomed out), not per tick, so the plain
 * static-buffer + SetIntArrayRegion path is fine here. */
extern "C" JNIEXPORT jboolean JNICALL Java_dev_picori_tmc_GameStateNative_renderRoomMap(JNIEnv* env, jclass,
                                                                                         jintArray out) {
    const int n = SECOND_SCREEN_ROOM_MAP_W * SECOND_SCREEN_ROOM_MAP_H;
    if (env->GetArrayLength(out) < n) {
        return JNI_FALSE;
    }
    static uint32_t px[SECOND_SCREEN_ROOM_MAP_W * SECOND_SCREEN_ROOM_MAP_H];
    if (!Port_SecondScreenRender_RenderRoomMapArgb(px)) {
        return JNI_FALSE;
    }
    env->SetIntArrayRegion(out, 0, n, (const jint*)px);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_dev_picori_tmc_GameStateNative_requestEquip(JNIEnv*, jclass, jint itemId,
                                                                                    jint slot) {
    Port_SecondScreenState_RequestEquip((uint8_t)itemId, (uint8_t)(slot != 0));
}

/* Real heart glyphs (5-frame strip, empty..full) — see
 * Port_SecondScreenRender_RenderHeartSheetArgb. */
extern "C" JNIEXPORT jboolean JNICALL Java_dev_picori_tmc_GameStateNative_renderHeartSheet(JNIEnv* env, jclass,
                                                                                            jintArray out) {
    const int n = SECOND_SCREEN_HEART_SHEET_W * 16;
    if (env->GetArrayLength(out) < n) {
        return JNI_FALSE;
    }
    static uint32_t px[SECOND_SCREEN_HEART_SHEET_W * 16];
    if (!Port_SecondScreenRender_RenderHeartSheetArgb(px)) {
        return JNI_FALSE;
    }
    env->SetIntArrayRegion(out, 0, n, (const jint*)px);
    return JNI_TRUE;
}
