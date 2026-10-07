package tech.finbeat.zontcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Хранилище настроек приложения. Пароль не хранится — только токен ZONT. */
public class Prefs {

    private static final String FILE = "zont_prefs";

    private static final String K_LOGIN = "login";
    private static final String K_TOKEN = "token";
    private static final String K_DEVICE_ID = "device_id";
    private static final String K_DEVICE_NAME = "device_name";
    private static final String K_GUARD = "guard_key";
    private static final String K_SENSORS = "sensors";
    private static final String K_SCENARIOS = "scenarios";
    private static final String K_REFRESH = "refresh_sec";
    private static final String K_LIVE = "live_updates";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getLogin() { return sp.getString(K_LOGIN, ""); }

    public String getToken() { return sp.getString(K_TOKEN, ""); }

    public boolean isLoggedIn() { return !getToken().isEmpty(); }

    public void setAuth(String login, String token) {
        sp.edit().putString(K_LOGIN, login).putString(K_TOKEN, token).apply();
    }

    public void clearToken() { sp.edit().remove(K_TOKEN).apply(); }

    public long getDeviceId() { return sp.getLong(K_DEVICE_ID, -1); }

    public String getDeviceName() { return sp.getString(K_DEVICE_NAME, ""); }

    /** Ключ источника охраны: "zone:&lt;id&gt;", "vehicle" или "" (нет). */
    public String getGuardKey() { return sp.getString(K_GUARD, ""); }

    /** Ключи датчиков/статусов: "sensor:&lt;id&gt;", "status:&lt;id&gt;". */
    public List<String> getSensorKeys() { return split(sp.getString(K_SENSORS, "")); }

    /** Ключи сценариев/кнопок: "scenario:&lt;id&gt;", "button:&lt;id&gt;". */
    public List<String> getScenarioKeys() { return split(sp.getString(K_SCENARIOS, "")); }

    public int getRefreshSec() { return sp.getInt(K_REFRESH, 30); }

    /** Мгновенные обновления через WebSocket (по умолчанию включены). */
    public boolean isLiveEnabled() { return sp.getBoolean(K_LIVE, true); }

    public void setLiveEnabled(boolean on) { sp.edit().putBoolean(K_LIVE, on).apply(); }

    public boolean isConfigured() { return isLoggedIn() && getDeviceId() >= 0; }

    public void saveSelection(long deviceId, String deviceName, String guardKey,
                              List<String> sensors, List<String> scenarios, int refreshSec) {
        sp.edit()
                .putLong(K_DEVICE_ID, deviceId)
                .putString(K_DEVICE_NAME, deviceName)
                .putString(K_GUARD, guardKey)
                .putString(K_SENSORS, TextUtils.join(",", sensors))
                .putString(K_SCENARIOS, TextUtils.join(",", scenarios))
                .putInt(K_REFRESH, refreshSec)
                .apply();
    }

    private static List<String> split(String s) {
        List<String> res = new ArrayList<>();
        if (s == null || s.isEmpty()) return res;
        for (String part : Arrays.asList(s.split(","))) {
            if (!part.trim().isEmpty()) res.add(part.trim());
        }
        return res;
    }
}
