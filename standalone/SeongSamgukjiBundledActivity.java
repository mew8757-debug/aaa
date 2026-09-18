package com.winlator;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-touch private build for Seong Samgukji: Shu-Han Heroes.
 * game1.Zip and game2.Zip are supplied only at build time and embedded under assets/seonggame/.
 */
public class SeongSamgukjiBundledActivity extends MainActivity {
    private static final String PREFS = "seong_samgukji_bundled";
    private static final String KEY_INSTALLED = "installed";
    private static final String KEY_CONTAINER_ID = "container_id";
    private static final String KEY_EXE_PATH = "exe_path";
    private static final String GAME1 = "seonggame/game1.Zip";
    private static final String GAME2 = "seonggame/game2.Zip";

    private final Handler handler = new Handler();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private File gameDir;
    private boolean restartedFromGame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        restartedFromGame = getIntent().hasExtra("container_id") && getIntent().hasExtra("start_path");
        super.onCreate(savedInstanceState);

        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        gameDir = new File(base, "SeongSamgukji");

        buildUi();
        waitForRuntime();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView title = new TextView(this);
        title.setText("성삼국지 촉한영걸전");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("최초 실행 시 게임을 자동 설치합니다.\n설치가 끝나면 바로 게임이 시작됩니다.");
        sub.setTextSize(16);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(14), 0, dp(26));
        root.addView(sub);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress);

        status = new TextView(this);
        status.setText("실행환경을 확인하고 있습니다…");
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void waitForRuntime() {
        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) {
            status.setText("Windows 호환 실행환경을 자동 설치 중입니다…");
            handler.postDelayed(this::waitForRuntime, 800);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String exePath = prefs.getString(KEY_EXE_PATH, "");
        boolean installed = prefs.getBoolean(KEY_INSTALLED, false) && new File(exePath).isFile();

        if (installed) {
            progress.setVisibility(View.GONE);
            status.setText("준비 완료. 게임을 실행합니다.");
            if (!restartedFromGame) handler.postDelayed(this::launchGame, 600);
        }
        else {
            installBundledGame();
        }
    }

    private void installBundledGame() {
        progress.setVisibility(View.VISIBLE);
        status.setText("게임 데이터를 자동 설치 중입니다…\n처음 한 번만 시간이 걸립니다.");

        executor.execute(() -> {
            try {
                File work = new File(getCacheDir(), "seong_bundle_install");
                deleteRecursive(work);
                deleteRecursive(gameDir);
                work.mkdirs();
                gameDir.mkdirs();

                File p1 = new File(work, "part1");
                File p2 = new File(work, "part2");
                p1.mkdirs();
                p2.mkdirs();

                unzipAsset(GAME1, p1);
                unzipAsset(GAME2, p2);

                mergeInto(normalizeRoot(p1), gameDir);
                mergeInto(normalizeRoot(p2), gameDir);

                // Old Cao Cao MOD intro AVI files often produce a black ActiveMovie window in Wine.
                disableIntroMovies(gameDir);

                File exe = findBestExe(gameDir);
                if (exe == null) throw new Exception("게임 실행 파일(EXE)을 찾지 못했습니다.");

                createOrUpdateContainer(exe);
                deleteRecursive(work);
            }
            catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("자동 설치 실패\n" + e.getMessage());
                    Toast.makeText(this, "설치 오류: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void unzipAsset(String assetName, File destination) throws Exception {
        try (InputStream raw = getAssets().open(assetName, android.content.res.AssetManager.ACCESS_STREAMING);
             BufferedInputStream bis = new BufferedInputStream(raw, 131072);
             ZipArchiveInputStream zis = new ZipArchiveInputStream(bis, "MS949", true)) {

            ZipArchiveEntry entry;
            byte[] buffer = new byte[131072];
            String rootCanonical = destination.getCanonicalPath() + File.separator;

            while ((entry = zis.getNextZipEntry()) != null) {
                if (entry.isUnixSymlink()) continue;
                File out = new File(destination, entry.getName());
                String outCanonical = out.getCanonicalPath();
                if (!outCanonical.startsWith(rootCanonical)) continue;

                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }

                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();

                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 131072)) {
                    int n;
                    while ((n = zis.read(buffer)) > 0) os.write(buffer, 0, n);
                }
            }
        }
    }

    private void createOrUpdateContainer(File exe) throws Exception {
        ContainerManager manager = new ContainerManager(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int existingId = prefs.getInt(KEY_CONTAINER_ID, 0);
        Container existing = existingId > 0 ? manager.getContainerById(existingId) : null;

        if (existing != null) {
            configureContainer(existing);
            existing.saveData();
            finishInstall(existing.id, exe);
            return;
        }

        JSONObject data = new JSONObject();
        data.put("name", "성삼국지 촉한영걸전");
        data.put("screenSize", "1024x768");
        data.put("envVars", Container.DEFAULT_ENV_VARS + " MESA_EXTENSION_MAX_YEAR=2003");
        data.put("drives", "D:" + gameDir.getAbsolutePath());
        data.put("dxwrapper", DXWrappers.WINED3D);
        data.put("dxwrapperConfig", "ddrawWrapper=" + DXWrappers.CNC_DDRAW);
        data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
        data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);

        manager.createContainerAsync(data, container -> {
            if (container == null) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("게임 실행환경 생성에 실패했습니다.");
                });
                return;
            }
            configureContainer(container);
            container.saveData();
            finishInstall(container.id, exe);
        });
    }

    private void configureContainer(Container container) {
        container.setName("성삼국지 촉한영걸전");
        container.setScreenSize("1024x768");
        container.setEnvVars(Container.DEFAULT_ENV_VARS + " MESA_EXTENSION_MAX_YEAR=2003");
        container.setDrives("D:" + gameDir.getAbsolutePath());
        container.setDXWrapper(DXWrappers.WINED3D);
        container.setDXWrapperConfig("ddrawWrapper=" + DXWrappers.CNC_DDRAW);
    }

    private void finishInstall(int containerId, File exe) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_INSTALLED, true)
                .putInt(KEY_CONTAINER_ID, containerId)
                .putString(KEY_EXE_PATH, exe.getAbsolutePath())
                .apply();

        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            status.setText("설치 완료. 게임을 시작합니다.");
            handler.postDelayed(this::launchGame, 500);
        });
    }

    private void launchGame() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int containerId = prefs.getInt(KEY_CONTAINER_ID, 0);
        String exePath = prefs.getString(KEY_EXE_PATH, "");
        File exe = new File(exePath);

        if (containerId <= 0 || !exe.isFile()) {
            status.setText("게임 설치 정보가 손상되어 다시 설치가 필요합니다.");
            return;
        }

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", containerId);
        intent.putExtra("exec_path", exe.getAbsolutePath());
        startActivity(intent);
    }

    private File normalizeRoot(File dir) {
        File[] children = dir.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) return dir;
        return children[0];
    }

    private void mergeInto(File src, File dst) throws Exception {
        File[] files = src.listFiles();
        if (files == null) return;
        for (File file : files) {
            File target = new File(dst, file.getName());
            if (file.isDirectory()) {
                target.mkdirs();
                mergeInto(file, target);
            }
            else copyFile(file, target);
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(src), 131072);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 131072)) {
            byte[] buffer = new byte[131072];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private File findBestExe(File root) {
        List<File> candidates = new ArrayList<>();
        collectExe(root, candidates, 0);
        if (candidates.isEmpty()) return null;

        candidates.sort(
                Comparator.<File>comparingInt(this::exeScore).reversed()
                        .thenComparing(Comparator.comparingLong(File::length).reversed())
        );
        return candidates.get(0);
    }

    private void collectExe(File dir, List<File> out, int depth) {
        if (depth > 6) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectExe(f, out, depth + 1);
            else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) out.add(f);
        }
    }

    private int exeScore(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        int score = 0;
        if (n.equals("ekd5.exe")) score += 10000;
        if (n.equals("game.exe")) score += 8000;
        if (n.contains("ekd") || n.contains("ccz")) score += 4000;
        if (n.contains("saint") || n.contains("three") || n.contains("kingdom")) score += 3000;
        if (file.getParentFile() != null && file.getParentFile().equals(gameDir)) score += 1200;
        if (file.length() > 200_000 && file.length() < 30_000_000) score += 500;
        for (String bad : Arrays.asList("setup", "install", "unins", "config", "editor", "patch", "update", "launcher")) {
            if (n.contains(bad)) score -= 5000;
        }
        return score;
    }

    private void disableIntroMovies(File root) {
        disableIntroMoviesRecursive(root, 0);
    }

    private void disableIntroMoviesRecursive(File dir, int depth) {
        if (depth > 4) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                disableIntroMoviesRecursive(f, depth + 1);
                continue;
            }

            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".avi")) continue;

            boolean startupMovie =
                    n.equals("logo.avi") ||
                    n.equals("open.avi") ||
                    n.equals("opening.avi") ||
                    n.equals("intro.avi") ||
                    n.equals("start.avi") ||
                    n.startsWith("logo_") ||
                    n.startsWith("opening_");

            if (startupMovie) {
                File disabled = new File(f.getParentFile(), f.getName() + ".disabled");
                if (!disabled.exists()) f.renameTo(disabled);
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
