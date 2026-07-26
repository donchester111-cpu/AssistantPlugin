# AssistantPlugin

Android/AIDE project for `com.godwin.assistant`.

## Import into AIDE

1. Download and extract this project.
2. Open/import the extracted `AssistantPlugin` folder in AIDE.
3. Let Gradle sync.
4. Build the project.

## Important

The project contains two separate Java classes:

- `AssistantPlugin.java`
- `AssistantAccessibilityService.java`

The package is:

`com.godwin.assistant`

The project includes the AndroidX dependencies required by the plugin.

The original Sherpa-ONNX/KittenTTS section was not blindly included because the Java API differs between Sherpa-ONNX AAR versions. Add the exact AAR version you are using before wiring that feature back in.

Screen capture also requires Android MediaProjection permission. The accessibility service does not pretend that an arbitrary VirtualDisplay is a valid screenshot source.

The rest of the plugin architecture and action bridge is preserved.
