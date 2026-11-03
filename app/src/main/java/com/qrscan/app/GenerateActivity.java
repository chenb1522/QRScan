package com.qrscan.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 生成二维码：只做一件事 —— 填文本、出码、存相册。
 *
 * 纠错等级固定 Q(25%)、点阵固定方块，界面上不提供任何额外选项。
 */
public class GenerateActivity extends Activity {

    private static final String TAG = "QRScanGen";
    private static final int REQ_WRITE = 2001;
    private static final int EXPORT_SIZE = 1024;
    private static final int RENDER_DELAY = 200;
    private static final ErrorCorrectionLevel EC = ErrorCorrectionLevel.Q;

    private EditText input;
    private ImageView preview;
    private View scrollView;
    private View previewCard;

    private int previewTarget;
    private int cardSize;
    private boolean keyboardShown;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_generate);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        input = (EditText) findViewById(R.id.gen_input);
        preview = (ImageView) findViewById(R.id.gen_preview);
        scrollView = findViewById(R.id.gen_scroll);
        previewCard = findViewById(R.id.gen_preview_card);

        int statusH = dimen("status_bar_height");
        View topBar = findViewById(R.id.gen_top_bar);
        topBar.setPadding(topBar.getPaddingLeft(), statusH + dp(12),
                topBar.getPaddingRight(), topBar.getPaddingBottom());

        // 预览卡片做成正方形：宽度 = 屏宽 - 左右各 20dp
        cardSize = getResources().getDisplayMetrics().widthPixels - dp(40);
        applyPreviewHeight(false);
        watchKeyboard();

        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 键盘弹起时把输入框滚进可视区，避免只看到半截预览
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    scrollInputIntoView();
                }
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(renderTask);
                ui.postDelayed(renderTask, RENDER_DELAY);
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveToGallery();
            }
        });

        String preset = getIntent() == null ? null : getIntent().getStringExtra("text");
        if (preset != null && preset.length() > 0) {
            input.setText(preset);
            input.setSelection(preset.length());
        } else {
            ui.postDelayed(renderTask, 60);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(renderTask);
    }

    /** 键盘弹起时把预览压成小方块，保证输入框与保存键都在可视区内。 */
    private void watchKeyboard() {
        final View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (isFinishing() || previewCard == null) {
                            return;
                        }
                        android.graphics.Rect r = new android.graphics.Rect();
                        root.getWindowVisibleDisplayFrame(r);
                        int screenH = getResources().getDisplayMetrics().heightPixels;
                        boolean shown = screenH - r.height() > screenH / 4;
                        if (shown == keyboardShown) {
                            return;
                        }
                        keyboardShown = shown;
                        applyPreviewHeight(shown);
                        if (shown) {
                            scrollInputIntoView();
                        }
                    }
                });
    }

    /** 幂等：高度已经是目标值就直接返回，避免布局反复重排。 */
    private void applyPreviewHeight(boolean compact) {
        if (previewCard == null) {
            return;
        }
        int h = compact ? compactHeight() : cardSize;
        android.view.ViewGroup.LayoutParams lp = previewCard.getLayoutParams();
        if (lp.height == h) {
            return;
        }
        lp.height = h;
        previewCard.setLayoutParams(lp);
        previewTarget = Math.max(240, h - dp(36));
        ui.removeCallbacks(renderTask);
        ui.post(renderTask);
    }

    /**
     * 键盘弹起时的预览高度：用 ScrollView 的实际可用高度反算，
     * 预留「内容」标签 + 输入框 + 间距，让整页刚好放下不产生滚动。
     */
    private int compactHeight() {
        int fallback = Math.min(cardSize,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.18f));
        if (scrollView == null || scrollView.getHeight() <= 0) {
            return fallback;
        }
        int reserved = dp(22) + dp(34) + dp(8) + dp(160) + dp(24);
        int h = scrollView.getHeight() - reserved;
        if (h < dp(140)) {
            return Math.min(cardSize, dp(140));
        }
        return Math.min(h, cardSize);
    }
    private void scrollInputIntoView() {
        if (scrollView == null) {
            return;
        }
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                int target = input.getBottom() + dp(16) - scrollView.getHeight();
                if (target > 0) {
                    scrollView.scrollTo(0, target);
                }
            }
        });
    }

    // ------------------------------------------------------------------

    private void render() {
        if (isFinishing()) {
            return;
        }
        String text = input.getText().toString();
        if (text.trim().length() == 0) {
            preview.setImageDrawable(null);
            return;
        }
        Bitmap bmp = QrEncoder.encode(text, previewTarget, EC, false);
        if (bmp == null) {
            preview.setImageDrawable(null);
            toast(getString(R.string.too_long));
            return;
        }
        preview.setImageBitmap(bmp);
    }

    // ------------------------------------------------------------------

    private void saveToGallery() {
        if (input.getText().toString().trim().length() == 0) {
            toast(getString(R.string.empty_content));
            return;
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        doSave();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            doSave();
        } else {
            toast(getString(R.string.save_fail));
        }
    }

    private void doSave() {
        Bitmap bmp = QrEncoder.encode(input.getText().toString(), EXPORT_SIZE, EC, false);
        if (bmp == null) {
            toast(getString(R.string.too_long));
            return;
        }
        String name = "QRScan_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".png";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/QRScan");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        OutputStream os = null;
        try {
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                toast(getString(R.string.save_fail));
                return;
            }
            os = getContentResolver().openOutputStream(uri);
            if (os == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                toast(getString(R.string.save_fail));
                return;
            }
            os.flush();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            }
            toast(getString(R.string.saved_ok));
            Log.i(TAG, "saved " + uri);
        } catch (Throwable t) {
            Log.w(TAG, "save failed", t);
            toast(getString(R.string.save_fail));
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id <= 0 ? 0 : getResources().getDimensionPixelSize(id);
    }
}