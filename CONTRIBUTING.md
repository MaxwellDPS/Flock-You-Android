# Contributing to Flock You Android

Thank you for your interest in contributing! This document covers how to set up the project for development and how the CI/CD pipeline works.

## Development Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- Git

### Getting Started

1. **Fork and clone the repository**
   ```bash
   git clone https://github.com/YOUR_USERNAME/flock-you-android.git
   cd flock-you-android
   ```

2. **Open in Android Studio**
   - File → Open → Select the project folder
   - Wait for Gradle sync to complete

3. **Add Google Maps API Key** (optional, for map features)
   - Get an API key from [Google Cloud Console](https://console.cloud.google.com/)
   - Add to `local.properties`:
     ```properties
     MAPS_API_KEY=your_api_key_here
     ```
   - Or set in `AndroidManifest.xml` directly for testing

4. **Run the app**
   - Select a device/emulator
   - Click Run (▶️)

## CI/CD Pipeline

The project uses GitHub Actions for continuous integration and deployment.

### Workflows

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `android-ci.yml` | Push, PR, Manual | Build, lint, test, and optionally release |
| `release-on-tag.yml` | Tag push (`v*.*.*`) | Automatic release when a version tag is pushed |

### Manual Release

1. Go to **Actions** → **Android CI/CD**
2. Click **Run workflow**
3. Configure options:
   - ✅ **Create a GitHub Release** - Check to create a release
   - **Release version** - e.g., `1.2.0`
   - **Mark as pre-release** - For beta/alpha releases
   - **Release notes** - Custom release notes (optional)
4. Click **Run workflow**

### Automatic Release (via Tags)

```bash
# Create and push a version tag
git tag v1.2.0
git push origin v1.2.0
```

This automatically triggers a release build.

## Setting Up Release Signing

For signed release builds, you need to configure these repository secrets:

### Required Secrets

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded keystore file |
| `KEYSTORE_PASSWORD` | Password for the keystore |
| `KEY_ALIAS` | Alias of the signing key |
| `KEY_PASSWORD` | Password for the key |

### Creating a Keystore

```bash
# Generate a new keystore
keytool -genkey -v -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias flockyou

# Convert to base64 for GitHub secrets
base64 -i release-keystore.jks | pbcopy  # macOS
base64 release-keystore.jks | xclip      # Linux
```

### Adding Secrets to GitHub

1. Go to your repository → **Settings** → **Secrets and variables** → **Actions**
2. Click **New repository secret**
3. Add each secret:

   **KEYSTORE_BASE64:**
   ```
   Paste the base64 output from above
   ```

   **KEYSTORE_PASSWORD:**
   ```
   Your keystore password
   ```

   **KEY_ALIAS:**
   ```
   flockyou (or whatever alias you used)
   ```

   **KEY_PASSWORD:**
   ```
   Your key password
   ```

### Without Signing Secrets

If secrets aren't configured, the CI will build an **unsigned** release APK. This is fine for testing but shouldn't be distributed to users.

## Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful commit messages
- Keep commits focused and atomic

### Commit Message Format

```
type(scope): description

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`

Examples:
```
feat(scan): add support for new device type
fix(ble): handle null device names
docs: update README with new features
ci(deps): update gradle to 8.2.2
```

## Pull Request Process

1. Create a feature branch from `main`
   ```bash
   git checkout -b feat/your-feature
   ```

2. Make your changes and commit

3. Push and create a PR
   ```bash
   git push origin feat/your-feature
   ```

4. Ensure CI passes (build, lint, tests)

5. Request review

## Testing

### Running Tests Locally

```bash
# Unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lint

# Build debug APK
./gradlew assembleDebug
```

### Testing Detection

For testing surveillance device detection without actual devices:

1. Create a WiFi hotspot with an SSID like `Flock_Test_Camera`
2. Use a BLE advertising app to broadcast as `Flock-Device`
3. The app should detect these as test devices

## Project Structure

```
app/src/main/java/com/flockyou/
├── data/
│   ├── model/          # Data classes & detection patterns
│   └── repository/     # Database & data access
├── di/                 # Dependency injection
├── service/            # Background scanning service
└── ui/
    ├── components/     # Reusable UI components
    ├── screens/        # Screen composables & ViewModels
    └── theme/          # Material 3 theming
```

## Questions?

Open an issue for:
- Bug reports
- Feature requests
- Questions about the codebase

---

Thank you for contributing! 🎉
