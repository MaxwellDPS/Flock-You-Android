# OEM Customization Directory

This directory contains resources for OEM partners to customize the Flock You application with their own branding.

## Directory Structure

```
app/src/oem/
├── README.md                    # This file
└── res/
    ├── values/
    │   ├── strings.xml          # String overrides (app name, descriptions)
    │   └── colors.xml           # Color overrides (brand colors)
    ├── drawable/                # OEM-specific drawables
    ├── mipmap-hdpi/             # Launcher icons (72x72px)
    ├── mipmap-xhdpi/            # Launcher icons (96x96px)
    ├── mipmap-xxhdpi/           # Launcher icons (144x144px)
    └── mipmap-xxxhdpi/          # Launcher icons (192x192px)
```

## How to Customize

### 1. App Name and Strings

Edit `res/values/strings.xml` to override the application name and other text:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Your Brand Detector</string>
    <string name="app_name_oem">Your Brand Detector</string>
    <string name="app_description">Your custom app description</string>
</resources>
```

### 2. Brand Colors

Edit `res/values/colors.xml` to apply your brand colors:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="brand_primary">#1A73E8</color>
    <color name="brand_secondary">#34A853</color>
</resources>
```

### 3. Launcher Icons

Replace the placeholder files in the mipmap directories with your branded launcher icons:

| Directory         | Size      | Files Needed                              |
|-------------------|-----------|-------------------------------------------|
| mipmap-hdpi       | 72x72px   | ic_launcher.png, ic_launcher_round.png    |
| mipmap-xhdpi      | 96x96px   | ic_launcher.png, ic_launcher_round.png    |
| mipmap-xxhdpi     | 144x144px | ic_launcher.png, ic_launcher_round.png    |
| mipmap-xxxhdpi    | 192x192px | ic_launcher.png, ic_launcher_round.png    |

You can also use adaptive icons by providing:
- `ic_launcher.xml` (adaptive icon definition)
- `ic_launcher_foreground.xml` or `ic_launcher_foreground.png`
- `ic_launcher_background.xml` or a background color

### 4. Custom Drawables

Add any OEM-specific drawable resources (logos, backgrounds, etc.) to the `res/drawable/` directory.

## Building the OEM Variant

To build the OEM variant of the application:

```bash
# Debug build
./gradlew assembleOemDebug

# Release build
./gradlew assembleOemRelease
```

## Resource Merging

Android Gradle Plugin automatically merges resources from the `oem` source set with the `main` source set. Resources in `oem` take precedence over `main`, allowing you to override any resource without modifying the base application.

### Merge Priority (highest to lowest):
1. Build type resources (e.g., `debug/`)
2. Product flavor resources (e.g., `oem/`)
3. Main source set (`main/`)

## Build Configuration

The OEM build variant includes special build configuration flags:

- `IS_SYSTEM_BUILD = true` - Enables system-level features
- `IS_OEM_BUILD = true` - Enables OEM-specific behavior
- `BUILD_MODE = "oem"` - Identifies the build as OEM variant

These can be accessed in code via `BuildConfig`:

```kotlin
if (BuildConfig.IS_OEM_BUILD) {
    // OEM-specific logic
}
```

## Signing

For production OEM builds, you should sign the APK with your platform certificate. See the main project documentation for details on configuring release signing.

## Notes

- Delete the `.gitkeep` placeholder files after adding your actual resources
- All resources are optional - only override what you need to change
- Test your customizations with both debug and release builds
- Ensure launcher icons meet Google's design guidelines for adaptive icons
