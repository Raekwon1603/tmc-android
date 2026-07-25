package dev.picori.tmc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
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
 * the zelda3-android MinimapView architecture. Native code only supplies
 * data: a per-tick game snapshot and a one-time item-icon sheet rendered
 * from the player's own ROM (GameStateNative). Everything visual lives
 * here, where scaling, alpha, gradients and text are free.
 *
 * Layout (landscape): live automap panel on the left, 4x4 touch inventory
 * on the right, hearts + rupees along the bottom of the item side. Tap a
 * grid item to equip it to A; press-and-hold equips to B. Outside gameplay
 * (title, file select, cutscenes) an idle triforce mark is shown instead.
 */
public class SecondScreenView extends View {
    private static final long TICK_MS = 33;
    private static final long LONG_PRESS_MS = 350;

    // TMC pause-menu inspired theme.
    private static final int COL_BG = 0xFF14120E;
    private static final int COL_PANEL = 0xFF1D2418;
    private static final int COL_PANEL_EDGE = 0xFFC8A840;
    private static final int COL_CELL = 0xFF23262E;
    private static final int COL_CELL_EMPTY = 0xFF1A1C22;
    private static final int COL_GOLD = 0xFFE8C848;
    private static final int COL_TAG_A = 0xFF3FB954;
    private static final int COL_TAG_B = 0xFF5580F0;
    private static final int COL_ROOM_UNSEEN = 0xFF262C34;
    private static final int COL_ROOM_SEEN = 0xFF3E6EA8;
    private static final int COL_ROOM_HERE = 0xFF74B0F0;
    private static final int COL_HEART = 0xFFE83038;
    private static final int COL_HEART_EMPTY = 0xFF3A2226;
    private static final int COL_RUPEE = 0xFF48C858;
    private static final int COL_TEXT = 0xFFF0EAD0;

    private final int[] snap = new int[GameStateNative.snapshotSize()];
    private Bitmap iconSheet;
    private final int[] sheetBuf = new int[GameStateNative.ICON_SHEET_W * GameStateNative.ICON_SHEET_H];

    private final Paint fill = new Paint();
    private final Paint icons = new Paint(); // deliberately unfiltered: nearest-neighbor pixel art
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect src = new Rect();
    private final RectF rect = new RectF();

    private final RectF[] cellRects = new RectF[GameStateNative.ITEM_SLOTS];
    private final int[] cellItems = new int[GameStateNative.ITEM_SLOTS];
    private int downSlot = -1;
    private long downTime;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            GameStateNative.getSnapshot(snap);
            if (iconSheet == null && GameStateNative.renderIconSheet(sheetBuf)) {
                iconSheet = Bitmap.createBitmap(sheetBuf, GameStateNative.ICON_SHEET_W,
                        GameStateNative.ICON_SHEET_H, Bitmap.Config.ARGB_8888);
            }
            invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public SecondScreenView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
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
        fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF1A1712, COL_BG, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        if (snap[GameStateNative.IN_GAME] == 0) {
            drawIdle(c, w, h);
            return;
        }

        float pad = h / 32f;
        float split = w * 0.45f;
        float statusH = h / 7f;

