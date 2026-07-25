package dev.picori.tmc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * The second-screen panel, drawn entirely with android.graphics.Canvas —
 * the zelda3-android MinimapView architecture (tab bar + parchment menu
 * boxes + a Java-side item-icon sheet rendered once from the player's own
 * ROM). Native code only supplies data: a per-tick packed snapshot
 * (GameStateNative.getSnapshot) and the icon sheet (renderIconSheet).
 * Everything visual — panels, grid, bracket cursor, hearts, tab bar — lives
 * here in Java, matching Sam's split: native state, Java pixels.
 *
 * Three tabs, switched by tapping the bar at the bottom: GEAR (equipped
 * loadout + hearts/rupees/kinstones/figurines/elements), MAP (live automap
 * with a pulsing player marker), ITEMS (the full 16-slot pause-menu grid,
 * tap to equip A / hold to equip B). Outside gameplay (title, file select,
 * cutscenes) an idle triforce mark is shown instead of any tab.
 */
public class SecondScreenView extends View {
    private static final long TICK_MS = 33;
    private static final long LONG_PRESS_MS = 350;

    private static final int TAB_GEAR = 0, TAB_MAP = 1, TAB_ITEMS = 2;
    private int tab = TAB_MAP;

    // TMC pause-menu palette: parchment/mint box on a dark backdrop, gold
    // trim, maroon active-tab highlight — matching the game's own menu art
    // rather than borrowing LTTP's brown-parchment look wholesale.
    private static final int COL_BG = 0xFF14120E;
    private static final int COL_BOX = 0xFFE4DCC2;
    private static final int COL_BOX_BORDER = 0xFF8A6A2C;
    private static final int COL_BOX_INNER = 0xFFFFFFFF;
    private static final int COL_CELL = 0xFFCFC49C;
    private static final int COL_CELL_EMPTY = 0xFFDCD3B4;
    private static final int COL_GOLD = 0xFFC89A2E;
    private static final int COL_TAG_A = 0xFF2E8B45;
    private static final int COL_TAG_B = 0xFF2E5EC8;
    private static final int COL_ROOM_UNSEEN = 0xFFB8AC86;
    private static final int COL_ROOM_SEEN = 0xFF6B9BC9;
    private static final int COL_ROOM_HERE = 0xFF8FC4F5;
    private static final int COL_HEART = 0xFFD82830;
    private static final int COL_HEART_EMPTY = 0xFFBCA888;
    private static final int COL_RUPEE = 0xFF3AA050;
    private static final int COL_TEXT_DARK = 0xFF3A2E18;
    private static final int COL_TAB_ACTIVE = 0xFF7A2020;
    private static final int COL_TAB_INACTIVE = 0xFF2A2418;
    private static final int COL_TAB_TEXT = 0xFFF0E8D0;
    private static final int COL_SIDEBAR = 0xFFD8E8C8;
    private static final int COL_RING_BG = 0xFF141414;
    private static final int[] ELEMENT_COLORS = { 0xFF3AA050, 0xFFD84030, 0xFF3080D8, 0xFFD8D030 };
    private static final String[] ELEMENT_NAMES = { "EARTH", "FIRE", "WATER", "WIND" };

    private final int[] snap = new int[GameStateNative.snapshotSize()];
    private Bitmap iconSheet;
    private final int[] sheetBuf = new int[GameStateNative.ICON_SHEET_W * GameStateNative.ICON_SHEET_H];
    private Bitmap heartSheet;
    private final int[] heartBuf = new int[GameStateNative.HEART_SHEET_W * GameStateNative.HEART_SHEET_H];

    private final Paint fill = new Paint();
    private final Paint icons = new Paint(); // deliberately unfiltered: nearest-neighbor pixel art
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect src = new Rect();
    private final RectF rect = new RectF();
    private float u = 1f; // uniform unit, scaled off panel height like Sam's `u`

