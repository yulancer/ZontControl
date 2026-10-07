package tech.finbeat.zontcontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * «Живые» уведомления об изменениях на приборе через WebSocket ZONT
 * (тот же канал, что использует веб-кабинет my.zont.online):
 *   wss://my.zont.online/ws?_auth_token=&lt;token&gt;   субпротокол "zont-comet".
 *
 * ВНИМАНИЕ: канал не описан в публичной документации ZONT и может измениться.
 * Поэтому он используется только как «звонок»: при событии по нашему устройству
 * приложение перечитывает состояние через документированный REST API v3.
 * Если канал перестанет работать, приложение продолжит работать на обычном опросе.
 *
 * Формат сообщений: JSON-массив событий, например
 *   [{"event":"_comet_session","session":"..."}]
 *   [{"event":"devices/123/state","device":{...},"device_id":123}]
 *   [{"event":"io_value_changed","port":{"name":"...","value":...},"device_id":123}]
 */
public class ZontLive {

    private static final String TAG = "ZontLive";
    private static final String WS_URL = "wss://my.zont.online/ws";
    private static final String SUBPROTOCOL = "zont-comet";

    /** Служебные порты, которые меняются постоянно и не влияют на экран. */
    private static final Set<String> NOISE_PORTS = new HashSet<>(Arrays.asList(
            "wifi-state", "additional-wifi-state", "ethernet-state", "gsm-state",
            "connection-state", "balance-value"));

    /** События, после которых стоит перечитать состояние устройства. */
    private static final Set<String> RELEVANT_EVENTS = new HashSet<>(Arrays.asList(
            "io_value_changed", "online", "offline", "updated", "new_rf_statuses",
            "new_temperature", "z3k_boiler_state", "send_custom_command_response",
            "send_z3k_command_response"));

    public interface Listener {
        /** Изменилось что-то на нашем устройстве (вызывается в главном потоке). */
        void onDeviceChanged();

        /** Соединение установлено / потеряно (вызывается в главном потоке). */
        void onLiveStateChanged(boolean connected);
    }

    private static OkHttpClient sharedClient;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private WebSocket ws;
    private String sessionId;
    private String token;
    private String clientId;
    private long deviceId;
    private boolean running;
    private boolean connected;
    private int attempt;
    private int generation;   // отбрасывает колбэки от старых соединений

    private final Runnable reconnect = this::connect;

    public ZontLive(Listener listener) {
        this.listener = listener;
    }

    private static synchronized OkHttpClient client() {
        if (sharedClient == null) {
            sharedClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();
        }
        return sharedClient;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Подключиться (или переподключиться к другому устройству). Вызывать из главного потока. */
    public void start(String token, String clientId, long deviceId) {
        if (running && deviceId == this.deviceId && token.equals(this.token)) return;
        stop();
        this.token = token;
        this.clientId = clientId;
        this.deviceId = deviceId;
        this.running = true;
        this.attempt = 0;
        connect();
    }

    /** Отключиться. Вызывать из главного потока. */
    public void stop() {
        running = false;
        main.removeCallbacks(reconnect);
        generation++;
        if (ws != null) {
            ws.close(1000, null);
            ws = null;
        }
        setConnected(false);
    }

    private void connect() {
        if (!running) return;
        final int gen = ++generation;
        StringBuilder url = new StringBuilder(WS_URL)
                .append("?_client=app&_brand=zont&_auth_token=").append(enc(token));
        if (sessionId != null) url.append("&session=").append(enc(sessionId));

        Request request = new Request.Builder()
                .url(url.toString())
                .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
                .header("X-ZONT-Client", clientId == null ? "android-app" : clientId)
                .header("X-ZONT-Token", token)
                .build();

        ws = client().newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                main.post(() -> {
                    if (gen != generation) return;
                    attempt = 0;
                    setConnected(true);
                    // Пропущенные за время отключения изменения — перечитать
                    listener.onDeviceChanged();
                });
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                final boolean changed = handleMessage(text);
                if (changed) {
                    main.post(() -> {
                        if (gen == generation) listener.onDeviceChanged();
                    });
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                main.post(() -> onDisconnected(gen, "closed " + code));
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String why = response != null ? ("HTTP " + response.code()) : String.valueOf(t);
                main.post(() -> onDisconnected(gen, why));
            }
        });
    }

    /** Разбор сообщения (фоновый поток OkHttp). true — нужно перечитать устройство. */
    private boolean handleMessage(String text) {
        boolean changed = false;
        try {
            JSONArray events = new JSONArray(text);
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null) continue;
                String event = e.optString("event", "");
                if ("_comet_session".equals(event)) {
                    sessionId = e.optString("session", null);
                    continue;
                }
                if ("_comet_session_expired".equals(event)) {
                    sessionId = null;
                    changed = true;
                    continue;
                }
                if (e.optLong("device_id", -1) != deviceId) continue;

                if (event.startsWith("devices/")) {
                    changed = true;                       // devices/<id>/state и т.п.
                } else if ("io_value_changed".equals(event)) {
                    JSONObject port = e.optJSONObject("port");
                    String name = port != null ? port.optString("name", "") : "";
                    if (!NOISE_PORTS.contains(name)) changed = true;
                } else if (RELEVANT_EVENTS.contains(event)) {
                    changed = true;
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "bad message: " + ex);
        }
        return changed;
    }

    private void onDisconnected(int gen, String why) {
        if (gen != generation) return;
        ws = null;
        setConnected(false);
        if (!running) return;
        Log.i(TAG, "disconnected: " + why);
        // Экспоненциальная задержка 1, 2, 4 ... 60 c (+ случайная добавка)
        long delay = Math.min(60_000L, 1000L << Math.min(attempt, 6));
        delay += (long) (Math.random() * 1000);
        attempt++;
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, delay);
    }

    private void setConnected(boolean c) {
        if (connected == c) return;
        connected = c;
        listener.onLiveStateChanged(c);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
