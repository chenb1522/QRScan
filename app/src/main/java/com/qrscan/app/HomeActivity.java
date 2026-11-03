package com.qrscan.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 首页：先让用户选做什么。
 *
 * 这一页不碰相机、不申请任何权限，冷启动只是一个静态选择界面，
 * 选「解析」才进扫码页并触发相机权限，选「生成」直接进生成页。
 */
public class HomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        int statusH = dimen("status_bar_height");
        View root = findViewById(R.id.home_root);
        root.setPadding(root.getPaddingLeft(), statusH,
                root.getPaddingRight(), dimen("navigation_bar_height"));

        findViewById(R.id.card_scan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, MainActivity.class));
            }
        });

        findViewById(R.id.card_generate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, GenerateActivity.class));
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id <= 0 ? 0 : getResources().getDimensionPixelSize(id);
    }
}