    private final RectF[] cellRects = new RectF[GameStateNative.ITEM_SLOTS];
    private final int[] cellItems = new int[GameStateNative.ITEM_SLOTS];
    private final RectF tabGearR = new RectF(), tabMapR = new RectF(), tabItemsR = new RectF();
    private int downSlot = -1;
    private long downTime;
    private boolean downOnGrid;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            GameStateNative.getSnapshot(snap);
            if (iconSheet == null && GameStateNative.renderIconSheet(sheetBuf)) {
                iconSheet = Bitmap.createBitmap(sheetBuf, GameStateNative.ICON_SHEET_W,
                        GameStateNative.ICON_SHEET_H, Bitmap.Config.ARGB_8888);
            }
            if (heartSheet == null && GameStateNative.renderHeartSheet(heartBuf)) {
                heartSheet = Bitmap.createBitmap(heartBuf, GameStateNative.HEART_SHEET_W,
                        GameStateNative.HEART_SHEET_H, Bitmap.Config.ARGB_8888);
            }
            invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public SecondScreenView(Context context) {
        super(context);
        icons.setFilterBitmap(false); // nearest-neighbor: keep the GBA's blocky pixel look, no smoothing
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        for (int i = 0; i < cellRects.length; i++) {
            cellRects[i] = new RectF();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        fill.setShader(null);
        fill.setColor(COL_BG);
        c.drawRect(0, 0, w, h, fill);

        if (snap[GameStateNative.IN_GAME] == 0) {
            drawIdle(c, w, h);
            return;
        }

        u = h / 720f;
        float tabH = 84 * u;
        float pad = 10 * u;
        float sideW = 150 * u;

        RectF main = new RectF(pad, pad, w - sideW - pad, h - tabH - pad);
        RectF side = new RectF(w - sideW, pad, w - pad, h - tabH - pad);

        if (tab == TAB_GEAR) {
            drawGearPanel(c, main);
        } else if (tab == TAB_ITEMS) {
            drawItemsPanel(c, main);
        } else {
            drawMapPanel(c, main);
        }
        drawSidebar(c, side);
        drawTabBar(c, w, h, tabH);
    }

    // ---- idle (title/cutscene) ----

    private void drawIdle(Canvas c, int w, int h) {
        float r = Math.min(w, h) / 5f;
        float cx = w / 2f, cy = h / 2f - r / 2f;
        fill.setColor(COL_GOLD);
        drawTri(c, cx, cy - r, r);
        drawTri(c, cx - r, cy, r);
        drawTri(c, cx + r, cy, r);
    }

    private void drawTri(Canvas c, float cx, float top, float size) {
        path.reset();
        path.moveTo(cx, top);
        path.lineTo(cx - size, top + size);
        path.lineTo(cx + size, top + size);
        path.close();
        c.drawPath(path, fill);
    }

    // ---- menu chrome (Sam's menuBox: parchment fill, gold border, corner dots) ----

    private void menuBox(Canvas c, RectF r) {
        fill.setColor(COL_BOX);
        c.drawRoundRect(r, 10 * u, 10 * u, fill);
        stroke.setStrokeWidth(4 * u);
        stroke.setColor(COL_BOX_BORDER);
        rect.set(r.left + 3 * u, r.top + 3 * u, r.right - 3 * u, r.bottom - 3 * u);
        c.drawRoundRect(rect, 8 * u, 8 * u, stroke);
        stroke.setStrokeWidth(2 * u);
        stroke.setColor(Color.argb(140, 255, 255, 255));
        rect.set(r.left + 7 * u, r.top + 7 * u, r.right - 7 * u, r.bottom - 7 * u);
        c.drawRoundRect(rect, 6 * u, 6 * u, stroke);
        fill.setColor(COL_BOX_INNER);
        float d = 3.5f * u;
        c.drawCircle(r.left + 8 * u, r.top + 8 * u, d, fill);
        c.drawCircle(r.right - 8 * u, r.top + 8 * u, d, fill);
        c.drawCircle(r.left + 8 * u, r.bottom - 8 * u, d, fill);
        c.drawCircle(r.right - 8 * u, r.bottom - 8 * u, d, fill);
    }

    private void title(Canvas c, RectF r, String s) {
        text.setColor(COL_TEXT_DARK);
        text.setTextSize(22 * u);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, r.centerX(), r.top + 30 * u, text);
    }

