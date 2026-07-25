#include "port_second_screen_render.h"

#include "port_gba_mem.h"
#include "port_rom.h"
#include "sprite.h"
#include "structures.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

/* Defined in src/common.c, next to LoadPaletteGroup — needs the file-private
 * gPaletteGroups/PaletteGroup ROM tables, so it lives there rather than
 * here. Walks a palette group's entry chain, returning raw RGB555 bytes per
 * entry plus its destination bank (>= 16 means OBJ bank dest-16). */
extern const uint8_t* Port_GetRawPaletteGroupEntry(uint32_t group, uint32_t index, uint32_t* outDest,
                                                    uint32_t* outNumPals);

/* Mirrors src/menu/pauseMenu.c's GetSpriteAnimation322 (static there, so not
 * directly callable) — gSpriteAnimations_322 is a plain linked global, as is
 * gFrameObjLists (the per-frame OAM data blob, port_rom.c loads both). */
extern Frame* gSpriteAnimations_322[];
extern uint32_t gFrameObjLists[];
#define SPRITE_ANIM_322_COUNT 128
#define ICON_SPRITE_INDEX 322

/* The exact palette recipe was ground-truthed offline against the ROM by
 * tools/secondscreen/render_icons.py — keep the two in sync:
 *  - group 0xB (UI init, src/ui.c:127) parks the resident system palettes in
 *    OBJ banks 0..4; the item icons' own OAM palette nibbles reference these.
 *  - pause-menu group 182 layers its rows on OBJ banks 5..10.
 *  - each icon's bank = its frame's first obj entry byte 4 high nibble,
 *    added to the item screen's base bank 3 unless flags bit 0 replaces it
 *    (arm_DrawDirect, asm/src/intr.s:080B28C0..). */
#define RESIDENT_PALETTE_GROUP 0x0Bu
#define ICON_PALETTE_GROUP 182u
#define ICON_BASE_BANK 3u

static uint16_t sObjPal[16 * 16];
static bool sObjPalReady = false;

static bool EnsureObjPalettes(void) {
    if (sObjPalReady) {
        return true;
    }
    static const uint32_t groups[] = { RESIDENT_PALETTE_GROUP, ICON_PALETTE_GROUP };
    bool any = false;
    for (size_t g = 0; g < 2; g++) {
        for (uint32_t i = 0;; i++) {
            uint32_t dest = 0, numPals = 0;
            const uint8_t* src = Port_GetRawPaletteGroupEntry(groups[g], i, &dest, &numPals);
            if (src == NULL) {
                break;
            }
            if (dest < 16) {
                continue; /* BG rows — not what OBJ icons index */
            }
            uint32_t bank = dest - 16;
            if (bank + numPals > 16) {
                numPals = 16 - bank;
            }
            memcpy(&sObjPal[bank * 16], src, (size_t)numPals * 32u);
            any = true;
        }
    }
    if (any) {
        sObjPalReady = true;
    }
    return any;
}

/* One OAM piece of an icon frame (src/menu/pauseMenu.c's real per-icon
 * layout, mirrors port/port_draw.c's RenderSpritePieces byte format). Many
 * icons are NOT a single flat 16x16 block — badges/overlays/color variants
 * add extra pieces of their own shape/size/palette at their own signed
 * offset. Ground-truthed against the ROM by
 * tools/secondscreen/render_icons_v2.py, which found ~65 of the 127 items
 * are multi-piece — treating every icon as one 2x2-tile block (the previous
 * approach) reads the wrong tiles into the wrong spots for all of them,
 * which is the "distorted" icons seen on-device. */
typedef struct {
    int8_t x, y;
    uint8_t shape, size;
    uint32_t tileIndex; /* relative to the frame's SpriteFrame.firstTileIndex */
    uint32_t pal;
} IconPiece;

#define ICON_MAX_PIECES 8

/* (shape, size) -> (tileW, tileH), the GBA's own OAM shape/size table. */
static const uint8_t kOamTileDims[3][4][2] = {
    { { 1, 1 }, { 2, 2 }, { 4, 4 }, { 8, 8 } }, /* square */
    { { 2, 1 }, { 4, 1 }, { 4, 2 }, { 8, 4 } }, /* h-rect (wide) */
    { { 1, 2 }, { 1, 4 }, { 2, 4 }, { 4, 8 } }, /* v-rect (tall) */
};

/* Single-piece 16x16 icons carry offset (-8,-13) and land at cell (0,0) —
 * that's the caller's fixed anchor (gOamCmd.x/y in the real engine); every
 * piece's offset is relative to the same origin, so this recenters into
 * cell-local pixels. */
