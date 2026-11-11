package com.example.a22cameralink;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 4102;
    private EditText gasInput;
    private EditText roomInput;
    private TextView statusView;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("a22_link", MODE_PRIVATE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("📷 A22 Camera Link");
        title.setTextSize(25);
        panel.addView(title);

        TextView intro = new TextView(this);
        intro.setText("iPadのPWAへカメラ・マイクを送信します。開始操作と通知を必ず表示します。");
        intro.setTextSize(15);
        addGap(panel, 10);
        panel.addView(intro);

        TextView gasLabel = new TextView(this);
        gasLabel.setText("GAS WebアプリURL");
        addGap(panel, 18);
        panel.addView(gasLabel);
        gasInput = new EditText(this);
        gasInput.setSingleLine(true);
        gasInput.setHint("https://script.google.com/macros/s/.../exec");
        gasInput.setText(preferences.getString("gas_url", ""));
        panel.addView(gasInput, matchWidth());

        TextView roomLabel = new TextView(this);
        roomLabel.setText("iPadと同じ12桁の接続コード");
        addGap(panel, 16);
        panel.addView(roomLabel);
        roomInput = new EditText(this);
        roomInput.setSingleLine(true);
        roomInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        roomInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        roomInput.setHint("例：123456789012");
        roomInput.setText(preferences.getString("room", ""));
        panel.addView(roomInput, matchWidth());

        Button start = new Button(this);
        start.setText("▶ 配信開始（カメラ・マイクを使用）");
        addGap(panel, 24);
        panel.addView(start, matchWidth());
        start.setOnClickListener(view -> requestAndStart());

        Button stop = new Button(this);
        stop.setText("■ 配信停止");
        panel.addView(stop, matchWidth());
        stop.setOnClickListener(view -> {
            stopService(new Intent(this, StreamService.class));
            statusView.setText("配信を停止しました");
        });

        statusView = new TextView(this);
        statusView.setText("待機中。配信を始めると通知が表示されます。");
        statusView.setTextSize(15);
        addGap(panel, 18);
        panel.addView(statusView);

        TextView warning = new TextView(this);
        warning.setText("注意：配信中はカメラ・マイクのプライバシー表示と常時通知が出ます。ロック画面中も継続を試みますが、端末の省電力機能・カメラ制御・通信状態によって停止する場合があります。ロック画面から遠隔で新しく起動する機能ではありません。");
        warning.setTextSize(13);
        addGap(panel, 18);
        panel.addView(warning);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        setContentView(scroll);
    }

    private void requestAndStart() {
        String gas = gasInput.getText().toString().trim();
        String room = roomInput.getText().toString().trim();
        if (!gas.startsWith("https://script.google.com/macros/s/") || !gas.endsWith("/exec")) {
            statusView.setText("GASのWebアプリ /exec URLを確認してください");
            return;
        }
        if (!room.matches("[0-9]{12}")) {
            statusView.setText("接続コードは12桁の数字です");
            return;
        }
        preferences.edit().putString("gas_url", gas).putString("room", room).apply();

        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startStreaming(gas, room);
    }

    private void startStreaming(String gas, String room) {
        Intent intent = new Intent(this, StreamService.class);
        intent.putExtra(StreamService.EXTRA_GAS_URL, gas);
        intent.putExtra(StreamService.EXTRA_ROOM, room);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        statusView.setText("配信サービスを開始しました。画面をロックしても通知を消さないでください。");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                statusView.setText("必要な権限が許可されていません。設定からカメラ・マイク・通知を許可してください。");
                return;
            }
        }
        startStreaming(gasInput.getText().toString().trim(), roomInput.getText().toString().trim());
    }

    private void addGap(LinearLayout panel, int dp) {
        TextView spacer = new TextView(this);
        spacer.setHeight(dp(dp));
        panel.addView(spacer);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}