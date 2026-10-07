package tech.finbeat.zontcontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Главный экран (рассчитан на альбомную ориентацию). Три строки делят высоту экрана:
 *  1) статус охраны и кнопка переключения;
 *  2) плитки выбранных датчиков/статусов;
 *  3) кнопки выбранных сценариев.
 * Элементы в строке делят ширину поровну; при большом количестве переносятся на новую
 * линию внутри своей строки. Размер шрифта подбирается автоматически под размер плитки.
 */
public class MainActivity extends Activity implements ZontLive.Listener {

    /** Максимум элементов в одной линии: альбомная / книжная ориентация. */
    private static final int MAX_COLS_LANDSCAPE = 4;
    private static final int MAX_COLS_PORTRAIT = 2;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;

    private View content;
    private View notConfigured;
    private View guardCard;
    private TextView guardTitle;
    private TextView guardState;
    private Button guardToggle;
    private ProgressBar guardProgress;
    private LinearLayout sensorsRow;
    private LinearLayout scenariosRow;
    private TextView statusLine;

    private JSONObject device;          // последнее полученное состояние устройства
    private boolean loading;
    private boolean commandRunning;
    private boolean resumed;

    /** Живые обновления через WebSocket ZONT (см. ZontLive). */
    private ZontLive live;
    private boolean pendingRefresh;
    private long lastRefreshStart;
    private static final long MIN_REFRESH_GAP_MS = 1500;
    /** Страховочный опрос, пока WebSocket на связи. */
    private static final int LIVE_SAFETY_POLL_SEC = 120;
    private final Runnable throttledRefresh = () -> refresh(false);
    /** До этого момента опрашиваем часто (после команды / во время постановки-снятия). */
    private long fastPollUntil;
    private static final long FAST_POLL_MS = 2000;
    private static final long FAST_POLL_AFTER_COMMAND_MS = 30_000;

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (!commandRunning) refresh(false);
            scheduleRefresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        content = findViewById(R.id.content);
        notConfigured = findViewById(R.id.notConfigured);
        guardCard = findViewById(R.id.guardCard);
        guardTitle = findViewById(R.id.guardTitle);
        guardState = findViewById(R.id.guardState);
        guardToggle = findViewById(R.id.guardToggle);
        guardProgress = findViewById(R.id.guardProgress);
        sensorsRow = findViewById(R.id.sensorsRow);
        scenariosRow = findViewById(R.id.scenariosRow);
        statusLine = findViewById(R.id.statusLine);