#define ICON_ANCHOR_X 8
#define ICON_ANCHOR_Y 13

static uint32_t GetFramePieces(uint32_t frameIndex, IconPiece* out, uint32_t maxPieces) {
    const uint8_t* base = (const uint8_t*)gFrameObjLists;
    uint32_t spriteOff = gFrameObjLists[ICON_SPRITE_INDEX];
    uint32_t frameOff;
    memcpy(&frameOff, base + spriteOff + frameIndex * 4u, 4);
    const uint8_t* lst = base + frameOff;
    uint32_t count = lst[0];
    if (count > maxPieces) {
        count = maxPieces;
    }
    const uint8_t* p = lst + 1;
    for (uint32_t i = 0; i < count; i++, p += 5) {
        out[i].x = (int8_t)p[0];
        out[i].y = (int8_t)p[1];
        uint8_t shpsz = p[2];
        out[i].shape = (shpsz >> 6) & 3u;
        out[i].size = (shpsz >> 4) & 3u;
        uint8_t bit0 = shpsz & 1u;
        uint32_t tlo = p[3], thi = p[4];
        out[i].tileIndex = tlo | ((thi & 0xFu) << 8);
        uint32_t pal = thi >> 4;
        if (!bit0) {
            pal = (pal + ICON_BASE_BANK) & 0xFu;
        }
        out[i].pal = pal;
    }
    return count;
}

typedef uint32_t (*ColorFn)(uint16_t);

static uint32_t Rgb555ToSurfaceRgba(uint16_t c) {
    uint32_t r = (c & 0x1Fu) << 3;
    uint32_t g = ((c >> 5) & 0x1Fu) << 3;
    uint32_t b = ((c >> 10) & 0x1Fu) << 3;
    return 0xFF000000u | (b << 16) | (g << 8) | r;
}

static uint32_t Rgb555ToArgbInt(uint16_t c) {
    uint32_t r = (c & 0x1Fu) << 3;
    uint32_t g = ((c >> 5) & 0x1Fu) << 3;
    uint32_t b = ((c >> 10) & 0x1Fu) << 3;
    return 0xFF000000u | (r << 16) | (g << 8) | b;
}

/* Shared blit for any direct frame index of sprite 322: composites every
 * real OAM piece of that frame (not a fixed 2x2 block — see IconPiece above)
 * at its own position/shape/size/palette, writing scale x scale blocks
 * through `toColor`. This is the raw path the engine itself uses for fixed
 * UI elements (src/ui.c's sub_0801CB20: `sprite->frames[element->frameIndex]`
 * directly — no per-item gSpriteAnimations_322[] indirection). Returns
 * false when tables aren't ready or the frame is empty. */
static bool BlitFrame(uint32_t* pixels, int32_t bufWidth, int32_t bufHeight, int32_t stride, int32_t x,
                      int32_t y, int32_t scale, uint32_t frameIndex, ColorFn toColor) {
    const SpritePtr* sprite = Port_GetSpritePtr(ICON_SPRITE_INDEX);
    if (sprite == NULL || sprite->frames == NULL || sprite->ptr == NULL) {
        return false;
    }
    if (!EnsureObjPalettes()) {
        return false;
    }

    const SpriteFrame* frame = &sprite->frames[frameIndex];
    if (frame->numTiles == 0) {
        return false;
    }
    const uint8_t* tileData = (const uint8_t*)sprite->ptr;

    IconPiece pieces[ICON_MAX_PIECES];
    uint32_t pieceCount = GetFramePieces(frameIndex, pieces, ICON_MAX_PIECES);
    if (pieceCount == 0) {
        return false;
    }

    for (uint32_t pi = 0; pi < pieceCount; pi++) {
        const IconPiece* pc = &pieces[pi];
        if (pc->shape > 2) {
            continue;
        }
        int32_t tw = kOamTileDims[pc->shape][pc->size][0];
        int32_t th = kOamTileDims[pc->shape][pc->size][1];
        const uint16_t* bankPal = &sObjPal[pc->pal * 16];
        int32_t originX = x + ((int32_t)pc->x + ICON_ANCHOR_X) * scale;
        int32_t originY = y + ((int32_t)pc->y + ICON_ANCHOR_Y) * scale;

        for (int32_t ty = 0; ty < th; ty++) {
            for (int32_t tx = 0; tx < tw; tx++) {
                uint32_t tileIdx = frame->firstTileIndex + pc->tileIndex + (uint32_t)(ty * tw + tx);
                const uint8_t* tile = tileData + (size_t)tileIdx * 32u;
                for (int32_t py = 0; py < 8; py++) {
                    for (int32_t px = 0; px < 8; px++) {
                        uint8_t packed = tile[py * 4 + px / 2];
                        uint8_t ci = (px & 1) ? (uint8_t)(packed >> 4) : (uint8_t)(packed & 0x0Fu);
                        if (ci == 0) {
                            continue; /* transparent */
                        }
                        uint32_t color = toColor(bankPal[ci]);
                        for (int32_t sy = 0; sy < scale; sy++) {
                            int32_t destY = originY + (ty * 8 + py) * scale + sy;
                            if (destY < 0 || destY >= bufHeight) {
                                continue;
                            }
                            for (int32_t sx = 0; sx < scale; sx++) {
                                int32_t destX = originX + (tx * 8 + px) * scale + sx;
                                if (destX < 0 || destX >= bufWidth) {
                                    continue;
                                }
                                pixels[(size_t)destY * (size_t)stride + (size_t)destX] = color;
                            }
                        }
                    }
                }
            }
        }
    }
    return true;
}

