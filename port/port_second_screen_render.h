#ifndef PORT_SECOND_SCREEN_RENDER_H
#define PORT_SECOND_SCREEN_RENDER_H

/*
 * Second-screen minimap/item-icon compositor (Phase 3d).
 *
 * Deliberately independent of the live PPU: reads item-icon tile graphics
 * and palette data straight from the ROM/asset layer (Port_GetSpritePtr,
 * Port_GetRawPaletteGroupData — the latter defined in src/common.c, next to
 * LoadPaletteGroup, because the ROM palette-group table it reads is
 * file-private there), not from virtuappu_frame_buffer/mode1_memory or the
 * live gPaletteBuffer. See port_second_screen.h's file comment and
 * port_softslots.h's cautionary note about an earlier native-framebuffer/OAM
 * UI attempt that corrupted pause-menu visuals — this module exists to
 * avoid repeating that mistake.
 *
 * No Android-specific types in this header on purpose: callers hand in a
 * plain pixel buffer (already locked/owned by whoever's presenting it), so
 * this compositor has no dependency on ANativeWindow and could in principle
 * run on any platform this port targets.
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Draws a single item icon (native 16x16px, sprite 322 — see
 * src/menu/pauseMenu.c's GetSpriteAnimation322) at (x,y), scaled up by
 * `scale` (each source pixel becomes a scale x scale block — nearest-
 * neighbor, matching the GBA's own blocky look rather than smoothing it),
 * into `pixels`, a `stride`-pixels-per-row RGBA8888 buffer of size
 * bufWidth x bufHeight. itemId 0 (unassigned) draws nothing. Out-of-bounds
 * destination pixels are clipped silently. */
void Port_SecondScreenRender_DrawItemIcon(uint32_t* pixels, int32_t bufWidth, int32_t bufHeight, int32_t stride,
                                           int32_t x, int32_t y, int32_t scale, uint8_t itemId);

/* Renders the full item-icon sheet for the Java/Canvas second-screen UI
 * (zelda3-android's SS_RenderIconSheet pattern): cell index == item id, 16
 * columns x 8 rows of 16x16 cells (256x128), so the Java side needs no
 * name->cell manifest — it indexes straight by the item id it got from the
 * snapshot. `px` receives Android Bitmap ARGB (A<<24|R<<16|G<<8|B) with
 * alpha-0 background, ready for Bitmap.createBitmap(). Returns 1 when the
 * sprite tables were ready and the sheet was written, 0 otherwise (caller
 * should retry later — tables appear once the ROM finishes loading). */
int Port_SecondScreenRender_RenderIconSheetArgb(uint32_t* px);

#define SECOND_SCREEN_ICON_SHEET_COLS 16
#define SECOND_SCREEN_ICON_SHEET_ROWS 8
#define SECOND_SCREEN_ICON_SHEET_W (SECOND_SCREEN_ICON_SHEET_COLS * 16)
#define SECOND_SCREEN_ICON_SHEET_H (SECOND_SCREEN_ICON_SHEET_ROWS * 16)

#ifdef __cplusplus
}
#endif

#endif /* PORT_SECOND_SCREEN_RENDER_H */