        findViewById(R.id.openSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            if (prefs.isConfigured()) refresh(true);
        });
        guardToggle.setOnClickListener(v -> onGuardToggleClicked());

        applySystemBarInsets(findViewById(R.id.root));
        live = new ZontLive(this);
    }

    /** Отступы под статус-бар, навигационную панель и вырез экрана (edge-to-edge в Android 15+). */
    static void applySystemBarInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(l, t, r, b);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        boolean configured = prefs.isConfigured();
        content.setVisibility(configured ? View.VISIBLE : View.GONE);
        notConfigured.setVisibility(configured ? View.GONE : View.VISIBLE);
        if (configured) {
            render();          // показать то, что уже есть (или заглушки)
            refresh(true);
            if (prefs.isLiveEnabled()) {
                live.start(prefs.getToken(), prefs.getLogin(), prefs.getDeviceId());
            } else {
                live.stop();
            }
            scheduleRefresh();
        } else {
            live.stop();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(autoRefresh);
        main.removeCallbacks(throttledRefresh);
        live.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void scheduleRefresh() {
        main.removeCallbacks(autoRefresh);
        if (System.currentTimeMillis() < fastPollUntil) {
            main.postDelayed(autoRefresh, FAST_POLL_MS);
            return;
        }
        int sec = prefs.getRefreshSec();
        // При живом WebSocket частый опрос не нужен — только редкий страховочный
        if (sec > 0 && live != null && live.isConnected()) sec = Math.max(sec, LIVE_SAFETY_POLL_SEC);
        if (sec > 0) main.postDelayed(autoRefresh, Math.max(3, sec) * 1000L);
    }

    /** Перечитать устройство как можно скорее, но не чаще раза в MIN_REFRESH_GAP_MS. */
    private void requestRefreshSoon() {
        if (!resumed || !prefs.isConfigured()) return;
        if (loading) {
            pendingRefresh = true;
            return;
        }
        main.removeCallbacks(throttledRefresh);
        long wait = lastRefreshStart + MIN_REFRESH_GAP_MS - System.currentTimeMillis();
        if (wait > 0) main.postDelayed(throttledRefresh, wait);
        else refresh(false);
    }

    // ------------------------------------------------------------------ ZontLive.Listener

    @Override
    public void onDeviceChanged() {
        requestRefreshSoon();
    }

    @Override
    public void onLiveStateChanged(boolean connected) {
        if (resumed) scheduleRefresh();
    }

    /** Включить частый опрос на ms миллисекунд. */
    private void boostPolling(long ms) {
        long until = System.currentTimeMillis() + ms;
        if (until > fastPollUntil) fastPollUntil = until;
        if (resumed) scheduleRefresh();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ------------------------------------------------------------------ data

    private ZontApi api() {
        return new ZontApi(prefs.getLogin(), prefs.getToken());
    }

    private void refresh(boolean showProgress) {
        if (!prefs.isConfigured()) return;
        if (loading) {
            pendingRefresh = true;
            return;
        }
        loading = true;
        lastRefreshStart = System.currentTimeMillis();
        if (showProgress) statusLine.setText("Обновление…");
        final long deviceId = prefs.getDeviceId();
        executor.execute(() -> {
            try {
                JSONObject d = api().getDevice(deviceId);
                main.post(() -> {
                    loading = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (pendingRefresh) {
                        pendingRefresh = false;
                        requestRefreshSoon();
                    }
                    device = d;
                    render();
                    // Пока идёт постановка/снятие — опрашиваем часто, чтобы быстро увидеть итог
                    DeviceModel.Guard g = DeviceModel.guard(d, prefs.getGuardKey());
                    if (g != null && g.isInProgress()) boostPolling(4000);
                    String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    statusLine.setText(prefs.getDeviceName() + " · "
                            + (DeviceModel.isOnline(d) ? "на связи" : "НЕ на связи")
                            + " · обновлено " + time
                            + (live.isConnected() ? " · ⚡ онлайн" : ""));
                });
            } catch (ZontApi.ApiException e) {
                main.post(() -> {
                    loading = false;
                    pendingRefresh = false;
                    if (isFinishing() || isDestroyed()) return;
                    handleError(e);
                });
            }
        });
    }

    private void handleError(ZontApi.ApiException e) {
        statusLine.setText("Ошибка: " + e.getMessage());
        if (e.isAuthError()) {
            prefs.clearToken();
            Toast.makeText(this, "Требуется повторный вход в ZONT", Toast.LENGTH_LONG).show();
            openSettings();
        }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        renderGuard();
        renderSensors();
        renderScenarios();
    }

    private void renderGuard() {
        String key = prefs.getGuardKey();
        if (key.isEmpty()) {
            guardCard.setVisibility(View.GONE);
            return;
        }
        guardCard.setVisibility(View.VISIBLE);
        DeviceModel.Guard g = device != null ? DeviceModel.guard(device, key) : null;
        int color;
        if (g == null) {
            guardTitle.setText(getString(R.string.guard));
            guardState.setText(device == null ? "…" : "Зона не найдена");
            guardToggle.setText("—");
            guardToggle.setEnabled(false);
            color = getColor(R.color.guard_off);
        } else {
            guardTitle.setText(g.title);
            guardState.setText(g.stateText());
            guardToggle.setText(g.isEnabled() || "enabling".equals(g.state)
                    ? "Снять с охраны" : "Поставить на охрану");
            guardToggle.setEnabled(!commandRunning);
            if (g.alarm) color = getColor(R.color.guard_alarm);
            else if (g.isEnabled()) color = getColor(R.color.guard_on);
            else if (g.isInProgress()) color = getColor(R.color.guard_progress);
            else color = getColor(R.color.guard_off);
        }
        guardCard.setBackground(rounded(color));
    }

    private void renderSensors() {
        List<View> tiles = new ArrayList<>();
        for (String key : prefs.getSensorKeys()) {
            DeviceModel.Reading r = device != null ? DeviceModel.reading(device, key) : null;
            String title = r != null ? r.title : (device == null ? "…" : "Нет данных");
            String value = r != null ? r.value : "—";
            DeviceModel.Level lvl = r != null ? r.level : DeviceModel.Level.UNKNOWN;
            tiles.add(sensorTile(title, value, lvl));
        }
        fillGrid(sensorsRow, tiles);
    }

    private View sensorTile(String title, String value, DeviceModel.Level lvl) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.bg_card);
        tile.setPadding(dp(14), dp(8), dp(14), dp(8));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(levelColor(lvl));
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(1);
        v.setAutoSizeTextTypeUniformWithConfiguration(14, 64, 1, TypedValue.COMPLEX_UNIT_SP);

        tile.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tile.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return tile;
    }

    private void renderScenarios() {
        List<View> buttons = new ArrayList<>();
        for (String key : prefs.getScenarioKeys()) {
            String title = device != null ? DeviceModel.scenarioTitle(device, key) : null;
            Button b = new Button(this);
            b.setText(title != null ? title : (device == null ? "…" : "Не найден"));
            b.setAllCaps(false);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setBackgroundResource(R.drawable.bg_scenario);
            b.setStateListAnimator(null);
            b.setTextColor(0xFFFFFFFF);
            b.setPadding(dp(12), dp(6), dp(12), dp(6));
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setMaxLines(2);
            b.setAutoSizeTextTypeUniformWithConfiguration(12, 30, 1, TypedValue.COMPLEX_UNIT_SP);
            b.setEnabled(title != null && !commandRunning);
            b.setOnClickListener(v -> runScenario(key, title));
            buttons.add(b);
        }
        fillGrid(scenariosRow, buttons);
    }

    /**
     * Раскладывает элементы сеткой внутри строки: все линии одинаковой высоты,
     * все ячейки одинаковой ширины и растянуты на всё доступное место.
     * Пустая строка скрывается — остальные строки занимают её место.
     */
    private void fillGrid(LinearLayout row, List<View> items) {
        row.removeAllViews();
        int n = items.size();
        if (n == 0) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int cols = Math.min(n, landscape ? MAX_COLS_LANDSCAPE : MAX_COLS_PORTRAIT);
        int lines = (n + cols - 1) / cols;
        int m = dp(4);
        for (int r = 0; r < lines; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                View cell = idx < n ? items.get(idx) : new Space(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(m, m, m, m);
                line.addView(cell, lp);
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private void onGuardToggleClicked() {
        if (device == null) return;
        String key = prefs.getGuardKey();
        DeviceModel.Guard g = DeviceModel.guard(device, key);
        if (g == null) return;
        final boolean enable = !(g.isEnabled() || "enabling".equals(g.state));
        BigConfirm.show(this, g.title,
                enable ? "Поставить на охрану?" : "Снять с охраны?",
                enable ? "Поставить" : "Снять",
                getColor(enable ? R.color.guard_on : R.color.guard_progress),
                () -> setGuard(key, enable));
    }

    private void setGuard(String key, boolean enable) {
        final long deviceId = prefs.getDeviceId();
        startCommand(enable ? "Постановка на охрану…" : "Снятие с охраны…");
        guardProgress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                ZontApi api = api();
                JSONObject d = key.equals("vehicle")
                        ? api.setVehicleGuard(deviceId, enable)
                        : api.setGuardZone(deviceId, DeviceModel.keyId(key), enable);
                main.post(() -> finishCommand(d, enable ? "Охрана включена" : "Охрана снята", null));
            } catch (ZontApi.ApiException e) {
                main.post(() -> finishCommand(null, null, e));
            }
        });
    }

    private void runScenario(String key, String title) {
        final long deviceId = prefs.getDeviceId();
        final long id = DeviceModel.keyId(key);
        startCommand("Запуск: " + title + "…");
        executor.execute(() -> {
            try {
                JSONObject d = key.startsWith("button:")
                        ? api().triggerButton(deviceId, id)
                        : api().runScenario(deviceId, id);
                main.post(() -> finishCommand(d, "Выполнено: " + title, null));
            } catch (ZontApi.ApiException e) {
                main.post(() -> finishCommand(null, null, e));
            }
        });
    }

    private void startCommand(String text) {
        commandRunning = true;
        statusLine.setText(text);
        render(); // заблокировать кнопки
    }

    private void finishCommand(JSONObject updated, String okText, ZontApi.ApiException error) {
        commandRunning = false;
        if (isFinishing() || isDestroyed()) return;
        guardProgress.setVisibility(View.GONE);
        if (error != null) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            render();
            handleError(error);
            return;
        }
        Toast.makeText(this, okText, Toast.LENGTH_SHORT).show();
        boostPolling(FAST_POLL_AFTER_COMMAND_MS);
        if (updated != null) {
            device = updated;
            render();
            statusLine.setText(okText);
        } else {
            render();
            refresh(false);
        }
    }

    // ------------------------------------------------------------------ utils

    private int levelColor(DeviceModel.Level lvl) {
        switch (lvl) {
            case OK: return getColor(R.color.state_ok);
            case ACTIVE: return getColor(R.color.state_active);
            case ALARM: return getColor(R.color.state_alarm);
            case WARN: return getColor(R.color.state_warn);
            case FAILURE: return getColor(R.color.state_failure);
            default: return getColor(R.color.text_primary);
        }
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
