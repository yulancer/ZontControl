package tech.finbeat.zontcontrol;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Toolbar;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Окно настроек:
 *  - логин/пароль (получение токена ZONT);
 *  - выбор устройства;
 *  - выбор охранной зоны для верхней строки;
 *  - выбор датчиков/статусов (несколько);
 *  - выбор сценариев (несколько).
 */
public class SettingsActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;

    private View loginForm, loggedIn, deviceSection;
    private EditText loginEdit, passwordEdit, refreshEdit;
    private CheckBox liveCheck;
    private TextView loggedInAs, errorText;
    private ProgressBar progress;
    private Spinner deviceSpinner, guardSpinner;
    private LinearLayout sensorsList, scenariosList;

    private final List<JSONObject> devices = new ArrayList<>();
    private JSONObject selectedDevice;
    private List<DeviceModel.Option> guardOptions = new ArrayList<>();
    private final List<CheckBox> sensorChecks = new ArrayList<>();
    private final List<CheckBox> scenarioChecks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // Android 15+ рисует окно под системными панелями (edge-to-edge).
        // Сверху — подложка под статус-бар, слева/справа/снизу — отступы (включая клавиатуру).
        View root = findViewById(R.id.settingsRoot);
        View spacer = findViewById(R.id.statusBarSpacer);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            ViewGroup.LayoutParams lp = spacer.getLayoutParams();
            if (lp.height != t) {
                lp.height = t;
                spacer.setLayoutParams(lp);
            }
            v.setPadding(l, 0, r, b);
            return insets;
        });

        loginForm = findViewById(R.id.loginForm);
        loggedIn = findViewById(R.id.loggedIn);
        deviceSection = findViewById(R.id.deviceSection);
        loginEdit = findViewById(R.id.login);
        passwordEdit = findViewById(R.id.password);
        refreshEdit = findViewById(R.id.refreshInterval);
        loggedInAs = findViewById(R.id.loggedInAs);
        errorText = findViewById(R.id.error);
        progress = findViewById(R.id.progress);
        deviceSpinner = findViewById(R.id.deviceSpinner);
        guardSpinner = findViewById(R.id.guardSpinner);
        sensorsList = findViewById(R.id.sensorsList);
        scenariosList = findViewById(R.id.scenariosList);

        loginEdit.setText(prefs.getLogin());
        refreshEdit.setText(String.valueOf(prefs.getRefreshSec()));
        liveCheck = findViewById(R.id.liveUpdates);
        liveCheck.setChecked(prefs.isLiveEnabled());

        ((Button) findViewById(R.id.signIn)).setOnClickListener(v -> signIn());
        findViewById(R.id.pasteLogin).setOnClickListener(v -> pasteInto(loginEdit));
        findViewById(R.id.pastePassword).setOnClickListener(v -> pasteInto(passwordEdit));
        ((CheckBox) findViewById(R.id.showPassword)).setOnCheckedChangeListener((cb, show) -> {
            int sel = passwordEdit.getSelectionEnd();
            passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | (show
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordEdit.setSelection(Math.max(0, Math.min(sel, passwordEdit.length())));
        });
        ((Button) findViewById(R.id.signOut)).setOnClickListener(v -> signOut());
        ((Button) findViewById(R.id.reloadDevices)).setOnClickListener(v -> loadDevices());
        ((Button) findViewById(R.id.save)).setOnClickListener(v -> save());

        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < devices.size()) showDevice(devices.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        updateAuthUi();
        if (prefs.isLoggedIn()) loadDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ auth

    private void updateAuthUi() {
        boolean logged = prefs.isLoggedIn();
        loginForm.setVisibility(logged ? View.GONE : View.VISIBLE);
        loggedIn.setVisibility(logged ? View.VISIBLE : View.GONE);
        deviceSection.setVisibility(logged && !devices.isEmpty() ? View.VISIBLE : View.GONE);
        loggedInAs.setText("Вход выполнен: " + prefs.getLogin());
    }

    private void signIn() {
        final String login = loginEdit.getText().toString().trim();
        final String password = passwordEdit.getText().toString();
        if (login.isEmpty() || password.isEmpty()) {
            showError("Введите логин и пароль");
            return;
        }
        hideKeyboard();
        setBusy(true);
        executor.execute(() -> {
            try {
                String token = ZontApi.createToken(login, password);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    passwordEdit.setText("");
                    prefs.setAuth(login, token);
                    updateAuthUi();
                    loadDevices();
                });
            } catch (ZontApi.ApiException e) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    showError(e.getMessage());
                });
            }
        });
    }

    private void signOut() {
        prefs.clearToken();
        devices.clear();
        selectedDevice = null;
        updateAuthUi();
    }

    // ------------------------------------------------------------------ devices

    private void loadDevices() {
        setBusy(true);
        final ZontApi api = new ZontApi(prefs.getLogin(), prefs.getToken());
        executor.execute(() -> {
            try {
                List<JSONObject> list = api.getDevices();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    onDevicesLoaded(list);
                });
            } catch (ZontApi.ApiException e) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    showError(e.getMessage());
                    if (e.isAuthError()) signOut();
                });
            }
        });
    }

    private void onDevicesLoaded(List<JSONObject> list) {
        devices.clear();
        devices.addAll(list);
        if (devices.isEmpty()) {
            showError("В аккаунте нет устройств");
            updateAuthUi();
            return;
        }
        List<String> titles = new ArrayList<>();
        int selected = 0;
        long savedId = prefs.getDeviceId();
        for (int i = 0; i < devices.size(); i++) {
            JSONObject d = devices.get(i);
            titles.add(DeviceModel.deviceTitle(d) + (DeviceModel.isOnline(d) ? "" : "  · не на связи"));
            if (DeviceModel.id(d) == savedId) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        deviceSpinner.setSelection(selected);
        showDevice(devices.get(selected));
        updateAuthUi();
    }

    /** Заполняет списки охраны, датчиков и сценариев для выбранного устройства. */
    private void showDevice(JSONObject d) {
        if (d == selectedDevice) return;
        selectedDevice = d;
        boolean isSaved = DeviceModel.id(d) == prefs.getDeviceId();

        // Охрана
        guardOptions = new ArrayList<>(DeviceModel.guardOptions(d));
        guardOptions.add(new DeviceModel.Option("", "Не показывать"));
        ArrayAdapter<DeviceModel.Option> ga = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, guardOptions);
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        guardSpinner.setAdapter(ga);
        int gSel = 0; // по умолчанию — первая зона
        if (isSaved) {
            for (int i = 0; i < guardOptions.size(); i++) {
                if (guardOptions.get(i).key.equals(prefs.getGuardKey())) gSel = i;
            }
        }
        guardSpinner.setSelection(gSel);

        // Датчики и статусы
        fillChecks(sensorsList, sensorChecks, DeviceModel.sensorOptions(d),
                isSaved ? prefs.getSensorKeys() : new ArrayList<>(),
                "У устройства нет датчиков");

        // Сценарии
        fillChecks(scenariosList, scenarioChecks, DeviceModel.scenarioOptions(d),
                isSaved ? prefs.getScenarioKeys() : new ArrayList<>(),
                "У устройства нет сценариев и пользовательских кнопок");
    }

    /**
     * Список с галочками и кнопками ▲▼ для изменения порядка.
     * Порядок строк в списке = порядок плиток/кнопок на главном экране.
     * Ранее выбранные элементы идут первыми в сохранённом порядке, остальные — следом.
     */
    private void fillChecks(LinearLayout container, List<CheckBox> checks,
                            List<DeviceModel.Option> options, List<String> savedOrder, String emptyText) {
        container.removeAllViews();
        checks.clear();
        if (options.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(emptyText);
            t.setTextColor(getColor(R.color.text_secondary));
            container.addView(t);
            return;
        }
        // Сначала сохранённые (в их порядке), затем остальные в порядке устройства
        List<DeviceModel.Option> ordered = new ArrayList<>();
        for (String key : savedOrder) {
            for (DeviceModel.Option o : options) {
                if (o.key.equals(key)) { ordered.add(o); break; }
            }
        }
        for (DeviceModel.Option o : options) {
            if (!ordered.contains(o)) ordered.add(o);
        }
        Set<String> checked = new HashSet<>(savedOrder);

        for (DeviceModel.Option o : ordered) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox cb = new CheckBox(this);
            cb.setText(o.title);
            cb.setTag(o.key);
            cb.setChecked(checked.contains(o.key));
            row.addView(cb, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(moveButton("▲", "Выше", () -> moveRow(container, checks, cb, -1)));
            row.addView(moveButton("▼", "Ниже", () -> moveRow(container, checks, cb, +1)));

            container.addView(row);
            checks.add(cb);
        }
    }

    private Button moveButton(String symbol, String description, Runnable action) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(symbol);
        b.setContentDescription(description);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(getColor(R.color.primary));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                getResources().getDisplayMetrics()));
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Сдвигает строку с галочкой cb на delta позиций (−1 — выше, +1 — ниже). */
    private void moveRow(LinearLayout container, List<CheckBox> checks, CheckBox cb, int delta) {
        int from = checks.indexOf(cb);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= checks.size()) return;
        View row = container.getChildAt(from);
        container.removeViewAt(from);
        container.addView(row, to);
        checks.remove(from);
        checks.add(to, cb);
    }

    // ------------------------------------------------------------------ save

    private void save() {
        if (!prefs.isLoggedIn()) {
            showError("Сначала войдите в аккаунт ZONT");
            return;
        }
        if (selectedDevice == null) {
            showError("Выберите устройство");
            return;
        }
        int refresh;
        try {
            refresh = Integer.parseInt(refreshEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            refresh = 30;
        }
        if (refresh != 0 && refresh < 3) refresh = 3;

        String guardKey = "";
        int gPos = guardSpinner.getSelectedItemPosition();
        if (gPos >= 0 && gPos < guardOptions.size()) guardKey = guardOptions.get(gPos).key;

        prefs.saveSelection(
                DeviceModel.id(selectedDevice),
                selectedDevice.optString("name", DeviceModel.deviceTitle(selectedDevice)),
                guardKey,
                checkedKeys(sensorChecks),
                checkedKeys(scenarioChecks),
                refresh);
        prefs.setLiveEnabled(liveCheck.isChecked());
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
        finish();
    }

    private static List<String> checkedKeys(List<CheckBox> checks) {
        List<String> res = new ArrayList<>();
        for (CheckBox cb : checks) {
            if (cb.isChecked()) res.add((String) cb.getTag());
        }
        return res;
    }

    // ------------------------------------------------------------------ ui utils

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) errorText.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        errorText.setText(msg);
        errorText.setVisibility(View.VISIBLE);
    }

    /** Вставка текста из буфера обмена (на случай, если системное меню «Вставить» недоступно). */
    private void pasteInto(EditText edit) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence text = item.coerceToText(this);
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "В буфере обмена нет текста", Toast.LENGTH_SHORT).show();
            return;
        }
        String clean = text.toString().trim();
        int start = Math.max(0, Math.min(edit.getSelectionStart(), edit.getSelectionEnd()));
        int end = Math.max(0, Math.max(edit.getSelectionStart(), edit.getSelectionEnd()));
        edit.getText().replace(start, end, clean);
        edit.requestFocus();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View v = getCurrentFocus();
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
