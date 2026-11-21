package kr.routine.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/** Square QR code of a link, drawn dark on light in every theme so phones can read it. */
public final class QrView extends View {
    private static final int DARK = 0xff111417, LIGHT = 0xffffffff;
    private final Paint paint = new Paint();
    private BitMatrix matrix;

    public QrView(Context context, String content) {
        super(context);
        setContentDescription("친구용 초대 링크 QR 코드");
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException | IllegalArgumentException failure) { matrix = null; }
    }

    public boolean valid() { return matrix != null; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = Math.min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec) == 0 ? Integer.MAX_VALUE : MeasureSpec.getSize(heightSpec));
        setMeasuredDimension(size, size);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(LIGHT);
        if (matrix == null) return;
        int modules = matrix.getWidth();
        float cell = (float) Math.min(getWidth(), getHeight()) / modules;
        paint.setColor(DARK);
        for (int y = 0; y < modules; y++)
            for (int x = 0; x < modules; x++)
                if (matrix.get(x, y)) canvas.drawRect(x * cell, y * cell, (x + 1) * cell + 0.5f, (y + 1) * cell + 0.5f, paint);
    }
}
