package kr.routine.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Decorative origin-to-destination strip, not map geometry or a simulated vehicle position. */
public final class RouteSummaryView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final VehicleVisuals.Palette colors;
    public RouteSummaryView(Context context, VehicleVisuals.Palette colors) {
        super(context); this.colors=colors;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius=Math.min(getHeight()*.36f,getWidth()*.12f);
        float y=getHeight()/2f, left=radius+8, right=getWidth()-radius-8;
        paint.setColor(colors.road());
        canvas.drawRoundRect(left,y-radius*.85f,right,y+radius*.85f,radius,radius,paint);
        paint.setColor(colors.roadLine());
        for(float x=left+radius*1.6f;x<right-radius;x+=radius*1.1f)
            canvas.drawRect(x,y-4,Math.min(x+radius*.6f,right-radius),y+4,paint);
        paint.setColor(colors.text()); canvas.drawCircle(left,y,radius,paint);
        paint.setColor(colors.road());
        Path arrow=new Path(); arrow.moveTo(left,y-radius*.52f); arrow.lineTo(left-radius*.35f,y+radius*.4f);
        arrow.lineTo(left,y+radius*.2f); arrow.lineTo(left+radius*.35f,y+radius*.4f); arrow.close(); canvas.drawPath(arrow,paint);
        paint.setColor(colors.primary()); canvas.drawCircle(right,y,radius,paint);
        paint.setColor(colors.primaryText());
        canvas.drawCircle(right,y-radius*.1f,radius*.3f,paint);
        Path pin=new Path(); pin.moveTo(right-radius*.24f,y+radius*.05f); pin.lineTo(right,y+radius*.5f);
        pin.lineTo(right+radius*.24f,y+radius*.05f); pin.close(); canvas.drawPath(pin,paint);
        paint.setColor(colors.primary()); canvas.drawCircle(right,y-radius*.1f,radius*.11f,paint);
    }
}
