package io.buzincast.legacy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.buzincast.media.DisplayFit;
import static io.buzincast.legacy.LegacyUi.*;

/** A live preview owns its draft; only explicit Save may persist it. */
final class LegacyCalibrationView extends FrameLayout {
    interface Finished { void finish(boolean save); }
    private int margin;
    private final View preview;
    private final TextView percentage;
    private final Button save;
    private final Finished finished;

    LegacyCalibrationView(Context context,int initial,Finished finished) {
        super(context);margin=Math.max(0,Math.min(10,initial));this.finished=finished;
        setBackgroundColor(BG);setClickable(true);
        preview=new View(context) {
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                DisplayFit.Viewport box=DisplayFit.INSTANCE.viewport(getWidth(),getHeight(),getWidth(),getHeight(),margin);
                float inset=dp(context,3),corner=dp(context,35);
                float left=box.getX()+inset,top=box.getY()+inset;
                float right=box.getX()+box.getWidth()-inset,bottom=box.getY()+box.getHeight()-inset;
                paint.setColor(MINT);paint.setAlpha(70);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(context,1));
                canvas.drawRect(left,top,right,bottom,paint);
                paint.setAlpha(255);paint.setStrokeWidth(dp(context,3));
                for(float x:new float[]{left,right})for(float y:new float[]{top,bottom}) {
                    canvas.drawLine(x,y,x+(x==left?corner:-corner),y,paint);
                    canvas.drawLine(x,y,x,y+(y==top?corner:-corner),paint);
                }
            }
        };
        preview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(preview,new LayoutParams(-1,-1));
        LinearLayout controls=column(context);controls.setGravity(Gravity.CENTER);controls.setPadding(dp(context,24),dp(context,16),dp(context,24),dp(context,16));
        TextView heading=text(context,context.getString(R.string.setting_tv_margin),24,WHITE,true);heading.setGravity(Gravity.CENTER);addSpaced(controls,heading,-2);
        percentage=text(context,"",36,MINT,true);percentage.setGravity(Gravity.CENTER);addSpaced(controls,percentage,-2);
        for(int label:new int[]{R.string.calibration_adjust_hint,R.string.calibration_save_hint}) {
            TextView hint=text(context,context.getString(label),14,MUTED,false);hint.setGravity(Gravity.CENTER);addSpaced(controls,hint,-2);
        }
        LinearLayout adjustments=new LinearLayout(context);adjustments.setGravity(Gravity.CENTER);
        for(int change:new int[]{-1,1}) {
            Button control=button(context,R.string.setting_tv_margin,false,()->adjust(change));control.setText(change<0?"−":"+");
            control.setContentDescription(context.getString(R.string.setting_tv_margin)+" "+control.getText());
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(context,80),dp(context,48));size.setMargins(dp(context,6),0,dp(context,6),0);adjustments.addView(control,size);
        }
        addSpaced(controls,adjustments,-2);
        LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.CENTER);
        save=button(context,R.string.tv_edit_save,true,()->finished.finish(true));
        Button cancel=button(context,R.string.tv_edit_cancel,false,()->finished.finish(false));
        for(Button action:new Button[]{save,cancel}) {
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,dp(context,48));size.setMargins(dp(context,6),0,dp(context,6),0);actions.addView(action,size);
        }
        controls.addView(actions);addView(controls,new LayoutParams(-1,-2,Gravity.CENTER));update();
    }
    int margin() { return margin; }
    void focusSave() { save.requestFocus(); }
    private void adjust(int delta) { margin=Math.max(0,Math.min(10,margin+delta));update(); }
    private void update() { percentage.setText(margin+"%");preview.invalidate(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT ||
                key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) {
            if(event.getAction()==KeyEvent.ACTION_DOWN) {
                if(key==KeyEvent.KEYCODE_DPAD_LEFT)adjust(-1);
                else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)adjust(1);
                else if(event.getRepeatCount()==0)finished.finish(true);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
