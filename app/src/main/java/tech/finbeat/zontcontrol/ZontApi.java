package tech.finbeat.zontcontrol;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент ZONT Widget API v3 (https://my.zont.online/api/widget/v3).
 *
 * Аутентификация: токен получается один раз методом POST /authtokens по логину и паролю
 * (HTTP Basic), дальше все запросы идут с заголовком X-ZONT-Token. Пароль не хранится.
 * Каждый запрос содержит обязательный заголовок X-ZONT-Client.
 *
 * Все методы блокирующие — вызывать только из фонового потока.
 */
public class ZontApi {

    public static final String BASE_URL = "https://my.zont.online/api/widget/v3";
    public static final String CLIENT_NAME = "ZONT Пульт (Android)";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    // Команды (охрана, сценарии) ждут подтверждения от прибора, поэтому таймаут чтения большой
    private static final int READ_TIMEOUT_MS = 60_000;

    /** Ошибка API. {@link #isAuthError()} — токен недействителен, нужен повторный вход. */
    public static class ApiException extends Exception {
        public final int httpCode;
        public final String errorCode;

        public ApiException(int httpCode, String errorCode, String message) {
            super(message);
            this.httpCode = httpCode;
            this.errorCode = errorCode;
        }

        public boolean isAuthError() {
            return httpCode == 401 || httpCode == 403;
        }
    }

    private final String client;
    private final String token;

    public ZontApi(String client, String token) {
        this.client = client;
        this.token = token;
    }

    // ---------------------------------------------------------------- Auth

    /** POST /authtokens — получить токен по логину и паролю. */
    public static String createToken(String login, String password) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("client_name", CLIENT_NAME);
        } catch (JSONException ignored) {
        }
        String basic = "Basic " + Base64.encodeToString(
                (login + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject r = request("POST", "/authtokens", body, login, basic, null);
        String token = r.optString("token", "");
        if (token.isEmpty()) {
            throw new ApiException(200, "no_token", "Сервер не вернул токен");
        }
        return token;
    }

    // ---------------------------------------------------------------- State

    /** GET /devices — список устройств со всеми полями (охрана, датчики, сценарии...). */
    public List<JSONObject> getDevices() throws ApiException {
        JSONObject r = request("GET", "/devices", null, client, null, token);
        List<JSONObject> list = new ArrayList<>();
        JSONArray arr = r.optJSONArray("devices");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d != null) list.add(d);
            }
        }
        return list;
    }

    /** GET /devices/{device_id} — одно устройство и его состояние. */
    public JSONObject getDevice(long deviceId) throws ApiException {
        JSONObject r = request("GET", "/devices/" + deviceId, null, client, null, token);
        JSONObject d = r.optJSONObject("device");
        if (d == null) throw new ApiException(200, "no_device", "Устройство не найдено");
        return d;
    }

    // ---------------------------------------------------------------- Control
    // Все команды возвращают обновлённый объект устройства (поле "device") или null.

    /** POST /devices/{id}/guard-zones/{zone}/actions/activate — поставить/снять зону с охраны. */
    public JSONObject setGuardZone(long deviceId, long zoneId, boolean enable) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("zone_id", zoneId);
            body.put("enable", enable);
        } catch (JSONException ignored) {
        }
        return command("/devices/" + deviceId + "/guard-zones/" + zoneId + "/actions/activate", body);
    }

    /** POST /devices/{id}/vehicle/actions/guard — охрана автомобиля (ZTC). */
    public JSONObject setVehicleGuard(long deviceId, boolean enable) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("enable", enable);
        } catch (JSONException ignored) {
        }
        return command("/devices/" + deviceId + "/vehicle/actions/guard", body);
    }

    /** POST /devices/{id}/scenarios/{scenario}/actions/activate — запуск сценария. */
    public JSONObject runScenario(long deviceId, long scenarioId) throws ApiException {
        return command("/devices/" + deviceId + "/scenarios/" + scenarioId + "/actions/activate",
                new JSONObject());
    }

    /** POST /devices/{id}/controls/{button}/actions/trigger — нажатие пользовательской кнопки. */
    public JSONObject triggerButton(long deviceId, long buttonId) throws ApiException {
        return command("/devices/" + deviceId + "/controls/" + buttonId + "/actions/trigger",
                new JSONObject());
    }

    private JSONObject command(String path, JSONObject body) throws ApiException {
        JSONObject r = request("POST", path, body, client, null, token);
        return r.optJSONObject("device");
    }

    // ---------------------------------------------------------------- HTTP

    private static JSONObject request(String method, String path, JSONObject body,
                                      String client, String basicAuth, String token)
            throws ApiException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-ZONT-Client",
                    client == null || client.isEmpty() ? "android-app" : client);
            if (basicAuth != null) conn.setRequestProperty("Authorization", basicAuth);
            if (token != null) conn.setRequestProperty("X-ZONT-Token", token);

            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = is == null ? "" : readAll(is);

            JSONObject json = null;
            if (!text.isEmpty()) {
                try {
                    json = new JSONObject(text);
                } catch (JSONException e) {
                    // не JSON — обработаем ниже
                }
            }

            if (json == null) {
                throw new ApiException(code, "bad_response",
                        code >= 400 ? httpMessage(code) : "Некорректный ответ сервера");
            }
            if (code >= 400 || !json.optBoolean("ok", false)) {
                String errCode = json.optString("error", "");
                String msg = errorUi(json);
                if (msg.isEmpty()) msg = errCode.isEmpty() ? httpMessage(code) : errCode;
                throw new ApiException(code, errCode, msg);
            }
            return json;
        } catch (ApiException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new ApiException(0, "network", "Нет подключения к интернету");
        } catch (SocketTimeoutException e) {
            throw new ApiException(0, "timeout", "Сервер не отвечает (таймаут)");
        } catch (IOException e) {
            throw new ApiException(0, "network", "Ошибка сети: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String errorUi(JSONObject json) {
        Object ui = json.opt("error_ui");
        if (ui instanceof JSONArray) {
            JSONArray a = (JSONArray) ui;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(a.optString(i));
            }
            return sb.toString();
        }
        if (ui instanceof String) return (String) ui;
        return "";
    }

    private static String httpMessage(int code) {
        switch (code) {
            case 401:
                return "Неверный логин или пароль";
            case 403:
                return "Доступ запрещён (токен отозван?)";
            case 404:
                return "Объект не найден";
            default:
                return "Ошибка сервера (HTTP " + code + ")";
        }
    }

    private static String readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
