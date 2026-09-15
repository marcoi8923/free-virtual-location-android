package com.codex.mocklocation;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** 使用说明页：展示说明图片，底部按钮确认 */
public class GuideActivity extends Activity {

    public static final String EXTRA_FIRST_RUN = "first_run";

    private boolean firstRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        firstRun = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FIRST_RUN, false);

        TextView ok = (TextView) findViewById(R.id.btnGuideOk);
        ok.setText(firstRun ? "我已了解，开始使用" : "关闭");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirm();
            }
        });

        // 说明图按屏幕宽度铺满、高度按图片比例撑开，可上下滑动查看完整内容
        ImageView img = (ImageView) findViewById(R.id.imgGuide);
        try {
            Drawable d = img.getDrawable();
            int iw = d.getIntrinsicWidth();
            int ih = d.getIntrinsicHeight();
            int screenW = getResources().getDisplayMetrics().widthPixels;
            if (iw > 0 && ih > 0) {
                int h = (int) ((long) screenW * ih / iw);
                img.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h));
                img.setScaleType(ImageView.ScaleType.FIT_XY);
            }
        } catch (Throwable ignored) {
        }
    }

    private void confirm() {
        Prefs.setGuideConfirmed(this, true);
        finish();
    }

    @Override
    public void onBackPressed() {
        confirm();
        super.onBackPressed();
    }
}