    /** Dashed corner brackets (not a full border) — the equip cursor look
     *  from the reference screenshot, distinct from a plain highlighted box.
     *  One color for "equipped" full stop; the sidebar rings carry the A/B
     *  distinction, matching Sam's split between grid highlight and rings. */
    private void bracketCursor(Canvas c, RectF r) {
        float len = Math.min(r.width(), r.height()) * 0.3f;
        stroke.setStrokeWidth(3.5f * u);
        stroke.setColor(0xFFE86A2E);
        stroke.setPathEffect(new DashPathEffect(new float[] { 5 * u, 4 * u }, 0));
        path.reset();
        path.moveTo(r.left, r.top + len); path.lineTo(r.left, r.top); path.lineTo(r.left + len, r.top);
        path.moveTo(r.right - len, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + len);
        path.moveTo(r.right, r.bottom - len); path.lineTo(r.right, r.bottom); path.lineTo(r.right - len, r.bottom);
        path.moveTo(r.left + len, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - len);
        c.drawPath(path, stroke);
        stroke.setPathEffect(null);
    }

    // ---- MAP tab ----

    private void drawMapPanel(Canvas c, RectF r) {
        menuBox(c, r);
        title(c, r, "MAP");

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < GameStateNative.MAX_ROOMS; i++) {
            int b = GameStateNative.ROOMS + i * 4;
            if (snap[b + 2] == 0 || snap[b + 3] == 0) continue;
            minX = Math.min(minX, snap[b]);
            minY = Math.min(minY, snap[b + 1]);
            maxX = Math.max(maxX, snap[b] + snap[b + 2]);
            maxY = Math.max(maxY, snap[b + 1] + snap[b + 3]);
        }
        if (minX >= maxX || minY >= maxY) return;

        float top = r.top + 44 * u, bottom = r.bottom - 14 * u;
        float pad = (bottom - top) / 14f;
        float scale = Math.min((r.width() - 28 * u - 2 * pad) / (maxX - minX), (bottom - top - 2 * pad) / (maxY - minY));
        float ox = r.left + 14 * u + ((r.width() - 28 * u) - (maxX - minX) * scale) / 2f;
        float oy = top + ((bottom - top) - (maxY - minY) * scale) / 2f;

        long visited = (snap[GameStateNative.VISITED_LO] & 0xFFFFFFFFL)
                | ((long) snap[GameStateNative.VISITED_HI] << 32);
        int here = snap[GameStateNative.ROOM];

        for (int i = 0; i < GameStateNative.MAX_ROOMS; i++) {
            int b = GameStateNative.ROOMS + i * 4;
            if (snap[b + 2] == 0 || snap[b + 3] == 0) continue;
            rect.set(ox + (snap[b] - minX) * scale, oy + (snap[b + 1] - minY) * scale,
                    ox + (snap[b] - minX + snap[b + 2]) * scale,
                    oy + (snap[b + 1] - minY + snap[b + 3]) * scale);
            rect.inset(1.5f, 1.5f);
            boolean seen = ((visited >> (i & 63)) & 1) != 0;
            fill.setColor(i == here ? COL_ROOM_HERE : seen ? COL_ROOM_SEEN : COL_ROOM_UNSEEN);
            c.drawRoundRect(rect, 3f, 3f, fill);
        }

