# AssistantPlugin

An Android Accessibility Service plugin that demonstrates how to intercept and handle accessibility events.

## Project Structure

```
AssistantPlugin/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/
│   │   │   │       └── godwin/
│   │   │   │           └── assistant/
│   │   │   │               └── AssistantPlugin.java
│   │   │   ├── res/
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   ├── colors.xml
│   │   │   │   │   └── themes.xml
│   │   │   │   └── xml/
│   │   │   │       └── accessibility_service_config.xml
│   │   │   └── AndroidManifest.xml
│   │   └── ...
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
├── .gitignore
└── README.md
```

## Features

- Accessibility Service implementation
- Event handling for window state changes, view clicks, and text changes
- Logging functionality for debugging
- Proper AndroidManifest configuration

## Requirements

- Android SDK 21 or higher
- Android Studio 4.0 or higher
- Java 8 or higher

## Getting Started

1. Clone the repository
2. Open the project in Android Studio
3. Build and run the application
4. Enable the accessibility service in device settings

## Building

```bash
./gradlew build
```

## Installation

```bash
./gradlew installDebug
```

## License

MIT License
