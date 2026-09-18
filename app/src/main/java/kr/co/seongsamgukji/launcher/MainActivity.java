package kr.co.seongsamgukji.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS = "launcher_prefs";
    private static final String KEY_PACKAGE = "winlator_package";
    private static final String KEY_SHORTCUT = "shortcut_path";

    private final List<String> knownPackages = Arrays.asList(
            "com.winlator",
            "com.winlator.cmod",
            "com.winlator.bionic",
            "com.winlator.glibc"
    );

    private Spinner packageSpinner;
    private EditText customPackage;
    private EditText shortcutPath;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadSettings();
        updateStatus();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("성삼국지 촉한영걸전");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Android 전용 런처 v0.1\nWinlator 계열 실행환경을 이용해 Windows 게임 바로가기를 실행합니다.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        addLabel(root, "Winlator 선택");
        packageSpinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (String p : knownPackages) {
            labels.add((isInstalled(p) ? "✓ " : "  ") + p);
        }
        labels.add("직접 입력");
        packageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        root.addView(packageSpinner);

        addLabel(root, "직접 입력 패키지명 (필요한 경우)");
        customPackage = new EditText(this);
        customPackage.setHint("예: com.example.winlator");
        root.addView(customPackage);

        addLabel(root, ".desktop 바로가기 실제 경로");
        shortcutPath = new EditText(this);
        shortcutPath.setSingleLine(false);
        shortcutPath.setMinLines(2);
        shortcutPath.setHint("예: /storage/emulated/0/Winlator/Shortcuts/성삼국지.desktop");
        root.addView(shortcutPath);

        TextView help = new TextView(this);
        help.setText("Winlator에서 게임 EXE의 바로가기를 만든 뒤, 내보낸 .desktop 파일의 실제 파일 경로를 입력하세요. 경로가 비어 있으면 Winlator 자체를 엽니다.");
        help.setTextSize(13);
        help.setPadding(0, dp(8), 0, dp(16));
        root.addView(help);

        Button save = button("설정 저장");
        save.setOnClickListener(v -> {
            saveSettings();
            updateStatus();
            toast("설정을 저장했습니다.");
        });
        root.addView(save);

        Button launch = button("게임 실행");
        launch.setOnClickListener(v -> {
            saveSettings();
            launchGame();
        });
        root.addView(launch);

        Button openWinlator = button("Winlator 열기");
        openWinlator.setOnClickListener(v -> {
            saveSettings();
            launchPackage(getSelectedPackage());
        });
        root.addView(openWinlator);

        Button appSettings = button("Android 앱 설정 열기");
        appSettings.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(appSettings);

        status = new TextView(this);
        status.setPadding(0, dp(18), 0, 0);
        status.setTextSize(14);
        root.addView(status);

        setContentView(root);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void addLabel(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(4));
        root.addView(t);
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedPackage = p.getString(KEY_PACKAGE, "");
        String savedShortcut = p.getString(KEY_SHORTCUT, "");
        shortcutPath.setText(savedShortcut);

        int idx = knownPackages.indexOf(savedPackage);
        if (idx >= 0) {
            packageSpinner.setSelection(idx);
        } else if (!savedPackage.isEmpty()) {
            packageSpinner.setSelection(knownPackages.size());
            customPackage.setText(savedPackage);
        } else {
            int detected = firstInstalledIndex();
            packageSpinner.setSelection(detected >= 0 ? detected : 0);
        }
    }

    private void saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_PACKAGE, getSelectedPackage())
                .putString(KEY_SHORTCUT, shortcutPath.getText().toString().trim())
                .apply();
    }

    private String getSelectedPackage() {
        int pos = packageSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < knownPackages.size()) return knownPackages.get(pos);
        return customPackage.getText().toString().trim();
    }

    private void launchGame() {
        String pkg = getSelectedPackage();
        String shortcut = shortcutPath.getText().toString().trim();

        if (pkg.isEmpty()) {
            toast("Winlator 패키지명을 선택하거나 입력하세요.");
            return;
        }
        if (!isInstalled(pkg)) {
            toast("선택한 Winlator 앱을 찾지 못했습니다.");
            return;
        }

        if (!shortcut.isEmpty()) {
            try {
                Intent direct = new Intent();
                direct.setComponent(new ComponentName(pkg, pkg + ".XServerDisplayActivity"));
                direct.putExtra("shortcut_path", shortcut);
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(direct);
                return;
            } catch (Exception ignored) {
                try {
                    Intent direct2 = new Intent();
                    direct2.setComponent(new ComponentName(pkg, "com.winlator.XServerDisplayActivity"));
                    direct2.putExtra("shortcut_path", shortcut);
                    direct2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(direct2);
                    return;
                } catch (Exception ignoredAgain) {
                    toast("직접 실행이 지원되지 않아 Winlator를 엽니다.");
                }
            }
        }
        launchPackage(pkg);
    }

    private void launchPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            toast("Winlator 패키지명이 비어 있습니다.");
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            toast("Winlator 실행 화면을 찾지 못했습니다.");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private int firstInstalledIndex() {
        for (int i = 0; i < knownPackages.size(); i++) {
            if (isInstalled(knownPackages.get(i))) return i;
        }
        return -1;
    }

    private void updateStatus() {
        String pkg = getSelectedPackage();
        boolean installed = pkg != null && !pkg.isEmpty() && isInstalled(pkg);
        String shortcut = shortcutPath.getText().toString().trim();
        status.setText(
                "상태\n" +
                "• Winlator: " + (installed ? "설치됨" : "미확인") + "\n" +
                "• 패키지: " + (pkg == null || pkg.isEmpty() ? "(없음)" : pkg) + "\n" +
                "• 바로가기: " + (shortcut.isEmpty() ? "(미설정 - Winlator 화면을 엽니다)" : shortcut)
        );
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