        float px = ox + (snap[GameStateNative.PLAYER_X] - minX) * scale;
        float py = oy + (snap[GameStateNative.PLAYER_Y] - minY) * scale;
        float dot = Math.max(4f, (bottom - top) / 44f);
        float pulse = (float) (0.5 + 0.5 * Math.sin(android.os.SystemClock.uptimeMillis() / 250.0));
        fill.setShader(new RadialGradient(px, py, dot * 3f,
                Color.argb((int) (110 * pulse), 255, 235, 120), 0, Shader.TileMode.CLAMP));
        c.drawCircle(px, py, dot * 3f, fill);
        fill.setShader(null);
        fill.setColor(0xFF000000);
        c.drawCircle(px, py, dot + 1.5f, fill);
        fill.setColor(COL_GOLD);
        c.drawCircle(px, py, dot, fill);
    }

    // ---- ITEMS tab ----

    private void drawItemsPanel(Canvas c, RectF r) {
        menuBox(c, r);
        title(c, r, "ITEMS");

        final int cols = 4, rows = 4;
        float top = r.top + 44 * u, bottom = r.bottom - 14 * u;
        float cellW = (r.width() - 28 * u) / cols, cellH = (bottom - top) / rows;
        float cell = Math.min(cellW, cellH);
        float gridX = r.left + 14 * u + ((r.width() - 28 * u) - cell * cols) / 2f;
        float gridY = top + ((bottom - top) - cell * rows) / 2f;
        float gap = cell / 11f;

        int slotA = snap[GameStateNative.EQUIPPED_SLOT_A];
        int slotB = snap[GameStateNative.EQUIPPED_SLOT_B];

        for (int slot = 0; slot < GameStateNative.ITEM_SLOTS; slot++) {
            float cx0 = gridX + (slot % cols) * cell + gap;
            float cy0 = gridY + (slot / cols) * cell + gap;
            float cx1 = cx0 + cell - 2 * gap;
            float cy1 = cy0 + cell - 2 * gap;
            cellRects[slot].set(cx0, cy0, cx1, cy1);

            int itemId = snap[GameStateNative.MENU_ITEMS + slot];
            cellItems[slot] = itemId;

            rect.set(cx0, cy0, cx1, cy1);
            fill.setColor(itemId != 0 ? COL_CELL : COL_CELL_EMPTY);
            c.drawRoundRect(rect, gap, gap, fill);
            stroke.setStrokeWidth(1.5f * u);
            stroke.setColor(Color.argb(90, 0, 0, 0));
            c.drawRoundRect(rect, gap, gap, stroke);

            if (itemId == 0) continue;

            int iconId = itemId;
            if (slot >= 12) {
                int content = snap[GameStateNative.BOTTLES + (slot - 12)];
                if (content != 0) iconId = content;
            }
            drawIconCell(c, iconId, cx0, cy0, cx1, cy1);

            boolean isA = slot == slotA, isB = slot == slotB;
            if (isA || isB) {
                rect.set(cx0, cy0, cx1, cy1);
                bracketCursor(c, rect);
                float chip = cell / 4.6f;
                float chipX = isA ? cx0 : cx1 - chip;
                rect.set(chipX, cy0, chipX + chip, cy0 + chip);
                fill.setColor(isA ? COL_TAG_A : COL_TAG_B);
                c.drawRoundRect(rect, chip / 4f, chip / 4f, fill);
                text.setColor(0xFFF0EAD0);
                text.setTextSize(chip * 0.68f);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(isA ? "A" : "B", chipX + chip / 2f,
                        cy0 + chip / 2f - (text.ascent() + text.descent()) / 2f, text);
            }
        }
    }

    private void drawIconCell(Canvas c, int iconId, float cx0, float cy0, float cx1, float cy1) {
        if (iconSheet == null || iconId >= 128) return;
        int sx = (iconId % GameStateNative.ICON_SHEET_COLS) * GameStateNative.ICON_CELL;
        int sy = (iconId / GameStateNative.ICON_SHEET_COLS) * GameStateNative.ICON_CELL;
        src.set(sx, sy, sx + GameStateNative.ICON_CELL, sy + GameStateNative.ICON_CELL);
        float inset = (cx1 - cx0) / 8f;
        rect.set(cx0 + inset, cy0 + inset, cx1 - inset, cy1 - inset);
        c.drawBitmap(iconSheet, src, rect, icons);
    }

    // ---- GEAR tab (quest progress — equipped A/B + hearts + rupees live in
    // the always-visible sidebar now, so this tab covers what doesn't) ----

    private void drawGearPanel(Canvas c, RectF r) {
        menuBox(c, r);
        title(c, r, "GEAR");

        float x0 = r.left + 24 * u;
        float y0 = r.top + 70 * u;

        // Elements.
        float elCell = Math.max(28 * u, (r.width() - 48 * u) / 4.4f);
        text.setColor(COL_TEXT_DARK);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(13 * u);
        c.drawText("ELEMENTS", x0, y0 - 10 * u, text);
        int elements = snap[GameStateNative.ELEMENTS];
        for (int i = 0; i < 4; i++) {
            float cx = x0 + i * elCell + elCell * 0.4f, cy = y0 + elCell * 0.4f;
            boolean got = (elements & (1 << i)) != 0;
            fill.setColor(got ? ELEMENT_COLORS[i] : 0xFFB0A484);
            c.drawCircle(cx, cy, elCell * 0.34f, fill);
            stroke.setStrokeWidth(2 * u);
            stroke.setColor(COL_BOX_BORDER);
            c.drawCircle(cx, cy, elCell * 0.34f, stroke);
        }

        // Kinstones + figurines.
        float statY = y0 + elCell + 36 * u;
        text.setTextSize(16 * u);
        text.setColor(COL_TEXT_DARK);
        c.drawText("KINSTONES FUSED   " + snap[GameStateNative.KINSTONES_FUSED], x0, statY, text);
        c.drawText("FIGURINES FOUND   " + snap[GameStateNative.FIGURINE_COUNT], x0, statY + 30 * u, text);
    }

    // ---- sidebar (always visible: hearts, equipped A/B rings, rupees) ----

    private void drawSidebar(Canvas c, RectF r) {
        fill.setColor(COL_SIDEBAR);
        c.drawRoundRect(r, 10 * u, 10 * u, fill);
        stroke.setStrokeWidth(4 * u);
        stroke.setColor(COL_BOX_BORDER);
        c.drawRoundRect(r, 10 * u, 10 * u, stroke);

        float w = r.width();

        // Hearts: 2 per row so they stay legible in a narrow column.
        int health = snap[GameStateNative.HEALTH];
        int maxHealth = snap[GameStateNative.MAX_HEALTH];
        int heartCount = Math.min(maxHealth / 8, 20);
        float hs = Math.min(30 * u, (w - 16 * u) / 2f);
        float hx0 = r.left + (w - 2 * hs) / 2f;
        float hy = r.top + 10 * u;
        for (int i = 0; i < heartCount; i++) {
            int units = Math.max(0, Math.min(8, health - i * 8));
            drawHeartCell(c, hx0 + (i % 2) * hs, hy + (i / 2) * hs * 0.95f, hs * 0.9f, units);
        }
        int heartRows = Math.max((heartCount + 1) / 2, 1);

        // Equipped A / B rings, centered in the space between hearts and the
        // rupee chip at the bottom.
        float ringsTop = hy + heartRows * hs * 0.95f + 14 * u;
        float chipH = 56 * u;
        float ringsBottom = r.bottom - chipH - 10 * u;
        float ringR = Math.min((w - 20 * u) / 2f, (ringsBottom - ringsTop) / 4f - 6 * u);
        float rcx = r.centerX();
        float cyA = ringsTop + ringR + (ringsBottom - ringsTop - 4 * ringR) / 3f;
        float cyB = cyA + 2 * ringR + (ringsBottom - ringsTop - 4 * ringR) / 3f;
        drawItemRing(c, rcx, cyA, ringR, snap[GameStateNative.EQUIPPED_A], "A", COL_TAG_A);
        drawItemRing(c, rcx, cyB, ringR, snap[GameStateNative.EQUIPPED_B], "B", COL_TAG_B);

        // Rupee chip, anchored to the bottom.
        float cy0 = r.bottom - chipH;
        rect.set(r.left + 6 * u, cy0, r.right - 6 * u, r.bottom - 6 * u);
        fill.setColor(COL_BOX);
        c.drawRoundRect(rect, 8 * u, 8 * u, fill);
        stroke.setStrokeWidth(2.5f * u);
        stroke.setColor(COL_BOX_BORDER);
        c.drawRoundRect(rect, 8 * u, 8 * u, stroke);
        String rupees = String.valueOf(snap[GameStateNative.RUPEES]);
        text.setTextSize(18 * u);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(COL_TEXT_DARK);
        float ty = rect.bottom - chipH / 2f - (text.ascent() + text.descent()) / 2f;
        c.drawText(rupees, rect.right - 10 * u, ty, text);
        float rw = text.measureText(rupees);
        float rs = 10 * u;
        float rcxp = rect.right - 10 * u - rw - rs * 1.6f, rcyp = ty + (text.ascent() + text.descent()) / 2f;
        path.reset();
        path.moveTo(rcxp, rcyp - rs);
        path.lineTo(rcxp + rs * 0.7f, rcyp);
        path.lineTo(rcxp, rcyp + rs);
        path.lineTo(rcxp - rs * 0.7f, rcyp);
        path.close();
        fill.setColor(COL_RUPEE);
        c.drawPath(path, fill);
    }

    /** One equipped-item ring: dark circle, gold trim, the item's icon
     *  centered inside, and a small lettered badge on the ring's edge. */
    private void drawItemRing(Canvas c, float cx, float cy, float r, int itemId, String label, int labelColor) {
        fill.setColor(COL_RING_BG);
        c.drawCircle(cx, cy, r, fill);
        stroke.setStrokeWidth(5 * u);
        stroke.setColor(0xFF8A6A2C);
        c.drawCircle(cx, cy, r, stroke);
        stroke.setStrokeWidth(2 * u);
        stroke.setColor(COL_GOLD);
        c.drawCircle(cx, cy, r - 3 * u, stroke);

        if (itemId != 0) {
            float is = r * 1.35f;
            drawIconCell(c, itemId, cx - is / 2f, cy - is / 2f, cx + is / 2f, cy + is / 2f);
        }

        float bx = cx + r * 0.72f, by = cy - r * 0.72f;
        float br = Math.max(10 * u, r * 0.24f);
        fill.setColor(labelColor);
        c.drawCircle(bx, by, br, fill);
        stroke.setStrokeWidth(2 * u);
        stroke.setColor(COL_GOLD);
        c.drawCircle(bx, by, br, stroke);
        text.setColor(0xFFF0EAD0);
        text.setTextSize(br * 1.1f);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, bx, by - (text.ascent() + text.descent()) / 2f, text);
    }

    /** Real heart glyph from the ROM's own sprite 322 frames 0x71-0x75
     *  (GameStateNative.renderHeartSheet) — units 0..8 (eighths per heart,
     *  matching the engine's own health units) picks one of the 5 quarter
     *  states. Falls back to a plain vector heart only until the ROM's
     *  sprite tables finish loading. */
    private void drawHeartCell(Canvas c, float x, float y, float s, int units) {
        if (heartSheet == null) {
            drawHeartVector(c, x, y, s, units / 8f);
            return;
        }
        int frame = Math.max(0, Math.min(GameStateNative.HEART_FRAMES - 1, Math.round(units / 2f)));
        int sx = frame * GameStateNative.HEART_SHEET_H;
        src.set(sx, 0, sx + GameStateNative.HEART_SHEET_H, GameStateNative.HEART_SHEET_H);
        rect.set(x, y, x + s, y + s);
        c.drawBitmap(heartSheet, src, rect, icons);
    }

    /** Vector heart fallback; fillFrac 0..1 fills left-to-right (half hearts). */
    private void drawHeartVector(Canvas c, float x, float y, float s, float fillFrac) {
        path.reset();
        path.moveTo(x + s / 2f, y + s * 0.92f);
        path.cubicTo(x - s * 0.22f, y + s * 0.55f, x + s * 0.02f, y + s * 0.02f, x + s / 2f, y + s * 0.30f);
        path.cubicTo(x + s * 0.98f, y + s * 0.02f, x + s * 1.22f, y + s * 0.55f, x + s / 2f, y + s * 0.92f);
        path.close();
        fill.setColor(COL_HEART_EMPTY);
        c.drawPath(path, fill);
        if (fillFrac > 0f) {
            c.save();
            c.clipRect(x - s * 0.25f, y, x - s * 0.25f + s * 1.5f * fillFrac, y + s);
            fill.setColor(COL_HEART);
            c.drawPath(path, fill);
            c.restore();
        }
    }

    // ---- tab bar ----

    private void drawTabBar(Canvas c, int w, int h, float tabH) {
        float y = h - tabH + 4 * u;
        float bh = tabH - 14 * u;
        float x0 = 8 * u, xr = w - 8 * u, gap = 8 * u;
        float bw = (xr - x0 - 2 * gap) / 3f;
        tabGearR.set(x0, y, x0 + bw, y + bh);
        tabMapR.set(x0 + bw + gap, y, x0 + 2 * bw + gap, y + bh);
        tabItemsR.set(x0 + 2 * (bw + gap), y, x0 + 3 * bw + 2 * gap, y + bh);
        drawTabButton(c, tabGearR, "GEAR", tab == TAB_GEAR);
        drawTabButton(c, tabMapR, "MAP", tab == TAB_MAP);
        drawTabButton(c, tabItemsR, "ITEMS", tab == TAB_ITEMS);
    }

    private void drawTabButton(Canvas c, RectF r, String label, boolean active) {
        fill.setColor(active ? COL_TAB_ACTIVE : COL_TAB_INACTIVE);
        c.drawRoundRect(r, 8 * u, 8 * u, fill);
        stroke.setStrokeWidth(3 * u);
        stroke.setColor(active ? COL_GOLD : COL_BOX_BORDER);
        rect.set(r.left + 2 * u, r.top + 2 * u, r.right - 2 * u, r.bottom - 2 * u);
        c.drawRoundRect(rect, 6 * u, 6 * u, stroke);
        text.setColor(COL_TAB_TEXT);
        text.setTextSize(14 * u);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2f, text);
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (tabGearR.contains(x, y) || tabMapR.contains(x, y) || tabItemsR.contains(x, y)) {
                    downOnGrid = false;
                    return true;
                }
                downOnGrid = tab == TAB_ITEMS;
                downSlot = downOnGrid ? hitSlot(x, y) : -1;
                downTime = e.getEventTime();
                return downOnGrid && downSlot >= 0;
            case MotionEvent.ACTION_UP:
                if (tabGearR.contains(x, y)) { tab = TAB_GEAR; performClick(); }
                else if (tabMapR.contains(x, y)) { tab = TAB_MAP; performClick(); }
                else if (tabItemsR.contains(x, y)) { tab = TAB_ITEMS; performClick(); }
                else if (downOnGrid) {
                    int slot = hitSlot(x, y);
                    if (slot >= 0 && slot == downSlot && cellItems[slot] != 0) {
                        boolean longPress = e.getEventTime() - downTime >= LONG_PRESS_MS;
                        GameStateNative.requestEquip(cellItems[slot], longPress ? 1 : 0);
                        performClick();
                    }
                }
                downSlot = -1;
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int hitSlot(float x, float y) {
        for (int i = 0; i < cellRects.length; i++) {
            if (cellRects[i].contains(x, y)) return i;
        }
        return -1;
    }
}
