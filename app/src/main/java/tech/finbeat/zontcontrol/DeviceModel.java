package tech.finbeat.zontcontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Разбор объекта устройства из ZONT Widget API v3 (схема Device):
 * guard_zones[], sensors[], scenarios[], controls{buttons, statuses, ...}, car_state.
 */
public final class DeviceModel {

    private DeviceModel() {
    }

    /** Элемент выбора в настройках. */
    public static class Option {
        public final String key;
        public final String title;

        public Option(String key, String title) {
            this.key = key;
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum Level { OK, ACTIVE, ALARM, WARN, FAILURE, UNKNOWN }

    /** Отображаемое значение датчика/статуса. */
    public static class Reading {
        public final String title;
        public final String value;
        public final Level level;

        public Reading(String title, String value, Level level) {
            this.title = title;
            this.value = value;
            this.level = level;
        }
    }

    /** Состояние охраны: state = enabled|disabled|enabling|disabling|unknown. */
    public static class Guard {
        public final String title;
        public final String state;
        public final boolean alarm;

        public Guard(String title, String state, boolean alarm) {
            this.title = title;
            this.state = state;
            this.alarm = alarm;
        }

        public boolean isEnabled() { return "enabled".equals(state); }

        public boolean isInProgress() { return "enabling".equals(state) || "disabling".equals(state); }

        public String stateText() {
            if (alarm) return "ТРЕВОГА";
            switch (state) {
                case "enabled": return "Под охраной";
                case "disabled": return "Снято с охраны";
                case "enabling": return "Постановка…";
                case "disabling": return "Снятие…";
                default: return "Неизвестно";
            }
        }
    }

    // ------------------------------------------------------------ общие поля

    public static long id(JSONObject o) { return o.optLong("id", -1); }

    public static String deviceTitle(JSONObject d) {
        String name = d.optString("name", "");
        JSONObject info = d.optJSONObject("device_info");
        String model = info != null ? info.optString("model", "") : "";
        if (name.isEmpty()) return model.isEmpty() ? ("#" + id(d)) : model;
        return model.isEmpty() ? name : name + " (" + model + ")";
    }

    public static boolean isOnline(JSONObject d) { return d.optBoolean("online", false); }

    // ------------------------------------------------------------ варианты для настроек

    public static List<Option> guardOptions(JSONObject d) {
        List<Option> res = new ArrayList<>();
        JSONArray zones = d.optJSONArray("guard_zones");
        if (zones != null) {
            for (int i = 0; i < zones.length(); i++) {
                JSONObject z = zones.optJSONObject(i);
                if (z == null) continue;
                res.add(new Option("zone:" + id(z), "Зона: " + z.optString("name", "#" + id(z))));
            }
        }
        if (d.optJSONObject("car_state") != null) {
            res.add(new Option("vehicle", "Охрана автомобиля"));
        }
        return res;
    }

    public static List<Option> sensorOptions(JSONObject d) {
        List<Option> res = new ArrayList<>();
        JSONArray sensors = d.optJSONArray("sensors");
        if (sensors != null) {
            for (int i = 0; i < sensors.length(); i++) {
                JSONObject s = sensors.optJSONObject(i);
                if (s == null) continue;
                String type = sensorTypeName(s.optString("type"));
                res.add(new Option("sensor:" + id(s),
                        s.optString("name", "Датчик #" + id(s)) + (type.isEmpty() ? "" : "  · " + type)));
            }
        }
        JSONObject controls = d.optJSONObject("controls");
        if (controls != null) {
            addLabelled(res, controls.optJSONArray("statuses"), "status:", "статус");
            addLabelled(res, controls.optJSONArray("toggle_buttons"), "toggle:", "переключатель");
        }
        return res;
    }

    public static List<Option> scenarioOptions(JSONObject d) {
        List<Option> res = new ArrayList<>();
        JSONArray sc = d.optJSONArray("scenarios");
        if (sc != null) {
            for (int i = 0; i < sc.length(); i++) {
                JSONObject s = sc.optJSONObject(i);
                if (s == null) continue;
                res.add(new Option("scenario:" + id(s), s.optString("name", "Сценарий #" + id(s))));
            }
        }
        // Пользовательские кнопки тоже можно запускать как «сценарии»
        JSONObject controls = d.optJSONObject("controls");
        JSONArray buttons = controls != null ? controls.optJSONArray("buttons") : null;
        if (buttons != null) {
            for (int i = 0; i < buttons.length(); i++) {
                JSONObject b = buttons.optJSONObject(i);
                if (b == null) continue;
                res.add(new Option("button:" + id(b), b.optString("name", "Кнопка #" + id(b)) + "  · кнопка"));
            }
        }
        return res;
    }

    private static void addLabelled(List<Option> res, JSONArray arr, String prefix, String kind) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            res.add(new Option(prefix + id(s), labelName(s) + "  · " + kind));
        }
    }

    /** Подпись для кнопки сценария (без пометок типа). */
    public static String scenarioTitle(JSONObject d, String key) {
        JSONObject o = findByKey(d, key);
        if (o == null) return null;
        return o.optString("name", key);
    }

    // ------------------------------------------------------------ текущие значения

    public static Guard guard(JSONObject d, String key) {
        if (key == null || key.isEmpty()) return null;
        if (key.equals("vehicle")) {
            JSONObject car = d.optJSONObject("car_state");
            if (car == null) return null;
            return new Guard("Охрана автомобиля", car.optString("guard", "unknown"), false);
        }
        JSONObject z = findByKey(d, key);
        if (z == null) return null;
        return new Guard(z.optString("name", "Охрана"), z.optString("state", "unknown"),
                z.optBoolean("alarm", false));
    }

