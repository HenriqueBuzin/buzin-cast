package io.buzincast.legacy;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Platform Views keep focus and selected destination independent on API17. */
final class LegacyUi {
    static final int MINT=0xff7cf4cd, WHITE=0xffe4edf2, MUTED=0xff94a8b6, BG=0xff080f19, PANEL=0xff111e2a;
    static int dp(Context context,float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(Context context,int color,int stroke,int width,int radius) {
        GradientDrawable result=new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(context,radius));
        if (width>0) result.setStroke(dp(context,width),stroke); return result;
    }
    static void focus(View view,boolean filled) {
        focus(view,filled,PANEL,1);
    }
    static void focus(View view,boolean filled,int normalColor,int normalStroke) {
        Context context=view.getContext(); StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},shape(context,filled?MINT:0xff153436,filled?WHITE:MINT,2,12));
        states.addState(new int[]{android.R.attr.state_selected},shape(context,0xff14302f,MINT,2,12));
        states.addState(new int[]{},shape(context,filled?MINT:normalColor,0xff293e48,filled?0:normalStroke,12));
        view.setBackground(states); view.setFocusable(true); view.setFocusableInTouchMode(true);
        view.setOnTouchListener((target,event)-> { if (event.getActionMasked()==MotionEvent.ACTION_UP) target.requestFocusFromTouch(); return false; });
    }
    static TextView text(Context context,CharSequence value,int size,int color,boolean bold) {
        TextView text=new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT,Typeface.BOLD); text.setLineSpacing(dp(context,3),1); return text;
    }
    static LinearLayout column(Context context) { LinearLayout result=new LinearLayout(context);result.setOrientation(LinearLayout.VERTICAL);return result; }
    static Button button(Context context,int label,boolean filled,Runnable click) {
        Button result=new Button(context); result.setText(label); result.setAllCaps(false);result.setTextSize(13);
        result.setTextColor(filled?0xff082b27:WHITE); result.setPadding(dp(context,16),dp(context,6),dp(context,16),dp(context,6));
        result.setMinHeight(0); result.setMinimumHeight(dp(context,46));focus(result,filled);
        result.setOnClickListener(v->click.run()); return result;
    }
    static LinearLayout row(Context context,CharSequence title,CharSequence detail,Runnable click) {
        LinearLayout row=column(context);row.setPadding(dp(context,16),dp(context,12),dp(context,16),dp(context,12));
        row.addView(text(context,title,15,WHITE,true));
        if (detail!=null && detail.length()>0) row.addView(text(context,detail,12,MUTED,false));
        focus(row,false);row.setOnClickListener(v->click.run());return row;
    }
    static void addSpaced(LinearLayout parent,View view,int height) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,height<0?height:dp(parent.getContext(),height));
        params.bottomMargin=dp(parent.getContext(),10);parent.addView(view,params);
    }
}
