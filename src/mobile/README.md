# Android / Реплика

Исходники приложения находятся в [app](../../app), настройки сборки — в корне репозитория. Существующий Gradle-проект не дублируется внутри шаблона.

Структура:

- `app/src/main/java/team/replika`: интерфейс, запись, foreground service, редактор, история и экспорт.
- `app/src/main/java/team/replika/engine`: аудиодекодер, ASR, диаризация, пунктуация, обработка фрагментов и контрольные точки.
- `app/src/main/cpp`: JNI для llama.cpp, пунктуации и разделения голосов через ONNX Runtime.
- `app/src/test`: проверки контрактов, временной шкалы, редактора и восстановления.
- `app/src/androidTest`: настоящий инференс на Android и измерения.

Сборка: PowerShell 7, JDK 17+, Android SDK 35, NDK 27.1.12297006, CMake 3.22.1.

```powershell
./scripts/setup-engine.ps1
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Нужен SDK-путь в `local.properties`. Модели и сторонние бинарники скачиваются по зафиксированным URL и SHA-256; в Git не включаются. При обработке на устройстве сеть не используется.