    public static Reading reading(JSONObject d, String key) {
        JSONObject o = findByKey(d, key);
        if (o == null) return null;

        if (key.startsWith("status:") || key.startsWith("toggle:")) {
            JSONObject label = o.optJSONObject("name");
            Boolean active = o.isNull("active") ? null : o.optBoolean("active");
            String title = labelName(o);
            String value;
            if (label != null) {
                value = Boolean.TRUE.equals(active)
                        ? label.optString("active_label", "Вкл")
                        : label.optString("inactive_label", "Выкл");
            } else {
                value = active == null ? "—" : (active ? "Вкл" : "Выкл");
            }
            if (value.isEmpty()) value = Boolean.TRUE.equals(active) ? "Вкл" : "Выкл";
            Level lvl = active == null ? Level.UNKNOWN : (active ? Level.ACTIVE : Level.OK);
            return new Reading(title, value, lvl);
        }

        // sensor
        String title = o.optString("name", "Датчик");
        String type = o.optString("type", "");
        String status = o.optString("status", "unknown");
        Boolean triggered = o.isNull("triggered") || !o.has("triggered") ? null : o.optBoolean("triggered");
        boolean hasValue = o.has("value") && !o.isNull("value");

        String value;
        if ("failure".equals(status)) {
            value = "Нет связи";
        } else if (isDiscrete(type) && triggered != null) {
            value = discreteText(type, triggered);
        } else if (hasValue) {
            String unit = o.isNull("unit") ? "" : o.optString("unit", "");
            if (unit.isEmpty()) unit = defaultUnit(type);
            value = formatNumber(o.optDouble("value")) + (unit.isEmpty() ? "" : " " + unit);
            value = value.replace(" °", "°");
        } else if (triggered != null) {
            value = triggered ? "Сработал" : "Норма";
        } else {
            value = "—";
        }

        Level lvl;
        switch (status) {
            case "ok": lvl = Boolean.TRUE.equals(triggered) ? Level.WARN : Level.OK; break;
            case "alarm": lvl = Level.ALARM; break;
            case "silent_alarm": lvl = Level.WARN; break;
            case "failure": lvl = Level.FAILURE; break;
            default: lvl = Level.UNKNOWN;
        }
        return new Reading(title, value, lvl);
    }

    // ------------------------------------------------------------ поиск по ключу

    /** Находит элемент устройства по ключу вида "prefix:id". */
    public static JSONObject findByKey(JSONObject d, String key) {
        int p = key.indexOf(':');
        if (p < 0) return null;
        String prefix = key.substring(0, p);
        long id;
        try {
            id = Long.parseLong(key.substring(p + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        JSONObject controls = d.optJSONObject("controls");
        JSONArray arr;
        switch (prefix) {
            case "zone": arr = d.optJSONArray("guard_zones"); break;
            case "sensor": arr = d.optJSONArray("sensors"); break;
            case "scenario": arr = d.optJSONArray("scenarios"); break;
            case "status": arr = controls != null ? controls.optJSONArray("statuses") : null; break;
            case "toggle": arr = controls != null ? controls.optJSONArray("toggle_buttons") : null; break;
            case "button": arr = controls != null ? controls.optJSONArray("buttons") : null; break;
            default: arr = null;
        }
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id(o) == id) return o;
        }
        return null;
    }

    public static long keyId(String key) {
        int p = key.indexOf(':');
        try {
            return Long.parseLong(key.substring(p + 1));
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ вспомогательное

    /** name у статусов/переключателей — объект StatusLabel {name, active_label, inactive_label}. */
    private static String labelName(JSONObject o) {
        JSONObject label = o.optJSONObject("name");
        if (label != null) return label.optString("name", "#" + id(o));
        return o.optString("name", "#" + id(o));
    }

    private static boolean isDiscrete(String type) {
        switch (type) {
            case "opening":
            case "motion":
            case "leakage":
            case "smoke":
            case "gas":
            case "discrete":
            case "boiler_failure":
            case "room_thermostat":
                return true;
            default:
                return false;
        }
    }

    private static String discreteText(String type, boolean triggered) {
        switch (type) {
            case "opening": return triggered ? "Открыто" : "Закрыто";
            case "motion": return triggered ? "Движение" : "Нет движения";
            case "leakage": return triggered ? "Протечка!" : "Сухо";
            case "smoke": return triggered ? "Дым!" : "Норма";
            case "gas": return triggered ? "Газ!" : "Норма";
            case "boiler_failure": return triggered ? "Авария" : "Норма";
            case "room_thermostat": return triggered ? "Запрос тепла" : "Нет запроса";
            default: return triggered ? "Сработал" : "Норма";
        }
    }

    private static String defaultUnit(String type) {
        switch (type) {
            case "temperature": return "°C";
            case "voltage": return "В";
            case "pressure": return "бар";
            case "humidity": return "%";
            case "modulation": return "%";
            default: return "";
        }
    }

    public static String sensorTypeName(String type) {
        switch (type) {
            case "temperature": return "температура";
            case "voltage": return "напряжение";
            case "pressure": return "давление";
            case "humidity": return "влажность";
            case "opening": return "открытие";
            case "motion": return "движение";
            case "leakage": return "протечка";
            case "smoke": return "дым";
            case "room_thermostat": return "комн. термостат";
            case "boiler_failure": return "авария котла";
            case "power_source": return "питание";
            case "modulation": return "модуляция";
            case "discrete": return "дискретный";
            case "dhw_speed": return "расход ГВС";
            case "gas": return "газ";
            default: return "";
        }
    }

    private static String formatNumber(double v) {
        DecimalFormat f = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(v);
    }
}
