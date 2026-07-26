#include "port_second_screen_state.h"

#include <string.h>

#ifdef __ANDROID__

#include <pthread.h>

#include "area.h"
#include "game.h"
#include "item.h"
#include "itemMetaData.h"
#include "main.h"
#include "map.h"
#include "player.h"
#include "room.h"
#include "save.h"

static SecondScreenSnapshot sSnapshot;
static pthread_mutex_t sSnapshotMutex = PTHREAD_MUTEX_INITIALIZER;

/* Pending tap-to-equip request from the UI thread, consumed by Publish().
 * itemId 0 = nothing pending. Guarded by sSnapshotMutex — same one-memcpy
 * discipline, never held across engine calls. */
static uint8_t sPendingEquipItem = 0;
static uint8_t sPendingEquipSlot = 0;

/* Port-side automap: which rooms of each area have been entered this
 * session. TMC's own per-room "visited" state is scattered across
 * area-specific local flags with no uniform room->flag mapping, so the
 * port tracks it directly — same approach as zelda3-android's visited-room
 * dungeon map. Game-thread only. */
static uint64_t sVisitedByArea[256];

void Port_SecondScreenState_Publish(void) {
    /* Assembled outside the lock: this runs on the game thread itself, the
     * same thread that owns gRoomControls/gPlayerEntity/gSave during normal
     * gameplay, so reading them here is exactly as safe as any other engine
     * code doing so — no cross-thread race on this side. The lock only
     * needs to guard the swap into sSnapshot, which the second-screen
     * thread does read cross-thread. */
    SecondScreenSnapshot next;
    memset(&next, 0, sizeof(next));

    uint8_t equipItem;
    uint8_t equipSlot;
    pthread_mutex_lock(&sSnapshotMutex);
    equipItem = sPendingEquipItem;
    equipSlot = sPendingEquipSlot;
    sPendingEquipItem = 0;
    pthread_mutex_unlock(&sSnapshotMutex);

    next.inGame = gMain.task == TASK_GAME;
    if (next.inGame) {
        /* Tap-to-equip goes through the engine's own path (swap handling,
         * HUD refresh) and only for items actually in the inventory — a
         * stale tap from a previous save can't equip something Link
         * doesn't own. */
        if (equipItem != 0 && GetInventoryValue(equipItem) == 1) {
            ForceEquipItem(equipItem, equipSlot ? EQUIP_SLOT_B : EQUIP_SLOT_A);
        }

        next.area = gRoomControls.area;
        next.room = gRoomControls.room;
        next.playerX = gPlayerEntity.base.x.HALF.HI;
        next.playerY = gPlayerEntity.base.y.HALF.HI;
        next.playerRoomX = next.playerX - (int32_t)gRoomControls.origin_x;
        next.playerRoomY = next.playerY - (int32_t)gRoomControls.origin_y;
        next.equippedA = gSave.stats.equipped[SLOT_A];
        next.equippedB = gSave.stats.equipped[SLOT_B];
        next.equippedSlotA = next.equippedA ? gItemMetaData[next.equippedA].menuSlot : 0xFF;
        next.equippedSlotB = next.equippedB ? gItemMetaData[next.equippedB].menuSlot : 0xFF;
        next.health = gSave.stats.health;
        next.maxHealth = gSave.stats.maxHealth;
        next.rupees = gSave.stats.rupees;
        next.kinstonesFused = gSave.kinstones.fusedCount;
        {
            uint8_t count = 0;
            for (u32 i = 0; i < 36; i++) {
                count += (gSave.figurines[i / 8] >> (i % 8)) & 1;
            }
            next.figurineCount = count;
        }
        next.elements = (uint8_t)((GetInventoryValue(ITEM_EARTH_ELEMENT) == 1 ? 1 : 0) |
                                   (GetInventoryValue(ITEM_FIRE_ELEMENT) == 1 ? 2 : 0) |
                                   (GetInventoryValue(ITEM_WATER_ELEMENT) == 1 ? 4 : 0) |
                                   (GetInventoryValue(ITEM_WIND_ELEMENT) == 1 ? 8 : 0));

        /* Mirror of the pause menu's item-screen fill loop
         * (src/menu/pauseMenu.c: PauseMenu_ItemMenu_Init): every owned
         * activatable item lands in its ItemMetaData menu slot; later item
         * ids overwrite earlier ones in the same slot, exactly like the
         * real menu. */
        for (u32 item = ITEM_SMITH_SWORD; item < ITEM_BOTTLE_EMPTY; item++) {
            if (GetInventoryValue(item) == 1) {
                u32 slot = gItemMetaData[item].menuSlot;
                if (slot < SECOND_SCREEN_ITEM_SLOTS) {
                    next.menuItems[slot] = (uint8_t)item;
                }
            }
        }
        for (u32 i = 0; i < 4; i++) {
            next.bottleContents[i] = gSave.stats.bottles[i];
        }

        for (u32 i = 0; i < SECOND_SCREEN_MAX_ROOMS && i < MAX_ROOMS; i++) {
            const RoomResInfo* info = &gArea.roomResInfos[i];
            next.rooms[i].x = info->map_x;
            next.rooms[i].y = info->map_y;
            next.rooms[i].w = info->pixel_width;
            next.rooms[i].h = info->pixel_height;
        }

        sVisitedByArea[next.area] |= 1ull << (next.room & 63);
        next.visitedMask = sVisitedByArea[next.area];

        /* Local-area map window, centered on the player, straight from the
         * live MapLayer the primary screen is already rendering from this
         * frame (gMapBottom) — real tile data, not a guessed ROM table.
         * Metatile grid coords follow the same formula the engine itself
         * uses for tile lookups (src/scroll.c's FillActTileForLayer): world
         * pixels minus the room origin, >>4 for 16px metatiles, &0x3F for
         * the 64x64 grid.
         *
         * This runs on the game thread every tick regardless of which
         * second-screen tab is visible, so it has to stay cheap: the
         * player only crosses into a new metatile every 8-16 frames of
         * normal walking, so most ticks just replay the last computed
         * window from a static cache instead of redoing the 24x18 nested
         * tile lookup. */
        {
            static int32_t sLastTileX = INT32_MIN, sLastTileY = INT32_MIN;
            static uint8_t sLastArea = 0xFF, sLastRoom = 0xFF;
            static uint16_t sCachedSubtilesBottom[SECOND_SCREEN_LOCAL_MAP_TILES_W * SECOND_SCREEN_LOCAL_MAP_TILES_H *
                                                   4];
            static uint16_t sCachedSubtilesTop[SECOND_SCREEN_LOCAL_MAP_TILES_W * SECOND_SCREEN_LOCAL_MAP_TILES_H *
                                                4];
            static uint16_t sCachedBgControlBottom, sCachedBgControlTop;

            int32_t playerTileX = (next.playerX - (int32_t)gRoomControls.origin_x) >> 4;
            int32_t playerTileY = (next.playerY - (int32_t)gRoomControls.origin_y) >> 4;

            if (playerTileX != sLastTileX || playerTileY != sLastTileY || next.area != sLastArea ||
                next.room != sLastRoom) {
                sLastTileX = playerTileX;
                sLastTileY = playerTileY;
                sLastArea = next.area;
                sLastRoom = next.room;

                int32_t startTileX = playerTileX - SECOND_SCREEN_LOCAL_MAP_TILES_W / 2;
                int32_t startTileY = playerTileY - SECOND_SCREEN_LOCAL_MAP_TILES_H / 2;
                for (int32_t ty = 0; ty < SECOND_SCREEN_LOCAL_MAP_TILES_H; ty++) {
                    for (int32_t tx = 0; tx < SECOND_SCREEN_LOCAL_MAP_TILES_W; tx++) {
                        uint32_t gx = (uint32_t)(startTileX + tx) & 0x3F;
                        uint32_t gy = (uint32_t)(startTileY + ty) & 0x3F;
                        uint32_t cell = (uint32_t)(ty * SECOND_SCREEN_LOCAL_MAP_TILES_W + tx) * 4;

                        uint16_t tileIdBottom = gMapBottom.mapData[gy * 64 + gx];
                        uint16_t* dstBottom = &sCachedSubtilesBottom[cell];
                        if (tileIdBottom < 0x4000) {
                            dstBottom[0] = gMapBottom.subTiles[tileIdBottom * 4 + 0];
                            dstBottom[1] = gMapBottom.subTiles[tileIdBottom * 4 + 1];
                            dstBottom[2] = gMapBottom.subTiles[tileIdBottom * 4 + 2];
                            dstBottom[3] = gMapBottom.subTiles[tileIdBottom * 4 + 3];
                        } else {
                            dstBottom[0] = dstBottom[1] = dstBottom[2] = dstBottom[3] = 0;
                        }

                        uint16_t tileIdTop = gMapTop.mapData[gy * 64 + gx];
                        uint16_t* dstTop = &sCachedSubtilesTop[cell];
                        if (tileIdTop < 0x4000) {
                            dstTop[0] = gMapTop.subTiles[tileIdTop * 4 + 0];
                            dstTop[1] = gMapTop.subTiles[tileIdTop * 4 + 1];
                            dstTop[2] = gMapTop.subTiles[tileIdTop * 4 + 2];
                            dstTop[3] = gMapTop.subTiles[tileIdTop * 4 + 3];
                        } else {
                            dstTop[0] = dstTop[1] = dstTop[2] = dstTop[3] = 0;
                        }
                    }
                }
                sCachedBgControlBottom = gMapBottom.bgSettings ? gMapBottom.bgSettings->control : 0;
                sCachedBgControlTop = gMapTop.bgSettings ? gMapTop.bgSettings->control : 0;
            }

            memcpy(next.localSubtilesBottom, sCachedSubtilesBottom, sizeof(sCachedSubtilesBottom));
            memcpy(next.localSubtilesTop, sCachedSubtilesTop, sizeof(sCachedSubtilesTop));
            next.bgControlBottom = sCachedBgControlBottom;
            next.bgControlTop = sCachedBgControlTop;
        }
    }

    pthread_mutex_lock(&sSnapshotMutex);
    sSnapshot = next;
    pthread_mutex_unlock(&sSnapshotMutex);
}

void Port_SecondScreenState_Read(SecondScreenSnapshot* out) {
    pthread_mutex_lock(&sSnapshotMutex);
    *out = sSnapshot;
    pthread_mutex_unlock(&sSnapshotMutex);
}

void Port_SecondScreenState_RequestEquip(uint8_t itemId, uint8_t slot) {
    pthread_mutex_lock(&sSnapshotMutex);
    sPendingEquipItem = itemId;
    sPendingEquipSlot = slot;
    pthread_mutex_unlock(&sSnapshotMutex);
}

#else /* !__ANDROID__ — no second-screen thread to publish to. */

void Port_SecondScreenState_Publish(void) {}

void Port_SecondScreenState_Read(SecondScreenSnapshot* out) {
    memset(out, 0, sizeof(*out));
}

void Port_SecondScreenState_RequestEquip(uint8_t itemId, uint8_t slot) {
    (void)itemId;
    (void)slot;
}

#endif
