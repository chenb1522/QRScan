package com.qrscan.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.text.method.ScrollingMovementMethod;
import android.view.Window;
import android.view.WindowManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.SurfaceView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 扫码主界面：实时预览识别 + 相册导入识别 + 结果卡片。 */
public class MainActivity extends Activity
        implements CameraController.FrameCallback, CameraController.ErrorCallback {

    private static final int REQ_CAMERA = 1001;
    private static final int REQ_PICK = 1002;
    private static final String TAG = "QRScan";

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_ACCENT = 0xFF4ADE80;

    private SurfaceView previewView;
    private ScanView scanView;
    private View topBar;
    private View bottomBar;
    private View resultSheet;
    private View permPanel;
    private View busyPanel;

    private TextView resultTypeView;
    private TextView resultFormatView;
    private TextView resultText;
    private TextView resultDetail;
    private TextView btnCopy;
    private TextView btnOpen;
    private TextView btnPerm;
    private ImageButton btnGallery;
    private ImageButton btnFlash;
    private ImageButton btnFlip;
    private ImageButton btnHistory;
    private ImageButton btnBack;
    private ImageButton btnResultMake;
    private ImageButton btnResultClose;

    private CameraController camera;
    private ExecutorService decoder;
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private byte[] frameBuffer;

    private HistoryStore history;
    private Vibrator vibrator;
    private ToneGenerator tone;
    private Payloads.Info current;
    private QrDecoder.Decoded currentDecoded;

    private int lastGoodRotation = 90;
    private boolean resultVisible;
    private boolean flashOn;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bindViews();
        applyInsets();

        decoder = Executors.newSingleThreadExecutor();
        history = new HistoryStore(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
        } catch (Throwable ignored) {
        }

        camera = new CameraController(this);
        camera.setFrameCallback(this);
        camera.setErrorCallback(this);
        camera.attach(previewView.getHolder());

        bindActions();
        scanView.startScanLine();

        if (!hasCameraPermission()) {
            permPanel.setVisibility(View.VISIBLE);
        }

        previewView.post(new Runnable() {
            @Override
            public void run() {
                handleExternalIntent(getIntent());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            permPanel.setVisibility(View.GONE);
            camera.start();
        } else {
            permPanel.setVisibility(View.VISIBLE);
        }
        if (!resultVisible) {
            scanView.startScanLine();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        camera.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        camera.stop();
        if (decoder != null) {
            decoder.shutdownNow();
        }
        if (tone != null) {
            try {
                tone.release();
            } catch (Throwable ignored) {
            }
            tone = null;
        }
        vibrator = null;
    }

    @Override
    public void onBackPressed() {
        if (resultVisible) {
            hideResult();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    private void bindViews() {
        previewView = (SurfaceView) findViewById(R.id.preview);
        scanView = (ScanView) findViewById(R.id.scan_view);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        resultSheet = findViewById(R.id.result_sheet);
        permPanel = findViewById(R.id.perm_panel);
        busyPanel = findViewById(R.id.busy_panel);

        resultTypeView = (TextView) findViewById(R.id.result_type);
        resultFormatView = (TextView) findViewById(R.id.result_format);
        resultText = (TextView) findViewById(R.id.result_text);
        resultDetail = (TextView) findViewById(R.id.result_detail);
        btnCopy = (TextView) findViewById(R.id.btn_copy);
        btnOpen = (TextView) findViewById(R.id.btn_open);
        btnPerm = (TextView) findViewById(R.id.btn_perm);

        btnGallery = (ImageButton) findViewById(R.id.btn_gallery);
        btnFlash = (ImageButton) findViewById(R.id.btn_flash);
        btnFlip = (ImageButton) findViewById(R.id.btn_flip);
        btnHistory = (ImageButton) findViewById(R.id.btn_history);
        btnBack = (ImageButton) findViewById(R.id.btn_back);
        btnResultMake = (ImageButton) findViewById(R.id.btn_result_make);
        btnResultClose = (ImageButton) findViewById(R.id.btn_result_close);

        resultText.setMovementMethod(new ScrollingMovementMethod());
    }

    private void applyInsets() {
        int statusH = dimen("status_bar_height");
        int navH = dimen("navigation_bar_height");
        if (statusH <= 0) {
            statusH = dp(24);
        }
        topBar.setPadding(topBar.getPaddingLeft(), statusH + dp(14),
                topBar.getPaddingRight(), topBar.getPaddingBottom());
        bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                bottomBar.getPaddingRight(), navH + dp(22));
        scanView.setPadding(0, statusH, 0, navH);
        View sheet = resultSheet;
        sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(),
                sheet.getPaddingRight(), dp(18));
    }

    private void bindActions() {
        btnGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        btnFlash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doToggleFlash();
            }
        });
        btnFlip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                camera.switchCamera();
                lastGoodRotation = 90;
                toast("已切换摄像头");
            }
        });
        btnHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHistory();
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestCameraPermission();
            }
        });
        btnResultMake.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current == null) {
                    return;
                }
                Intent i = new Intent(MainActivity.this, GenerateActivity.class);
                i.putExtra("text", current.raw);
                startActivity(i);
            }
        });
        btnResultClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideResult();
            }
        });
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current != null) {
                    copyToClipboard(current.raw);
                }
            }
        });
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCurrent();
            }
        });

    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            permPanel.setVisibility(View.GONE);
            camera.start();
        } else {
            toast(getString(R.string.perm_need));
        }
    }

    // ------------------------------------------------------------------
    // 实时帧
    // ------------------------------------------------------------------

    @Override
    public void onPreviewFrame(byte[] data, int width, int height, int frameRotation) {
        if (resultVisible || data == null || width <= 0 || height <= 0) {
            return;
        }
        if (!decoding.compareAndSet(false, true)) {
            return;
        }
        final int yLen = width * height;
        if (frameBuffer == null || frameBuffer.length < yLen) {
            frameBuffer = new byte[yLen];
        }
        System.arraycopy(data, 0, frameBuffer, 0, yLen);

        final byte[] snapshot = frameBuffer;
        final int w = width;
        final int h = height;
        final int rot = frameRotation > 0 ? frameRotation : 90;

        try {
            decoder.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        int[] order = rotationOrder(lastGoodRotation, rot);
                        QrDecoder.Decoded d = QrDecoder.decodeYuv(snapshot, w, h, order);
                        if (d != null) {
                            if (d.rotation >= 0) {
                                lastGoodRotation = d.rotation;
                            }
                            postDecoded(d);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        decoding.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            decoding.set(false);
        }
    }

    private static int[] rotationOrder(int last, int base) {
        // ZXing 自身能纠正二维码方向，先试不旋转的原帧可省掉一次整帧拷贝；
        // 命中角度仍是有效的 fallback（极端角度、条码类目标时有用）。
        int[] out = new int[4];
        int idx = 0;
        out[idx++] = 0;
        int b = ((base % 360) + 360) % 360;
        int[] cands = {((last % 360) + 360) % 360, b, (b + 90) % 360,
                (b + 180) % 360, (b + 270) % 360};
        for (int i = 0; i < cands.length && idx < 4; i++) {
            int c = cands[i];
            boolean dup = false;
            for (int j = 0; j < idx; j++) {
                if (out[j] == c) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out[idx++] = c;
            }
        }
        return out;
    }

    private void postDecoded(final QrDecoder.Decoded d) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                handleDecoded(d);
            }
        });
    }

    @Override
    public void onCameraError(String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast(getString(R.string.camera_fail) + "：" + getString(R.string.perm_need));
            }
        });
    }

    // ------------------------------------------------------------------
    // 结果
    // ------------------------------------------------------------------

    private void handleDecoded(QrDecoder.Decoded d) {
        if (d == null || isFinishing()) {
            return;
        }
        currentDecoded = d;
        current = Payloads.classify(d.text);
        showResult();
        feedback();
        if (history != null) {
            history.add(d.text, current.typeLabel);
        }
    }

    private void showResult() {
        if (current == null) {
            return;
        }
        resultTypeView.setText(current.typeLabel);
        resultFormatView.setText(currentDecoded == null ? "" : currentDecoded.formatName);
        resultText.setText(current.display);
        resultText.scrollTo(0, 0);

        if (current.detail != null && current.detail.length() > 0) {
            resultDetail.setVisibility(View.VISIBLE);
            resultDetail.setText(current.detail);
        } else {
            resultDetail.setVisibility(View.GONE);
        }

        boolean canOpen = current.canOpen();
        btnOpen.setEnabled(canOpen);
        btnOpen.setAlpha(canOpen ? 1f : 0.38f);

        scanView.stopScanLine();
        boolean first = !resultVisible;
        resultVisible = true;
        resultSheet.setVisibility(View.VISIBLE);
        if (first) {
            resultSheet.setAlpha(0f);
            resultSheet.setTranslationY(dp(46));
            bottomBar.animate().alpha(0f).setDuration(150).start();
            resultSheet.animate().alpha(1f).translationY(0f)
                    .setDuration(230).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void hideResult() {
        resultVisible = false;
        current = null;
        currentDecoded = null;
        resultSheet.animate().alpha(0f).translationY(dp(46)).setDuration(170)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            resultSheet.setVisibility(View.GONE);
                        }
                    }
                }).start();
        bottomBar.animate().alpha(1f).setDuration(170).start();
        scanView.startScanLine();
        decoding.set(false);
    }

    // ------------------------------------------------------------------
    // 结果操作
    // ------------------------------------------------------------------

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("qr", text));
            toast(getString(R.string.copied));
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void openCurrent() {
        if (current == null) {
            return;
        }
        // 名片走系统"新建联系人"流程，其余走 ACTION_VIEW
        if (Payloads.K_CONTACT.equals(current.kind)) {
            try {
                Intent i = new Intent(Intent.ACTION_INSERT);
                i.setType(ContactsContract.Contacts.CONTENT_TYPE);
                if (current.contactName != null) {
                    i.putExtra(ContactsContract.Intents.Insert.NAME, current.contactName);
                }
                if (current.contactPhone != null) {
                    i.putExtra(ContactsContract.Intents.Insert.PHONE, current.contactPhone);
                }
                if (current.contactEmail != null) {
                    i.putExtra(ContactsContract.Intents.Insert.EMAIL, current.contactEmail);
                }
                startActivity(i);
            } catch (Throwable t) {
                toast("没有可用的联系人应用");
            }
            return;
        }
        String uri = Payloads.openUri(current);
        if (uri == null) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("没有可以打开该内容的 App");
        }
    }


    // ------------------------------------------------------------------
    // 相册 / 外部图片
    // ------------------------------------------------------------------

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Throwable t) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            try {
                startActivityForResult(fallback, REQ_PICK);
            } catch (Throwable t2) {
                toast("无法打开相册");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                decodeImageUri(uri);
            }
        }
    }

    private void handleExternalIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Uri uri = null;
        try {
            String type = intent.getType();
            Log.i(TAG, "external intent action=" + intent.getAction() + " type=" + type);
            if (type != null && type.startsWith("image/")) {
                Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (extra instanceof Uri) {
                    uri = (Uri) extra;
                }
            }
            if (uri == null && intent.getData() != null) {
                Uri d = intent.getData();
                String t = getContentResolver().getType(d);
                Log.i(TAG, "data uri=" + d + " mime=" + t);
                if (t == null || t.startsWith("image/")) {
                    uri = d;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "external intent parse failed", t);
        }
        Log.i(TAG, "resolved image uri=" + uri);
        if (uri != null) {
            decodeImageUri(uri);
        }
    }

    private void decodeImageUri(final Uri uri) {
        showBusy(true);
        Log.i(TAG, "decodeImageUri start " + uri);
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp;
                try {
                    bmp = QrDecoder.loadBitmap(MainActivity.this, uri);
                } catch (Throwable t) {
                    Log.e(TAG, "loadBitmap failed", t);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showBusy(false);
                            toast("读取图片失败");
                        }
                    });
                    return;
                }
                final QrDecoder.Decoded result = bmp == null ? null : QrDecoder.decodeBitmap(bmp);
                Log.i(TAG, "bitmap=" + (bmp == null ? "null" : bmp.getWidth() + "x" + bmp.getHeight())
                          + " hit=" + (result != null)
                          + (result == null ? "" : (" [" + result.formatName + "] " + result.costMs + "ms")));
                if (bmp != null && !bmp.isRecycled()) {
                    bmp.recycle();
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showBusy(false);
                        if (result != null) {
                            if (resultVisible) {
                                hideResult();
                            }
                            handleDecoded(result);
                        } else {
                            toast(getString(R.string.no_code) + "，" + getString(R.string.no_code_detail));
                        }
                    }
                });
            }
        });
    }

    private void showBusy(boolean busy) {
        busyPanel.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // 历史
    // ------------------------------------------------------------------

    private void showHistory() {
        final List<HistoryStore.Item> items = history.list();
        if (items == null || items.isEmpty()) {
            toast(getString(R.string.history_empty));
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_history);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            win.setAttributes(lp);
        }

        LinearLayout list = (LinearLayout) dialog.findViewById(R.id.history_list);
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        List<HistoryStore.Item> shown = items.size() > 40 ? items.subList(0, 40) : items;

        for (int a = 0; a < shown.size(); a++) {
            final HistoryStore.Item it = shown.get(a);
            View row = getLayoutInflater().inflate(R.layout.item_history, list, false);
            TextView title = (TextView) row.findViewById(R.id.item_title);
            TextView time = (TextView) row.findViewById(R.id.item_time);
            String one = it.text.replace("\n", " ").trim();
            if (one.length() > 46) {
                one = one.substring(0, 46) + "…";
            }
            title.setText("[" + (it.label == null ? "" : it.label) + "] " + one);
            time.setText(fmt.format(new Date(it.time)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    showHistoryItem(it);
                }
            });
            list.addView(row);
        }

        dialog.findViewById(R.id.btn_hist_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                history.clear();
                dialog.dismiss();
                toast("历史已清空");
            }
        });
        dialog.findViewById(R.id.btn_hist_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private void showHistoryItem(HistoryStore.Item it) {
        if (it == null) {
            return;
        }
        QrDecoder.Decoded d = new QrDecoder.Decoded(it.text, null, 0L, -1);
        if (resultVisible) {
            hideResult();
        }
        handleDecoded(d);
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------
    private void doToggleFlash() {
        boolean support = camera.hasFlash();
        if (!support) {
            toast("该设备没有补光灯");
            return;
        }
        flashOn = camera.toggleFlash();
        btnFlash.setImageTintList(ColorStateList.valueOf(flashOn ? COLOR_ACCENT : COLOR_TEXT));
    }

    private void feedback() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(45,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(45);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (tone != null) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 110);
            }
        } catch (Throwable ignored) {
        }
    }

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
        if (id <= 0) {
            return 0;
        }
        return getResources().getDimensionPixelSize(id);
    }
}
