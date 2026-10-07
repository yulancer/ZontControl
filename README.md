# ZONT Пульт (Android)

Приложение для управления прибором ZONT через **ZONT Widget API v3**
(`https://my.zont.online/api/widget/v3`, документация: https://my.zont.online/api/widget/v3/).

## Возможности
**Настройки**
- логин и пароль ZONT → приложение получает токен (`POST /authtokens`), пароль не сохраняется;
- выбор устройства из списка (`GET /devices`);
- выбор охранной зоны для верхней строки (`guard_zones`, для ZTC — охрана автомобиля);
- выбор нескольких датчиков/статусов (`sensors`, `controls.statuses`, `controls.toggle_buttons`);
- выбор нескольких сценариев (`scenarios`) и пользовательских кнопок (`controls.buttons`);
- интервал автообновления.

**Главный экран**
1. Верхняя строка — статус охраны (цвет: зелёный — под охраной, серый — снято, оранжевый — постановка/снятие, красный — тревога) и кнопка переключения с подтверждением.
2. Средняя строка — плитки выбранных датчиков/статусов.
3. Нижняя строка — кнопки запуска выбранных сценариев.

## Используемые методы API
| Действие | Метод |
|---|---|
| Получить токен | `POST /authtokens` (Basic auth) |
| Список устройств | `GET /devices` |
| Состояние устройства | `GET /devices/{device_id}` |
| Охрана зоны | `POST /devices/{id}/guard-zones/{zone}/actions/activate` `{"enable": true}` |
| Охрана авто (ZTC) | `POST /devices/{id}/vehicle/actions/guard` `{"enable": true}` |
| Запуск сценария | `POST /devices/{id}/scenarios/{scenario}/actions/activate` |
| Пользовательская кнопка | `POST /devices/{id}/controls/{button}/actions/trigger` |

Все запросы отправляются с заголовками `X-ZONT-Client` (логин пользователя) и `X-ZONT-Token`.

## Сборка
Android Studio → Open → эта папка. Java, без сторонних библиотек.
AGP 9.3.1, Gradle 9.6.1, compileSdk 36, minSdk 24.

## Структура
- `ZontApi.java` — HTTP-клиент API
- `DeviceModel.java` — разбор JSON устройства (охрана, датчики, сценарии)
- `Prefs.java` — настройки (SharedPreferences)
- `MainActivity.java` — главный экран
- `SettingsActivity.java` — окно настроек
