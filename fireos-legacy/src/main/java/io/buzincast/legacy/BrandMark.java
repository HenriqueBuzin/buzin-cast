package io.buzincast.legacy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The exact paths of the existing buzin_mark vector, drawn on the API17 Canvas. */
final class BrandMark extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tile=new Path(), letter=new Path();
    BrandMark(Context context) {
        super(context);
        tile.moveTo(25,0);tile.lineTo(75,0);tile.quadTo(100,0,100,25);tile.lineTo(100,75);
        tile.quadTo(100,100,75,100);tile.lineTo(25,100);tile.quadTo(0,100,0,75);tile.lineTo(0,25);tile.quadTo(0,0,25,0);tile.close();
        letter.setFillType(Path.FillType.EVEN_ODD);
        letter.moveTo(29,18);letter.lineTo(54,18);letter.cubicTo(79,18,83,43,67,50);letter.cubicTo(88,59,77,84,54,84);letter.lineTo(29,84);letter.close();
        letter.moveTo(43,31);letter.lineTo(53,31);letter.cubicTo(66,31,66,44,53,44);letter.lineTo(43,44);letter.close();
        letter.moveTo(43,57);letter.lineTo(55,57);letter.cubicTo(69,57,69,71,55,71);letter.lineTo(43,71);letter.close();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);canvas.save();
        float size=Math.min(getWidth(),getHeight());canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/100,size/100);
        paint.setColor(0xff7cf4cd);canvas.drawPath(tile,paint);paint.setColor(0xff092a24);canvas.drawPath(letter,paint);canvas.restore();
    }
}