/* Item icons resolve itemId -> frame via gSpriteAnimations_322[itemId]
 * (src/menu/pauseMenu.c's GetSpriteAnimation322), then blit that frame. */
static bool BlitIcon(uint32_t* pixels, int32_t bufWidth, int32_t bufHeight, int32_t stride, int32_t x,
                     int32_t y, int32_t scale, uint8_t itemId, ColorFn toColor) {
    if (itemId == 0 || itemId >= SPRITE_ANIM_322_COUNT) {
        return false;
    }
    Frame* animation = (Frame*)port_resolve_addr((uintptr_t)gSpriteAnimations_322[itemId]);
    if (animation == NULL) {
        return false;
    }
    return BlitFrame(pixels, bufWidth, bufHeight, stride, x, y, scale, animation->index, toColor);
}

void Port_SecondScreenRender_DrawItemIcon(uint32_t* pixels, int32_t bufWidth, int32_t bufHeight, int32_t stride,
                                           int32_t x, int32_t y, int32_t scale, uint8_t itemId) {
    if (scale < 1) {
        scale = 1;
    }
    BlitIcon(pixels, bufWidth, bufHeight, stride, x, y, scale, itemId, Rgb555ToSurfaceRgba);
}

int Port_SecondScreenRender_RenderIconSheetArgb(uint32_t* px) {
    const SpritePtr* sprite = Port_GetSpritePtr(ICON_SPRITE_INDEX);
    if (sprite == NULL || sprite->frames == NULL || sprite->ptr == NULL || !EnsureObjPalettes()) {
        return 0;
    }
    memset(px, 0, (size_t)SECOND_SCREEN_ICON_SHEET_W * SECOND_SCREEN_ICON_SHEET_H * 4u);
    for (uint32_t item = 1; item < SPRITE_ANIM_322_COUNT; item++) {
        BlitIcon(px, SECOND_SCREEN_ICON_SHEET_W, SECOND_SCREEN_ICON_SHEET_H, SECOND_SCREEN_ICON_SHEET_W,
                 (int32_t)(item % SECOND_SCREEN_ICON_SHEET_COLS) * 16,
                 (int32_t)(item / SECOND_SCREEN_ICON_SHEET_COLS) * 16, 1, (uint8_t)item, Rgb555ToArgbInt);
    }
    return 1;
}

/* Real heart glyphs — src/ui.c's HeartUIElement: frames 0x71 (empty) through
 * 0x75 (full) of the SAME sprite 322 sheet items use, direct frame index
 * (no gSpriteAnimations_322[] indirection — fixed UI elements skip that,
 * see src/ui.c's sub_0801CB20). Ground-truthed against the ROM by
 * tools/secondscreen/render_icons.py's heart-frame check. */
#define HEART_FRAME_BASE 0x71u
#define HEART_FRAME_COUNT 5u

int Port_SecondScreenRender_RenderHeartSheetArgb(uint32_t* px) {
    if (!EnsureObjPalettes()) {
        return 0;
    }
    memset(px, 0, (size_t)SECOND_SCREEN_HEART_SHEET_W * 16u * 4u);
    for (uint32_t i = 0; i < HEART_FRAME_COUNT; i++) {
        BlitFrame(px, SECOND_SCREEN_HEART_SHEET_W, 16, SECOND_SCREEN_HEART_SHEET_W, (int32_t)(i * 16), 0, 1,
                  HEART_FRAME_BASE + i, Rgb555ToArgbInt);
    }
    return 1;
}
