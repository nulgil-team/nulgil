package kr.routine.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * "오늘의 경로" strip from the screen design: current position ─ stops (flag) or an add button (+) ─ destination,
 * with a title and a time line under each node. Drawing and layout only; screens pass the nodes and taps.
 */
public final class RouteStrip {
    private RouteStrip() {}

    public enum Kind { ORIGIN, STOP, ADD, DESTINATION }
    /** One node. {@code onTap} null: not tappable. */
    public record Node(Kind kind, String title, String detail, Runnable onTap) {}

    private static final int NODE = 96, BAR = 74;
    static final int ORIGIN_FILL = 0xfff3f1ea, STOP_FILL = 0xfff2c12e, DESTINATION_FILL = 0xff1a66c8, MARK = 0xff1b1c1e;

    public static LinearLayout build(UiKit kit, List<Node> nodes) {
        LinearLayout box = kit.column();
        FrameLayout track = new FrameLayout(kit.activity);
        track.addView(new Track(kit.activity, kit.colors), new FrameLayout.LayoutParams(UiKit.MATCH, NODE));
        LinearLayout circles = kit.row(), labels = kit.row();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            int gravity = i == 0 ? Gravity.START : i == nodes.size() - 1 ? Gravity.END : Gravity.CENTER_HORIZONTAL;
            FrameLayout cell = new FrameLayout(kit.activity);
            NodeView circle = new NodeView(kit.activity, node.kind(), kit.colors);
            circle.setContentDescription(node.title() + (node.detail().isEmpty() ? "" : ", " + node.detail()));
            if (node.onTap() != null) { circle.setClickable(true); circle.setOnClickListener(v -> node.onTap().run()); }
            cell.addView(circle, new FrameLayout.LayoutParams(NODE, NODE, gravity | Gravity.CENTER_VERTICAL));
            circles.addView(cell, new LinearLayout.LayoutParams(0, NODE, 1));

            LinearLayout text = kit.column(); text.setGravity(gravity);
            TextView title = kit.text(node.title(), 34, true, kit.colors.text()); title.setGravity(gravity);
            text.addView(title);
            if (!node.detail().isEmpty()) {
                TextView detail = kit.text(node.detail(), 28, false, kit.colors.secondary()); detail.setGravity(gravity);
                kit.add(text, detail, 2);
            }
            if (node.onTap() != null) { text.setClickable(true); text.setOnClickListener(v -> node.onTap().run()); }
            labels.addView(text, new LinearLayout.LayoutParams(0, UiKit.WRAP, 1));
        }
        track.addView(circles, new FrameLayout.LayoutParams(UiKit.MATCH, NODE));
        box.addView(track, new LinearLayout.LayoutParams(UiKit.MATCH, NODE));
        kit.add(box, labels, 12);
        return box;
    }

    /** One node's circle on its own, for lists (guide screen). */
    public static View icon(UiKit kit, Kind kind) { return new NodeView(kit.activity, kind, kit.colors); }

    /** Rounded bar with a dashed line between the first and the last node. */
    private static final class Track extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final VehicleVisuals.Palette colors;
        Track(Context context, VehicleVisuals.Palette colors) { super(context); this.colors = colors; }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), top = (h - BAR) / 2f;
            paint.setStyle(Paint.Style.FILL); paint.setColor(colors.surface());
            canvas.drawRoundRect(new RectF(0, top, w, top + BAR), BAR / 2f, BAR / 2f, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(6); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(colors.secondary()); paint.setPathEffect(new DashPathEffect(new float[]{34, 26}, 0));
            Path line = new Path(); line.moveTo(NODE, h / 2f); line.lineTo(w - NODE, h / 2f); canvas.drawPath(line, paint);
            paint.setPathEffect(null);
        }
    }

    /** Circle with a drawn glyph: arrow (origin), flag (stop), plus (add), pin (destination). */
    private static final class NodeView extends View {
        private final Kind kind;
        private final VehicleVisuals.Palette colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        NodeView(Context context, Kind kind, VehicleVisuals.Palette colors) { super(context); this.kind = kind; this.colors = colors; }
        @Override protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()), c = s / 2f, r = s / 2f - 3;
            paint.setPathEffect(null);
            if (kind == Kind.ADD) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(colors.surface()); canvas.drawCircle(c, c, r, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4); paint.setColor(colors.text());
                paint.setPathEffect(new DashPathEffect(new float[]{10, 8}, 0)); canvas.drawCircle(c, c, r - 2, paint);
                paint.setPathEffect(null); paint.setStrokeWidth(6); paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(c - s * .17f, c, c + s * .17f, c, paint); canvas.drawLine(c, c - s * .17f, c, c + s * .17f, paint);
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(switch (kind) { case ORIGIN -> ORIGIN_FILL; case STOP -> STOP_FILL; default -> DESTINATION_FILL; });
            canvas.drawCircle(c, c, r, paint);
            Path glyph = new Path();
            switch (kind) {
                case ORIGIN -> { // navigation arrow
                    glyph.moveTo(c, c - s * .2f); glyph.lineTo(c + s * .15f, c + s * .18f);
                    glyph.lineTo(c, c + s * .1f); glyph.lineTo(c - s * .15f, c + s * .18f); glyph.close();
                    paint.setColor(MARK);
                }
                case STOP -> { // flag on a pole
                    float x = c - s * .12f;
                    glyph.addRect(x, c - s * .2f, x + s * .05f, c + s * .2f, Path.Direction.CW);
                    glyph.moveTo(x + s * .05f, c - s * .2f); glyph.lineTo(c + s * .18f, c - s * .2f);
                    glyph.lineTo(c + s * .1f, c - s * .1f); glyph.lineTo(c + s * .18f, c); glyph.lineTo(x + s * .05f, c); glyph.close();
                    paint.setColor(MARK);
                }
                default -> { // map pin
                    float pr = s * .14f, py = c - s * .05f;
                    glyph.addCircle(c, py, pr, Path.Direction.CW);
                    glyph.moveTo(c - pr * .85f, py + pr * .5f); glyph.lineTo(c, c + s * .22f); glyph.lineTo(c + pr * .85f, py + pr * .5f); glyph.close();
                    paint.setColor(0xffffffff);
                }
            }
            canvas.drawPath(glyph, paint);
            if (kind == Kind.DESTINATION) { paint.setColor(DESTINATION_FILL); canvas.drawCircle(c, c - s * .05f, s * .055f, paint); }
        }
    }
}
