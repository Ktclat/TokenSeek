package io.tokenseek.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small line icons with an independent, accessible touch target. */
final class NativeChatIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String icon;
    private int tint;
    private int ringPercent = -1;
    private String ringText = "—";

    NativeChatIcon(Context context, String icon, int tint) {
        super(context);
        this.icon = icon;
        this.tint = tint;
        setFocusable(true);
    }

    void setIcon(String name, int color) {
        icon = name;
        tint = color;
        invalidate();
    }

    void setMeter(String text, int percent) {
        ringText = text;
        ringPercent = percent;
        invalidate();
    }

    @Override public CharSequence getAccessibilityClassName() {
        return android.widget.Button.class.getName();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        boolean meter = "meter".equals(icon);
        float size = Math.min(Math.min(getWidth(), getHeight()), density * (meter ? 34 : 22));
        canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(tint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.65f);
        switch (icon) {
            case "drawer":
                line(canvas, 3,7,20,7); line(canvas,3,15,13,15); break;
            case "new":
                path.reset(); path.moveTo(9,20); path.lineTo(4,21); path.lineTo(5,16);
                path.cubicTo(0,7,8,0,16,4); path.cubicTo(25,8,20,22,9,20);
                canvas.drawPath(path, paint); line(canvas,9,12,16,12); line(canvas,12.5f,8.5f,12.5f,15.5f); break;
            case "attach":
                path.reset(); path.moveTo(8,13); path.lineTo(14.5f,6.5f);
                path.cubicTo(18,3,21,6,18,9); path.lineTo(9,18);
                path.cubicTo(3,24,-1,16,4,11); path.lineTo(12,3);
                canvas.drawPath(path,paint); break;
            case "remove": line(canvas,6,6,18,18); line(canvas,6,18,18,6); break;
            case "voice":
                canvas.drawRoundRect(9,3,15,15,3,3,paint);
                path.reset(); path.moveTo(5,11); path.cubicTo(5,21,19,21,19,11);
                canvas.drawPath(path,paint); line(canvas,12,19,12,22); break;
            case "send": line(canvas,12,20,12,4); line(canvas,5,11,12,4); line(canvas,12,4,19,11); break;
            case "stop": paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(6,6,18,18,2,2,paint); break;
            case "copy":
                canvas.drawRoundRect(8,8,20,21,2,2,paint);
                path.reset(); path.moveTo(5,16); path.lineTo(4,16); path.lineTo(4,4); path.lineTo(15,4);
                canvas.drawPath(path,paint); break;
            case "edit":
                path.reset(); path.moveTo(4,20); path.lineTo(5,15); path.lineTo(16,4); path.lineTo(20,8);
                path.lineTo(9,19); path.close(); canvas.drawPath(path,paint); break;
            case "retry":
                canvas.drawArc(4,4,20,20,-75,288,false,paint);
                line(canvas,4,4,4,10); line(canvas,4,10,10,10); break;
            case "speak":
                path.reset(); path.moveTo(3,10); path.lineTo(7,10); path.lineTo(12,5);
                path.lineTo(12,19); path.lineTo(7,14); path.lineTo(3,14); path.close();
                canvas.drawPath(path,paint); canvas.drawArc(10,4,21,20,-57,115,false,paint); break;
            case "prev": line(canvas,15,5,8,12); line(canvas,8,12,15,19); break;
            case "next": line(canvas,9,5,16,12); line(canvas,16,12,9,19); break;
            case "meter":
                paint.setStrokeWidth(1.5f);
                paint.setAlpha(55);
                canvas.drawCircle(12,12,10,paint);
                paint.setAlpha(255);
                if (ringPercent > 0) canvas.drawArc(new RectF(2,2,22,22),-90,ringPercent * 3.6f,false,paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(ringText.length() > 3 ? 5.6f : 6.7f);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                canvas.drawText(ringText,12,12 - (paint.ascent() + paint.descent()) / 2,paint);
                break;
            default: break;
        }
        canvas.restore();
    }

    private void line(Canvas canvas, float x, float y, float x2, float y2) {
        canvas.drawLine(x,y,x2,y2,paint);
    }
}
