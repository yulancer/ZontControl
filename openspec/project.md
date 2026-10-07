# Project Context

## Purpose
«ZONT Пульт» — Android-приложение для быстрого управления одним прибором ZONT
(охранно-отопительный контроллер) с экрана, закреплённого в альбомной ориентации:
статус и переключение охраны, отображение нескольких датчиков/статусов и запуск
нескольких сценариев. Целевая конфигурация — 2 статуса и 2 сценария (может меняться).

## Tech Stack
- Java 17 (без Kotlin в исходниках), Android Views (без Jetpack Compose, без AppCompat)
- Android Gradle Plugin 9.3.1, Gradle 9.6.1 (wrapper скопирован из проекта Sauna)
- compileSdk 36, targetSdk 36, minSdk 26 (нужен для автоподбора размера шрифта)
- HTTP REST: `HttpURLConnection`; JSON: `org.json` из Android SDK
- WebSocket: OkHttp 4.12.0 (единственная внешняя зависимость)
- Хранение настроек: `SharedPreferences`
- Пакет / applicationId: `tech.finbeat.zontcontrol`
- Расположение проекта: `C:\Projects\Android\ZontControl`

## Project Conventions

### Code Style
- Интерфейс и комментарии в коде — на русском языке
- Классы: `ZontApi` (REST), `ZontLive` (WebSocket), `DeviceModel` (разбор JSON устройства),
  `Prefs` (настройки), `MainActivity`, `SettingsActivity`, `BigConfirm` (диалог подтверждения)
- Сетевые вызовы — только в фоновом потоке (`ExecutorService`), результат — через `Handler(main)`
- Перед возвратом результата в UI проверять `isFinishing() || isDestroyed()`

### Architecture Patterns
- Источник истины о состоянии устройства — REST `GET /devices/{id}` (Widget API v3)
- WebSocket используется только как сигнал «что-то изменилось» → перечитать REST
- Элементы устройства адресуются строковыми ключами `prefix:id`
  (`zone:`, `sensor:`, `status:`, `toggle:`, `scenario:`, `button:`, а также `vehicle`)
- Системные отступы (edge-to-edge, Android 15+) обрабатываются вручную через
  `OnApplyWindowInsetsListener`

### Testing Strategy
- Компиляция Java-кода проверяется `javac` против `android.jar` (API 36) из локального SDK
- Ручная проверка на устройстве в Android Studio
- После правок файлов извне: `File → Reload All from Disk`; после правок Gradle-файлов —
  `File → Sync Project with Gradle Files`

### Git Workflow
- Не определён (репозиторий не инициализирован)

## Domain Context
- ZONT Widget API v3: `https://my.zont.online/api/widget/v3`
  (документация OpenAPI: https://my.zont.online/api/widget/v3/)
- Классическое API (для справки): https://zont-online.ru/api/docs/
- Обязательный заголовок каждого запроса: `X-ZONT-Client` (в приложении — логин пользователя)
- Аутентификация: токен (`X-ZONT-Token`), получаемый один раз по логину/паролю
- Охранная зона: `guard_zones[]` с `state` ∈ {unknown, disabled, enabled, disabling, enabling} и `alarm`
- Датчики: `sensors[]` (`type`, `status`, `value`, `unit`, `triggered`)
- Пользовательские элементы: `controls.statuses[]`, `controls.toggle_buttons[]`, `controls.buttons[]`
- Сценарии: `scenarios[]`

## Important Constraints
- Пароль пользователя НЕ хранится — только токен
- WebSocket-канал `wss://my.zont.online/ws` не описан в публичной документации ZONT
  и может измениться без предупреждения; приложение обязано работать и без него
- Команды ZONT ждут подтверждения от прибора — таймаут чтения HTTP 60 с
- Экран рассчитан на тряску (использование в движении): крупные зоны нажатия

## External Dependencies
- Облако ZONT (`my.zont.online`): REST Widget API v3 и WebSocket `zont-comet`
- Maven Central: `com.squareup.okhttp3:okhttp:4.12.0`