        drawMapPanel(c, pad, pad, split - pad, h - pad);
        drawItemGrid(c, split + pad, pad, w - pad, h - statusH - pad);
        drawStatus(c, split + pad, h - statusH, w - pad, h - pad);
    }

    // ---- panels ----

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

    private void panel(Canvas c, float x0, float y0, float x1, float y1) {
        float rad = (y1 - y0) / 28f;
        rect.set(x0, y0, x1, y1);
        fill.setColor(COL_PANEL);
        c.drawRoundRect(rect, rad, rad, fill);
        stroke.setColor(COL_PANEL_EDGE);
        stroke.setStrokeWidth(Math.max(2f, rad / 3f));
        c.drawRoundRect(rect, rad, rad, stroke);
    }

    private void drawMapPanel(Canvas c, float x0, float y0, float x1, float y1) {
        panel(c, x0, y0, x1, y1);

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

        float pad = (y1 - y0) / 14f;
        float scale = Math.min((x1 - x0 - 2 * pad) / (maxX - minX), (y1 - y0 - 2 * pad) / (maxY - minY));
        float ox = x0 + ((x1 - x0) - (maxX - minX) * scale) / 2f;
        float oy = y0 + ((y1 - y0) - (maxY - minY) * scale) / 2f;

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

        // Player marker: gold dot with a soft pulse halo at the true
        // area-space position.
        float px = ox + (snap[GameStateNative.PLAYER_X] - minX) * scale;
        float py = oy + (snap[GameStateNative.PLAYER_Y] - minY) * scale;
        float dot = Math.max(4f, (y1 - y0) / 44f);
        float pulse = (float) (0.5 + 0.5 * Math.sin(android.os.SystemClock.uptimeMillis() / 250.0));
        fill.setShader(new RadialGradient(px, py, dot * 3f,
                Color.argb((int) (90 * pulse), 255, 235, 120), 0, Shader.TileMode.CLAMP));
        c.drawCircle(px, py, dot * 3f, fill);
        fill.setShader(null);
        fill.setColor(0xFF000000);
        c.drawCircle(px, py, dot + 1.5f, fill);
        fill.setColor(COL_GOLD);
        c.drawCircle(px, py, dot, fill);
    }

    private void drawItemGrid(Canvas c, float x0, float y0, float x1, float y1) {
        final int cols = 4, rows = 4;
        float cellW = (x1 - x0) / cols, cellH = (y1 - y0) / rows;
        float cell = Math.min(cellW, cellH);
        float gap = cell / 11f;

        int slotA = snap[GameStateNative.EQUIPPED_SLOT_A];
        int slotB = snap[GameStateNative.EQUIPPED_SLOT_B];

        for (int slot = 0; slot < GameStateNative.ITEM_SLOTS; slot++) {
            float cx0 = x0 + (slot % cols) * cellW + gap;
            float cy0 = y0 + (slot / cols) * cellH + gap;
            float cx1 = cx0 + cell - 2 * gap;
            float cy1 = cy0 + cell - 2 * gap;
            cellRects[slot].set(cx0, cy0, cx1, cy1);

            int itemId = snap[GameStateNative.MENU_ITEMS + slot];
            cellItems[slot] = itemId;

            rect.set(cx0, cy0, cx1, cy1);
            fill.setColor(itemId != 0 ? COL_CELL : COL_CELL_EMPTY);
            c.drawRoundRect(rect, gap, gap, fill);

            if (itemId == 0) continue;

            // Bottles show their contents (the icon players recognize).
            int iconId = itemId;
            if (slot >= 12) {
                int content = snap[GameStateNative.BOTTLES + (slot - 12)];
                if (content != 0) iconId = content;
            }

            if (iconSheet != null && iconId < 128) {
                int sx = (iconId % GameStateNative.ICON_SHEET_COLS) * GameStateNative.ICON_CELL;
                int sy = (iconId / GameStateNative.ICON_SHEET_COLS) * GameStateNative.ICON_CELL;
                src.set(sx, sy, sx + GameStateNative.ICON_CELL, sy + GameStateNative.ICON_CELL);
                float inset = (cx1 - cx0) / 8f;
                rect.set(cx0 + inset, cy0 + inset, cx1 - inset, cy1 - inset);
                c.drawBitmap(iconSheet, src, rect, icons);
            }

            boolean isA = slot == slotA, isB = slot == slotB;
            if (isA || isB) {
                rect.set(cx0, cy0, cx1, cy1);
                stroke.setColor(COL_GOLD);
                stroke.setStrokeWidth(Math.max(2f, gap / 2f));
                c.drawRoundRect(rect, gap, gap, stroke);

                float chip = cell / 4.2f;
                float chipX = isA ? cx0 : cx1 - chip;
                rect.set(chipX, cy0, chipX + chip, cy0 + chip);
                fill.setColor(isA ? COL_TAG_A : COL_TAG_B);
                c.drawRoundRect(rect, chip / 4f, chip / 4f, fill);
                text.setColor(COL_TEXT);
                text.setTextSize(chip * 0.72f);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(isA ? "A" : "B", chipX + chip / 2f,
                        cy0 + chip / 2f - (text.ascent() + text.descent()) / 2f, text);
            }
        }
    }

    private void drawStatus(Canvas c, float x0, float y0, float x1, float y1) {
        float hs = (y1 - y0) * 0.5f;
        int health = snap[GameStateNative.HEALTH];
        int maxHealth = snap[GameStateNative.MAX_HEALTH];
        int hearts = Math.min(maxHealth / 8, 10);
        float hx = x0;
        for (int i = 0; i < hearts; i++) {
            int units = Math.max(0, Math.min(8, health - i * 8));
            drawHeart(c, hx, y0, hs, units / 8f);
            hx += hs * 1.18f;
        }

        // Rupee diamond + count, right-aligned.
        text.setColor(COL_TEXT);
        text.setTextSize((y1 - y0) * 0.62f);
        text.setTextAlign(Paint.Align.RIGHT);
        String rupees = String.valueOf(snap[GameStateNative.RUPEES]);
        float ty = y1 - (y1 - y0) / 2f - (text.ascent() + text.descent()) / 2f;
        c.drawText(rupees, x1, ty, text);
        float rw = text.measureText(rupees);
        float rs = (y1 - y0) * 0.34f;
        float rcx = x1 - rw - rs * 1.4f, rcy = y0 + (y1 - y0) / 2f;
        path.reset();
        path.moveTo(rcx, rcy - rs);
        path.lineTo(rcx + rs * 0.7f, rcy);
        path.lineTo(rcx, rcy + rs);
        path.lineTo(rcx - rs * 0.7f, rcy);
        path.close();
        fill.setColor(COL_RUPEE);
        c.drawPath(path, fill);
    }

    /** Vector heart; fillFrac 0..1 fills left-to-right (half hearts). */
    private void drawHeart(Canvas c, float x, float y, float s, float fillFrac) {
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

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downSlot = hitSlot(e.getX(), e.getY());
                downTime = e.getEventTime();
                return downSlot >= 0;
            case MotionEvent.ACTION_UP:
                int slot = hitSlot(e.getX(), e.getY());
                if (slot >= 0 && slot == downSlot && cellItems[slot] != 0) {
                    boolean longPress = e.getEventTime() - downTime >= LONG_PRESS_MS;
                    GameStateNative.requestEquip(cellItems[slot], longPress ? 1 : 0);
                    performClick();
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
